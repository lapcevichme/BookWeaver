import unittest
import tempfile
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from core.database import init_book_db, get_book_session, init_system_db, get_system_session
from core.book_repository import BookRepository
from core.data_models import Book, Chapter, Character, ScenarioEntry, ChapterSummary
from core.system_models import SystemSetting, AmbientTrack, SFXTrack


class TestRepository(unittest.TestCase):
    def setUp(self):
        self.temp_dir = Path(tempfile.mkdtemp())
        self.book_id = "repo_test_book"
        self.book_dir = self.temp_dir / self.book_id
        self.book_dir.mkdir(parents=True, exist_ok=True)

        init_book_db(self.book_dir)
        with get_book_session(self.book_dir) as session:
            book = Book(id=self.book_id, title="Repo Test Book", author="Test Author")
            session.add(book)
            chap1 = Chapter(id="vol_1_chap_1", book_id=self.book_id, volume_num=1, chapter_num=1, title="Chapter One", raw_text="Hello world raw text")
            session.add(chap1)
            char1 = Character(book_id=self.book_id, name="Alice", description="Protagonist", spoiler_free_description="Alice")
            session.add(char1)
            summary1 = ChapterSummary(chapter_id="vol_1_chap_1", teaser="Chapter 1 Teaser", synopsis="Chapter 1 Synopsis")
            session.add(summary1)
            session.commit()

        self.repo = BookRepository(book_id=self.book_id)
        self.repo.book_output_dir = self.book_dir

    def tearDown(self):
        from core.database import dispose_engine
        dispose_engine(self.book_dir)
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_get_book(self):
        book = self.repo.get_book()
        self.assertIsNotNone(book)
        self.assertEqual(book.title, "Repo Test Book")

    def test_get_chapter_and_text(self):
        chap = self.repo.get_chapter("vol_1_chap_1")
        self.assertIsNotNone(chap)
        self.assertEqual(chap.title, "Chapter One")

        text = self.repo.get_chapter_text("vol_1_chap_1")
        self.assertEqual(text, "Hello world raw text")

    def test_update_chapter_status(self):
        self.repo.update_chapter_status("vol_1_chap_1", "completed")
        chap = self.repo.get_chapter("vol_1_chap_1")
        self.assertEqual(chap.status, "completed")

    def test_characters_crud(self):
        chars = self.repo.get_characters()
        self.assertEqual(len(chars), 1)
        self.assertEqual(chars[0].name, "Alice")

        new_char = Character(book_id=self.book_id, name="Bob", description="Sidekick", spoiler_free_description="Bob")
        self.repo.save_characters([new_char])

        chars_updated = self.repo.get_characters()
        self.assertEqual(len(chars_updated), 2)

    def test_scenario_entries(self):
        with self.repo.get_session() as session:
            entry1 = ScenarioEntry(chapter_id="vol_1_chap_1", type="narration", text="Once upon a time", order_index=1)
            entry2 = ScenarioEntry(chapter_id="vol_1_chap_1", type="dialogue", text="Hi Alice", speaker_name="Bob", order_index=2)
            session.add(entry1)
            session.add(entry2)
            session.commit()

        entries = self.repo.get_scenario_entries("vol_1_chap_1")
        self.assertEqual(len(entries), 2)
        self.assertEqual(entries[0].text, "Once upon a time")
        self.assertEqual(entries[1].text, "Hi Alice")

    def test_load_manifest(self):
        manifest = self.repo.load_manifest()
        self.assertEqual(manifest.project_id, self.book_id)
        self.assertEqual(manifest.meta.title, "Repo Test Book")
        self.assertEqual(len(manifest.structure), 1)

    def test_chapter_cache(self):
        self.repo.set_cache("vol_1_chap_1", "raw_scenario", {"scenes": [1, 2, 3]})
        cached = self.repo.get_cache("vol_1_chap_1", "raw_scenario")
        self.assertEqual(cached, {"scenes": [1, 2, 3]})

        # Проверяем, что кэш лежит именно в ChapterCache
        with self.repo.get_session() as session:
            from core.data_models import ChapterCache
            cache_row = session.get(ChapterCache, "vol_1_chap_1")
            self.assertIsNotNone(cache_row)
            self.assertEqual(cache_row.raw_scenario, {"scenes": [1, 2, 3]})

    def test_character_delete_sets_null_on_scenario_entry(self):
        chars = self.repo.get_characters()
        alice = next(c for c in chars if c.name == "Alice")

        with self.repo.get_session() as session:
            entry = ScenarioEntry(
                chapter_id="vol_1_chap_1",
                speaker_id=alice.id,
                speaker_name="Alice",
                type="dialogue",
                text="Hello from Alice",
                order_index=10
            )
            session.add(entry)
            session.commit()
            entry_id = entry.id

        # Удаляем персонажа
        with self.repo.get_session() as session:
            db_alice = session.get(Character, alice.id)
            session.delete(db_alice)
            session.commit()

        # Реплика должна остаться, но speaker_id должен стать None (ondelete="SET NULL")
        with self.repo.get_session() as session:
            db_entry = session.get(ScenarioEntry, entry_id)
            self.assertIsNotNone(db_entry)
            self.assertIsNone(db_entry.speaker_id)
            self.assertEqual(db_entry.speaker_name, "Alice")
            self.assertEqual(db_entry.text, "Hello from Alice")

    def test_chapter_delete_cascades_entries_and_summary(self):
        with self.repo.get_session() as session:
            entry = ScenarioEntry(chapter_id="vol_1_chap_1", type="narration", text="To be deleted", order_index=99)
            session.add(entry)
            session.commit()

        with self.repo.get_session() as session:
            chap = session.get(Chapter, "vol_1_chap_1")
            session.delete(chap)
            session.commit()

        with self.repo.get_session() as session:
            entries = self.repo.get_scenario_entries("vol_1_chap_1")
            self.assertEqual(len(entries), 0)
            summary = session.get(ChapterSummary, "vol_1_chap_1")
            self.assertIsNone(summary)

    def test_mutable_json_in_place_updates(self):
        chars = self.repo.get_characters()
        alice = next(c for c in chars if c.name == "Alice")

        # In-place мутации списков и словарей в JSON
        with self.repo.get_session() as session:
            db_alice = session.get(Character, alice.id)
            db_alice.aliases.append("Ali")
            db_alice.chapter_mentions["vol_1_chap_1"] = "Main hero action"
            session.commit()

        # Проверяем в новой сессии, что изменения применились на диске
        with self.repo.get_session() as session:
            db_alice = session.get(Character, alice.id)
            self.assertIn("Ali", db_alice.aliases)
            self.assertEqual(db_alice.chapter_mentions.get("vol_1_chap_1"), "Main hero action")

        # In-place мутации в Book.tags и Book.config
        with self.repo.get_session() as session:
            db_book = session.get(Book, self.book_id)
            db_book.tags.append("fantasy")
            db_book.config["tts_engine"] = "cosyvoice"
            session.commit()

        with self.repo.get_session() as session:
            db_book = session.get(Book, self.book_id)
            self.assertIn("fantasy", db_book.tags)
            self.assertEqual(db_book.config.get("tts_engine"), "cosyvoice")

    def test_system_models(self):
        engine = init_system_db()
        self.assertIsNotNone(engine)
        with get_system_session() as session:
            setting = SystemSetting(key="test_key", value_json='"test_val"')
            session.merge(setting)
            session.commit()

            fetched = session.get(SystemSetting, "test_key")
            self.assertIsNotNone(fetched)
            self.assertEqual(fetched.value, "test_val")

    def test_dto_separation(self):
        from pydantic import BaseModel
        from sqlmodel import SQLModel
        from core.data_models import (
            CharacterPatch, CharacterPatchList, RawScenario, RawScenarioEntry,
            SoundDesignResult, SoundDesignItem, EmotionMap, VoiceDirection,
            VolumeSummary, BookManifest, CharacterType, RoleTier, CharacterGender
        )

        dtos = [
            CharacterPatch, CharacterPatchList, RawScenario, RawScenarioEntry,
            SoundDesignResult, SoundDesignItem, EmotionMap, VoiceDirection,
            VolumeSummary, BookManifest
        ]
        for dto in dtos:
            self.assertTrue(issubclass(dto, BaseModel), f"{dto} must be Pydantic BaseModel")
            self.assertFalse(issubclass(dto, SQLModel), f"{dto} must NOT be SQLModel")

        # Проверяем валидаторы CharacterPatch
        patch = CharacterPatch(
            name="Боб",
            entity_type="PERSON",  # Проверка приведения к нижнему регистру
            role_tier="PROTAGONIST",
            gender="MALE"
        )
        self.assertEqual(patch.entity_type, CharacterType.PERSON)
        self.assertEqual(patch.role_tier, RoleTier.PROTAGONIST)
        self.assertEqual(patch.gender, CharacterGender.MALE)

        # Проверяем валидаторы EmotionMap (оборачивание словаря)
        from uuid import uuid4
        uid = uuid4()
        emo_map = EmotionMap.model_validate({uid: {"emotion": "Whisper", "tts_text": "тихо"}})
        self.assertIn(uid, emo_map.emotions)
        self.assertEqual(emo_map.emotions[uid].emotion, "whisper")
        # Свободный текст вместо метки (старый формат instruct) не ломает этап, а дает neutral
        emo_map = EmotionMap.model_validate({uid: {"emotion": "громко, с восторгом"}})
        self.assertEqual(emo_map.emotions[uid].emotion, "neutral")


if __name__ == "__main__":
    unittest.main()
