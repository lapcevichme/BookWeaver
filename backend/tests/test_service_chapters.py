"""Тесты гибридной детекции служебных глав (эвристики + уверенность LLM)."""
import unittest
from unittest.mock import MagicMock
import tempfile
import shutil
from pathlib import Path

from core.data_models import Chapter, Book, ChapterStatus
from core.book_repository import BookRepository
from core.service_chapters import (
    is_heuristic_service_chapter,
    classify_service_chapter_llm,
    classify_chapter,
    detect_and_mark_service_chapters,
)


class TestServiceChaptersHeuristics(unittest.TestCase):
    def test_empty_or_too_short(self):
        is_srv, conf, reason = is_heuristic_service_chapter("Заголовок", "   ")
        self.assertTrue(is_srv)
        self.assertEqual(conf, 1.0)
        self.assertEqual(reason, "empty_or_too_short")

        is_srv, conf, reason = is_heuristic_service_chapter("Заголовок", "Короткий текст на 30 символов.")
        self.assertTrue(is_srv)
        self.assertEqual(conf, 1.0)

    def test_title_keywords(self):
        titles = [
            "Глоссарий персонажей",
            "Примечания переводчика",
            "Заметки от переводчика к тому 1",
            "Послесловие автора",
            "Цветные иллюстрации",
            "Glossary of Terms",
            "Translator's Afterword",
        ]
        story_text = "Это длинный художественный текст повествования. " * 10
        for t in titles:
            is_srv, conf, reason = is_heuristic_service_chapter(t, story_text)
            self.assertTrue(is_srv, f"Failed for title: {t}")
            self.assertEqual(conf, 1.0)
            self.assertIn("title_keyword", reason)

    def test_text_disclaimers(self):
        disclaimers = [
            "Внимание п.п: Этот перевод является машинным. Я сделал его чтобы почитать самому, но потом решил выложить...",
            "Сообщение от переводчика данной книги. Сюжетной роли нет, приятного чтения!",
            "Том 2 Глава 0.0 - Имена персонажей: 貓貓 — Маомао — Иероглиф означает Кошка. Хань Ло Мень (кит.) — Кан Луомен (яп.)...",
        ]
        for disc in disclaimers:
            full_text = disc + (" Текст заполнитель. " * 10)
            is_srv, conf, reason = is_heuristic_service_chapter("Глава без ключевых слов", full_text)
            self.assertTrue(is_srv)
            self.assertEqual(conf, 1.0)
            self.assertEqual(reason, "text_keyword_disclaimer")

    def test_chapter_zero_not_blindly_killed_if_narrative(self):
        # Если Глава 0 содержит дисклеймер:
        disc = "Том 1 Глава 0. Примечания переводчика: данный релиз подготовлен для ознакомления..." + (" текст " * 20)
        is_srv, conf, reason = is_heuristic_service_chapter("Глава 0", disc, chapter_num=0)
        self.assertTrue(is_srv)
        self.assertEqual(conf, 1.0)

        # Если Глава 0 является настоящим художественным прологом:
        prologue = "Снег падал на черепичные крыши Запретного города. Маомао куталась в ветхий плащ, глядя на высокие стены..." * 5
        is_srv, conf, reason = is_heuristic_service_chapter("Глава 0. Пролог", prologue, chapter_num=0)
        # Не должна слепо отсекаться! Помечается как подозрительная для второго этапа (LLM)
        self.assertFalse(is_srv)
        self.assertEqual(conf, 0.5)
        self.assertEqual(reason, "suspect_chapter_zero")

    def test_normal_story_chapter(self):
        story = "Маомао аккуратно разложила сушеные травы на циновке. Аптекарь Луомен одобрительно кивнул ее стараниям." * 5
        is_srv, conf, reason = is_heuristic_service_chapter("Глава 1. Продавец трав", story, chapter_num=1)
        self.assertFalse(is_srv)
        self.assertEqual(conf, 1.0)
        self.assertEqual(reason, "story")


class TestServiceChaptersLLMAndDB(unittest.TestCase):
    def setUp(self):
        self.test_dir = tempfile.mkdtemp()
        self.book_id = "test_service_book"
        self.db_path = Path(self.test_dir) / "project.db"

        # Инициализируем тестовый BookRepository на временной директории
        import config
        self._orig_output_dir = config.OUTPUT_DIR
        config.OUTPUT_DIR = Path(self.test_dir)

        self.repo = BookRepository(self.book_id)
        with self.repo.get_session() as session:
            session.add(Book(id=self.book_id, title="Test Service Book"))
            session.commit()

    def tearDown(self):
        import config
        config.OUTPUT_DIR = self._orig_orig = self._orig_output_dir
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def test_llm_classification_service_high_confidence(self):
        mock_llm = MagicMock()
        mock_llm.choice_probabilities.return_value = {"да": 0.96, "нет": 0.04}

        text = "Заметки на полях: переводчик благодарит читателей за донаты..." * 5
        is_srv, conf, reason = classify_service_chapter_llm(text, mock_llm)

        self.assertTrue(is_srv)
        self.assertAlmostEqual(conf, 0.96)
        self.assertIn("llm_service_prob", reason)

    def test_llm_classification_story_high_confidence(self):
        mock_llm = MagicMock()
        mock_llm.choice_probabilities.return_value = {"да": 0.02, "нет": 0.98}

        text = "Она шла по тёмному коридору внутреннего дворца, прислушиваясь к каждому шороху..." * 5
        is_srv, conf, reason = classify_service_chapter_llm(text, mock_llm)

        self.assertFalse(is_srv)
        self.assertAlmostEqual(conf, 0.98)
        self.assertIn("llm_story_prob", reason)

    def test_hybrid_flow_and_detect_and_mark_service_chapters(self):
        with self.repo.get_session() as session:
            # 1. Служебная глава 0 с дисклеймером
            c0 = Chapter(
                id="vol_1_chap_0", book_id=self.book_id, volume_num=1, chapter_num=0,
                title="Глава 0.0", raw_text="Внимание п.п: Этот перевод является машинным..." * 5,
                order_index=1
            )
            # 2. Настоящий сюжет
            c1 = Chapter(
                id="vol_1_chap_1", book_id=self.book_id, volume_num=1, chapter_num=1,
                title="Глава 1", raw_text="Маомао собирала травы в лесу и встретила незнакомца." * 5,
                order_index=2
            )
            # 3. Пустая страница в конце тома
            c2 = Chapter(
                id="vol_1_chap_2", book_id=self.book_id, volume_num=1, chapter_num=2,
                title="Пустая", raw_text="   ",
                order_index=3
            )
            # 4. Подозрительная глава 3 (Пролог без ключевых слов)
            c3 = Chapter(
                id="vol_1_chap_3", book_id=self.book_id, volume_num=1, chapter_num=0,
                title="Глава 0. Пролог", raw_text="В далеком царстве правил старый император." * 5,
                order_index=4
            )
            session.add_all([c0, c1, c2, c3])
            session.commit()

        # Мокаем LLM для подозрительной c3 (модель должна подтвердить, что это сюжет)
        mock_llm = MagicMock()
        mock_llm.choice_probabilities.return_value = {"да": 0.05, "нет": 0.95}

        marked = detect_and_mark_service_chapters(self.repo, llm_service=mock_llm)
        marked_ids = [m[0] for m in marked]

        # c0 и c2 должны быть помечены как служебные
        self.assertIn("vol_1_chap_0", marked_ids)
        self.assertIn("vol_1_chap_2", marked_ids)
        # c1 и c3 — сюжетные
        self.assertNotIn("vol_1_chap_1", marked_ids)
        self.assertNotIn("vol_1_chap_3", marked_ids)

        # Проверяем метод get_story_chapters
        story_chaps = self.repo.get_story_chapters()
        story_ids = [c.id for c in story_chaps]
        self.assertEqual(story_ids, ["vol_1_chap_1", "vol_1_chap_3"])

        # Проверяем, что служебным главам выставлен characters_state = skipped
        chap_0 = self.repo.get_chapter("vol_1_chap_0")
        self.assertTrue(chap_0.is_service)
        self.assertEqual(chap_0.characters_state, "skipped")
        self.assertEqual(chap_0.status, ChapterStatus.COMPLETED)


if __name__ == "__main__":
    unittest.main()
