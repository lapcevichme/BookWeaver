import json
import time
import logging
import config
from pathlib import Path
from threading import Lock
from typing import Dict, Any, List, Optional
from dataclasses import dataclass, field

logger = logging.getLogger(__name__)

@dataclass
class LLMMetrics:
    prompt_type: str
    model_name: str
    input_tokens: int = 0
    output_tokens: int = 0
    latency_ms: float = 0.0
    status: str = "success"
    error_message: Optional[str] = None
    timestamp: float = field(default_factory=time.time)
    cost_usd: Optional[float] = None
    attempts: int = 1
    cached_tokens: int = 0
    reasoning_tokens: int = 0

@dataclass
class AudioMetrics:
    entry_id: str
    speaker: str
    text_length: int
    audio_duration_ms: int
    synthesis_latency_ms: float
    rtf: float  # Real-Time Factor
    cer: float
    attempts: int
    status: str = "success" # success, hallucination, error
    timestamp: float = field(default_factory=time.time)

class MetricsCollector:
    _instance = None
    _lock = Lock()

    def __new__(cls):
        with cls._lock:
            if cls._instance is None:
                cls._instance = super(MetricsCollector, cls).__new__(cls)
                cls._instance._init()
        return cls._instance

    def _init(self):
        self.active_book: Optional[str] = None
        self.active_chapter: Optional[str] = None

    def start_chapter(self, chapter_id: str):
        if ":" in chapter_id:
            self.active_book, self.active_chapter = chapter_id.split(":", 1)
        else:
            self.active_book = None
            self.active_chapter = chapter_id
        logger.debug(f"Metrics: Started tracking chapter {self.active_chapter} for book {self.active_book}")

    def start_book(self, book_name: str):
        """Этап уровня книги (кастинг): вызовы пишутся без главы."""
        self.active_book, self.active_chapter = book_name, None

    def log_llm_call(self, metrics: LLMMetrics):
        if not self.active_book:
            logger.warning("Metrics: Attempted to log LLM call without active book/chapter.")
            return

        try:
            from core.book_repository import BookRepository
            from core.data_models import LLMMetric as DBLLMMetric
            
            repo = BookRepository(book_id=self.active_book)
            with repo.get_session() as session:
                db_metric = DBLLMMetric(
                    chapter_id=self.active_chapter,
                    prompt_type=metrics.prompt_type,
                    model_name=metrics.model_name,
                    input_tokens=metrics.input_tokens,
                    output_tokens=metrics.output_tokens,
                    latency_ms=metrics.latency_ms,
                    status=metrics.status,
                    error_message=metrics.error_message,
                    timestamp=metrics.timestamp,
                    cost_usd=metrics.cost_usd,
                    attempts=metrics.attempts,
                    cached_tokens=metrics.cached_tokens,
                    reasoning_tokens=metrics.reasoning_tokens,
                )
                session.add(db_metric)
                session.commit()
            
            if metrics.status == "json_error":
                self.increment("json_parse_errors")
        except Exception as e:
            logger.error(f"Metrics: Failed to log LLM call to DB: {e}")

    def log_audio_gen(self, metrics: AudioMetrics):
        if not self.active_book or not self.active_chapter:
            logger.warning("Metrics: Attempted to log Audio gen without active book/chapter.")
            return

        try:
            from core.book_repository import BookRepository
            from core.data_models import AudioMetric as DBAudioMetric
            
            repo = BookRepository(book_id=self.active_book)
            with repo.get_session() as session:
                db_metric = DBAudioMetric(
                    chapter_id=self.active_chapter,
                    entry_id=metrics.entry_id,
                    speaker=metrics.speaker,
                    text_length=metrics.text_length,
                    audio_duration_ms=metrics.audio_duration_ms,
                    synthesis_latency_ms=metrics.synthesis_latency_ms,
                    rtf=metrics.rtf,
                    cer=metrics.cer,
                    attempts=metrics.attempts,
                    status=metrics.status,
                    timestamp=metrics.timestamp
                )
                session.add(db_metric)
                session.commit()

            if metrics.status == "hallucination":
                self.increment("tts_hallucinations")
            if metrics.attempts > 1:
                self.increment("tts_retries", metrics.attempts - 1)
        except Exception as e:
            logger.error(f"Metrics: Failed to log Audio gen to DB: {e}")

    def increment(self, counter_name: str, value: int = 1):
        if not self.active_book or not self.active_chapter:
            return

        try:
            from core.book_repository import BookRepository
            from core.data_models import MetricCounter as DBMetricCounter
            from sqlmodel import select
            
            repo = BookRepository(book_id=self.active_book)
            with repo.get_session() as session:
                statement = select(DBMetricCounter).where(
                    DBMetricCounter.chapter_id == self.active_chapter,
                    DBMetricCounter.counter_name == counter_name
                )
                counter = session.exec(statement).first()
                if counter:
                    counter.value += value
                else:
                    counter = DBMetricCounter(
                        chapter_id=self.active_chapter,
                        counter_name=counter_name,
                        value=value
                    )
                session.add(counter)
                session.commit()
        except Exception as e:
            logger.error(f"Metrics: Failed to increment counter to DB: {e}")

    def save_to_file(self, file_path: Path):
        # Метрики пишутся напрямую в базу данных в реальном времени,
        # поэтому сохранение в глобальный JSON-файл больше не требуется.
        pass

metrics_collector = MetricsCollector()
