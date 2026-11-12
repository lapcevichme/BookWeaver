import json
import logging
import threading
from sqlmodel import create_engine, Session, SQLModel
from pathlib import Path
from typing import Optional, Dict, Set
from pydantic import BaseModel

from sqlalchemy import event
from sqlalchemy.engine import Engine

logger = logging.getLogger(__name__)

# Потокобезопасный кэш движков БД и статуса инициализации
_engines_lock = threading.RLock()
_book_engines: Dict[Path, Engine] = {}
_initialized_book_dbs: Set[Path] = set()
_system_engine: Optional[Engine] = None
_system_db_initialized: bool = False


@event.listens_for(Engine, "connect")
def set_sqlite_pragma(dbapi_connection, connection_record):
    """Включает WAL-режим, foreign keys и busy_timeout для высоких нагрузок на чтение/запись."""
    try:
        cursor = dbapi_connection.cursor()
        cursor.execute("PRAGMA journal_mode=WAL;")
        cursor.execute("PRAGMA foreign_keys=ON;")
        cursor.execute("PRAGMA busy_timeout=5000;")
        cursor.execute("PRAGMA synchronous=NORMAL;")
        cursor.close()
    except Exception as e:
        logger.warning(f"Failed to set PRAGMA for SQLite connection: {e}")


def pydantic_json_serializer(obj):
    """Кастомный сериализатор для JSON-колонок, понимающий Pydantic модели."""
    return json.dumps(obj, default=lambda o: o.model_dump(mode='json') if isinstance(o, BaseModel) else str(o), ensure_ascii=False)


def get_engine_for_book(book_path: Path) -> Engine:
    """Возвращает кэшированный engine для конкретного файла базы данных книги."""
    resolved_path = Path(book_path).resolve()
    with _engines_lock:
        if resolved_path not in _book_engines:
            db_path = resolved_path / "project.db"
            db_path.parent.mkdir(parents=True, exist_ok=True)
            _book_engines[resolved_path] = create_engine(
                f"sqlite:///{db_path}",
                connect_args={"check_same_thread": False},
                json_serializer=pydantic_json_serializer
            )
        return _book_engines[resolved_path]


def dispose_engine(book_path: Path):
    """Сбрасывает пул соединений и удаляет engine книги из кэша (например, при удалении проекта)."""
    resolved_path = Path(book_path).resolve()
    with _engines_lock:
        if resolved_path in _book_engines:
            try:
                _book_engines[resolved_path].dispose()
            except Exception as e:
                logger.warning(f"Error disposing engine for {resolved_path}: {e}")
            del _book_engines[resolved_path]
        _initialized_book_dbs.discard(resolved_path)


def get_book_tables():
    from core import data_models
    return [
        data_models.Book.__table__,
        data_models.Chapter.__table__,
        data_models.ChapterCache.__table__,
        data_models.ChapterSummary.__table__,
        data_models.Character.__table__,
        data_models.ScenarioEntry.__table__,
        data_models.SpeakerLink.__table__,
        data_models.LLMMetric.__table__,
        data_models.AudioMetric.__table__,
        data_models.MetricCounter.__table__,
    ]


def init_book_db(book_path: Path, force: bool = False) -> Engine:
    """Инициализирует таблицы и применяет миграции для конкретной книги (один раз за процесс, если force=False)."""
    resolved_path = Path(book_path).resolve()
    with _engines_lock:
        if not force and resolved_path in _initialized_book_dbs:
            return get_engine_for_book(resolved_path)

    engine = get_engine_for_book(resolved_path)
    SQLModel.metadata.create_all(engine, tables=get_book_tables())

    try:
        with engine.connect() as conn:
            from sqlalchemy import text
            # 1. Проверяем колонку storage_size_bytes в book
            res = conn.execute(text("PRAGMA table_info(book)"))
            columns = [row[1] for row in res]
            if "storage_size_bytes" not in columns:
                logger.info(f"Migration: Adding storage_size_bytes column to {resolved_path.name}")
                conn.execute(text("ALTER TABLE book ADD COLUMN storage_size_bytes INTEGER DEFAULT 0"))
                conn.commit()

            # 2. Проверяем колонку embedding в character
            res_char = conn.execute(text("PRAGMA table_info(character)"))
            char_columns = [row[1] for row in res_char]
            if "embedding" not in char_columns:
                logger.info(f"Migration: Adding embedding column to {resolved_path.name}")
                conn.execute(text("ALTER TABLE character ADD COLUMN embedding JSON DEFAULT NULL"))
                conn.commit()
            chapter_columns = [row[1] for row in conn.execute(text("PRAGMA table_info(chapter)")).fetchall()]
            if chapter_columns and "characters_state" not in chapter_columns:
                logger.info(f"Migration: Adding characters_state column to {resolved_path.name}")
                conn.execute(text("ALTER TABLE chapter ADD COLUMN characters_state TEXT DEFAULT NULL"))
                conn.commit()
            if char_columns and "voice_profile" not in char_columns:
                # voice_base раньше хранил и id голоса (UI), и теги от LLM. Теги (с пробелами или
                # запятыми; id голосов — snake_case) переезжают в voice_profile.
                logger.info(f"Migration: Adding voice_profile column to {resolved_path.name}")
                conn.execute(text("ALTER TABLE character ADD COLUMN voice_profile TEXT DEFAULT ''"))
                conn.execute(text("UPDATE character SET voice_profile = voice_base, voice_base = '' "
                                  "WHERE voice_base LIKE '% %' OR voice_base LIKE '%,%'"))
                conn.commit()
            if "description_timeline" not in char_columns:
                logger.info(f"Migration: Adding description_timeline column to {resolved_path.name}")
                conn.execute(text("ALTER TABLE character ADD COLUMN description_timeline JSON DEFAULT '{}'"))
                conn.commit()
            if char_columns and "voice_casting" not in char_columns:
                logger.info(f"Migration: Adding voice_casting column to {resolved_path.name}")
                conn.execute(text("ALTER TABLE character ADD COLUMN voice_casting JSON DEFAULT '{}'"))
                conn.commit()

            # 3. Миграция тяжелых кэшей из chapter в chaptercache
            res_chap = conn.execute(text("PRAGMA table_info(chapter)"))
            chap_columns = [row[1] for row in res_chap]
            old_cache_cols = [c for c in ["cache_raw_scenario", "cache_ambient", "cache_emotion"] if c in chap_columns]
            if old_cache_cols:
                logger.info(f"Migration: Moving chapter caches to chaptercache table in {resolved_path.name}")
                raw_col = 'cache_raw_scenario' if 'cache_raw_scenario' in chap_columns else 'NULL'
                amb_col = 'cache_ambient' if 'cache_ambient' in chap_columns else 'NULL'
                emo_col = 'cache_emotion' if 'cache_emotion' in chap_columns else 'NULL'

                conn.execute(text(f"""
                    INSERT OR IGNORE INTO chaptercache (chapter_id, raw_scenario, ambient, emotion)
                    SELECT id, {raw_col}, {amb_col}, {emo_col}
                    FROM chapter
                    WHERE {raw_col} IS NOT NULL OR {amb_col} IS NOT NULL OR {emo_col} IS NOT NULL
                """))

                for col in old_cache_cols:
                    conn.execute(text(f"ALTER TABLE chapter DROP COLUMN {col}"))
                conn.commit()

            # 4. Создание недостающих индексов
            conn.execute(text("CREATE INDEX IF NOT EXISTS ix_chapter_book_order ON chapter (book_id, order_index)"))
            conn.execute(text("CREATE INDEX IF NOT EXISTS ix_scenarioentry_chapter_order ON scenarioentry (chapter_id, order_index)"))
            conn.execute(text("CREATE INDEX IF NOT EXISTS ix_scenarioentry_speaker_id ON scenarioentry (speaker_id)"))
            conn.execute(text("CREATE UNIQUE INDEX IF NOT EXISTS uq_metriccounter_chapter_counter ON metriccounter (chapter_id, counter_name)"))
            conn.commit()

            # 5. Очистка пустых призрачных таблиц от общей метадаты SQLModel
            ghost_tables = ["taskrecord", "ambienttrack", "sfxtrack", "pronunciationentry", "systemsetting"]
            for gt in ghost_tables:
                tbl_check = conn.execute(text(f"SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='{gt}'")).scalar()
                if tbl_check:
                    cnt = conn.execute(text(f"SELECT COUNT(*) FROM \"{gt}\"")).scalar()
                    if cnt == 0:
                        conn.execute(text(f"DROP TABLE \"{gt}\""))
            conn.execute(text("PRAGMA optimize;"))
            conn.commit()
    except Exception as e:
        logger.warning(f"Migration/cleanup failed for {resolved_path.name}: {e}")

    with _engines_lock:
        _initialized_book_dbs.add(resolved_path)

    return engine


def get_book_session(book_path: Path) -> Session:
    """Возвращает сессию для конкретной книги."""
    engine = get_engine_for_book(book_path)
    return Session(engine, expire_on_commit=False)


def get_system_engine() -> Engine:
    """Возвращает кэшированный engine системной базы данных."""
    global _system_engine
    with _engines_lock:
        if _system_engine is None:
            import config
            db_path = config.OUTPUT_DIR / "system.db"
            db_path.parent.mkdir(parents=True, exist_ok=True)
            _system_engine = create_engine(f"sqlite:///{db_path}", connect_args={"check_same_thread": False})
        return _system_engine


def get_system_tables():
    from core import system_models
    return [
        system_models.AmbientTrack.__table__,
        system_models.SFXTrack.__table__,
        system_models.PronunciationEntry.__table__,
        system_models.SystemSetting.__table__,
    ]


def init_system_db(force: bool = False) -> Engine:
    """Инициализирует таблицы системной БД."""
    global _system_db_initialized
    engine = get_system_engine()
    with _engines_lock:
        if not force and _system_db_initialized:
            return engine

    SQLModel.metadata.create_all(engine, tables=get_system_tables())

    # Очистка пустых таблиц, не относящихся к системной БД
    try:
        with engine.connect() as conn:
            from sqlalchemy import text
            ghost_tables = [
                "book", "chapter", "chaptercache", "character",
                "chaptersummary", "scenarioentry", "llmmetric",
                "audiometric", "metriccounter", "taskrecord"
            ]
            for gt in ghost_tables:
                tbl_check = conn.execute(text(f"SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='{gt}'")).scalar()
                if tbl_check:
                    cnt = conn.execute(text(f"SELECT COUNT(*) FROM \"{gt}\"")).scalar()
                    if cnt == 0:
                        conn.execute(text(f"DROP TABLE \"{gt}\""))
            conn.execute(text("PRAGMA optimize;"))
            conn.commit()
    except Exception as e:
        logger.warning(f"Failed to clean ghost tables in system.db: {e}")

    with _engines_lock:
        _system_db_initialized = True

    return engine


def optimize_db(engine: Engine) -> list:
    """Запускает PRAGMA optimize для сбора статистики и оптимизации планировщика запросов SQLite."""
    try:
        with engine.connect() as conn:
            from sqlalchemy import text
            res = conn.execute(text("PRAGMA optimize;")).fetchall()
            conn.commit()
            return [str(row[0]) for row in res]
    except Exception as e:
        logger.warning(f"PRAGMA optimize failed: {e}")
        return []


def get_system_session() -> Session:
    return Session(get_system_engine(), expire_on_commit=False)
