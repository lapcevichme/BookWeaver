import unittest
from types import SimpleNamespace
from unittest.mock import patch

from core.character_view import chapter_order, characters_as_of
from core.data_models import (
    Chapter,
    ChapterSummary,
    CharacterArchive,
    CharacterGender,
    CharacterType,
    LlmAlias,
    LlmFact,
    RoleTier,
    VolumeSummary,
)
from pipelines.fast_catchup import (
    FastCatchupPipeline,
    LlmCatchupCharacterPatch,
    build_catchup_batches,
    select_catchup_chapters,
)


def _make_chapter(vol: int, num: float, order_index: int) -> Chapter:
    num_str = str(int(num)) if float(num).is_integer() else str(num)
    return Chapter(
        id=f"vol_{vol}_chap_{num_str}",
        book_id="test_book",
        volume_num=vol,
        chapter_num=num,
        order_index=order_index,
        title=f"Глава {num_str}",
    )


class TestFastCatchup(unittest.TestCase):
    def test_select_catchup_chapters_by_chapter_and_volume(self):
        chaps = [
            _make_chapter(1, 1, 0),
            _make_chapter(1, 2, 1),
            _make_chapter(2, 1, 2),
            _make_chapter(2, 2, 3),
            _make_chapter(3, 1, 4),
        ]
        by_ch = select_catchup_chapters(chaps, until_chapter="vol_2_chap_1")
        self.assertEqual([c.id for c in by_ch], ["vol_1_chap_1", "vol_1_chap_2", "vol_2_chap_1"])

        by_vol = select_catchup_chapters(chaps, until_volume=2)
        self.assertEqual([c.id for c in by_vol], ["vol_1_chap_1", "vol_1_chap_2", "vol_2_chap_1", "vol_2_chap_2"])

        with self.assertRaises(ValueError):
            select_catchup_chapters(chaps, until_chapter="vol_1_chap_1", until_volume=1)

        with self.assertRaises(ValueError):
            select_catchup_chapters(chaps, until_chapter="vol_9_chap_9")

    def test_build_catchup_batches_respects_volume_and_word_budget(self):
        chaps = [
            _make_chapter(1, 1, 0),
            _make_chapter(1, 2, 1),
            _make_chapter(1, 3, 2),
            _make_chapter(2, 1, 3),
            _make_chapter(2, 2, 4),
        ]
        words_map = {
            "vol_1_chap_1": "слово " * 800,
            "vol_1_chap_2": "слово " * 700,
            "vol_1_chap_3": "слово " * 900,
            "vol_2_chap_1": "слово " * 2500,  # больше бюджета -> отдельный батч
            "vol_2_chap_2": "слово " * 600,
        }
        batches = build_catchup_batches(chaps, text_loader=lambda c: words_map[c.id], batch_max_words=1600)
        self.assertEqual(len(batches), 4)
        self.assertEqual([c.id for c in batches[0].chapters], ["vol_1_chap_1", "vol_1_chap_2"])
        self.assertEqual(batches[0].volume_num, 1)
        self.assertEqual([c.id for c in batches[1].chapters], ["vol_1_chap_3"])
        self.assertEqual(batches[1].volume_num, 1)
        self.assertEqual([c.id for c in batches[2].chapters], ["vol_2_chap_1"])
        self.assertEqual(batches[2].volume_num, 2)
        self.assertEqual([c.id for c in batches[3].chapters], ["vol_2_chap_2"])
        self.assertEqual(batches[3].volume_num, 2)

    def test_apply_batch_patches_filtering_dating_and_as_of_compatibility(self):
        chaps = [
            _make_chapter(1, 1, 0),
            _make_chapter(1, 10, 1),
            _make_chapter(1, 20, 2),
            _make_chapter(2, 1, 3),
        ]
        order = chapter_order(chaps)
        pipe = FastCatchupPipeline(model_manager=None)  # type: ignore[arg-type]
        archive = CharacterArchive(book_title="test_book", characters=[])

        # Батч 1 (конец: vol_1_chap_10)
        batch1_patches = [
            LlmCatchupCharacterPatch(
                ref=None,
                name="Маомао",
                role_tier=RoleTier.PROTAGONIST,
                gender=CharacterGender.FEMALE,
                aliases=[
                    LlmAlias(alias="Травница", kind="nickname"),
                    LlmAlias(alias="Дегустатор ядов", kind="description"),
                ],
                card="Дочь аптекаря из квартала удовольствий, обожает яды.",
                batch_action="Попала во внутренний двор и стала дегустатором.",
                new_facts=[
                    LlmFact(fact="Служит дегустатором у супруги Гёкуё", kind="status"),
                    LlmFact(fact="Невосприимчива к части ядов из-за экспериментов на себе", kind="skill"),
                ],
            ),
            LlmCatchupCharacterPatch(
                ref=None,
                name="Отец Маомао",
                role_tier=RoleTier.MAJOR,
                gender=CharacterGender.MALE,
                card="Старый аптекарь из квартала удовольствий.",
                batch_action="Научил Маомао медицине.",
            ),
            # Должны отфильтроваться
            LlmCatchupCharacterPatch(
                ref=None,
                name="Стражник",
                role_tier=RoleTier.BACKGROUND,
                card="Стоит у ворот.",
            ),
            LlmCatchupCharacterPatch(
                ref=None,
                name="Серебряная шпилька",
                entity_type=CharacterType.OBJECT,
                card="Украшение.",
            ),
        ]
        pipe._apply_batch_patches(archive, batch1_patches, "vol_1_chap_10", "test_book")
        self.assertEqual(len(archive.characters), 2)
        self.assertEqual(pipe.stats["skipped_background"], 1)
        self.assertEqual(pipe.stats["skipped_object"], 1)

        maomao = next(c for c in archive.characters if c.name == "Маомао")
        self.assertIn("Травница", maomao.aliases)
        self.assertNotIn("Дегустатор ядов", maomao.aliases)
        self.assertTrue(any(f["fact"] == "Дегустатор ядов" and f["kind"] == "status" for f in maomao.facts))
        self.assertTrue(any(f.get("source") == "catchup" for f in maomao.facts))
        self.assertEqual(maomao.alias_meta["Травница"]["since"], "vol_1_chap_10")

        # Батч 2 (конец: vol_1_chap_20): переименование плейсхолдера и закрытие статуса
        status_id = next(f["id"] for f in maomao.facts if "Гёкуё" in f["fact"])
        batch2_patches = [
            LlmCatchupCharacterPatch(
                ref="Маомао",
                name="Маомао",
                batch_action="Уволена из дворца и нанята Джинши.",
                new_facts=[LlmFact(fact="Служит личной служанкой у Джинши", kind="status")],
                retired_facts=[status_id],
            ),
            LlmCatchupCharacterPatch(
                ref="Отец Маомао",
                name="Отец Маомао",
                rename_to="Луомен",
                batch_action="Раскрывается, что он бывший придворный лекарь.",
            ),
        ]
        pipe._apply_batch_patches(archive, batch2_patches, "vol_1_chap_20", "test_book")

        luomen = next(c for c in archive.characters if c.name == "Луомен")
        self.assertIn("Отец Маомао", luomen.aliases)
        self.assertEqual(luomen.name_timeline["vol_1_chap_10"], "Отец Маомао")
        self.assertEqual(luomen.name_timeline["vol_1_chap_20"], "Луомен")

        retired = next(f for f in maomao.facts if f["id"] == status_id)
        self.assertEqual(retired["until"], "vol_1_chap_20")

        # Проверка антиспойлерного среза as_of на границе K (vol_1_chap_20) и на K+1 (vol_2_chap_1)
        views_at_k = characters_as_of(archive.characters, order, "vol_1_chap_20")
        self.assertEqual({v.name for v in views_at_k}, {"Маомао", "Луомен"})

        views_at_k1 = characters_as_of(archive.characters, order, "vol_2_chap_1")
        self.assertEqual({v.name for v in views_at_k1}, {"Маомао", "Луомен"})

        # На vol_1_chap_10 (до раскрытия) Луомен ещё виден как «Отец Маомао»
        views_mid = characters_as_of(archive.characters, order, "vol_1_chap_10")
        self.assertEqual({v.name for v in views_mid}, {"Маомао", "Отец Маомао"})

    def test_on_volume_completed_counts_catchup_state(self):
        from pipelines.character_analysis import CharacterAnalysisPipeline

        pipe = CharacterAnalysisPipeline(model_manager=None)  # type: ignore[arg-type]
        triggered = []

        ch1 = _make_chapter(1, 1, 0)
        ch1.characters_state = "catchup"
        ch2 = _make_chapter(1, 2, 1)
        ch2.characters_state = "done"

        with patch.object(
            pipe,
            "trigger_volume_identity_scan",
            side_effect=lambda repo, book, vol: triggered.append(vol) or {},
        ):
            pipe._on_volume_completed(
                repo=object(),
                book_name="test_book",
                volume_num=1,
                all_story_chapters=[ch1, ch2],
            )
        self.assertEqual(triggered, [1])

    def test_catchup_cli_parser(self):
        import cli

        parser = cli.build_parser()
        args = parser.parse_args(
            ["catchup", "my_book", "--until-volume", "3", "--batch-words", "20000", "--no-audit", "--dry-run"]
        )
        self.assertEqual(args.command, "catchup")
        self.assertEqual(args.book, "my_book")
        self.assertEqual(args.until_volume, 3)
        self.assertEqual(args.batch_words, 20000)
        self.assertTrue(args.no_audit)
        self.assertTrue(args.dry_run)

    def test_mixed_volume_summary_combines_catchup_recaps_and_chapter_summaries(self):
        from pipelines.summary_generation import SummaryGenerationPipeline

        ch1 = _make_chapter(4, 1, 0)
        ch1.characters_state = "catchup"
        ch2 = _make_chapter(4, 2, 1)
        ch2.characters_state = "done"

        captured_prompts = []
        saved = {}

        class FakeLLM:
            def call_for_pydantic(self, model_cls, prompt, prompt_type=None):
                captured_prompts.append(prompt)
                return VolumeSummary(volume_num=4, summary="Объединённый пересказ 4 тома.")

        class FakeMM:
            def get_llm_service(self, name):
                return FakeLLM()

        class FakeRepo:
            book_id = "test_book"

            def get_all_chapters(self):
                return [ch1, ch2]

            def get_book(self):
                return SimpleNamespace(config={"catchup_recaps": {"4": [["Том 4, гл. 1", "События из catchup."]]}})

            def get_summary_archive(self):
                return SimpleNamespace(
                    summaries={
                        "vol_4_chap_2": ChapterSummary(
                            chapter_id="vol_4_chap_2", teaser="t", synopsis="Финал из Фазы 2."
                        )
                    }
                )

            def get_volume_summary(self, v):
                return None

            def get_characters(self):
                return []

            def save_volume_summary(self, v, text):
                saved[v] = text

        pipe = SummaryGenerationPipeline(FakeMM())  # type: ignore[arg-type]
        res = pipe.generate_volume_summary(FakeRepo(), 4)  # type: ignore[arg-type]
        self.assertIsNotNone(res)
        self.assertEqual(saved[4], "Объединённый пересказ 4 тома.")
        self.assertIn("События из catchup.", captured_prompts[0])
        self.assertIn("Финал из Фазы 2.", captured_prompts[0])


if __name__ == "__main__":
    unittest.main()
