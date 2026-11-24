import argparse
import re
import logging
from pathlib import Path

import config
from utils.text_utils import clean_raw_text
from utils.setup_logging import setup_logging

logger = logging.getLogger(__name__)

TEXT_EXTENSIONS = {'.txt', '.md', '.json', '.csv', '.xml'}


class TextCleaner:
    def __init__(self, recursive: bool = False, dry_run: bool = False):
        self.recursive = recursive
        self.dry_run = dry_run

    def clean_text(self, content: str) -> str:
        """
        Основная логика очистки текста (делегируется в utils.text_utils).
        """
        return clean_raw_text(content)

    def run(self, directory: Path):
        logger.info(f"--- ЗАПУСК ОЧИСТКИ ТЕКСТА ---")
        logger.info(f"Папка: {directory}")
        logger.info(f"Режим: {'DRY RUN' if self.dry_run else 'LIVE'}")

        if not directory.exists():
            logger.error(f"Директория {directory} не существует.")
            return

        iterator = directory.rglob('*') if self.recursive else directory.glob('*')

        files_processed = 0
        files_changed = 0

        for file_path in iterator:
            if file_path.is_file() and file_path.suffix.lower() in TEXT_EXTENSIONS:
                files_processed += 1
                try:
                    original_content = file_path.read_text(encoding='utf-8')
                    cleaned_content = self.clean_text(original_content)

                    if original_content != cleaned_content:
                        logger.info(f"[FIX] {file_path.name}")
                        if not self.dry_run:
                            file_path.write_text(cleaned_content, encoding='utf-8')
                        files_changed += 1
                    else:
                        logger.debug(f"[OK] {file_path.name}")
                        pass

                except UnicodeDecodeError:
                    logger.error(f"[ERR] {file_path.name} - Неверная кодировка (не UTF-8)")
                except Exception as e:
                    logger.error(f"[ERR] {file_path.name} - {e}")

        logger.info(f"--- ИТОГ: Проверено {files_processed}, Изменено {files_changed} ---")


if __name__ == "__main__":
    setup_logging()

    parser = argparse.ArgumentParser(
        description="Утилита для очистки текстовых файлов от мусора (NBSP, Zero-width, лишние переносы).")

    default_path = config.RAW_TEXT_DIR

    parser.add_argument("path", nargs='?', type=Path, default=default_path,
                        help=f"Путь к папке (по умолчанию: {default_path})")
    parser.add_argument("--recursive", "-r", action="store_true", help="Искать во всех подпапках")
    parser.add_argument("--dry-run", "-d", action="store_true", help="Только показать изменения")

    args = parser.parse_args()

    cleaner = TextCleaner(recursive=args.recursive, dry_run=args.dry_run)
    cleaner.run(args.path)