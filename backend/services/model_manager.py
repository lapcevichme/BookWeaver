import os
import logging
from threading import Lock
from typing import Dict
from dotenv import load_dotenv

import config
from services.llm_service import LLMService

load_dotenv()
logger = logging.getLogger(__name__)


class ModelManager:
    """
    Централизованный менеджер ресурсов (Resource Orchestrator).
    Реализует паттерны Singleton и Sequential Model Loading.
    Позволяет динамически загружать и выгружать модели для экономии VRAM.
    """

    def __init__(self):
        self._services: Dict[str, object] = {}
        self._locks: Dict[str, Lock] = {
            'tts': Lock(),
            'llm_character_analyzer': Lock(),
            'llm_scenario_generator': Lock(),
            'llm_summary_generator': Lock(),
        }

    def get_tts_service(self):
        """Возвращает сервис TTS (Lazy Loading)."""
        service_key = "tts_service"
        if service_key not in self._services:
            from services.tts_service import TTSService
            self._services[service_key] = TTSService()
        return self._services[service_key]

    def get_embedding_service(self):
        """Возвращает сервис эмбеддингов для векторизации и дедупликации."""
        service_key = "embedding_service"
        if service_key not in self._services:
            from services.embedding_service import EmbeddingService
            self._services[service_key] = EmbeddingService()
        return self._services[service_key]

    def get_llm_service(self, service_type: str) -> LLMService:
        """Возвращает экземпляр LLMService."""
        provider = getattr(config, 'LLM_PROVIDER', 'google')

        api_key = None
        if provider == 'openrouter':
            api_key = getattr(config, 'OPENROUTER_API_KEY', None)
        elif provider == 'google':
            api_key = getattr(config, 'GOOGLE_API_KEY', None)

        if service_type == 'character_analyzer':
            service_key = 'llm_character_analyzer'
            model_name = config.FAST_MODEL_NAME
            temperature = config.ANALYZER_LLM_TEMPERATURE
        elif service_type == 'scenario_generator':
            service_key = 'llm_scenario_generator'
            model_name = config.POWERFUL_MODEL_NAME
            temperature = config.GENERATOR_LLM_TEMPERATURE
        elif service_type == 'summary_generator':
            service_key = 'llm_summary_generator'
            model_name = config.FAST_MODEL_NAME
            temperature = config.SUMMARY_LLM_TEMPERATURE
        else:
            raise ValueError(f"Неизвестный тип LLM-сервиса: {service_type}")

        if service_key not in self._services:
            with self._locks[service_key]:
                if service_key not in self._services:
                    self._services[service_key] = LLMService(
                        model_name=model_name,
                        temperature=temperature,
                        provider=provider,
                        api_key=api_key
                    )
        return self._services[service_key]

    def unload_service(self, service_key: str):
        """
        Ключевой метод для VRAM Orchestration.
        Позволяет принудительно освободить ресурсы занятые сервисом.
        """
        if service_key in self._services:
            service = self._services[service_key]

            if hasattr(service, 'unload'):
                logger.info(f"ORCHESTRATOR: Выгрузка сервиса '{service_key}' для освобождения VRAM...")
                service.unload()

            pass

    def unload_all_gpu_models(self):
        """Полная очистка GPU перед запуском очень тяжелых задач (например, FLUX)."""
        logger.info("ORCHESTRATOR: Emergency VRAM cleanup requested.")
        if 'tts_service' in self._services:
            self.unload_service('tts_service')