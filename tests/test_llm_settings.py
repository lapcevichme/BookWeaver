"""Настройки LLM-вызовов: таймаут по этапам и отсутствие встроенных повторов SDK."""
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parent.parent))

import config
from services.llm_service import LLMService


class TestTimeoutByStage(unittest.TestCase):
    def test_stage_timeout_and_fallback(self):
        with patch.object(config, "LLM_TIMEOUT_BY_STAGE", {"character_recon": 240}, create=True), \
             patch.object(config, "LLM_REQUEST_TIMEOUT", 1200, create=True):
            self.assertEqual(LLMService._timeout_for("character_recon"), 240)
            self.assertEqual(LLMService._timeout_for("scenario_generation"), 1200)

    def test_broken_value_falls_back(self):
        with patch.object(config, "LLM_TIMEOUT_BY_STAGE", {"character_recon": "abc"}, create=True), \
             patch.object(config, "LLM_REQUEST_TIMEOUT", 1200, create=True):
            self.assertEqual(LLMService._timeout_for("character_recon"), 1200)

    def test_with_options_keeps_sdk_retries_disabled(self):
        from openai import OpenAI
        client = OpenAI(base_url="https://example.invalid/v1", api_key="x", timeout=1200, max_retries=0)
        per_call = client.with_options(timeout=240)
        self.assertEqual(per_call.max_retries, 0)
        self.assertEqual(per_call.timeout, 240)

    def test_allow_fallbacks_defaults_and_override(self):
        self.assertTrue(config.OPENROUTER_ALLOW_FALLBACKS)
        with patch.object(config, "OPENROUTER_ALLOW_FALLBACKS", False, create=True):
            self.assertFalse(config.OPENROUTER_ALLOW_FALLBACKS)

    def test_default_request_timeout_is_300(self):
        self.assertEqual(config._LLM_REQUEST_TIMEOUT_DEFAULT, 300)


if __name__ == "__main__":
    unittest.main()
