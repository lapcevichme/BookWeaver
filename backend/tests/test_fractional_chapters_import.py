import io
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from core.data_models import Chapter
from core.book_repository import BookRepository
from utils.book_parsers import EpubParser, ZipParser, FolderParser, TxtParser
from utils.book_converter import BookConverter
from utils.file_utils import get_natural_sort_key, parse_vol_chap_from_path
from utils.init_manifest import init_manifest


class TestFileUtilsFractional(unittest.TestCase):
    def test_parse_vol_chap_from_path(self):
        v, c = parse_vol_chap_from_path(Path("some/dir/vol_1/chapter_0.5.txt"))
        self.assertEqual(v, 1)
        self.assertEqual(c, 0.5)

        v, c = parse_vol_chap_from_path(Path("some/dir/vol_2/chapter_10.md"))
        self.assertEqual(v, 2)
        self.assertEqual(c, 10)

        v, c = parse_vol_chap_from_path(Path("some/dir/vol_1/chapter_0.0.md"))
        self.assertEqual(v, 1)
        self.assertEqual(c, 0.0)

        v, c = parse_vol_chap_from_path(Path("some/dir/vol_1/chapter_0.txt"))
        self.assertEqual(v, 1)
        self.assertEqual(c, 0)

    def test_natural_sort_order(self):
        paths = [
            "input/books/b/vol_2/chapter_1.txt",
            "input/books/b/vol_1/chapter_10.txt",
            "input/books/b/vol_1/chapter_2.txt",
            "input/books/b/vol_1/chapter_0.5.txt",
            "input/books/b/vol_1/chapter_0.1.txt",
            "input/books/b/vol_1/chapter_0.txt",
            "input/books/b/vol_1/chapter_0.0.txt",
            "input/books/b/vol_1/chapter_1.txt",
            "input/books/b/vol_2/chapter_0.5.txt",
        ]

        expected = [
            "input/books/b/vol_1/chapter_0.txt",
            "input/books/b/vol_1/chapter_0.0.txt",
            "input/books/b/vol_1/chapter_0.1.txt",
            "input/books/b/vol_1/chapter_0.5.txt",
            "input/books/b/vol_1/chapter_1.txt",
            "input/books/b/vol_1/chapter_2.txt",
            "input/books/b/vol_1/chapter_10.txt",
            "input/books/b/vol_2/chapter_0.5.txt",
            "input/books/b/vol_2/chapter_1.txt",
        ]

        sorted_paths = sorted(paths, key=get_natural_sort_key)
        self.assertEqual(sorted_paths, expected)


class TestZipAndFolderParsers(unittest.TestCase):
    def test_zip_parser_fractional_chapters(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            zip_path = Path(tmpdir) / "test_book.zip"
            with zipfile.ZipFile(zip_path, "w") as zf:
                zf.writestr("metadata.json", json.dumps({"title": "Тестовая книга", "author": "Автор"}))
                zf.writestr("vol_1/chapter_0.0.txt", "Дисклеймер переводчика")
                zf.writestr("vol_1/chapter_0.txt", "Иллюстрации тома 1")
                zf.writestr("vol_1/chapter_0.1.txt", "Аннотация тома 1")
                zf.writestr("vol_1/chapter_0.5.txt", "Пролог тома 1")
                zf.writestr("vol_1/chapter_1.txt", "Первая глава")
                zf.writestr("vol_2/chapter_0.5.txt", "Пролог тома 2")
                zf.writestr("vol_2/chapter_1.txt", "Глава 1 тома 2")

            parser = ZipParser()
            volumes, meta, cover, images = parser.parse(zip_path)

            self.assertIn(1, volumes)
            self.assertIn(2, volumes)
            self.assertEqual(len(volumes[1]), 5)
            self.assertEqual(len(volumes[2]), 2)
            self.assertIn("0.0", volumes[1])
            self.assertIn("0", volumes[1])
            self.assertIn("0.1", volumes[1])
            self.assertIn("0.5", volumes[1])
            self.assertIn("1", volumes[1])
            self.assertIn("0.5", volumes[2])
            self.assertIn("1", volumes[2])

    def test_folder_parser_fractional_chapters(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            book_dir = Path(tmpdir) / "my_ranobe"
            v1_dir = book_dir / "vol_1"
            v1_dir.mkdir(parents=True)
            v2_dir = book_dir / "vol_2"
            v2_dir.mkdir(parents=True)

            (book_dir / "metadata.json").write_text(json.dumps({"title": "Ранобэ", "author": "Автор"}), encoding="utf-8")
            (v1_dir / "chapter_0.0.txt").write_text("Предупреждение", encoding="utf-8")
            (v1_dir / "chapter_0.txt").write_text("Картинки", encoding="utf-8")
            (v1_dir / "chapter_0.5.txt").write_text("Пролог", encoding="utf-8")
            (v1_dir / "chapter_1.txt").write_text("Глава 1", encoding="utf-8")
            (v2_dir / "chapter_0.5.txt").write_text("Пролог 2", encoding="utf-8")

            parser = FolderParser()
            volumes, meta, cover, images = parser.parse(book_dir)

            self.assertEqual(len(volumes[1]), 4)
            self.assertEqual(len(volumes[2]), 1)
            self.assertEqual(volumes[1]["0.5"], "Пролог")
            self.assertEqual(volumes[2]["0.5"], "Пролог 2")


class TestEpubParserLNLN(unittest.TestCase):
    def test_lnln_epub_parses_all_62_sections(self):
        epub_path = Path("was_in_root/cleanup_20260531/LNLN.epub")
        if not epub_path.exists():
            self.skipTest("Файл LNLN.epub не найден")

        parser = EpubParser()
        volumes, meta, cover, images = parser.parse(epub_path)

        self.assertIn(1, volumes)
        self.assertIn(2, volumes)
        self.assertEqual(len(volumes[1]), 36)
        self.assertEqual(len(volumes[2]), 26)

        # Проверяем, что 0.0, 0, 0.1, 0.5 не склеились в одну главу
        self.assertIn("0.0", volumes[1])
        self.assertIn("0", volumes[1])
        self.assertIn("0.1", volumes[1])
        self.assertIn("0.5", volumes[2])

        # Проверяем, что пролог 0.5 содержит сюжетный диалог Джинши и Императора
        v2_prologue = volumes[2]["0.5"]
        self.assertIn("Джинши", v2_prologue)
        self.assertIn("Император", v2_prologue)


class TestBookConverterIntegration(unittest.TestCase):
    def test_book_converter_with_fractional_chapters_and_service_marking(self):
        import shutil
        from core import path_manager
        from sqlmodel import select

        book_name = "test_fractional_book"
        input_dir = path_manager.get_book_dir(book_name)
        output_dir = path_manager.get_book_output_dir(book_name)

        if input_dir.exists():
            shutil.rmtree(input_dir)
        if output_dir.exists():
            shutil.rmtree(output_dir)

        try:
            with tempfile.TemporaryDirectory() as tmpdir:
                zip_path = Path(tmpdir) / f"{book_name}.zip"
                with zipfile.ZipFile(zip_path, "w") as zf:
                    zf.writestr("metadata.json", json.dumps({"title": "Фармацевт Тест", "author": "Автор"}))
                    zf.writestr("vol_1/chapter_0.0.txt", "# Внимание\nЭтот перевод является машинным.")
                    zf.writestr("vol_1/chapter_0.txt", "# Иллюстрации\nТом 1 Иллюстрации.")
                    zf.writestr("vol_1/chapter_0.1.txt", "# Аннотация\nКраткая аннотация.")
                    zf.writestr("vol_1/chapter_0.5.txt", "# Пролог\nМаомао сидела в лавке травника и толкла корень лотоса. Травы пахли терпко и свежо, напоминая о прошедшем лете и обещали долгую и спокойную зиму в родном квартале удовольствий.")
                    zf.writestr("vol_1/chapter_1.txt", "# Маомао\nНачало истории Маомао во внешнем дворце. Девушка не ожидала, что её похитят торговцы людьми и продадут служанкой во дворец императора.")

                converter = BookConverter(zip_path)
                converter.run()

                repo = BookRepository(book_name)
                with repo.get_session() as session:
                    chapters = session.exec(
                        select(Chapter).where(Chapter.book_id == book_name).order_by(Chapter.order_index)
                    ).all()

                    self.assertEqual(len(chapters), 5)

                    expected_ids = [
                        "vol_1_chap_0",
                        "vol_1_chap_0.0",
                        "vol_1_chap_0.1",
                        "vol_1_chap_0.5",
                        "vol_1_chap_1",
                    ]
                    actual_ids = [c.id for c in chapters]
                    self.assertEqual(actual_ids, expected_ids)

                    # Проверяем последовательность order_index
                    for idx, c in enumerate(chapters, 1):
                        self.assertEqual(c.order_index, idx)

                    # Проверяем служебную разметку
                    by_id = {c.id: c for c in chapters}
                    self.assertTrue(by_id["vol_1_chap_0.0"].is_service)
                    self.assertTrue(by_id["vol_1_chap_0"].is_service)
                    self.assertTrue(by_id["vol_1_chap_0.1"].is_service)
                    self.assertFalse(by_id["vol_1_chap_0.5"].is_service)
                    self.assertFalse(by_id["vol_1_chap_1"].is_service)

                    # Проверяем извлечение текста через path_manager
                    text_0_5 = path_manager.get_chapter_text_path(book_name, 1, 0.5).read_text(encoding="utf-8")
                    self.assertIn("Маомао сидела в лавке травника", text_0_5)

                    text_1 = path_manager.get_chapter_text_path(book_name, 1, 1).read_text(encoding="utf-8")
                    self.assertIn("Начало истории Маомао", text_1)
        finally:
            if input_dir.exists():
                shutil.rmtree(input_dir)
            if output_dir.exists():
                shutil.rmtree(output_dir)


if __name__ == "__main__":
    unittest.main()
