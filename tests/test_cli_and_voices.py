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


if __name__ == "__main__":
    unittest.main()
