import json
import logging
from pathlib import Path
from sqlmodel import Session, select, text
import config
from core.book_repository import BookRepository
from core.data_models import ScenarioEntry
from core import path_manager
from utils.setup_logging import setup_logging

logger = logging.getLogger(__name__)

def migrate_subtitles():
    setup_logging()
    logger.info("🚀 Запуск миграции субтитров из JSON в БД...")
    
    output_dir = config.OUTPUT_DIR
    if not output_dir.exists():
        logger.info("Папка output не найдена. Нечего мигрировать.")
        return

    # Перебираем все папки книг в output
    for book_path in output_dir.iterdir():
        if not book_path.is_dir():
            continue
        
        book_name = book_path.name
        # Пропускаем системные файлы
        if book_name == "system.db" or book_name.endswith(".db"):
            continue
            
        logger.info(f"--- Обработка книги: {book_name} ---")
        
        repo = BookRepository(book_name)
        
        # --- Добавляем колонку, если её нет ---
        try:
            with repo.get_session() as session:
                session.execute(text("ALTER TABLE scenarioentry ADD COLUMN audio_subtitles JSON"))
                session.commit()
                logger.info(f"  Колонка audio_subtitles добавлена в БД книги {book_name}")
        except Exception as e:
            if "duplicate column name" in str(e).lower() or "already exists" in str(e).lower():
                pass
            else:
                logger.warning(f"  Не удалось добавить колонку в {book_name}: {e}")

        # Перебираем папки глав внутри книги
        for chapter_dir in book_path.iterdir():
            if not chapter_dir.is_dir() or not chapter_dir.name.startswith("vol_"):
                continue
            
            chapter_id = chapter_dir.name
            # Ищем и оригинальный файл, и уже переименованный (на случай перезапуска)
            subtitle_file = chapter_dir / "subtitles.json"
            if not subtitle_file.exists():
                subtitle_file = chapter_dir / "subtitles.json.migrated"
            
            if not subtitle_file.exists():
                continue
            
            logger.info(f"  Найдено: {subtitle_file}")
            
            try:
                with open(subtitle_file, 'r', encoding='utf-8') as f:
                    subtitles_data = json.load(f)
                
                with repo.get_session() as session:
                    # Загружаем все записи сценария для этой главы
                    statement = select(ScenarioEntry).where(ScenarioEntry.chapter_id == chapter_id)
                    db_entries = session.exec(statement).all()
                    
                    # Создаем мапы для поиска
                    entries_by_text = {e.text: e for e in db_entries if e.text}
                    entries_by_audio = {e.audio_file: e for e in db_entries if e.audio_file}
                    
                    updated_count = 0
                    for sub_item in subtitles_data:
                        audio_file = sub_item.get("audio_file")
                        text_content = sub_item.get("text")
                        
                        entry = entries_by_audio.get(audio_file)
                        if not entry and text_content:
                            entry = entries_by_text.get(text_content)
                        
                        if not entry:
                            continue
                        
                        # Обновляем audio_file, если он был пустой
                        if not entry.audio_file and audio_file:
                            entry.audio_file = audio_file
                            
                        # Конвертируем в относительные тайминги
                        start_ms = sub_item.get("start_ms", 0)
                        words = sub_item.get("words", [])
                        
                        relative_words = []
                        for w in words:
                            relative_words.append({
                                "word": w["word"],
                                "start": w["start"] - start_ms,
                                "end": w["end"] - start_ms
                            })
                        
                        entry.audio_subtitles = {
                            "words": relative_words
                        }
                        session.add(entry)
                        updated_count += 1
                    
                    session.commit()
                    logger.info(f"  ✅ Обновлено записей: {updated_count} / {len(subtitles_data)}")
                
                if subtitle_file.suffix != ".migrated":
                    subtitle_file.rename(subtitle_file.with_suffix(".json.migrated"))
                
            except Exception as e:
                logger.error(f"  ❌ Ошибка миграции {subtitle_file}: {e}")

    logger.info("✨ Миграция субтитров завершена.")

if __name__ == "__main__":
    migrate_subtitles()
