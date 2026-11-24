import unittest
import tempfile
import shutil
import sys
from pathlib import Path
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).parent.parent))

from api_server import create_app
from api import state
from core.database import init_book_db, get_book_session
from core.data_models import Book, Chapter, Character, ChapterSummary, ScenarioEntry


class TestMobileAPI(unittest.TestCase):
    def setUp(self):
        self.temp_dir = Path(tempfile.mkdtemp())
        self.book_id = "mobile_test_book"
        self.book_dir = self.temp_dir / self.book_id
        self.book_dir.mkdir(parents=True, exist_ok=True)

        init_book_db(self.book_dir)
        with get_book_session(self.book_dir) as session:
            book = Book(id=self.book_id, title="Mobile Test Book", author="Mobile Author")
            session.add(book)

            chap1 = Chapter(id="vol_1_chap_1", book_id=self.book_id, volume_num=1, chapter_num=1, title="Opening Chapter", raw_text="Mobile raw text content")
            session.add(chap1)

            summary1 = ChapterSummary(chapter_id="vol_1_chap_1", teaser="Mobile teaser", synopsis="Mobile synopsis")
            session.add(summary1)

            char1 = Character(book_id=self.book_id, name="Heroine", description="Female lead", spoiler_free_description="Heroine")
            session.add(char1)

            entry1 = ScenarioEntry(chapter_id="vol_1_chap_1", type="narration", text="Beginning of adventure", order_index=1)
            session.add(entry1)

            session.commit()

        # Patch config.OUTPUT_DIR to temp directory for mobile tests
        import config
        self.original_output_dir = config.OUTPUT_DIR
        config.OUTPUT_DIR = self.temp_dir

        self.app = create_app()
        self.client = TestClient(self.app)
        self.headers = {"Authorization": f"Bearer {state.SERVER_TOKEN}"}

    def tearDown(self):
        import config
        from core.database import dispose_engine
        dispose_engine(self.book_dir)
        config.OUTPUT_DIR = self.original_output_dir
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_ping_and_onboarding(self):
        res = self.client.get("/api/ping")
        self.assertEqual(res.status_code, 200)
        self.assertEqual(res.json()["status"], "ok")

        res_onboarding = self.client.get("/api/onboarding-data")
        self.assertEqual(res_onboarding.status_code, 200)
        self.assertIn("token", res_onboarding.json())

    def test_get_all_books(self):
        res = self.client.get("/api/books", headers=self.headers)
        self.assertEqual(res.status_code, 200)
        books = res.json()
        self.assertEqual(len(books), 1)
        self.assertEqual(books[0]["book_name"], self.book_id)

    def test_get_book_structure(self):
        res = self.client.get(f"/api/books/{self.book_id}/structure", headers=self.headers)
        self.assertEqual(res.status_code, 200)
        data = res.json()
        self.assertEqual(data["manifest"]["book_name"], self.book_id)
        self.assertEqual(len(data["chapters"]), 1)
        self.assertEqual(data["chapters"][0]["id"], "vol_1_chap_1")

    def test_get_original_text(self):
        res = self.client.get(f"/api/books/{self.book_id}/vol_1_chap_1/originalText", headers=self.headers)
        self.assertEqual(res.status_code, 200)
        self.assertEqual(res.text, "Mobile raw text content")

    def test_get_characters(self):
        res = self.client.get(f"/api/books/{self.book_id}/characters", headers=self.headers)
        self.assertEqual(res.status_code, 200)
        chars = res.json()
        self.assertEqual(len(chars), 1)
        self.assertEqual(chars[0]["name"], "Heroine")

    def test_get_chapter_info(self):
        res = self.client.get(f"/api/books/{self.book_id}/chapters/vol_1_chap_1/info", headers=self.headers)
        self.assertEqual(res.status_code, 200)
        info = res.json()
        self.assertEqual(info["teaser"], "Mobile teaser")

    def test_get_playback_data(self):
        res = self.client.get(f"/api/books/{self.book_id}/vol_1_chap_1/playbackData", headers=self.headers)
        self.assertEqual(res.status_code, 200)
        playback = res.json()
        self.assertIn("sync_map", playback)
        self.assertEqual(len(playback["sync_map"]), 1)
    def test_range_requests_audio(self):
        # Create dummy audio file
        audio_dir = self.book_dir / "vol_1_chap_1" / "audio"
        audio_dir.mkdir(parents=True, exist_ok=True)
        dummy_audio = audio_dir / "001_narrator.mp3"
        dummy_audio.write_bytes(b"A" * 1000)

        # Full file request
        res_full = self.client.get(f"/static/books/{self.book_id}/vol_1_chap_1/audio/001_narrator.mp3", headers=self.headers)
        self.assertEqual(res_full.status_code, 200)

        # Range request: fixed range
        range_headers = {**self.headers, "Range": "bytes=0-99"}
        res_range = self.client.get(f"/static/books/{self.book_id}/vol_1_chap_1/audio/001_narrator.mp3", headers=range_headers)
        self.assertEqual(res_range.status_code, 206)
        self.assertEqual(res_range.headers.get("content-range"), "bytes 0-99/1000")
        self.assertEqual(len(res_range.content), 100)

        # Range request: open-ended
        open_range_headers = {**self.headers, "Range": "bytes=900-"}
        res_open = self.client.get(f"/static/books/{self.book_id}/vol_1_chap_1/audio/001_narrator.mp3", headers=open_range_headers)
        self.assertEqual(res_open.status_code, 206)
        self.assertEqual(res_open.headers.get("content-range"), "bytes 900-999/1000")
        self.assertEqual(len(res_open.content), 100)

        # Range request: suffix
        suffix_range_headers = {**self.headers, "Range": "bytes=-50"}
        res_suffix = self.client.get(f"/static/books/{self.book_id}/vol_1_chap_1/audio/001_narrator.mp3", headers=suffix_range_headers)
        self.assertEqual(res_suffix.status_code, 206)
        self.assertEqual(res_suffix.headers.get("content-range"), "bytes 950-999/1000")
        self.assertEqual(len(res_suffix.content), 50)

        # Range request: invalid range
        invalid_range_headers = {**self.headers, "Range": "bytes=2000-3000"}
        res_invalid = self.client.get(f"/static/books/{self.book_id}/vol_1_chap_1/audio/001_narrator.mp3", headers=invalid_range_headers)
        self.assertEqual(res_invalid.status_code, 416)


if __name__ == "__main__":
    unittest.main()
