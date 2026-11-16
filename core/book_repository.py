from typing import List, Optional, Dict, Any, Tuple, Union
from sqlmodel import Session, select
from core.database import get_book_session, init_book_db
from core.data_models import (
    Book, Chapter, ChapterCache, Character, ScenarioEntry, ChapterSummary,
    CharacterArchive, ChapterSummaryArchive, BookManifest, ManifestMeta,
    ManifestChapterEntry, ManifestConfig, ChapterStatus, IdentityLink
)
from core import path_manager
from pathlib import Path

class BookRepository:
    def __init__(self, book_id: str):
        self.book_id = book_id
        self.book_output_dir = path_manager.get_book_output_dir(book_id)

    def get_session(self) -> Session:
        """Возвращает сессию для базы данных этой конкретной книги."""
        # Всегда вызываем инициализацию, чтобы применились миграции (ALTER TABLE и т.д.)
        init_book_db(self.book_output_dir)
        return get_book_session(self.book_output_dir)

    def get_book(self) -> Optional[Book]:
        with self.get_session() as session:
            return session.get(Book, self.book_id)

    def get_chapter(self, chapter_id: str) -> Optional[Chapter]:
        with self.get_session() as session:
            return session.get(Chapter, chapter_id)

    def get_all_chapters(self) -> List[Chapter]:
        from sqlalchemy.orm import defer
        with self.get_session() as session:
            return session.exec(
                select(Chapter)
                .where(Chapter.book_id == self.book_id)
                .options(defer(Chapter.raw_text))
                .order_by(Chapter.order_index)
            ).all()

    def get_story_chapters(self) -> List[Chapter]:
        """Возвращает только сюжетные главы (is_service == False), отсортированные по порядку."""
        from sqlalchemy.orm import defer
        with self.get_session() as session:
            return session.exec(
                select(Chapter)
                .where(Chapter.book_id == self.book_id, Chapter.is_service == False)
                .options(defer(Chapter.raw_text))
                .order_by(Chapter.order_index)
            ).all()

    def mark_chapter_service(self, chapter_id: str, is_service: bool = True):
        """Отмечает главу как служебную (или возвращает в сюжет). Для служебных сразу ставит skipped/completed."""
        with self.get_session() as session:
            chap = session.get(Chapter, chapter_id)
            if chap:
                chap.is_service = is_service
                if is_service:
                    chap.characters_state = "skipped"
                    chap.status = ChapterStatus.COMPLETED
                session.add(chap)
                session.commit()

    def update_chapter_status(self, chapter_id: str, status: Union[ChapterStatus, str]):
        with self.get_session() as session:
            db_chap = session.get(Chapter, chapter_id)
            if db_chap:
                db_chap.status = ChapterStatus(status) if isinstance(status, str) and status in ChapterStatus._value2member_map_ else status
                session.add(db_chap)
                session.commit()

    def set_characters_state(self, chapter_ids: List[str], state: Optional[str]):
        """Отмечает прогресс анализа персонажей: "done", "skipped" или None (сброс)."""
        with self.get_session() as session:
            for chapter_id in chapter_ids:
                db_chap = session.get(Chapter, chapter_id)
                if db_chap:
                    db_chap.characters_state = state
                    session.add(db_chap)
            session.commit()

    def save_characters(self, characters: List[Character]):
        with self.get_session() as session:
            for char in characters:
                char.book_id = self.book_id
                session.merge(char)
            session.commit()

    def merge_characters(self, primary_id: Any, duplicate_id: Any) -> Optional[Character]:
        """
        Объединяет дубликат персонажа в основного персонажа:
        - переносит все алиасы
        - объединяет chapter_mentions
        - переносит визуальные/голосовые таймлайны
        - обновляет speaker_id во всех ScenarioEntry
        - удаляет дубликат из БД
        """
        from uuid import UUID
        pid = UUID(str(primary_id))
        did = UUID(str(duplicate_id))
        if pid == did:
            return None

        with self.get_session() as session:
            primary = session.get(Character, pid)
            duplicate = session.get(Character, did)
            if not primary or not duplicate:
                return primary

            # Объединяем алиасы
            combined_aliases = set(primary.aliases or [])
            combined_aliases.add(duplicate.name)
            for a in (duplicate.aliases or []):
                combined_aliases.add(a)
            primary.aliases = sorted(list(combined_aliases))

            # Объединяем mentions
            for chap_id, mention in (duplicate.chapter_mentions or {}).items():
                if chap_id not in primary.chapter_mentions:
                    primary.chapter_mentions[chap_id] = mention

            # Объединяем visual/voice timelines
            for chap_id, vt in (duplicate.visual_timeline or {}).items():
                if chap_id not in primary.visual_timeline:
                    primary.visual_timeline[chap_id] = vt

            for chap_id, vt in (duplicate.voice_timeline or {}).items():
                if chap_id not in primary.voice_timeline:
                    primary.voice_timeline[chap_id] = vt

            # Перепривязываем реплики в ScenarioEntry
            from sqlmodel import select
            entries = session.exec(select(ScenarioEntry).where(ScenarioEntry.speaker_id == did)).all()
            for entry in entries:
                entry.speaker_id = pid
                entry.speaker_name = primary.name
                session.add(entry)

            session.delete(duplicate)
            session.add(primary)
            session.commit()
            session.refresh(primary)
            return primary

    def get_chapter_text(self, chapter_id: str) -> str:
        """Возвращает сырой текст главы из БД или из файла (с кэшированием в БД)."""
        with self.get_session() as session:
            db_chap = session.get(Chapter, chapter_id)
            if not db_chap:
                raise ValueError(f"Глава {chapter_id} не найдена в БД.")
            if db_chap.raw_text:
                return db_chap.raw_text

            path = path_manager.get_chapter_text_path(self.book_id, db_chap.volume_num, db_chap.chapter_num)
            if path.exists():
                text = path.read_text("utf-8")
                db_chap.raw_text = text
                session.add(db_chap)
                session.commit()
                return text
            raise FileNotFoundError(f"Текст главы {chapter_id} не найден на диске.")

    def get_characters(self) -> List[Character]:
        with self.get_session() as session:
            return session.exec(select(Character).where(Character.book_id == self.book_id)).all()

    def get_identity_links(self, status: Optional[str] = "confirmed") -> List[IdentityLink]:
        with self.get_session() as session:
            q = select(IdentityLink).where(IdentityLink.book_id == self.book_id)
            if status is not None:
                q = q.where(IdentityLink.status == status)
            return session.exec(q).all()

    def get_character_archive(self) -> CharacterArchive:
        chars = self.get_characters()
        processed = set()
        for c in chars:
            processed.update(c.chapter_mentions.keys())
        return CharacterArchive(characters=chars, processed_chapters=list(processed))

    def get_summary_archive(self) -> ChapterSummaryArchive:
        with self.get_session() as session:
            statement = select(ChapterSummary).join(Chapter).where(Chapter.book_id == self.book_id)
            summaries = session.exec(statement).all()
            return ChapterSummaryArchive(summaries={s.chapter_id: s for s in summaries})

    def get_scenario_entries(self, chapter_id: str) -> List[ScenarioEntry]:
        with self.get_session() as session:
            statement = select(ScenarioEntry).where(ScenarioEntry.chapter_id == chapter_id).order_by(ScenarioEntry.order_index)
            return session.exec(statement).all()

    def set_cache(self, chapter_id: str, name: str, data: Any):
        clean_name = name.removeprefix("cache_")
        with self.get_session() as session:
            cache = session.get(ChapterCache, chapter_id)
            if not cache:
                cache = ChapterCache(chapter_id=chapter_id)
            if hasattr(cache, clean_name):
                setattr(cache, clean_name, data)
            elif hasattr(cache, f"cache_{clean_name}"):
                setattr(cache, f"cache_{clean_name}", data)
            session.add(cache)
            session.commit()

    def get_cache(self, chapter_id: str, name: str) -> Optional[Any]:
        clean_name = name.removeprefix("cache_")
        with self.get_session() as session:
            cache = session.get(ChapterCache, chapter_id)
            if cache:
                if hasattr(cache, clean_name) and getattr(cache, clean_name) is not None:
                    return getattr(cache, clean_name)
                if hasattr(cache, f"cache_{clean_name}") and getattr(cache, f"cache_{clean_name}") is not None:
                    return getattr(cache, f"cache_{clean_name}")
            
            # Fallback для старых записей до миграции
            db_chap = session.get(Chapter, chapter_id)
            if db_chap and hasattr(db_chap, f"cache_{clean_name}"):
                return getattr(db_chap, f"cache_{clean_name}", None)
            return None

    def update_storage_size(self):
        """Рекурсивно считает размер папки книги и сохраняет в БД."""
        def get_dir_size(path: Path):
            return sum(f.stat().st_size for f in path.rglob('*') if f.is_file())
        
        size = get_dir_size(self.book_output_dir)
        with self.get_session() as session:
            book = session.get(Book, self.book_id)
            if book:
                book.storage_size_bytes = size
                session.add(book)
                session.commit()
        return size

    def optimize(self) -> List[str]:
        """Запускает оптимизацию планировщика запросов SQLite для базы книги."""
        from core.database import optimize_db, get_engine_for_book
        return optimize_db(get_engine_for_book(self.book_output_dir))

    def load_manifest(self) -> BookManifest:
        with self.get_session() as session:
            book = session.get(Book, self.book_id)
            if not book:
                raise ValueError(f"Книга {self.book_id} не найдена в БД.")

            meta = ManifestMeta(
                title=book.title,
                author=book.author,
                description=book.description,
                tags=book.tags,
                status=book.status
            )

            from sqlalchemy.orm import defer
            db_chapters = session.exec(
                select(Chapter)
                .where(Chapter.book_id == self.book_id)
                .options(defer(Chapter.raw_text))
                .order_by(Chapter.order_index)
            ).all()

            structure = [
                ManifestChapterEntry(
                    order=c.order_index,
                    title=c.title or f"Глава {c.chapter_num}",
                    vol=c.volume_num,
                    chap=c.chapter_num,
                    status=c.status,
                    id=c.id
                ) for c in db_chapters
            ]

            return BookManifest(
                project_id=self.book_id,
                meta=meta,
                structure=structure,
                config=ManifestConfig(**book.config)
            )
