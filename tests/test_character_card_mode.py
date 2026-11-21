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
        self.assertNotIn("Добродетельная супруга", char.aliases)
        self.assertNotIn("Лишу", char.aliases)
        self.assertTrue(any(f["fact"] == "Добродетельная супруга" and f["kind"] == "status" for f in char.facts))

    def test_patch_name_does_not_rename_a_name(self):
        pipeline = self._pipeline()
        char = Character(book_id="b", name="Маомао", description="d", spoiler_free_description="d")
        pipeline._update_character(char, self._patch(name="Отец Маомао"), "c1", {})
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


class TestTitleSuccession(unittest.TestCase):
    """Новый носитель титула (Лоулан) не склеивается с прежним (А-Дуо) по алиасу-титулу."""

    def setUp(self):
        from pipelines.character_analysis import CharacterAnalysisPipeline
        self.pipeline = CharacterAnalysisPipeline(MagicMock())
        patcher = patch("pipelines.character_analysis.config")
        cfg = patcher.start()
        self.addCleanup(patcher.stop)
        cfg.CHARACTER_DESCRIPTION_MODE = "facts"
        self.pipeline.model_manager.get_embedding_service.return_value.get_embedding.return_value = None
        self.aduo = Character(book_id="b", name="А-Дуо", aliases=["Целомудренная супруга"],
                              description="Бывшая супруга.", spoiler_free_description="Бывшая супруга.")
        self.jinshi = Character(book_id="b", name="Джинши", description="Евнух.", spoiler_free_description="Евнух.")
        self.archive = CharacterArchive(characters=[self.aduo, self.jinshi])
        self.refs = self.pipeline._assign_refs(self.archive.characters)

    def _patch(self, **kw):
        from core.data_models import LlmCharacterPatch
        return LlmCharacterPatch.model_validate({"description_update": "Новая супруга.", **kw})

    def test_new_title_holder_is_not_resolved_by_title_alias(self):
        p = self._patch(ref=None, name="Лоулан", aliases=[{"alias": "Целомудренная супруга", "kind": "description"}])
        self.assertIsNone(self.pipeline._resolve_patch(p, self.refs, self.archive))

    def test_new_title_holder_is_created_without_taken_alias(self):
        from core.data_models import LlmCharacterPatchList
        p = self._patch(ref=None, name="Лоулан", aliases=[
            {"alias": "Целомудренная супруга", "kind": "description"},
            {"alias": "Госпожа Лоулан", "kind": "name"}
        ])
        self.pipeline._apply_patch(self.archive, LlmCharacterPatchList(patches=[p]), self.refs, [], ["Лоулан"],
                                   "vol_2_chap_3", "b")
        loulan = next(c for c in self.archive.characters if c.name == "Лоулан")
        self.assertNotIn("Целомудренная супруга", loulan.aliases)
        self.assertEqual(self.aduo.description, "Бывшая супруга.")
        self.assertEqual(self.aduo.aliases, ["Целомудренная супруга"])

    def test_proper_name_alias_still_resolves_candidate(self):
        p = self._patch(ref=None, name="Красивый евнух", aliases=["Джинши"])
        self.assertIs(self.pipeline._resolve_patch(p, self.refs, self.archive), self.jinshi)

    def test_explicit_ref_still_uses_aliases(self):
        p = self._patch(ref="unknown", name="Лоулан", aliases=["Целомудренная супруга"])
        self.assertIs(self.pipeline._resolve_patch(p, self.refs, self.archive), self.aduo)

    def test_role_to_personal_name_upgrade_does_not_pollute_aliases(self):
        char = Character(book_id="b", name="Целомудренная супруга", description="Супруга императора.",
                         chapter_mentions={"vol_1_chap_1": "Появление."})
        self.pipeline._update_character(char, self._patch(ref="x", personal_name="А-Дуо"), "vol_1_chap_10", {})
        self.assertEqual(char.name, "А-Дуо")
        self.assertNotIn("Целомудренная супруга", char.aliases)
        self.assertEqual(char.name_timeline, {"vol_1_chap_1": "Целомудренная супруга", "vol_1_chap_10": "А-Дуо"})
        self.assertTrue(any(f["fact"] == "Целомудренная супруга" and f["kind"] == "status" for f in char.facts))

    def test_succession_flow_aduo_and_loulan(self):
        from core.data_models import LlmCharacterPatchList
        aduo = Character(
            book_id="b", name="А-Дуо", aliases=[],
            description="Бывшая Целомудренная супруга.",
            facts=[{"id": "f1", "fact": "Целомудренная супруга", "kind": "status",
                    "chapter": "vol_1_chap_1", "until": "vol_1_chap_30"}],
            chapter_mentions={"vol_1_chap_1": "во дворце"}
        )
        archive = CharacterArchive(characters=[aduo])
        refs = self.pipeline._assign_refs(archive.characters)
        p = self._patch(ref=None, name="Лоулан", aliases=[{"alias": "Целомудренная супруга", "kind": "description"}])
        self.pipeline._apply_patch(archive, LlmCharacterPatchList(patches=[p]), refs, [], ["Лоулан"],
                                   "vol_2_chap_3", "b")
        self.assertEqual(len(archive.characters), 2)
        loulan = next(c for c in archive.characters if c.name == "Лоулан")
        self.assertIsNotNone(loulan)
        self.assertNotIn("Целомудренная супруга", aduo.aliases)
        self.assertNotIn("Целомудренная супруга", loulan.aliases)
        self.assertEqual(aduo.name, "А-Дуо")
        self.assertEqual(aduo.description, "Бывшая Целомудренная супруга.")
        self.assertEqual(aduo.facts[0]["until"], "vol_1_chap_30")
        self.assertTrue(any(f["fact"] == "Целомудренная супруга" and f["kind"] == "status" for f in loulan.facts))


class TestAliasKinds(unittest.TestCase):
    """Вид алиаса пишет модель: описание (титул/роль) не становится алиасом и не сопоставляет персонажей."""

    def setUp(self):
        from pipelines.character_analysis import CharacterAnalysisPipeline
        self.pipeline = CharacterAnalysisPipeline(MagicMock())
        patcher = patch("pipelines.character_analysis.config")
        cfg = patcher.start()
        self.addCleanup(patcher.stop)
        cfg.CHARACTER_DESCRIPTION_MODE = "facts"
        self.pipeline.model_manager.get_embedding_service.return_value.get_embedding.return_value = None

    def _patch(self, **kw):
        from core.data_models import LlmCharacterPatch
        return LlmCharacterPatch.model_validate(kw)

    def test_description_alias_is_not_added(self):
        char = Character(book_id="b", name="Джинши", description="d", spoiler_free_description="d")
        self.pipeline._update_character(char, self._patch(ref="x", aliases=[
            {"alias": "наследник", "kind": "description"}, {"alias": "Ка Зуйгэцу", "kind": "name"}]),
            "vol_3_chap_20", {})
        self.assertEqual(char.aliases, ["Ка Зуйгэцу"])
        self.assertEqual(char.alias_meta["Ка Зуйгэцу"], {"kind": "name", "since": "vol_3_chap_20"})
        self.assertEqual(self.pipeline.stats["alias_description_skipped"], 1)

    def test_first_since_is_kept(self):
        char = Character(book_id="b", name="Маомао", description="d", spoiler_free_description="d")
        self.pipeline._update_character(char, self._patch(ref="x", aliases=[{"alias": "Веснушка", "kind": "nickname"}]),
                                        "vol_1_chap_3", {})
        self.pipeline._update_character(char, self._patch(ref="x", aliases=["Веснушка"]), "vol_2_chap_1", {})
        self.assertEqual(char.alias_meta["Веснушка"]["since"], "vol_1_chap_3")

    def test_new_patch_matches_by_personal_alias_kind_only(self):
        heir = Character(book_id="b", name="Принц", aliases=["Наследник", "Ка Зуйгэцу"],
                         description="d", spoiler_free_description="d")
        archive = CharacterArchive(characters=[heir])
        refs = self.pipeline._assign_refs(archive.characters)
        by_title = self._patch(name="Новый", aliases=[{"alias": "Наследник", "kind": "description"}])
        by_name = self._patch(name="Маска", aliases=[{"alias": "Ка Зуйгэцу", "kind": "name"}])
        self.assertIsNone(self.pipeline._resolve_patch(by_title, refs, archive))
        self.assertIs(self.pipeline._resolve_patch(by_name, refs, archive), heir)

    def test_personal_name_role_not_added_to_aliases_but_status_and_timeline(self):
        char = Character(book_id="b", name="Добродетельная супруга", description="d", spoiler_free_description="d",
                         chapter_mentions={"vol_1_chap_15": "x"})
        self.pipeline._update_character(char, self._patch(ref="x", personal_name="Лишу"), "vol_1_chap_17", {})
        self.assertEqual(char.name, "Лишу")
        self.assertNotIn("Добродетельная супруга", char.aliases)
        self.assertEqual(char.name_timeline, {"vol_1_chap_15": "Добродетельная супруга", "vol_1_chap_17": "Лишу"})
        self.assertTrue(any(f["fact"] == "Добродетельная супруга" and f["kind"] == "status" for f in char.facts))

    def test_personal_name_proper_name_kept_in_aliases(self):
        char = Character(book_id="b", name="Смит", description="d", spoiler_free_description="d",
                         chapter_mentions={"vol_1_chap_15": "x"})
        self.pipeline._update_character(char, self._patch(ref="x", rename_to="Уинстон Смит"), "vol_1_chap_17", {})
        self.assertEqual(char.name, "Уинстон Смит")
        self.assertIn("Смит", char.aliases)
        self.assertEqual(char.alias_meta["Смит"], {"kind": "previous_name", "since": "vol_1_chap_15"})


class TestAliasesAsOf(unittest.TestCase):
    def test_alias_hidden_before_its_chapter(self):
        from core.character_view import character_as_of
        order = {"vol_1_chap_3": 3, "vol_3_chap_19": 90, "vol_3_chap_20": 91}
        char = Character(book_id="b", name="Джинши", description="d", spoiler_free_description="d",
                         aliases=["Ка Зуйгэцу", "Красивый евнух"], chapter_mentions={"vol_1_chap_3": "x"},
                         alias_meta={"Ка Зуйгэцу": {"kind": "name", "since": "vol_3_chap_20"}})
        self.assertEqual(character_as_of(char, order, "vol_3_chap_19").aliases, ["Красивый евнух"])
        self.assertEqual(character_as_of(char, order, "vol_3_chap_20").aliases, ["Ка Зуйгэцу", "Красивый евнух"])


class TestNameTimeline(unittest.TestCase):
    """Имя на момент главы: «Лишу» до 1.17 — спойлер, слушатель знает «Добродетельную супругу»."""

    def setUp(self):
        from pipelines.character_analysis import CharacterAnalysisPipeline
        self.pipeline = CharacterAnalysisPipeline(MagicMock())
        patcher = patch("pipelines.character_analysis.config")
        patcher.start().CHARACTER_DESCRIPTION_MODE = "facts"
        self.addCleanup(patcher.stop)
        self.order = {"vol_1_chap_15": 15, "vol_1_chap_16": 16, "vol_1_chap_17": 17}

    def _lishu(self):
        from core.data_models import LlmCharacterPatch
        char = Character(book_id="b", name="Добродетельная супруга", description="d", spoiler_free_description="d",
                         chapter_mentions={"vol_1_chap_15": "x"})
        self.pipeline._update_character(char, LlmCharacterPatch.model_validate({"ref": "x", "personal_name": "Лишу"}),
                                        "vol_1_chap_17", {})
        return char

    def test_rename_writes_timeline(self):
        char = self._lishu()
        self.assertEqual(char.name_timeline, {"vol_1_chap_15": "Добродетельная супруга", "vol_1_chap_17": "Лишу"})

    def test_name_as_of(self):
        from core.character_view import character_as_of
        char = self._lishu()
        early = character_as_of(char, self.order, "vol_1_chap_16")
        self.assertEqual(early.name, "Добродетельная супруга")
        self.assertNotIn("Добродетельная супруга", early.aliases)
        late = character_as_of(char, self.order, "vol_1_chap_17")
        self.assertEqual(late.name, "Лишу")
        self.assertNotIn("Добродетельная супруга", late.aliases)

    def test_history_after_chapter_gives_earliest_name(self):
        from core.character_view import name_as_of
        char = Character(book_id="b", name="Лишу", description="d", spoiler_free_description="d",
                         name_timeline={"vol_1_chap_16": "Добродетельная супруга", "vol_1_chap_17": "Лишу"})
        self.assertEqual(name_as_of(char, self.order, 15), "Добродетельная супруга")

    def test_no_history_keeps_name(self):
        from core.character_view import name_as_of
        char = Character(book_id="b", name="Маомао", description="d", spoiler_free_description="d")
        self.assertEqual(name_as_of(char, self.order, 15), "Маомао")


if __name__ == "__main__":
    unittest.main()
