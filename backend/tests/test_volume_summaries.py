"""Тесты генерации, сохранения и сквозной передачи пересказов томов (VolumeSummary)."""
import unittest
from unittest.mock import MagicMock, patch
import tempfile
import shutil
from pathlib import Path

from core.data_models import Chapter, Book, ChapterSummary, VolumeSummary, RawChapterSummary
from core.book_repository import BookRepository
from pipelines.summary_generation import SummaryGenerationPipeline
import pipelines.prompts as prompts


class TestVolumeSummaries(unittest.TestCase):
    def setUp(self):
        self.test_dir = tempfile.mkdtemp()
        self.book_id = "test_vol_book"

        import config
        self._orig_output = config.OUTPUT_DIR
        config.OUTPUT_DIR = Path(self.test_dir)

        self.repo = BookRepository(self.book_id)
        with self.repo.get_session() as session:
            session.add(Book(id=self.book_id, title="Test Volume Book"))
            session.commit()

    def tearDown(self):
        import config
        config.OUTPUT_DIR = self._orig_output
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def test_repo_volume_summary_crud(self):
        self.assertIsNone(self.repo.get_volume_summary(1))

        self.repo.save_volume_summary(1, "События тома 1: Маомао попала во дворец.")
        vol1 = self.repo.get_volume_summary(1)
        self.assertIsNotNone(vol1)
        self.assertEqual(vol1.volume_num, 1)
        self.assertEqual(vol1.summary, "События тома 1: Маомао попала во дворец.")

        # Проверка upsert (обновление)
        self.repo.save_volume_summary(1, "Обновленное саммари тома 1.")
        vol1_upd = self.repo.get_volume_summary(1)
        self.assertEqual(vol1_upd.summary, "Обновленное саммари тома 1.")

        # Несколько томов
        self.repo.save_volume_summary(2, "События тома 2.")
        all_vols = self.repo.get_all_volume_summaries()
        self.assertEqual(len(all_vols), 2)
        self.assertIn(1, all_vols)
        self.assertIn(2, all_vols)

    def test_prompt_format(self):
        summaries = [
            "Глава 1: Маомао пробует яд.",
            "Глава 2: Джинши раскрывает заговор.",
        ]
        prompt = prompts.format_volume_summary_prompt(1, summaries)
        self.assertIn("ТОМА 1", prompt)
        self.assertIn("Глава 1: Маомао пробует яд.", prompt)
        self.assertIn("Глава 2: Джинши раскрывает заговор.", prompt)
        self.assertIn("volume_num", prompt)
        self.assertIn("summary", prompt)

    def test_generate_volume_summary_method(self):
        with self.repo.get_session() as session:
            c1 = Chapter(id="vol_1_chap_1", book_id=self.book_id, volume_num=1, chapter_num=1, order_index=1)
            c2 = Chapter(id="vol_1_chap_2", book_id=self.book_id, volume_num=1, chapter_num=2, order_index=2)
            s1 = ChapterSummary(chapter_id="vol_1_chap_1", teaser="t1", synopsis="Синопсис 1")
            s2 = ChapterSummary(chapter_id="vol_1_chap_2", teaser="t2", synopsis="Синопсис 2")
            session.add_all([c1, c2, s1, s2])
            session.commit()

        mock_llm = MagicMock()
        mock_llm.call_for_pydantic.return_value = VolumeSummary(
            volume_num=1,
            summary="Глобальное саммари тома 1 от LLM"
        )
        mock_mm = MagicMock()
        mock_mm.get_llm_service.return_value = mock_llm

        pipeline = SummaryGenerationPipeline(mock_mm)
        result = pipeline.generate_volume_summary(self.repo, 1)

        self.assertIsNotNone(result)
        self.assertEqual(result.summary, "Глобальное саммари тома 1 от LLM")

        # Проверяем сохранение в репозитории
        saved = self.repo.get_volume_summary(1)
        self.assertIsNotNone(saved)
        self.assertEqual(saved.summary, "Глобальное саммари тома 1 от LLM")

    def test_pipeline_passes_prev_volume_summary_to_next_volume_chapters(self):
        # Сохраняем рекап тома 1 в репозиторий
        self.repo.save_volume_summary(1, "События Тома 1: Императрица родила наследника.")

        with self.repo.get_session() as session:
            # Создаем главу тома 2 с полноценным текстом (>100 символов)
            c_v2 = Chapter(
                id="vol_2_chap_1", book_id=self.book_id, volume_num=2, chapter_num=1,
                title="Глава 1",
                raw_text="Текст главы второго тома с подробным описанием происходящих во дворце событий. " * 4,
                order_index=1
            )
            session.add(c_v2)
            session.commit()

        captured_prompts = []
        def fake_call(model, prompt, **kwargs):
            captured_prompts.append(prompt)
            if model == RawChapterSummary:
                return RawChapterSummary(teaser="t_v2", synopsis="syn_v2")
            if model == VolumeSummary:
                return VolumeSummary(volume_num=2, summary="Recap vol 2")
            return None

        mock_llm = MagicMock()
        mock_llm.call_for_pydantic.side_effect = fake_call
        mock_mm = MagicMock()
        mock_mm.get_llm_service.return_value = mock_llm

        pipeline = SummaryGenerationPipeline(mock_mm)
        pipeline.run(self.book_id)

        # Проверяем, что в промпт главы тома 2 попал контекст тома 1
        self.assertTrue(len(captured_prompts) >= 1)
        chap_prompt = captured_prompts[0]
        self.assertIn("ГЛОБАЛЬНЫЙ КОНТЕКСТ (СОБЫТИЯ ПРЕДЫДУЩЕГО ТОМА)", chap_prompt)
        self.assertIn("События Тома 1: Императрица родила наследника.", chap_prompt)


if __name__ == "__main__":
    unittest.main()
