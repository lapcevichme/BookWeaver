import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
from uuid import uuid4

from core import voices
from core.data_models import BookStatus, ManifestMeta


class TestResolveVoice(unittest.TestCase):
    def setUp(self):
        self.lib = Path(tempfile.mkdtemp())
        for name in ("narrator_default", "maomao_voice"):
            (self.lib / name).mkdir()
            (self.lib / name / "reference.wav").write_bytes(b"RIFF")
        self.patcher = patch.object(voices.path_manager, "get_voice_path",
                                    side_effect=lambda vid: self.lib / vid / "reference.wav")
        self.patcher.start()
        self.maomao = SimpleNamespace(id=uuid4(), name="Маомао", voice_base="maomao_voice")
        self.jinshi = SimpleNamespace(id=uuid4(), name="Джинши", voice_base="")
        self.rifa = SimpleNamespace(id=uuid4(), name="Рифа", voice_base="deleted_voice")
        self.config = SimpleNamespace(default_narrator_voice="narrator_default",
                                      character_voices={self.jinshi.id: "jinshi_voice"})
        self.chars = [self.maomao, self.jinshi, self.rifa]

    def tearDown(self):
        self.patcher.stop()

    def test_narrator(self):
        for speaker in (None, "", "Рассказчик"):
            v = voices.resolve_voice(speaker, self.chars, self.config)
            self.assertEqual((v.voice_id, v.source), ("narrator_default", voices.SOURCE_NARRATOR))

    def test_voice_base_wins(self):
        v = voices.resolve_voice("Маомао", self.chars, self.config)
        self.assertEqual((v.voice_id, v.source, v.exists), ("maomao_voice", voices.SOURCE_VOICE_BASE, True))

    def test_config_voice_reported_even_without_file(self):
        v = voices.resolve_voice("Джинши", self.chars, self.config)
        self.assertEqual((v.voice_id, v.source, v.exists), ("jinshi_voice", voices.SOURCE_CONFIG, False))

    def test_missing_voice_folder_and_unknown_speaker_fall_back_to_narrator(self):
        for speaker in ("Рифа", "Неизвестно"):
            v = voices.resolve_voice(speaker, self.chars, self.config)
            self.assertEqual((v.voice_id, v.source), ("narrator_default", voices.SOURCE_FALLBACK))

    def test_renamed_character_found_by_id(self):
        # Реплика записана, когда Маомао звали иначе; id тот же — голос тоже
        v = voices.resolve_voice("Служанка", self.chars, self.config, speaker_id=self.maomao.id)
        self.assertEqual((v.voice_id, v.source), ("maomao_voice", voices.SOURCE_VOICE_BASE))

    def test_generic_voice_by_role_and_by_character_gender(self):
        (self.lib / "generic_female").mkdir()
        (self.lib / "generic_female" / "reference.wav").write_bytes(b"RIFF")
        rifa = SimpleNamespace(id=uuid4(), name="Рифа", voice_base="", gender="female", voice_profile="adult female, sharp")
        for speaker, chars in (("Служанка 1", self.chars), ("Рифа", [rifa])):
            v = voices.resolve_voice(speaker, chars, self.config)
            self.assertEqual((v.voice_id, v.source), ("generic_female", voices.SOURCE_GENERIC))

    def test_missing_generic_voice_falls_back_but_says_what_is_wanted(self):
        v = voices.resolve_voice("Старый стражник", self.chars, self.config)
        self.assertEqual((v.voice_id, v.source, v.wanted), ("narrator_default", voices.SOURCE_FALLBACK, "generic_old_male"))


class TestSpeakerLinks(unittest.TestCase):
    def setUp(self):
        self.lib = Path(tempfile.mkdtemp())
        for name in ("narrator_default", "jinshi_voice", "generic_female", "generic_female_2", "generic_male"):
            (self.lib / name).mkdir()
            (self.lib / name / "reference.wav").write_bytes(b"RIFF")
        self.patcher = patch.object(voices.path_manager, "get_voice_path",
                                    side_effect=lambda vid: self.lib / vid / "reference.wav")
        self.patcher.start()
        self.jinshi = SimpleNamespace(id=uuid4(), name="Джинши", voice_base="jinshi_voice", gender="male", voice_profile="")
        self.config = SimpleNamespace(default_narrator_voice="narrator_default", character_voices={})

    def tearDown(self):
        self.patcher.stop()

    @staticmethod
    def _link(label, kind="extra", status="suggested", **kw):
        base = dict(speaker_label=label, kind=kind, status=status, character_id=None, gender=None,
                    age_group=None, person_key=label, confidence="high", identity_hidden=False)
        base.update(kw)
        return SimpleNamespace(**base)

    def test_confirmed_link_gives_character_voice_suggested_does_not(self):
        for status, expected in (("confirmed", "jinshi_voice"), ("suggested", "generic_male")):
            link = self._link("Красавец-евнух", kind="character", status=status, character_id=self.jinshi.id)
            v = voices.resolve_voice("Красавец-евнух", [self.jinshi], self.config, link=link)
            self.assertEqual(v.voice_id, expected, status)

    def test_two_maids_get_different_voices_same_person_same_voice(self):
        links = [self._link("Служанка 1", gender="female", age_group="adult", person_key="maid1"),
                 self._link("Служанка 2", gender="female", age_group="adult", person_key="maid2"),
                 self._link("Та же служанка", gender="female", age_group="adult", person_key="maid1")]
        entries = [SimpleNamespace(speaker_name=n) for n in ("Служанка 1", None, "Служанка 2", "Та же служанка")]
        ctx = voices.link_context(links, entries)
        got = {label: voices.resolve_voice(label, [], self.config, link=l, extra_index=i).voice_id
               for label, (l, i) in ctx.items()}
        self.assertEqual(got, {"Служанка 1": "generic_female", "Служанка 2": "generic_female_2",
                               "Та же служанка": "generic_female"})

    def test_extra_uses_llm_gender_not_dictionary(self):
        # «Голос из толпы» словарь не знает; LLM сказала — пожилая женщина, такого голоса нет -> wanted
        link = self._link("Голос из толпы", gender="female", age_group="old")
        v = voices.resolve_voice("Голос из толпы", [], self.config, link=link)
        self.assertEqual((v.source, v.wanted), (voices.SOURCE_FALLBACK, "generic_old_female"))


class TestUnresolvedSpeakers(unittest.TestCase):
    def test_only_lines_without_speaker_id(self):
        from pipelines.speaker_resolution import unresolved_speakers
        e = lambda t, name=None, sid=None, text="x": SimpleNamespace(type=t, speaker_name=name, speaker_id=sid, text=text)
        entries = [e("narration", text="Вошел красивый евнух."), e("dialogue", "Красавец-евнух", text="Останься."),
                   e("dialogue", "Маомао", sid=uuid4()), e("dialogue", "Рассказчик"), e("image", "Х", text=None)]
        got = unresolved_speakers(entries)
        self.assertEqual(list(got), ["Красавец-евнух"])
        self.assertEqual(got["Красавец-евнух"][0], {"text": "Останься.", "before": "Вошел красивый евнух."})


class TestRoleCategory(unittest.TestCase):
    def test_roles(self):
        cases = {"Служанка 1": "female", "Стражник 2": "male", "Старый стражник": "old_male",
                 "Пожилая женщина": "old_female", "Старший евнух": "male", "Маленькая служанка": "child",
                 "Старик": "old_male", "Голос из толпы": None, "": None}
        for speaker, expected in cases.items():
            self.assertEqual(voices.role_category(speaker), expected, speaker)

    def test_character_category(self):
        c = lambda g, p: SimpleNamespace(gender=g, voice_profile=p)
        self.assertEqual(voices.character_category(c("male", "adult male, calm")), "male")
        self.assertEqual(voices.character_category(c("female", "old female, raspy")), "old_female")
        self.assertEqual(voices.character_category(c("female", "infant, weak")), "child")
        self.assertIsNone(voices.character_category(c("unknown", "")))


class TestScenarioCoverage(unittest.TestCase):
    def test_images_do_not_count(self):
        from core.data_models import RawScenario
        from pipelines.scenario_generation import ScenarioGenerationPipeline
        src = "Примечание переводчика.\n![Иллюстрация](../images/img_970436a6f2.jpg)\n![Иллюстрация](../images/img_6772b898e8.jpg)"
        result = RawScenario(scenario=[{"type": "narration", "text": "Примечание переводчика."},
                                       {"type": "image", "src": "../images/img_970436a6f2.jpg"},
                                       {"type": "image", "src": "../images/img_6772b898e8.jpg"}])
        self.assertEqual(ScenarioGenerationPipeline._text_coverage(result, src), 1.0)


class TestBookStatusAndTags(unittest.TestCase):
    def test_ranobelib_statuses(self):
        self.assertEqual(BookStatus.parse("Онгоинг"), BookStatus.ONGOING)
        self.assertEqual(BookStatus.parse("Завершён"), BookStatus.COMPLETED)
        self.assertEqual(BookStatus.parse("completed"), BookStatus.COMPLETED)
        with self.assertLogs(level="WARNING"):
            self.assertEqual(BookStatus.parse("что-то странное"), BookStatus.ONGOING)

    def test_manifest_meta_accepts_russian_status(self):
        self.assertEqual(ManifestMeta(status="Онгоинг").status, BookStatus.ONGOING)

    def test_string_tags_from_metadata(self):
        from utils.init_manifest import _parse_tags
        self.assertEqual(_parse_tags("['Медицина', 'ГГ женщина']"), ["Медицина", "ГГ женщина"])
        self.assertEqual(_parse_tags(None), [])


class TestCli(unittest.TestCase):
    def _run(self, argv):
        import cli
        out = io.StringIO()
        with redirect_stdout(out), patch.object(cli, "setup_logging"):
            code = cli.main(argv)
        return code, out.getvalue()

    def test_missing_book_is_not_found_with_json_error(self):
        code, out = self._run(["status", "no-such-book-xyz", "--json"])
        self.assertEqual(code, 3)
        self.assertIn("не найдена", json.loads(out)["error"])

    def test_unknown_stage_is_error(self):
        code, _ = self._run(["run", "no-such-book-xyz", "--stages", "chars,voices"])
        self.assertEqual(code, 1)


class TestCasting(unittest.TestCase):
    PROFILE = {"gender": "female", "age": 17, "pitch": "medium-low", "timbre": "clear, slightly husky",
               "pace": "moderate", "manner": "dry, matter-of-fact", "instruct_zh": "女性，17岁"}

    def test_instruct_ladder_for_women(self):
        from core.casting import instruct_variants
        v = instruct_variants(self.PROFILE)
        self.assertTrue(v[0].startswith("Female, 17 years old, alto range, medium-low pitch, clear, slightly husky voice"))
        self.assertIn("alto range, low-pitched", v[1])
        self.assertIn("not high-pitched", v[1])
        self.assertEqual(v[2], "女性，17岁")

    def test_child_and_male(self):
        from core.casting import instruct_variants, f0_band
        child = dict(self.PROFILE, gender="male", age=9, instruct_zh="")
        self.assertEqual(instruct_variants(child)[0].split(", ")[:2], ["Boy", "9 years old"])
        self.assertEqual(len(instruct_variants(child)), 1)
        self.assertEqual(f0_band("female", 17), (150.0, 265.0))
        self.assertEqual(f0_band("male", 70), (75.0, 170.0))

    def test_replica_counts_include_confirmed_links(self):
        from core.casting import replica_counts
        jinshi = uuid4()
        e = lambda name, sid=None, ch="c4": SimpleNamespace(type="dialogue", text="Да.", speaker_id=sid,
                                                             speaker_name=name, chapter_id=ch)
        links = [SimpleNamespace(kind="character", status="confirmed", character_id=jinshi,
                                 chapter_id="c4", speaker_label="Красавец-евнух"),
                 SimpleNamespace(kind="character", status="suggested", character_id=jinshi,
                                 chapter_id="c4", speaker_label="Евнух")]
        counts = replica_counts([e("Джинши", jinshi), e("Красавец-евнух"), e("Евнух")], links)
        self.assertEqual(counts[str(jinshi)], 2)


if __name__ == "__main__":
    unittest.main()
