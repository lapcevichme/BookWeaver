import threading
import time
import re
import json
import logging
import os
from pathlib import Path
from typing import Type, TypeVar, Optional, Tuple
from threading import Lock
from dotenv import load_dotenv
from pydantic import BaseModel, ValidationError
from utils.metrics import metrics_collector, LLMMetrics

load_dotenv()
logger = logging.getLogger(__name__)

PydanticModel = TypeVar("PydanticModel", bound=BaseModel)

# Этапы, которым reasoning не нужен, если в настройке openrouter_reasoning_by_stage не сказано иное:
# эмбиент без reasoning выбирается так же (2026-11-06), а SFX теперь правила (utils/sfx_rules.py).
# Разведка персонажей: low урезает reasoning в 2.5-3 раза без потери полноты; none — модель по умолчанию
# отвечает «не изменилось» (14 отметок из 122 против 45), docs/experiments/2026-11-13_character-cards.md
# Судья связей личностей: low хватает (11/11 на парах Кусурии), docs/experiments/2026-11-13_identity-signals.md
STAGE_REASONING_DEFAULTS = {"ambient_design": "none", "character_recon": "low",
                            "identity_scan": "low", "identity_judge": "low"}

PRICING_REGISTRY = {
    "xiaomi/mimo-v2-flash:free": {"input": 0.0, "output": 0.0}
}


class LLMService:
    """
    Централизованный класс для работы с LLM (Google Gemini или OpenRouter) через единый клиент OpenAI SDK.
    """

    def __init__(self, model_name: str, temperature: float = 0.5, provider: str = "google", api_key: str = None):
        self.model_name = model_name
        self.temperature = temperature
        self.provider = provider.lower()
        self.api_key = api_key
        self._client = None
        self._lock = Lock()
        self._call_info = threading.local()  # цена/попытки последнего вызова этого потока — для метрик
        self._usage_lock = Lock()  # сервис вызывается из нескольких потоков (параллельные чанки)

        self.total_input_tokens = 0
        self.total_output_tokens = 0
        self.total_cost = 0.0

        logger.info(
            f"Сервис LLMService сконфигурирован: провайдер '{self.provider}', модель '{self.model_name}'."
        )

    @property
    def client(self):
        if self._client is None:
            with self._lock:
                if self._client is None:
                    from openai import OpenAI
                    if self.provider == "google":
                        api_key = self.api_key or os.getenv("GOOGLE_API_KEY")
                        base_url = "https://generativelanguage.googleapis.com/v1beta/openai/"
                    else:
                        api_key = self.api_key or os.getenv("OPENROUTER_API_KEY")
                        base_url = "https://openrouter.ai/api/v1"

                    import config
                    # Повторы делаем сами в _raw_call_llm: встроенные повторы SDK (max_retries=2)
                    # умножались на наши и могли оплатить один и тот же запрос несколько раз.
                    self._client = OpenAI(base_url=base_url, api_key=api_key,
                                          timeout=config.LLM_REQUEST_TIMEOUT, max_retries=0)
        return self._client

    def _track_usage(self, input_tokens: int, output_tokens: int, real_cost: Optional[float] = None):
        with self._usage_lock:
            self._track_usage_unlocked(input_tokens, output_tokens, real_cost)

    def _track_usage_unlocked(self, input_tokens: int, output_tokens: int, real_cost: Optional[float] = None):
        self.total_input_tokens += input_tokens
        self.total_output_tokens += output_tokens
        if real_cost is not None:
            # Реальная стоимость от OpenRouter (учитывает провайдера и кэш)
            self.total_cost += real_cost
            return
        price_info = PRICING_REGISTRY.get(self.model_name)
        if price_info:
            cost = (input_tokens / 1_000_000) * price_info["input"] + (output_tokens / 1_000_000) * price_info["output"]
            self.total_cost += cost

    def call_for_pydantic(self, pydantic_model: Type[PydanticModel], prompt: str, prompt_type: str = "unknown") -> Optional[PydanticModel]:
        start_time = time.time()
        response_text, usage = None, (0, 0)
        status = "success"
        error_msg = None

        self._call_info.data = {}
        try:
            response_text, usage = self._raw_call_llm(prompt, prompt_type)
            if not response_text:
                status = "api_error"
            else:
                result = self._process_response_text(response_text, pydantic_model)
                if not result:
                    status = "json_error"
                    self._dump_failed_response(prompt_type, prompt, response_text)
                    logger.error(f"❌ JSON/Validation Error for {prompt_type}. Raw response: {response_text[:1000]}...")
                else:
                    return result
        except Exception as e:
            status = "api_error"
            error_msg = str(e)
            logger.error(f"LLM Call Error: {e}", exc_info=True)
        finally:
            latency_ms = (time.time() - start_time) * 1000
            metrics_collector.log_llm_call(LLMMetrics(
                prompt_type=prompt_type,
                model_name=self.model_name,
                input_tokens=usage[0],
                output_tokens=usage[1],
                latency_ms=latency_ms,
                status=status,
                error_message=error_msg,
                **getattr(self._call_info, "data", {}),
            ))
        return None

    def choice_probabilities(self, prompt: str, options: Tuple[str, ...], prompt_type: str = "unknown") -> Optional[dict]:
        """
        Вероятности вариантов ответа по первому токену (прием «модели решений»: docs/experiments/2026-11-13_decision-models.md).
        Варианты должны различаться первым токеном (буквы A-H, да/нет). Без reasoning: первым должен идти ответ.
        Провайдер выбирается среди тех, кто отдает logprobs (require_parameters), закрепление порядка не действует.
        Возвращает {вариант: вероятность} по видимым top-10 (нормировано) или None.
        """
        if self.provider != "openrouter":
            logger.warning("choice_probabilities: logprobs поддержаны только для OpenRouter")
            return None
        import math
        start_time, usage, status, error_msg = time.time(), (0, 0), "success", None
        self._call_info.data = {"attempts": 1}
        try:
            completion = self.client.with_options(timeout=self._timeout_for(prompt_type)).chat.completions.create(
                model=self.model_name, messages=[{"role": "user", "content": prompt}], temperature=0,
                max_tokens=1, logprobs=True, top_logprobs=10,
                extra_body={"provider": {"require_parameters": True}, "reasoning": {"enabled": False}},
            )
            if completion.usage:
                usage = (completion.usage.prompt_tokens, completion.usage.completion_tokens)
                real_cost = getattr(completion.usage, "cost", None)
                self._track_usage(*usage, real_cost=real_cost)
                self._call_info.data.update(cost_usd=real_cost)
            content = completion.choices[0].logprobs.content if completion.choices and completion.choices[0].logprobs else None
            if not content:
                status = "api_error"
                error_msg = "провайдер не вернул logprobs"
                return None
            wanted = {o.strip().lower(): o for o in options}
            dist = {}
            for cand in content[0].top_logprobs:
                key = wanted.get(cand.token.strip().lower())
                if key:
                    dist[key] = dist.get(key, 0.0) + math.exp(cand.logprob)
            total = sum(dist.values())
            if not total:
                status = "json_error"
                error_msg = f"ни одного варианта в top-10: {[c.token for c in content[0].top_logprobs]}"
                return None
            return {k: v / total for k, v in sorted(dist.items(), key=lambda kv: -kv[1])}
        except Exception as e:
            status, error_msg = "api_error", str(e)
            logger.warning(f"choice_probabilities ({prompt_type}): {e}")
            return None
        finally:
            metrics_collector.log_llm_call(LLMMetrics(
                prompt_type=prompt_type, model_name=self.model_name, input_tokens=usage[0], output_tokens=usage[1],
                latency_ms=(time.time() - start_time) * 1000, status=status, error_message=error_msg,
                **getattr(self._call_info, "data", {}),
            ))

    def _dump_failed_response(self, prompt_type: str, prompt: str, response_text: str):
        """Сохраняет полный промпт и сырой ответ модели, если ответ не прошел валидацию."""
        try:
            from datetime import datetime
            import config
            out_dir = Path(config.BASE_DIR) / "logs" / "llm_failures"
            out_dir.mkdir(parents=True, exist_ok=True)
            name = f"{datetime.now():%Y%m%d_%H%M%S}_{prompt_type}_{self.model_name.replace('/', '_')}.txt"
            (out_dir / name).write_text(
                f"=== MODEL: {self.model_name}\n=== PROMPT ({len(prompt)} chars)\n{prompt}\n\n"
                f"=== RESPONSE ({len(response_text)} chars)\n{response_text}\n",
                encoding="utf-8",
            )
            logger.error(f"Сырой ответ сохранен: {out_dir / name}")
        except Exception as e:
            logger.warning(f"Не удалось сохранить сырой ответ: {e}")

    @staticmethod
    def _openrouter_reasoning(prompt_type: str = "unknown") -> Optional[dict]:
        """
        Настройка reasoning на OpenRouter: '' (по умолчанию модели), 'none', 'low', 'medium', 'high'.
        openrouter_reasoning_by_stage ({"sound_design": "none", ...}) переопределяет
        общую настройку openrouter_reasoning_effort для конкретного prompt_type.
        """
        import config
        by_stage = getattr(config, "OPENROUTER_REASONING_BY_STAGE", None) or {}
        if isinstance(by_stage, str):
            try:
                by_stage = json.loads(by_stage) if by_stage.strip() else {}
            except json.JSONDecodeError:
                logger.warning(f"openrouter_reasoning_by_stage не является JSON: {by_stage!r}")
                by_stage = {}
        effort = by_stage.get(prompt_type)
        if effort is None:
            effort = STAGE_REASONING_DEFAULTS.get(prompt_type)
        if effort is None:
            effort = getattr(config, "OPENROUTER_REASONING_EFFORT", "")
        effort = (effort or "").strip().lower()
        if not effort:
            return None
        if effort == "none":
            return {"enabled": False}
        return {"effort": effort}

    @staticmethod
    def _timeout_for(prompt_type: str) -> float:
        """Таймаут запроса для этапа: llm_timeout_by_stage, иначе общий llm_request_timeout."""
        import config
        by_stage = getattr(config, "LLM_TIMEOUT_BY_STAGE", None) or {}
        try:
            return float(by_stage[prompt_type])
        except (KeyError, TypeError, ValueError):
            return float(config.LLM_REQUEST_TIMEOUT)

    @staticmethod
    def _openrouter_routing() -> Optional[dict]:
        """
        Закрепление провайдера на OpenRouter. Порядок берется из настройки
        openrouter_provider_order (через запятую). Фолбэки на другие провайдеры
        отключены, чтобы запрос случайно не ушел к дорогому эндпоинту.
        """
        import config
        order = getattr(config, "OPENROUTER_PROVIDER_ORDER", "") or ""
        if isinstance(order, str):
            order = [x.strip() for x in order.split(",") if x.strip()]
        if not order:
            return None
        return {"order": order, "allow_fallbacks": False}

    def _raw_call_llm(self, prompt: str, prompt_type: str = "unknown") -> Tuple[Optional[str], Tuple[int, int]]:
        max_retries = 3
        for attempt in range(max_retries):
            if not hasattr(self._call_info, "data"):
                self._call_info.data = {}
            self._call_info.data["attempts"] = attempt + 1
            if attempt > 0:
                metrics_collector.increment("api_retries")
            try:
                kwargs = {
                    "model": self.model_name,
                    "messages": [{"role": "user", "content": prompt}],
                    "temperature": self.temperature,
                }
                if "gemma" not in self.model_name.lower():
                    kwargs["response_format"] = {"type": "json_object"}
                if self.provider == "openrouter":
                    extra = {}
                    routing = self._openrouter_routing()
                    if routing:
                        extra["provider"] = routing
                    reasoning = self._openrouter_reasoning(prompt_type)
                    if reasoning:
                        extra["reasoning"] = reasoning
                    if extra:
                        kwargs["extra_body"] = extra

                client = self.client.with_options(timeout=self._timeout_for(prompt_type))
                completion = client.chat.completions.create(**kwargs)
                usage = (0, 0)
                if completion.usage:
                    usage = (completion.usage.prompt_tokens, completion.usage.completion_tokens)
                    details = getattr(completion.usage, "prompt_tokens_details", None)
                    cached = (getattr(details, "cached_tokens", 0) or 0) if details else 0
                    out_details = getattr(completion.usage, "completion_tokens_details", None)
                    reasoning_toks = (getattr(out_details, "reasoning_tokens", 0) or 0) if out_details else 0
                    real_cost = getattr(completion.usage, "cost", None)
                    self._track_usage(*usage, real_cost=real_cost)
                    self._call_info.data.update(cost_usd=real_cost, cached_tokens=cached, reasoning_tokens=reasoning_toks)
                    logger.info(
                        f"LLM usage [{prompt_type} | {self.model_name} @ {getattr(completion, 'provider', '?')}]: "
                        f"in={usage[0]} (cached={cached}), out={usage[1]} (reasoning={reasoning_toks}), cost=${real_cost if real_cost is not None else '?'}"
                    )

                content = completion.choices[0].message.content if completion.choices else None
                return content, usage
            except Exception as e:
                from openai import RateLimitError, APITimeoutError
                if isinstance(e, RateLimitError):
                    delay = min(60, 5 * 2 ** attempt)
                    logger.warning(f"LLM 429 (rate limit), попытка {attempt + 1}/{max_retries}. Жду {delay} с.")
                elif isinstance(e, APITimeoutError):
                    delay = 2
                    logger.warning(
                        f"LLM таймаут ({prompt_type}, {self._timeout_for(prompt_type):.0f} с), попытка {attempt + 1}/{max_retries}. Провайдер мог успеть "
                        f"обработать и оплатить запрос; повторяю."
                    )
                else:
                    delay = 2
                    logger.warning(f"LLM API Call attempt {attempt + 1} failed: {e}")
                time.sleep(delay)
        return None, (0, 0)

    @staticmethod
    def _extract_json(text: str):
        """
        Достает JSON из ответа модели: сначала пробует весь ответ целиком,
        затем содержимое ```json-блока, затем фрагмент от первой открывающей
        скобки ({ или [ — какая встретится раньше) до последней парной.
        """
        candidates = [text.strip()]
        fence = re.search(r'```(?:json)?\s*(.*?)\s*```', text, re.DOTALL)
        if fence:
            candidates.append(fence.group(1))
        starts = [i for i in (text.find('{'), text.find('[')) if i != -1]
        if starts:
            start = min(starts)
            end = text.rfind('}' if text[start] == '{' else ']')
            if end > start:
                candidates.append(text[start:end + 1])
        for candidate in candidates:
            try:
                return json.loads(candidate)
            except (json.JSONDecodeError, ValueError):
                continue
        return None

    def _process_response_text(self, text: str, pydantic_model: Type[PydanticModel]) -> Optional[PydanticModel]:
        text = re.sub(r'[\x00-\x1F]', '', text)
        data = self._extract_json(text)
        if data is None:
            logger.error("❌ JSON Parsing Error: в ответе не найден валидный JSON")
            return None
        try:
            return pydantic_model.model_validate(data)
        except ValidationError as ve:
            logger.error(f"❌ Pydantic Validation Error for {pydantic_model.__name__}: {ve}")
            return None
        except Exception as e:
            logger.error(f"❌ JSON Parsing Error: {e}")
            return None
