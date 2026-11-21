import unittest
from uuid import uuid4
from core.data_models import Character, CharacterArchive, LlmCharacterPatchList, LlmCharacterPatch, CharacterType
from services.model_manager import ModelManager
from pipelines.character_analysis import CharacterAnalysisPipeline


class TestCharacterDeduplication(unittest.TestCase):
    def setUp(self):
        self.mm = ModelManager()
        self.pipeline = CharacterAnalysisPipeline(self.mm)

    def test_alias_matching(self):
        """Проверяет, что патч с известным алиасом объединяется с существующим персонажем."""
        archive = CharacterArchive(characters=[
            Character(
                id=uuid4(),
                book_id="test_book",
                name="Джек",
                entity_type=CharacterType.ANIMAL,
                aliases=["Кот Джек", "Рыжий кот"],
                description="Рыжий кот Смита.",
                spoiler_free_description="Рыжий кот."
            )
        ])

        # Кандидат "Рыжий кот" — это алиас существующего персонажа (модель не дала ref)
        patch = LlmCharacterPatch(
            name="Рыжий кот",
            entity_type=CharacterType.ANIMAL,
            description_update="Тот же рыжий кот на коленях Уинстона.",
            current_chapter_action="Сидит на коленях."
        )
        patch_list = LlmCharacterPatchList(patches=[patch])
        refs = self.pipeline._assign_refs(archive.characters)

        self.pipeline._apply_patch(archive, patch_list, refs, [], ["Рыжий кот"], "vol_1_chap_3", "test_book")
        self.assertEqual(len(archive.characters), 1)
        self.assertEqual(archive.characters[0].name, "Джек")
        self.assertIn("vol_1_chap_3", archive.characters[0].chapter_mentions)


if __name__ == "__main__":
    unittest.main()
