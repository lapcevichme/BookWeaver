import logging
from typing import Optional, Dict, Any

from core.book_repository import BookRepository
from core import path_manager
from utils import file_utils
from utils.setup_logging import setup_logging


def _parse_tags(value: Any) -> list:
    """Теги из metadata.json: список или строка-список (ranobelib пишет "['Медицина', ...]")."""
    if isinstance(value, list):
        return [str(t) for t in value]
    if isinstance(value, str) and value.strip():
        import ast
        try:
            parsed = ast.literal_eval(value)
            if isinstance(parsed, (list, tuple)):
                return [str(t) for t in parsed]
        except (ValueError, SyntaxError):
            pass
        return [t.strip() for t in value.split(",") if t.strip()]
    return []

logger = logging.getLogger(__name__)


def init_manifest(
        book_name: str,
        metadata: Optional[Dict[str, Any]] = None
):
    """
    Инициализирует базу данных проекта (project.db) вместо манифеста.
    """
    if metadata is None:
        metadata = {}

    logger.info(f"ИНИЦИАЛИЗАЦИЯ БАЗЫ ДАННЫХ ПРОЕКТА: '{book_name}'")

    repo = BookRepository(book_id=book_name)
    book_src_dir = path_manager.get_book_dir(book_name)

    if not book_src_dir.exists():
        logger.error(f"ПАПКА НЕ НАЙДЕНА: {book_src_dir}")
        return

    # Создаем папку проекта и инициализируем БД
    path_manager.ensure_book_dirs(book_name)
    
    from core.data_models import Book, BookStatus, Chapter
    from sqlmodel import Session, select

    with repo.get_session() as session:
        # 1. Создаем или обновляем Книгу
        book = session.get(Book, book_name)
        if not book:
            book = Book(
                id=book_name,
                title=metadata.get("title", book_name),
                author=metadata.get("author", "Unknown Author"),
                description=metadata.get("description", ""),
                status=BookStatus.parse(metadata.get("status")).value,
                tags=_parse_tags(metadata.get("tags")),
                language=metadata.get("language", "ru"),
                cover_image_path=metadata.get("cover_image")
            )
            session.add(book)
        
        # 2. Сканируем главы и добавляем в БД
        chapter_paths = file_utils.get_all_chapters(book_src_dir)
        
        for idx, path in enumerate(chapter_paths, 1):
            try:
                vol, chap_num = file_utils.parse_vol_chap_from_path(path)
                import re
                chap_match = re.search(r"chapter_(\d+(?:\.\d+)?)", path.name)
                chap_suffix = chap_match.group(1) if chap_match else str(chap_num)
                local_id = f"vol_{vol}_chap_{chap_suffix}"
                
                existing_chap = session.get(Chapter, local_id)
                if not existing_chap:
                    # Читаем текст главы для базы
                    raw_text = path.read_text("utf-8")
                    
                    # Пытаемся извлечь заголовок из первой строки, если там Markdown-заголовок
                    chapter_title = None
                    first_line = raw_text.strip().split("\n")[0] if raw_text.strip() else ""
                    if first_line.startswith("#"):
                        chapter_title = first_line.lstrip("#").strip()
                    if not chapter_title:
                        chapter_title = f"Глава {chap_suffix}" + (f" (Том {vol})" if vol > 1 else "")
                    
                    new_chap = Chapter(
                        id=local_id,
                        book_id=book_name,
                        volume_num=vol,
                        chapter_num=chap_num,
                        title=chapter_title,
                        status="draft",
                        order_index=idx,
                        raw_text=raw_text
                    )
                    session.add(new_chap)
            except Exception as e:
                logger.error(f"Ошибка при импорте главы {path}: {e}")

        session.commit()

        # Автоматическая разметка служебных глав при создании проекта
        try:
            from core.service_chapters import detect_and_mark_service_chapters
            marked = detect_and_mark_service_chapters(repo)
            if marked:
                logger.info(f"Размечено служебных глав при инициализации: {len(marked)}")
        except Exception as e:
            logger.warning(f"Не удалось разметить служебные главы: {e}")

    logger.info(f"ПРОЕКТ ИНИЦИАЛИЗИРОВАН В БД: {book_name} ({len(chapter_paths)} глав)")


if __name__ == "__main__":
    setup_logging()
    init_manifest("test_book", {"title": "Test"})
