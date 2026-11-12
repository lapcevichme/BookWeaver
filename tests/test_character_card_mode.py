"""
Режим карточек (character_description_mode=card): разведка пишет действие каждому персонажу главы,
в патч идут только те, у кого глава меняет карточку личности, и новые имена.
"""
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).parent.parent))

from core.data_models import (Character, ChapterSummaryArchive, CharacterArchive, LlmCharacterPatchList,
                              LlmReconCardResult)


class TestCardMode(unittest.TestCase):
    def setUp(self):
        from utils.metrics import metrics_collector
        self._patchers = [patch.object(metrics_collector, n) for n in ("start_chapter", "log_llm_call", "save_to_file")]
        for p in self._patchers:
            p.start()

    def tearDown(self):
        for p in self._patchers:
            p.stop()

    def test_only_changed_cards_and_new_names_go_to_patch(self):
        from pipelines.character_analysis import CharacterAnalysisPipeline
        maomao = Character(book_id="b", name="Маомао", description="Аптекарь.", spoiler_free_description="Аптекарь.")
        jinshi = Character(book_id="b", name="Джинши", description="Евнух.", spoiler_free_description="Евнух.")
        archive = CharacterArchive(characters=[maomao, jinshi])
        recon = LlmReconCardResult.model_validate({
            "mentioned": [{"ref": "Маомао", "action": "Пробует яд.", "card_changed": False},
                          {"ref": "Джинши", "action": "Снимает маску улыбки.", "card_changed": True,
                           "why": "новая черта"},
                          {"ref": "Призрак", "action": "?", "card_changed": True}],
            "newly_discovered_names": ["Гаошун"]})

        pipeline = CharacterAnalysisPipeline(MagicMock())
        repo = MagicMock()
        repo.get_all_chapters.return_value = [SimpleNamespace(id="vol_1_chap_5", volume_num=1, chapter_num=5,
                                                              order_index=5, status="draft", characters_state=None)]
        repo.get_character_archive.return_value = archive
        repo.get_summary_archive.return_value = ChapterSummaryArchive(summaries={})
        repo.get_chapter_text.return_value = "текст"
        operation = MagicMock(return_value=LlmCharacterPatchList(patches=[]))
        with patch("pipelines.character_analysis.BookRepository", return_value=repo), \
             patch.object(pipeline, "_perform_recon_card", return_value=recon), \
             patch.object(pipeline, "_perform_operation", operation), \
             patch("pipelines.character_analysis.config") as cfg:
            cfg.CHARACTER_CALL_ATTEMPTS, cfg.CHARACTER_PATCH_BATCH = 3, 8
            cfg.CHARACTER_DESCRIPTION_MODE, cfg.CHARACTER_REF_STYLE = "card", "name"
            pipeline.run("b")

        to_update, candidates = operation.call_args.args[1], operation.call_args.args[2]
        self.assertEqual([c.name for c in to_update], ["Джинши"])
        self.assertEqual(candidates, ["Гаошун"])
        self.assertEqual(maomao.chapter_mentions["vol_1_chap_5"], "Пробует яд.")
        self.assertEqual(maomao.description_timeline, {})  # карточка не тронута
        self.assertEqual(pipeline.stats["recon_card_changed"], 1)
        self.assertEqual(pipeline.stats["recon_unknown_refs"], 1)


class TestFactsMode(unittest.TestCase):
    def _pipeline(self, mode="facts"):
        from pipelines.character_analysis import CharacterAnalysisPipeline
        pipeline = CharacterAnalysisPipeline(MagicMock())
        patcher = patch("pipelines.character_analysis.config")
        cfg = patcher.start()
        self.addCleanup(patcher.stop)
        cfg.CHARACTER_DESCRIPTION_MODE = mode
        return pipeline

    def _patch(self, **kw):
        from core.data_models import LlmCharacterPatch
        return LlmCharacterPatch.model_validate({"ref": "x", **kw})

    def test_facts_added_and_state_retired(self):
        pipeline = self._pipeline()
        char = Character(book_id="b", name="Маомао", description="Аптекарь.", spoiler_free_description="Аптекарь.")
        pipeline._update_character(char, self._patch(new_facts=[{"fact": "служанка гардероба", "kind": "status"}]),
                                   "vol_1_chap_1", {})
        state = pipeline._state_facts(char)
        self.assertEqual([f["fact"] for f in state], ["служанка гардероба"])
        pipeline._update_character(char, self._patch(retired_facts=[state[0]["id"]], new_facts=[
            {"fact": "фрейлина Гёкуё", "kind": "status"}, {"fact": "непереносимость гречки", "kind": "Health"}]),
            "vol_1_chap_5", {})
        self.assertEqual([f["fact"] for f in pipeline._state_facts(char)], ["фрейлина Гёкуё"])
        old = next(f for f in char.facts if f["fact"] == "служанка гардероба")
        self.assertEqual(old["until"], "vol_1_chap_5")
        self.assertEqual([f["id"] for f in char.facts], ["f1", "f2", "f3"])
        self.assertEqual(char.facts[2]["kind"], "health")

    def test_detail_facts_cannot_be_retired(self):
        pipeline = self._pipeline()
        char = Character(book_id="b", name="Маомао", description="d", spoiler_free_description="d",
                         facts=[{"id": "f1", "fact": "шрамы на руке", "kind": "appearance", "chapter": "c1", "until": None}])
        pipeline._update_character(char, self._patch(retired_facts=["f1"]), "c2", {})
        self.assertIsNone(char.facts[0]["until"])

    def test_personal_name_replaces_role(self):
        pipeline = self._pipeline()
        char = Character(book_id="b", name="Добродетельная супруга", description="d", spoiler_free_description="d")
        pipeline._update_character(char, self._patch(personal_name="Лишу", aliases=["Лишу"]), "c1", {})
        self.assertEqual(char.name, "Лишу")
        self.assertIn("Добродетельная супруга", char.aliases)
        self.assertNotIn("Лишу", char.aliases)

    def test_personal_name_does_not_rename_a_name(self):
        pipeline = self._pipeline()
        char = Character(book_id="b", name="Маомао", description="d", spoiler_free_description="d")
        pipeline._update_character(char, self._patch(personal_name="Сяомао"), "c1", {})
        self.assertEqual(char.name, "Маомао")

    def test_long_card_is_compacted(self):
        from core.data_models import LlmCardCompact
        pipeline = self._pipeline()
        pipeline.model_manager.get_llm_service.return_value.call_for_pydantic.return_value = LlmCardCompact(card="Коротко.")
        self.assertEqual(pipeline._fit_card("Маомао", "а" * 1200), "Коротко.")
        self.assertEqual(pipeline._fit_card("Маомао", "а" * 900), "а" * 900)

    def test_rewrite_mode_ignores_facts(self):
        pipeline = self._pipeline("rewrite")
        char = Character(book_id="b", name="Маомао", description="d", spoiler_free_description="d")
        pipeline._update_character(char, self._patch(new_facts=[{"fact": "x", "kind": "trait"}]), "c1", {})
        self.assertEqual(char.facts, [])


if __name__ == "__main__":
    unittest.main()
