"""Правила переименования персонажей: осознанное переименование от LLM и защита от порчи имен."""
import sys
import unittest
from pathlib import Path
from unittest.mock import MagicMock
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).parent.parent))

from core.data_models import Character, CharacterArchive, LlmCharacterPatch
from pipelines.character_analysis import (
    CharacterAnalysisPipeline,
    LlmPlaceholderAudit,
    LlmPlaceholderRename,
    apply_placeholder_renames,
    find_placeholder_renames,
)


class TestCharacterNaming(unittest.TestCase):
    def setUp(self):
        self.pipeline = CharacterAnalysisPipeline(MagicMock())
        self.pipeline.model_manager.get_embedding_service.return_value.get_embedding.return_value = None
        self.taken = frozenset({"маомао", "лишу", "джинши"})

    def test_explicit_rename_changes_name_and_saves_previous_in_aliases(self):
        cases = [
            ("отец Маомао", "Луомен"),
            ("Бродяга", "Арагорн"),
            ("Служанка", "Сяолан"),
            ("Смит", "Уинстон Смит"),
        ]
        for old_name, new_name in cases:
            with self.subTest(old=old_name, new=new_name):
                char = Character(
                    id=uuid4(), book_id="b", name=old_name,
                    description="d", spoiler_free_description="d",
                    chapter_mentions={"vol_1_chap_1": "Появление"}
                )
                patch = LlmCharacterPatch(rename_to=new_name, current_chapter_action="Действие")
                self.pipeline._update_character(char, patch, "vol_1_chap_5", {}, taken=self.taken)
                self.assertEqual(char.name, new_name)
                self.assertIn(old_name, char.aliases)
                self.assertEqual(char.alias_meta[old_name]["kind"], "previous_name")
                self.assertEqual(char.name_timeline, {"vol_1_chap_1": old_name, "vol_1_chap_5": new_name})

    def test_personal_name_compatibility_renames_character(self):
        char = Character(
            id=uuid4(), book_id="b", name="Целомудренная супруга",
            description="d", spoiler_free_description="d",
            chapter_mentions={"vol_1_chap_1": "Появление"}
        )
        patch = LlmCharacterPatch(personal_name="А-Дуо", current_chapter_action="Раскрытие имени")
        self.pipeline._update_character(char, patch, "vol_1_chap_30", {}, taken=self.taken)
        self.assertEqual(char.name, "А-Дуо")
        # В facts-режиме прежнее титульное имя уходит в статус-факты, а не засоряет алиасы
        self.assertTrue(any(f["fact"] == "Целомудренная супруга" and f["kind"] == "status" for f in (char.facts or [])))

    def test_patch_name_never_renames_character(self):
        cases = [
            ("Маомао", "Отец Маомао"),
            ("Лишу", "Фрейлина-дегустатор Лишу"),
            ("Джинши", "Евнух Джинши"),
            ("Хоннян", "Доктор"),
            ("Маомао", "Луомен"),
        ]
        for current_name, patch_name in cases:
            with self.subTest(current=current_name, patch=patch_name):
                char = Character(id=uuid4(), book_id="b", name=current_name, description="d", spoiler_free_description="d")
                patch = LlmCharacterPatch(name=patch_name, current_chapter_action="Действие")
                self.pipeline._update_character(char, patch, "vol_1_chap_10", {}, taken=self.taken)
                self.assertEqual(char.name, current_name)

    def test_rename_blocked_when_target_name_is_taken(self):
        char = Character(id=uuid4(), book_id="b", name="отец Маомао", description="d", spoiler_free_description="d")
        patch = LlmCharacterPatch(rename_to="Маомао", current_chapter_action="Попытка перезаписи")
        self.pipeline._update_character(char, patch, "vol_1_chap_7", {}, taken=self.taken)
        self.assertEqual(char.name, "отец Маомао")

    def test_resolve_patch_does_not_match_by_stripped_role_or_substring(self):
        maomao = Character(id=uuid4(), book_id="b", name="Маомао", description="d", spoiler_free_description="d")
        archive = CharacterArchive(characters=[maomao])
        refs = self.pipeline._assign_refs(archive.characters)

        father_patch = LlmCharacterPatch(name="Отец Маомао", current_chapter_action="Появление отца.")
        resolved = self.pipeline._resolve_patch(father_patch, refs, archive)
        self.assertIsNone(resolved)


class TestPlaceholderAudit(unittest.TestCase):
    def test_apply_placeholder_renames_preserves_spoiler_free_timeline(self):
        father = Character(
            id=uuid4(),
            book_id="b",
            name="Отец Маомао",
            aliases=["Луомен", "приёмный отец Маомао"],
            alias_meta={"Луомен": {"kind": "name", "since": "vol_1_chap_28"}},
            description="Приёмный отец Маомао, бывший дворцовый врач Луомен.",
            spoiler_free_description="Приёмный отец Маомао.",
            chapter_mentions={"vol_1_chap_23": "появление", "vol_1_chap_28": "раскрытие имени", "vol_2_chap_5": "лечение"},
        )
        jinshi = Character(
            id=uuid4(),
            book_id="b",
            name="Джинши",
            aliases=["Ка Зуйгэцу"],
            alias_meta={"Ка Зуйгэцу": {"kind": "name", "since": "vol_3_chap_20"}},
            description="Евнух, на самом деле Ка Зуйгэцу.",
            spoiler_free_description="Евнух.",
            chapter_mentions={"vol_1_chap_1": "появление"},
        )
        changed = apply_placeholder_renames(
            [father, jinshi],
            [LlmPlaceholderRename(current_name="Отец Маомао", rename_to="Луомен", reason="описательный плейсхолдер")],
        )
        self.assertEqual(len(changed), 1)
        self.assertEqual(father.name, "Луомен")
        self.assertIn("Отец Маомао", father.aliases)
        self.assertNotIn("Луомен", father.aliases)
        self.assertEqual(father.alias_meta["Отец Маомао"]["kind"], "previous_name")
        self.assertEqual(father.name_timeline, {"vol_1_chap_23": "Отец Маомао", "vol_1_chap_28": "Луомен"})
        self.assertEqual(jinshi.name, "Джинши")

    def test_apply_placeholder_renames_blocks_taken_name(self):
        doc = Character(id=uuid4(), book_id="b", name="Доктор", aliases=["Гуэн"], description="d", spoiler_free_description="d")
        other = Character(id=uuid4(), book_id="b", name="Гуэн", aliases=[], description="d", spoiler_free_description="d")
        changed = apply_placeholder_renames(
            [doc, other],
            [LlmPlaceholderRename(current_name="Доктор", rename_to="Гуэн")],
        )
        self.assertEqual(changed, [])
        self.assertEqual(doc.name, "Доктор")

    def test_find_placeholder_renames_calls_llm_only_for_eligible_cards(self):
        llm = MagicMock()
        llm.call_for_pydantic.return_value = LlmPlaceholderAudit(
            renames=[LlmPlaceholderRename(current_name="Высокая дама", rename_to="Суйрей", reason="описание внешности")]
        )
        lady = Character(id=uuid4(), book_id="b", name="Высокая дама", aliases=["Суйрей"], description="Суйрей — статная женщина.")
        plain = Character(id=uuid4(), book_id="b", name="Маомао", aliases=[], description="Аптекарь.")
        res = find_placeholder_renames(llm, [lady, plain])
        self.assertEqual(len(res), 1)
        self.assertEqual((res[0].current_name, res[0].rename_to), ("Высокая дама", "Суйрей"))
        prompt_arg = llm.call_for_pydantic.call_args[0][1]
        self.assertIn("Высокая дама", prompt_arg)
        self.assertNotIn("Маомао", prompt_arg)


if __name__ == "__main__":
    unittest.main()
