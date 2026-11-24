import os
import subprocess
import sys
from unittest.mock import MagicMock

from fastapi.testclient import TestClient

import config
from main import Application


def test_application_lite_mode_initializes_only_characters_and_summaries():
    app = Application(model_manager=MagicMock(), lite=True)
    assert app.lite is True
    assert app.character_pipeline is not None
    assert app.summary_pipeline is not None
    assert app.scenario_pipeline is None
    assert app.speaker_pipeline is None
    assert app.tts_pipeline is None
    assert app.image_pipeline is None


def test_lite_mode_starts_without_audio_packages():
    """Проверяет в изолированном процессе, что lite-режим не импортирует pydub/soundfile/pywhispercpp."""
    code = """
import sys
for mod in ("pydub", "soundfile", "pywhispercpp"):
    sys.modules[mod] = None

import config
assert config.IS_LITE_MODE is True

from unittest.mock import MagicMock
from main import Application
from pipelines.fast_catchup import FastCatchupPipeline
from pipelines.identity_links import IdentitySuggestPipeline
from pipelines.identity_verifier import IdentityVerifierPipeline
import api_server

app = Application(model_manager=MagicMock(), lite=True)
assert app.character_pipeline is not None
assert app.summary_pipeline is not None
assert app.tts_pipeline is None
fastapi_app = api_server.create_app()
assert fastapi_app is not None
print("LITE_OK")
"""
    env = dict(os.environ)
    env["BOOKWEAVER_MODE"] = "lite"
    proc = subprocess.run(
        [sys.executable, "-c", code],
        capture_output=True,
        text=True,
        env=env,
        check=False,
    )
    assert proc.returncode == 0, f"STDERR:\n{proc.stderr}\nSTDOUT:\n{proc.stdout}"
    assert "LITE_OK" in proc.stdout


def test_ai_tasks_returns_501_for_scenario_and_tts_in_lite_mode(monkeypatch):
    import api_server

    monkeypatch.setattr(config, "IS_LITE_MODE", True)
    client = TestClient(api_server.create_app())

    r1 = client.post("/api/v1/tasks/ai/generate-scenario", json={"book_name": "b", "volume_num": 1, "chapter_num": 1})
    assert r1.status_code == 501

    r2 = client.post("/api/v1/tasks/ai/synthesize-tts", json={"book_name": "b", "volume_num": 1, "chapter_num": 1})
    assert r2.status_code == 501
