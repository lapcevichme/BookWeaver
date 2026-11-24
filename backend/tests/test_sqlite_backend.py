import unittest
import tempfile
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from fastapi.testclient import TestClient

from core.database import init_book_db, get_book_session
from core.book_repository import BookRepository
from core.data_models import Book, Chapter, Character
from api_server import create_app


class TestSQLiteBackend(unittest.TestCase):
    def setUp(self):
        self.temp_dir = Path(tempfile.mkdtemp())
        self.book_id = "test_book_1"
        self.book_dir = self.temp_dir / self.book_id
        self.book_dir.mkdir(parents=True, exist_ok=True)

        init_book_db(self.book_dir)
        with get_book_session(self.book_dir) as session:
            book = Book(id=self.book_id, title="Test Book", author="Test Author")
            session.add(book)
            chap = Chapter(id="vol_1_chap_1", book_id=self.book_id, volume_num=1, chapter_num=1, title="Intro")
            session.add(chap)
            chap2 = Chapter(id="vol_1_chap_2", book_id=self.book_id, volume_num=1, chapter_num=2, title="Chapter 2")
            session.add(chap2)
            char = Character(book_id=self.book_id, name="Hero", description="Main character", spoiler_free_description="Hero")
            session.add(char)
            session.commit()

        self.repo = BookRepository(book_id=self.book_id)
        # Point repository to temp directory for testing
        self.repo.book_output_dir = self.book_dir

    def tearDown(self):
        from core.database import dispose_engine
        dispose_engine(self.book_dir)
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_book_repository_get_book(self):
        book = self.repo.get_book()
        self.assertIsNotNone(book)
        self.assertEqual(book.title, "Test Book")
        self.assertEqual(book.author, "Test Author")

    def test_book_repository_chapters_and_characters(self):
        chapters = self.repo.get_all_chapters()
        self.assertEqual(len(chapters), 2)
        self.assertEqual(chapters[0].id, "vol_1_chap_1")
        self.assertEqual(chapters[1].id, "vol_1_chap_2")

        characters = self.repo.get_characters()
        self.assertEqual(len(characters), 1)
        self.assertEqual(characters[0].name, "Hero")

    def test_sqlite_pragmas(self):
        with get_book_session(self.book_dir) as session:
            from sqlalchemy import text
            journal_mode = session.execute(text("PRAGMA journal_mode;")).scalar()
            busy_timeout = session.execute(text("PRAGMA busy_timeout;")).scalar()
            self.assertEqual(str(journal_mode).lower(), "wal")
            self.assertEqual(int(busy_timeout), 5000)

    def test_sqlite_optimize(self):
        from core.database import optimize_db, get_engine_for_book
        engine = get_engine_for_book(self.book_dir)
        res = optimize_db(engine)
        self.assertIsInstance(res, list)

        repo_res = self.repo.optimize()
        self.assertIsInstance(repo_res, list)

    def test_fastapi_app_endpoints(self):
        app = create_app()
        client = TestClient(app)

        response = client.get("/api/ping")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["status"], "ok")

        response = client.get("/api/v1/projects/dashboard/summary")
        self.assertEqual(response.status_code, 200)

        response = client.get("/api/v1/projects/")
        self.assertEqual(response.status_code, 200)

        import config
        orig_out = config.OUTPUT_DIR
        try:
            config.OUTPUT_DIR = self.temp_dir
            res = client.get(f"/api/v1/projects/{self.book_id}")
            self.assertEqual(res.status_code, 200)
            data = res.json()
            # Проверяем, что возвращены ВСЕ главы книги, а не одна
            self.assertEqual(len(data["chapters"]), 2)
            self.assertEqual(data["chapters"][0]["id"], "vol_1_chap_1")
            self.assertEqual(data["chapters"][1]["id"], "vol_1_chap_2")
        finally:
            config.OUTPUT_DIR = orig_out

    def test_pywhispercpp_tts_service(self):
        import wave, struct, math
        from services.tts_service import TTSService
        service = TTSService()
        
        audio_path = self.temp_dir / "sample.wav"
        with wave.open(str(audio_path), 'w') as f:
            f.setnchannels(1)
            f.setsampwidth(2)
            f.setframerate(16000)
            for i in range(16000 * 2):
                val = int(32767.0 * 0.3 * math.sin(2.0 * math.pi * 440.0 * i / 16000))
                f.writeframes(struct.pack('<h', val))

        txt = service._get_prompt_text(audio_path)
        self.assertIsInstance(txt, str)
        timings = service.generate_word_timings("sample", audio_path)
        self.assertIsInstance(timings, list)


if __name__ == "__main__":
    unittest.main()
