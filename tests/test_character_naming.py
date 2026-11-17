"""Правила переименования персонажей: осознанное переименование от LLM и защита от порчи имен."""
import sys
import unittest
from pathlib import Path
from unittest.mock import MagicMock
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).parent.parent))

from core.data_models import Character, CharacterArchive, LlmCharacterPatch
from pipelines.character_analysis import CharacterAnalysisPipeline


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


if __name__ == "__main__":
    unittest.main()
