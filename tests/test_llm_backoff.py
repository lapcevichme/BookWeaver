"""Тестирование устойчивости к сетевым сбоям и алгоритма экспоненциального backoff в LLMService."""
import unittest
from unittest.mock import MagicMock, patch
import httpx
import openai
from pydantic import BaseModel

import config
from services.llm_service import (
    LLMService,
    calculate_backoff_delay,
    is_transient_error,
    is_fatal_error,
)


class DummyModel(BaseModel):
    message: str


def _make_dummy_completion(content: str = '{"message": "ok"}', prompt_tokens: int = 10, completion_tokens: int = 5):
    choice = MagicMock()
    choice.message.content = content
    choice.logprobs = None

    usage = MagicMock()
    usage.prompt_tokens = prompt_tokens
    usage.completion_tokens = completion_tokens
    usage.prompt_tokens_details = None
    usage.completion_tokens_details = None
    usage.cost = 0.0001

    completion = MagicMock()
    completion.choices = [choice]
    completion.usage = usage
    completion.provider = "test_provider"
    return completion


class TestLLMBackoffAndClassification(unittest.TestCase):
    def test_calculate_backoff_delay_exact_without_jitter(self):
        delays = [
            calculate_backoff_delay(attempt=i, base_delay=2.0, factor=2.0, max_delay=60.0, jitter=0.0)
            for i in range(6)
        ]
        self.assertEqual(delays, [2.0, 4.0, 8.0, 16.0, 32.0, 60.0])

    def test_calculate_backoff_delay_caps_at_max(self):
        delay = calculate_backoff_delay(attempt=10, base_delay=2.0, factor=2.0, max_delay=60.0, jitter=0.0)
        self.assertEqual(delay, 60.0)

    def test_calculate_backoff_delay_with_jitter_range(self):
        for attempt in range(4):
            raw = 2.0 * (2.0 ** attempt)
            for _ in range(10):
                d = calculate_backoff_delay(attempt=attempt, base_delay=2.0, factor=2.0, max_delay=60.0, jitter=0.2)
                self.assertGreaterEqual(d, raw * 0.8 - 1e-6)
                self.assertLessEqual(d, raw * 1.2 + 1e-6)

    def test_error_classification_transient(self):
        req = httpx.Request("POST", "https://api.openai.com/v1/chat/completions")
        res_502 = httpx.Response(502, request=req)
        res_429 = httpx.Response(429, request=req)
        res_503 = httpx.Response(503, request=req)

        self.assertTrue(is_transient_error(openai.APIConnectionError(request=req)))
        self.assertTrue(is_transient_error(openai.APITimeoutError(request=req)))
        self.assertTrue(is_transient_error(openai.RateLimitError(message="rate limit", response=res_429, body=None)))
        self.assertTrue(is_transient_error(openai.InternalServerError(message="bad gateway", response=res_502, body=None)))
        self.assertTrue(is_transient_error(openai.APIStatusError(message="service unavailable", response=res_503, body=None)))
        self.assertTrue(is_transient_error(httpx.ConnectError("Connection refused", request=req)))
        self.assertTrue(is_transient_error(ConnectionResetError("Peer reset")))
        self.assertTrue(is_transient_error(TimeoutError("Timed out")))

    def test_error_classification_fatal(self):
        req = httpx.Request("POST", "https://api.openai.com/v1/chat/completions")
        res_401 = httpx.Response(401, request=req)
        res_400 = httpx.Response(400, request=req)
        res_403 = httpx.Response(403, request=req)
        res_404 = httpx.Response(404, request=req)

        self.assertTrue(is_fatal_error(openai.AuthenticationError(message="invalid key", response=res_401, body=None)))
        self.assertTrue(is_fatal_error(openai.BadRequestError(message="bad request", response=res_400, body=None)))
        self.assertTrue(is_fatal_error(openai.PermissionDeniedError(message="forbidden", response=res_403, body=None)))
        self.assertTrue(is_fatal_error(openai.NotFoundError(message="not found", response=res_404, body=None)))

        # Сетевые ошибки не фатальны
        self.assertFalse(is_fatal_error(openai.APIConnectionError(request=req)))


class TestLLMServiceRetryExecution(unittest.TestCase):
    def setUp(self):
        self.service = LLMService(model_name="test-model", provider="openrouter", api_key="dummy")
        self.sleeps = []
        self.service._sleep_fn = lambda s: self.sleeps.append(s)
        self.mock_client = MagicMock()
        self.service._client = self.mock_client

    def test_transient_network_recovery(self):
        req = httpx.Request("POST", "https://example.invalid")
        err1 = openai.APIConnectionError(request=req)
        err2 = openai.APIConnectionError(request=req)
        success = _make_dummy_completion('{"message": "recovered"}')

        mock_create = MagicMock(side_effect=[err1, err2, success])
        self.mock_client.with_options.return_value.chat.completions.create = mock_create

        with patch.object(config, "LLM_MAX_NETWORK_RETRIES", 6, create=True), \
             patch.object(config, "LLM_RETRY_INITIAL_DELAY", 2.0, create=True), \
             patch.object(config, "LLM_RETRY_JITTER", 0.0, create=True):

            content, usage = self.service._raw_call_llm("test prompt", prompt_type="test_prompt")

        self.assertEqual(content, '{"message": "recovered"}')
        self.assertEqual(mock_create.call_count, 3)
        self.assertEqual(len(self.sleeps), 2)
        self.assertEqual(self.sleeps, [2.0, 4.0])

    def test_server_502_recovery(self):
        req = httpx.Request("POST", "https://example.invalid")
        res_502 = httpx.Response(502, request=req)
        err = openai.InternalServerError(message="Bad Gateway", response=res_502, body=None)
        success = _make_dummy_completion('{"message": "server ok"}')

        mock_create = MagicMock(side_effect=[err, success])
        self.mock_client.with_options.return_value.chat.completions.create = mock_create

        with patch.object(config, "LLM_RETRY_INITIAL_DELAY", 3.0, create=True), \
             patch.object(config, "LLM_RETRY_JITTER", 0.0, create=True):

            content, _ = self.service._raw_call_llm("prompt", prompt_type="test")

        self.assertEqual(content, '{"message": "server ok"}')
        self.assertEqual(mock_create.call_count, 2)
        self.assertEqual(self.sleeps, [3.0])

    def test_rate_limit_with_retry_after_header(self):
        req = httpx.Request("POST", "https://example.invalid")
        res_429 = httpx.Response(429, request=req, headers={"retry-after": "12"})
        err = openai.RateLimitError(message="rate limited", response=res_429, body=None)
        success = _make_dummy_completion('{"message": "after rate limit"}')

        mock_create = MagicMock(side_effect=[err, success])
        self.mock_client.with_options.return_value.chat.completions.create = mock_create

        with patch.object(config, "LLM_RETRY_INITIAL_DELAY", 2.0, create=True), \
             patch.object(config, "LLM_RETRY_JITTER", 0.0, create=True):

            content, _ = self.service._raw_call_llm("prompt", prompt_type="test")

        self.assertEqual(content, '{"message": "after rate limit"}')
        self.assertEqual(len(self.sleeps), 1)
        self.assertEqual(self.sleeps[0], 12.0)

    def test_exhausted_retries_returns_none(self):
        req = httpx.Request("POST", "https://example.invalid")
        err = openai.APIConnectionError(request=req)

        mock_create = MagicMock(side_effect=err)
        self.mock_client.with_options.return_value.chat.completions.create = mock_create

        with patch.object(config, "LLM_MAX_NETWORK_RETRIES", 4, create=True), \
             patch.object(config, "LLM_RETRY_INITIAL_DELAY", 1.0, create=True), \
             patch.object(config, "LLM_RETRY_JITTER", 0.0, create=True):

            content, usage = self.service._raw_call_llm("prompt", prompt_type="test")

        self.assertIsNone(content)
        self.assertEqual(usage, (0, 0))
        self.assertEqual(mock_create.call_count, 4)
        self.assertEqual(self.sleeps, [1.0, 2.0, 4.0])

    def test_fatal_error_does_not_retry(self):
        req = httpx.Request("POST", "https://example.invalid")
        res_401 = httpx.Response(401, request=req)
        err = openai.AuthenticationError(message="Invalid API Key", response=res_401, body=None)

        mock_create = MagicMock(side_effect=err)
        self.mock_client.with_options.return_value.chat.completions.create = mock_create

        content, usage = self.service._raw_call_llm("prompt", prompt_type="test")

        self.assertIsNone(content)
        self.assertEqual(usage, (0, 0))
        self.assertEqual(mock_create.call_count, 1)
        self.assertEqual(len(self.sleeps), 0)

    def test_call_for_pydantic_end_to_end_recovery(self):
        req = httpx.Request("POST", "https://example.invalid")
        err = openai.APIConnectionError(request=req)
        success = _make_dummy_completion('{"message": "pydantic success"}')

        mock_create = MagicMock(side_effect=[err, success])
        self.mock_client.with_options.return_value.chat.completions.create = mock_create

        with patch.object(config, "LLM_RETRY_INITIAL_DELAY", 1.0, create=True), \
             patch.object(config, "LLM_RETRY_JITTER", 0.0, create=True):

            result = self.service.call_for_pydantic(DummyModel, "test prompt", prompt_type="test")

        self.assertIsNotNone(result)
        self.assertEqual(result.message, "pydantic success")
        self.assertEqual(mock_create.call_count, 2)
        self.assertEqual(self.sleeps, [1.0])

    def test_choice_probabilities_retries_on_transient_error(self):
        req = httpx.Request("POST", "https://example.invalid")
        err = openai.APIConnectionError(request=req)

        choice = MagicMock()
        choice.message.content = "A"
        top_cand = MagicMock()
        top_cand.token = "A"
        top_cand.logprob = -0.1
        content_token = MagicMock()
        content_token.top_logprobs = [top_cand]
        choice.logprobs.content = [content_token]

        completion = MagicMock()
        completion.choices = [choice]
        completion.usage = MagicMock(prompt_tokens=10, completion_tokens=1, cost=0.0001)

        mock_create = MagicMock(side_effect=[err, completion])
        self.mock_client.with_options.return_value.chat.completions.create = mock_create

        with patch.object(config, "LLM_RETRY_INITIAL_DELAY", 1.0, create=True), \
             patch.object(config, "LLM_RETRY_JITTER", 0.0, create=True):

            probs = self.service.choice_probabilities("prompt", options=("A", "B"), prompt_type="test")

        self.assertIsNotNone(probs)
        self.assertIn("A", probs)
        self.assertEqual(mock_create.call_count, 2)
        self.assertEqual(self.sleeps, [1.0])


if __name__ == "__main__":
    unittest.main()
