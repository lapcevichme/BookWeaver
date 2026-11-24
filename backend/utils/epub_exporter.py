import logging
import argparse
from pathlib import Path
from typing import Optional
import uuid
from ebooklib import epub

import config
from core.book_repository import BookRepository
from core import path_manager
from core.data_models import Scenario
from utils.setup_logging import setup_logging

logger = logging.getLogger(__name__)


class EpubExporter:
    """
    Экспортирует готовый проект (текст, картинки, глоссарий) в формат EPUB.
    """

    def __init__(self, book_name: str):
        self.book_name = book_name
        self.repo = BookRepository(book_name)
        self.export_dir = config.EXPORT_DIR
        self.export_dir.mkdir(parents=True, exist_ok=True)
        self.epub_path = self.export_dir / f"{self.book_name}.epub"
        self._added_images = set()

    def _add_image_to_epub(self, book: epub.EpubBook, image_path: Path, epub_folder: str = "images") -> Optional[str]:
        """
        Добавляет файл картинки внутрь EPUB и возвращает путь для src.
        """
        if not image_path.exists():
            logger.warning(f"Иллюстрация не найдена: {image_path}")
            return None

        file_name = image_path.name
        epub_internal_path = f"{epub_folder}/{file_name}"

        if epub_internal_path not in self._added_images:
            try:
                img_item = epub.EpubItem(
                    uid=f"img_{uuid.uuid4().hex[:8]}",
                    file_name=epub_internal_path,
                    media_type="image/jpeg" if file_name.lower().endswith(('.jpg', '.jpeg')) else "image/png",
                    content=image_path.read_bytes()
                )
                book.add_item(img_item)
                self._added_images.add(epub_internal_path)
            except Exception as e:
                logger.error(f"Ошибка при добавлении картинки {image_path.name} в EPUB: {e}")
                return None

        return epub_internal_path

    def _create_glossary(self, book: epub.EpubBook) -> Optional[epub.EpubHtml]:
        """
        Генерирует главу с глоссарием персонажей на основе данных из БД.
        """
        try:
            archive = self.repo.get_character_archive()
            if not archive.characters:
                return None
        except Exception as e:
            logger.warning(f"Не удалось загрузить архив персонажей для глоссария: {e}")
            return None

        html_content = [
            "<h1>Глоссарий персонажей</h1>",
            "<p><i>Ниже приведен список действующих лиц. Описания не содержат спойлеров к будущим событиям.</i></p>",
            "<hr/>"
        ]

        role_priority = {"protagonist": 0, "major": 1, "minor": 2, "background": 3}
        sorted_chars = sorted(archive.characters, key=lambda c: (role_priority.get(c.role_tier, 4), c.name))

        for char in sorted_chars:
            html_content.append(f"<div id='char_{char.id}' style='margin-bottom: 2em;'>")
            html_content.append(f"<h2>{char.name}</h2>")

            if char.aliases:
                aliases_str = ", ".join(char.aliases)
                html_content.append(f"<p><b>Также известен(на) как:</b> {aliases_str}</p>")

            role_ru = {
                "protagonist": "Главный герой", "major": "Второстепенный герой",
                "minor": "Эпизодический", "background": "Фоновый"
            }.get(char.role_tier, char.role_tier)

            html_content.append(f"<p><b>Роль:</b> {role_ru}</p>")
            html_content.append(f"<p><b>Описание:</b> {char.spoiler_free_description}</p>")
            html_content.append("</div>")

        chapter = epub.EpubHtml(title="Глоссарий персонажей", file_name="glossary.xhtml", lang="ru")
        chapter.content = "\n".join(html_content)
        book.add_item(chapter)
        return chapter

    def export(self) -> Path | None:
        """
        Основной метод сборки EPUB файла.
        """
        logger.info(f"Начало сборки EPUB для: '{self.book_name}'")

        try:
            manifest = self.repo.load_manifest()

            # Создаем книгу и настраиваем мету
            book = epub.EpubBook()
            book.set_identifier(f"id_{uuid.uuid4().hex}")
            book.set_title(manifest.meta.title or self.book_name)
            book.set_language(manifest.meta.language or 'ru')
            if manifest.meta.author:
                book.add_author(manifest.meta.author)

            # Обложка
            cover_path = path_manager.get_cover_path(self.book_name)
            if cover_path.exists():
                book.set_cover("cover.jpg", cover_path.read_bytes())

            # CSS
            style = """
            body { font-family: serif; line-height: 1.6; }
            .dialogue { margin-bottom: 0.5em; }
            .speaker { font-weight: bold; }
            .thought { font-style: italic; color: #333; }
            .narration { margin-bottom: 1em; }
            .illustration { text-align: center; margin: 1.5em 0; page-break-inside: avoid; }
            .illustration img { max-width: 100%; height: auto; }
            """
            default_css = epub.EpubItem(uid="style_default", file_name="style/default.css", media_type="text/css",
                                        content=style)
            book.add_item(default_css)

            # Сборка глав
            epub_chapters = []
            db_chapters = self.repo.get_all_chapters()
            book_input_dir = path_manager.get_book_dir(self.book_name)
            images_dir = book_input_dir / "images"

            for db_chap in db_chapters:
                vol = db_chap.volume_num
                chap = db_chap.chapter_num
                chapter_id = db_chap.id
                
                entries = self.repo.get_scenario_entries(chapter_id)
                scenario = Scenario(entries=entries) if entries else None

                if not scenario:
                    logger.warning(f"Сценарий для {chapter_id} не найден. Пропуск.")
                    continue

                chapter_title = db_chap.title or f"Том {vol}. Глава {chap}"
                for struct_item in manifest.structure:
                    if struct_item.vol == vol and struct_item.chap == chap:
                        chapter_title = struct_item.title
                        break

                c = epub.EpubHtml(title=chapter_title, file_name=f"chapter_{vol}_{chap}.xhtml", lang="ru")
                c.add_item(default_css)

                html_parts = [f"<h1>{chapter_title}</h1>"]

                # Лимит символов для одного абзаца (около 100-130 слов)
                MAX_PARAGRAPH_LENGTH = 800
                current_block_type = None
                current_speaker = None
                block_texts = []

                def flush_block():
                    """Собирает накопленные строки в один HTML-абзац и очищает буфер."""
                    if not block_texts:
                        return
                    joined_text = " ".join(block_texts)
                    if current_block_type == "dialogue":
                        spk = current_speaker if current_speaker else "Неизвестный"
                        html_parts.append(f"<p class='dialogue'><span class='speaker'>{spk}:</span> {joined_text}</p>")
                    elif current_block_type == "thought":
                        html_parts.append(f"<p class='thought'>{joined_text}</p>")
                    elif current_block_type == "narration":
                        html_parts.append(f"<p class='narration'>{joined_text}</p>")
                    block_texts.clear()

                for entry in scenario.entries:
                    if entry.type == "image" and entry.src:
                        flush_block()
                        img_filename = Path(entry.src).name
                        img_path = images_dir / img_filename

                        internal_path = self._add_image_to_epub(book, img_path)
                        if internal_path:
                            html_parts.append(
                                f"<div class='illustration'><img src='{internal_path}' alt='Illustration'/></div>")
                        current_block_type = None
                        continue

                    if entry.text and entry.text.strip():
                        text = entry.text.strip()

                        current_length = sum(len(t) for t in block_texts)
                        can_merge = (
                                entry.type == current_block_type and
                                entry.speaker == current_speaker and
                                entry.type in ["narration", "thought"] and
                                current_length < MAX_PARAGRAPH_LENGTH
                        )

                        if can_merge:
                            block_texts.append(text)
                        else:
                            flush_block()
                            current_block_type = entry.type
                            current_speaker = entry.speaker
                            block_texts.append(text)

                flush_block()

                c.content = "\n".join(html_parts)
                book.add_item(c)
                epub_chapters.append(c)

            # Глоссарий
            glossary_chapter = self._create_glossary(book)

            # Оглавление
            book.toc = tuple(epub_chapters)
            if glossary_chapter:
                book.toc = book.toc + (glossary_chapter,)

            book.add_item(epub.EpubNcx())
            book.add_item(epub.EpubNav())

            # Spine
            spine: List[Any] = ['nav'] + epub_chapters
            if glossary_chapter:
                spine.append(glossary_chapter)
            book.spine = spine

            # Сохранение
            epub.write_epub(str(self.epub_path), book)
            logger.info(f"✅ EPUB успешно создан: {self.epub_path}")
            return self.epub_path

        except Exception as e:
            logger.error(f"Ошибка при сборке EPUB: {e}", exc_info=True)
            return None


if __name__ == '__main__':
    setup_logging()
    parser = argparse.ArgumentParser()
    parser.add_argument("book_name", type=str, help="Имя папки книги")
    args = parser.parse_args()

    exporter = EpubExporter(args.book_name)
    exporter.export()
