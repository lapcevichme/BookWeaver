import os
import math
import logging
from typing import List, Optional, Tuple, Any, Dict
from threading import Lock
from dotenv import load_dotenv

import config

load_dotenv()
logger = logging.getLogger(__name__)


class EmbeddingService:
    """
    Легковесный сервис для генерации и сравнения текстовых эмбеддингов через OpenRouter / OpenAI API.
    Используется для дедупликации персонажей и семантического поиска.
    """

    def __init__(self, model_name: str = "openai/text-embedding-3-small"):
        self.model_name = model_name
        self._client = None
        self._lock = Lock()
        self._cache: Dict[str, List[float]] = {}

    @property
    def client(self):
        if self._client is None:
            with self._lock:
                if self._client is None:
                    from openai import OpenAI
                    provider = getattr(config, "LLM_PROVIDER", "openrouter").lower()
                    if provider == "openrouter":
                        api_key = getattr(config, "OPENROUTER_API_KEY", None) or os.getenv("OPENROUTER_API_KEY")
                        base_url = "https://openrouter.ai/api/v1"
                    else:
                        api_key = getattr(config, "GOOGLE_API_KEY", None) or os.getenv("GOOGLE_API_KEY")
                        base_url = "https://generativelanguage.googleapis.com/v1beta/openai/"
                    
                    self._client = OpenAI(base_url=base_url, api_key=api_key)
        return self._client

    def get_embedding(self, text: str) -> Optional[List[float]]:
        """Возвращает нормализованный вектор для строки текста."""
        clean_text = text.strip()
        if not clean_text:
            return None

        if clean_text in self._cache:
            return self._cache[clean_text]

        results = self.get_embeddings([clean_text])
        if results and results[0]:
            return results[0]
        return None

    DEFAULT_BATCH_SIZE = 128

    def get_embeddings(self, texts: List[str], batch_size: int = DEFAULT_BATCH_SIZE) -> List[Optional[List[float]]]:
        """Пакетное получение эмбеддингов для списка текстов с дедупликацией и нарезкой на батчи."""
        if not texts:
            return []

        clean_texts = [t.strip() for t in texts]
        results: List[Optional[List[float]]] = [None] * len(clean_texts)
        uncached_map: Dict[str, List[int]] = {}

        for idx, text in enumerate(clean_texts):
            if not text:
                continue
            if text in self._cache:
                results[idx] = self._cache[text]
            else:
                uncached_map.setdefault(text, []).append(idx)

        if uncached_map:
            unique_texts = list(uncached_map.keys())
            step = max(1, batch_size)
            for start in range(0, len(unique_texts), step):
                batch = unique_texts[start:start + step]
                try:
                    response = self.client.embeddings.create(
                        model=self.model_name,
                        input=batch
                    )
                    for i, item in enumerate(response.data):
                        vec = item.embedding
                        text_key = batch[i]
                        self._cache[text_key] = vec
                        for orig_idx in uncached_map[text_key]:
                            results[orig_idx] = vec
                except Exception as e:
                    logger.error(f"Ошибка получения эмбеддингов через {self.model_name}: {e}", exc_info=True)

        return results

    @staticmethod
    def cosine_similarity(vec1: List[float], vec2: List[float]) -> float:
        """Вычисляет косинусное сходство между двумя векторами (значение от -1.0 до 1.0)."""
        if not vec1 or not vec2 or len(vec1) != len(vec2):
            return 0.0

        dot_product = sum(a * b for a, b in zip(vec1, vec2))
        norm1 = math.sqrt(sum(a * a for a in vec1))
        norm2 = math.sqrt(sum(b * b for b in vec2))

        if norm1 == 0.0 or norm2 == 0.0:
            return 0.0

        return dot_product / (norm1 * norm2)

    def find_most_similar(
        self,
        target_vec: List[float],
        candidates: List[Tuple[Any, List[float]]],
        threshold: float = 0.75
    ) -> Optional[Tuple[Any, float]]:
        """
        Ищет наилучшее совпадение для target_vec среди списка пар (entity, vector).
        Возвращает (entity, similarity) если сходство >= threshold, иначе None.
        """
        best_match = None
        best_score = -1.0

        for entity, vec in candidates:
            if not vec:
                continue
            score = self.cosine_similarity(target_vec, vec)
            if score > best_score:
                best_score = score
                best_match = entity

        if best_score >= threshold:
            return best_match, best_score
        return None
