"""
Тесты предотвращения дрейфа имен в пересказах глав и томов.
"""
import unittest
from unittest.mock import MagicMock, patch
from uuid import uuid4

from core.data_models import Character, Chapter, ChapterSummary, VolumeSummary, RawChapterSummary
from core.book_repository import BookRepository
from pipelines import prompts
from pipelines.summary_generation import SummaryGenerationPipeline


class TestSummaryNameDrift(unittest.TestCase):
    def test_format_characters_for_summary_empty(self):
        self.assertEqual(prompts.format_characters_for_summary(None), "")
        self.assertEqual(prompts.format_characters_for_summary([]), "")

    def test_format_characters_for_summary_with_aliases(self):
        c1 = Character(
            id=uuid4(),
            book_id="book",
            name="А-Дуо",
            spoiler_free_description="Супруга",
            description="Чистая супруга",
            aliases=["Чистая супруга", "госпожа А-Дуо"]
        )
        c2 = Character(
            id=uuid4(),
            book_id="book",
            name="Маомао",
            spoiler_free_description="Аптекарь",
            description="Аптекарь",
            aliases=[]
        )
        block = prompts.format_characters_for_summary([c1, c2])
        self.assertIn("КАНОНИЧЕСКИЕ ИМЕНА И ТИТУЛЫ ПЕРСОНАЖЕЙ", block)
        self.assertIn("А-Дуо (Чистая супруга, госпожа А-Дуо)", block)
        self.assertIn("Маомао", block)

    def test_summary_generation_prompt_includes_characters(self):
        c = Character(
            id=uuid4(),
            book_id="book",
            name="Супруга Рифа",
            spoiler_free_description="Супруга",
            description="Супруга",
            aliases=["госпожа Рифа"]
        )
        # Без персонажей (холодный старт первой главы)
        prompt_cold = prompts.format_summary_generation_prompt(
            chapter_text="Текст главы...",
            chapter_id="vol_1_chap_1",
            previous_summaries=[]
        )
        self.assertNotIn("КАНОНИЧЕСКИЕ ИМЕНА", prompt_cold)

        # С известными персонажами
        prompt_warm = prompts.format_summary_generation_prompt(
            chapter_text="Текст главы...",
            chapter_id="vol_1_chap_2",
            previous_summaries=[],
            known_characters=[c]
        )
        self.assertIn("КАНОНИЧЕСКИЕ ИМЕНА И ТИТУЛЫ ПЕРСОНАЖЕЙ", prompt_warm)
        self.assertIn("Супруга Рифа (госпожа Рифа)", prompt_warm)

    def test_volume_summary_prompt_includes_characters(self):
        c = Character(
            id=uuid4(),
            book_id="book",
            name="Джинши",
            spoiler_free_description="Евнух",
            description="Евнух",
            aliases=[]
        )
        prompt = prompts.format_volume_summary_prompt(
            volume_num=1,
            chapter_summaries=["Глава 1: Пересказ..."],
            known_characters=[c]
        )
        self.assertIn("КАНОНИЧЕСКИЕ ИМЕНА И ТИТУЛЫ ПЕРСОНАЖЕЙ", prompt)
        self.assertIn("Джинши", prompt)

    def test_character_recon_card_prompt_guardrail(self):
        prompt = prompts.format_character_recon_card_prompt(
            chapter_text="Текст главы...",
            known_characters_json="[]",
            chapter_summary="В этой главе советник грустит о ребенке."
        )
        self.assertIn("НЕ создавай новое имя в newly_discovered_names", prompt)
        self.assertIn("сопоставляй с существующим `ref`", prompt)

    @patch("pipelines.prompts.format_summary_generation_prompt")
    def test_pipeline_passes_characters_to_summary_prompt(self, mock_prompt_format):
        mock_prompt_format.return_value = "dummy prompt"
        repo = MagicMock(spec=BookRepository)
        repo.book_id = "b"
        char = Character(
            id=uuid4(),
            book_id="b",
            name="Маомао",
            spoiler_free_description="d",
            description="d"
        )
        repo.get_characters.return_value = [char]
        chap = Chapter(id="vol_1_chap_2", book_id="b", volume_num=1, chapter_num=2, is_service=False)
        repo.get_all_chapters.return_value = [chap]
        repo.get_summary_archive.return_value = MagicMock(summaries={})
        repo.get_chapter_text.return_value = "Chapter text"
        repo.get_volume_summary.return_value = None

        mock_llm = MagicMock()
        mock_llm.call_for_pydantic.side_effect = lambda model_cls, *args, **kwargs: (
            VolumeSummary(volume_num=1, summary="Vol summary") if model_cls == VolumeSummary
            else RawChapterSummary(teaser="t", synopsis="s")
        )
        mm = MagicMock()
        mm.get_llm_service.return_value = mock_llm

        pipe = SummaryGenerationPipeline(mm)
        with patch("pipelines.summary_generation.BookRepository", return_value=repo), \
             patch("core.service_chapters.detect_and_mark_service_chapters"):
            pipe.run("b", max_chapters=1)

        self.assertTrue(mock_prompt_format.called)
        kwargs = mock_prompt_format.call_args.kwargs
        self.assertEqual(kwargs.get("known_characters"), [char])


if __name__ == "__main__":
    unittest.main()
