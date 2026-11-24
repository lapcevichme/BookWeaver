import json
import logging
import os
import shutil
from pathlib import Path
from uuid import UUID
from sqlmodel import Session, select
import config
from core.database import init_book_db, get_book_session
from core.data_models import Book, Chapter, Character, ScenarioEntry, ChapterSummary, CharacterType

logging.basicConfig(level=logging.INFO, format='%(levelname)s: %(message)s')
logger = logging.getLogger(__name__)

def migrate(delete_legacy: bool = False):
    output_dir = config.OUTPUT_DIR
    input_dir = config.INPUT_DIR / config.BOOKS_DIR_NAME
    
    if not output_dir.exists():
        logger.error(f"Папка {output_dir} не найдена. Нечего мигрировать.")
        return

    books_processed = 0
    for book_path in output_dir.iterdir():
        if not book_path.is_dir():
            continue
        
        book_id = book_path.name
        manifest_file = book_path / "manifest.json"
        
        if not manifest_file.exists():
            continue

        logger.info(f"======== Миграция книги: {book_id} ========")
        
        # 1. Инициализируем БД
        db_file = book_path / "project.db"
        # Если база уже есть, мы можем либо скипнуть, либо обновить. 
        # Давай просто инициализируем, init_book_db делает create_all (безопасно)
        init_book_db(book_path)
        
        try:
            with get_book_session(book_path) as session:
                # --- ЗАГРУЗКА ДАННЫХ ---
                m_data = json.loads(manifest_file.read_text("utf-8"))
                
                # 1. Книга (создаем или обновляем)
                book = session.get(Book, book_id)
                if not book:
                    book = Book(id=book_id, title=m_data.get("meta", {}).get("title", book_id))
                    session.add(book)
                
                book.author = m_data.get("meta", {}).get("author")
                book.description = m_data.get("meta", {}).get("description")
                book.status = m_data.get("meta", {}).get("status", "ongoing")
                book.cover_image_path = m_data.get("meta", {}).get("cover_image")
                book.tags = m_data.get("meta", {}).get("tags", [])
                book.config = m_data.get("config", {})
                
                # 2. Персонажи
                char_archive_file = book_path / "character_archive.json"
                char_name_to_id = {}
                
                if char_archive_file.exists():
                    c_data = json.loads(char_archive_file.read_text("utf-8"))
                    characters_list = c_data.get("characters", []) if isinstance(c_data, dict) else c_data
                    
                    for c in characters_list:
                        char_id = UUID(c["id"]) if isinstance(c["id"], str) else c["id"]
                        existing_char = session.get(Character, char_id)
                        
                        char_data = {
                            "name": c["name"],
                            "entity_type": c.get("entity_type", CharacterType.PERSON),
                            "aliases": c.get("aliases", []),
                            "gender": c.get("gender"),
                            "role_tier": c.get("role_tier", "background"),
                            "spoiler_free_description": c.get("spoiler_free_description", ""),
                            "description": c.get("description", ""),
                            "visual_base": c.get("visual_base"),
                            "voice_base": c.get("voice_base", ""),
                            "voice_timeline": c.get("voice_timeline", {}),
                            "visual_timeline": c.get("visual_timeline", {}),
                            "chapter_mentions": c.get("chapter_mentions", {})
                        }
                        
                        if existing_char:
                            for k, v in char_data.items(): setattr(existing_char, k, v)
                        else:
                            new_char = Character(id=char_id, book_id=book_id, **char_data)
                            session.add(new_char)
                        
                        char_name_to_id[c["name"]] = char_id
                    logger.info(f"  - Персонажей обработано: {len(characters_list)}")

                # 3. Главы
                summaries_file = book_path / "chapter_summaries.json"
                all_summaries = {}
                if summaries_file.exists():
                    s_data = json.loads(summaries_file.read_text("utf-8"))
                    all_summaries = s_data.get("summaries", {})

                chapters_list = m_data.get("structure", [])
                for chapter_item in chapters_list:
                    local_chap_id = chapter_item["id"]
                    vol = chapter_item.get('vol', 1)
                    chap_num = chapter_item.get('chap', 0)
                    
                    existing_chap = session.get(Chapter, local_chap_id)
                    
                    # Ищем текст только если его нет в БД
                    raw_text = existing_chap.raw_text if existing_chap else None
                    if not raw_text:
                        text_path = config.INPUT_DIR / config.BOOKS_DIR_NAME / book_id / f"vol_{vol}" / f"chapter_{chap_num}.md"
                        if not text_path.exists():
                            text_path = text_path.with_suffix(".txt")
                        if text_path.exists():
                            raw_text = text_path.read_text("utf-8")

                    # Кэши
                    raw_scenario_cache = None
                    cache_file = book_path / local_chap_id / "cache_raw_scenario.json"
                    if cache_file.exists(): raw_scenario_cache = json.loads(cache_file.read_text("utf-8"))
                    
                    ambient_cache = None
                    cache_file = book_path / local_chap_id / "cache_ambient.json"
                    if cache_file.exists(): ambient_cache = json.loads(cache_file.read_text("utf-8"))

                    chap_data = {
                        "book_id": book_id,
                        "volume_num": vol,
                        "chapter_num": chap_num,
                        "title": chapter_item.get("title"),
                        "status": chapter_item.get("status", "draft"),
                        "order_index": chapter_item.get("order", 0),
                        "raw_text": raw_text,
                        "cache_raw_scenario": raw_scenario_cache,
                        "cache_ambient": ambient_cache
                    }
                    
                    if existing_chap:
                        for k, v in chap_data.items(): setattr(existing_chap, k, v)
                    else:
                        new_chap = Chapter(id=local_chap_id, **chap_data)
                        session.add(new_chap)
                    
                    # Саммари
                    if local_chap_id in all_summaries:
                        summ = all_summaries[local_chap_id]
                        existing_summ = session.get(ChapterSummary, local_chap_id)
                        if not existing_summ:
                            session.add(ChapterSummary(
                                chapter_id=local_chap_id,
                                teaser=summ.get("teaser", ""),
                                synopsis=summ.get("synopsis", "")
                            ))

                    # Сценарий (идемпотентно)
                    scenario_file = book_path / local_chap_id / "scenario.json"
                    if scenario_file.exists():
                        entries_list = json.loads(scenario_file.read_text("utf-8"))
                        for i, e in enumerate(entries_list):
                            e_id = UUID(e["id"]) if isinstance(e["id"], str) else e["id"]
                            speaker_name = e.get("speaker")
                            entry = ScenarioEntry(
                                id=e_id, chapter_id=local_chap_id,
                                speaker_id=char_name_to_id.get(speaker_name),
                                type=e["type"], text=e.get("text"), tts_text=e.get("tts_text"),
                                speaker_name=speaker_name, instruct_prompt=e.get("instruct_prompt", "neutral"),
                                ambient=e.get("ambient", "none"), sfx=e.get("sfx"),
                                audio_file=e.get("audio_file") or e.get("audio_file_path"),
                                src=e.get("src"), order_index=i
                            )
                            session.merge(entry)

                session.commit()
                logger.info(f"  [DONE] Книга {book_id} мигрирована.")
                
                # --- УДАЛЕНИЕ ЛЕГАСИ ---
                if delete_legacy:
                    logger.info(f"  [CLEANUP] Удаление JSON файлов для {book_id}...")
                    manifest_file.unlink(missing_ok=True)
                    char_archive_file.unlink(missing_ok=True)
                    summaries_file.unlink(missing_ok=True)
                    # Удаляем кэши в папках глав
                    for chapter_item in chapters_list:
                        chap_dir = book_path / chapter_item["id"]
                        if chap_dir.exists():
                            (chap_dir / "cache_raw_scenario.json").unlink(missing_ok=True)
                            (chap_dir / "cache_ambient.json").unlink(missing_ok=True)
                            (chap_dir / "scenario.json").unlink(missing_ok=True)

            books_processed += 1
        except Exception as e:
            logger.error(f"  [ERROR] Ошибка миграции {book_id}: {e}", exc_info=True)

    logger.info(f"\nМиграция завершена! Обработано книг: {books_processed}")

if __name__ == "__main__":
    import sys
    cleanup = "--cleanup" in sys.argv
    migrate(delete_legacy=cleanup)
