"""
Текст референса определяет режим клонирования Qwen: с текстом — ICL (переносит манеру образца, например хрип),
без текста — только вектор диктора (манера теряется). Переход в «только вектор» не должен быть тихим.
"""
import importlib
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

import numpy as np


class TestClientReferenceText(unittest.TestCase):
    def setUp(self):
        from services.tts_service import TTSService
        self.service = TTSService.__new__(TTSService)
        self.service._reference_transcription_cache = {}
        self.service._whisper_model = None
        self.service.tts_client = MagicMock()
        self.dir = Path(tempfile.mkdtemp())
        self.wav = self.dir / "reference.wav"
        self.wav.write_bytes(b"RIFF")

    def test_missing_text_without_whisper_warns_and_sends_empty(self):
        with patch.object(type(self.service), "whisper_model", new=None), \
                self.assertLogs("services.tts_service", level="WARNING") as logs:
            self.service.synthesize("Привет.", self.wav)
        self.assertEqual(self.service.tts_client.synthesize.call_args.kwargs["prompt_text"], "")
        self.assertTrue(any("только по вектору" in m for m in logs.output))

    def test_reference_txt_is_used(self):
        (self.dir / "reference.txt").write_text("Точный текст образца.\n", encoding="utf-8")
        self.service.synthesize("Привет.", self.wav)
        self.assertEqual(self.service.tts_client.synthesize.call_args.kwargs["prompt_text"], "Точный текст образца.")


class _FakeModel:
    def __init__(self):
        self.modes = []

    def create_voice_clone_prompt(self, ref_audio, ref_text=None, x_vector_only_mode=False):
        self.modes.append(x_vector_only_mode)
        return {"xvec": x_vector_only_mode}

    def generate_voice_clone(self, text, language=None, voice_clone_prompt=None, **kwargs):
        return [np.zeros(240, dtype=np.float32)], 24000


class TestServerCloneMode(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        fake_torch = types.ModuleType("torch")
        fake_torch.cuda = types.SimpleNamespace(is_available=lambda: False, OutOfMemoryError=type("OOM", (Exception,), {}))
        fake_torch.float32 = "float32"
        cls._saved_torch = sys.modules.get("torch")
        sys.modules["torch"] = fake_torch
        sys.path.insert(0, str(Path(__file__).parent.parent / "tools" / "qwen_tts_server"))
        cls.server = importlib.import_module("server")

    @classmethod
    def tearDownClass(cls):
        sys.path.pop(0)
        sys.modules.pop("server", None)
        if cls._saved_torch is None:
            sys.modules.pop("torch", None)
        else:
            sys.modules["torch"] = cls._saved_torch

    def setUp(self):
        from fastapi.testclient import TestClient
        self.model = _FakeModel()
        self.server.model = self.model
        self.server.voice_prompt_cache.clear()
        self.client = TestClient(self.server.app)

    def _post(self, prompt_text=None):
        data = {"text": "Привет.", "voice_id": "jinshi_sick"}
        if prompt_text is not None:
            data["prompt_text"] = prompt_text
        return self.client.post("/api/tts", data=data, files={"prompt_wav": ("reference.wav", b"RIFF", "audio/wav")})

    def test_without_text_is_xvector_and_logged(self):
        with self.assertLogs("qwen3-tts-server", level="WARNING") as logs:
            self.assertEqual(self._post().status_code, 200)
        self.assertEqual(self.model.modes, [True])
        self.assertTrue(any("x_vector_only" in m for m in logs.output))

    def test_xvector_cache_does_not_shadow_icl(self):
        self._post()
        self._post("Текст образца.")
        self._post("Текст образца.")
        # x-vector промпт из первого запроса не подменяет ICL; ICL создается один раз и кэшируется
        self.assertEqual(self.model.modes, [True, False])
        self.assertEqual(set(self.server.voice_prompt_cache), {"jinshi_sick:xvec", "jinshi_sick:icl"})


if __name__ == "__main__":
    unittest.main()
