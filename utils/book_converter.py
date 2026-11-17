import sys
import shutil
import logging
from pathlib import Path
import re

project_root = Path(__file__).resolve().parent.parent
if str(project_root) not in sys.path:
    sys.path.insert(0, str(project_root))

from core import path_manager
from utils.book_parsers import EpubParser, TxtParser, ZipParser, FolderParser
from utils.init_manifest import init_manifest
from utils.text_utils import cleanup_filename
from utils.setup_logging import setup_logging

logger = logging.getLogger(__name__)

class BookConverter:
    def __init__(self, input_file: Path):
        if not input_file.exists():
            raise FileNotFoundError(f"Файл не найден: {input_file}")

        self.input_file = input_file
        self.book_name = cleanup_filename(input_file.stem)
        self.project_input_dir = path_manager.get_book_dir(self.book_name)
        self.project_output_dir = path_manager.get_book_output_dir(self.book_name)

    def run(self):
        print(f"Запуск конвертации: '{self.book_name}'")

        if self.project_input_dir.exists():
            print(f"⚠️ Удаление старой папки: {self.project_input_dir}")
            shutil.rmtree(self.project_input_dir)

        self.project_input_dir.mkdir(parents=True)

        suffix = self.input_file.suffix.lower()
        parser = None

        if self.input_file.is_dir():
            print("   -> Формат: Папка (RanobeLib / Директория с томами)")
            parser = FolderParser()
        elif suffix == '.epub':
            print("   -> Формат: EPUB")
            parser = EpubParser()
        elif suffix == '.txt':
            print("   -> Формат: TXT")
            parser = TxtParser()
        elif suffix == '.zip':
            print("   -> Формат: ZIP (RanobeLib / Archive)")
            parser = ZipParser()
        else:
            raise NotImplementedError(f"Формат {suffix} не поддерживается")

        try:
            volumes, meta, cover_bytes, images_dict = parser.parse(self.input_file)

            print(f"   -> Парсинг завершен.")
            print(f"      Томов: {len(volumes)}")
            print(f"      Метаданные: {meta.get('title')} / {meta.get('author')}")

        except Exception as e:
            # Не глотаем: иначе импорт «успешен», а книги нет
            raise RuntimeError(f"Ошибка парсинга {self.input_file.name}: {e}") from e

        self._save_chapters(volumes)
        print(f"✅ Тексты сохранены.")

        if images_dict:
            images_dir = path_manager.get_book_dir(self.book_name) / "images"
            images_dir.mkdir(exist_ok=True)
            for img_name, img_bytes in images_dict.items():
                (images_dir / img_name).write_bytes(img_bytes)
            print(f"✅ Извлечено и сохранено картинок: {len(images_dict)}")

        self.project_output_dir.mkdir(parents=True, exist_ok=True)
        if cover_bytes:
            cover_path = path_manager.get_cover_path(self.book_name)
            cover_path.write_bytes(cover_bytes)
            meta['cover_image'] = cover_path.name
            print(f"   -> Обложка сохранена ({cover_path.name}).")

        init_manifest(
            book_name=self.book_name,
            metadata=meta
        )

        print(f"🎉 Готово! Проект '{self.book_name}' инициализирован.")

    def _save_chapters(self, volumes: dict):
        for vol_num, chapters in volumes.items():
            vol_dir = path_manager.get_book_dir(self.book_name) / f"vol_{vol_num}"
            vol_dir.mkdir(exist_ok=True)

            for chap_num, text in chapters.items():
                clean_text = self._cleanup_text(text)
                if clean_text:
                    (vol_dir / f"chapter_{chap_num}.md").write_text(clean_text, encoding='utf-8')

    def _cleanup_text(self, text: str) -> str:
        lines = [line.strip() for line in text.splitlines() if line.strip()]
        text = "\n".join(lines)
        return re.sub(r'\n{3,}', '\n\n', text)


if __name__ == '__main__':
    import argparse

    setup_logging()
    parser = argparse.ArgumentParser()
    parser.add_argument("file", type=Path, help="Файл книги (.epub, .txt, .zip)")
    args = parser.parse_args()
    BookConverter(args.file).run()
