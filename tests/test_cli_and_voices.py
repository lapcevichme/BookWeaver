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
