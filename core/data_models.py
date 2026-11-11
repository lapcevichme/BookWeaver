"""
Центральный модуль, определяющий все основные структуры данных проекта.
Поддерживает SQLModel для БД. В этой архитектуре каждая книга имеет свою БД.
"""
import json
from pathlib import Path
from typing import List, Optional, Dict, Literal, Any, Union
from uuid import UUID, uuid4
from enum import Enum

from pydantic import model_validator, field_validator, ValidationError, BaseModel as PydanticBaseModel, ConfigDict
from sqlmodel import SQLModel, Field, Relationship, Column, JSON
from sqlalchemy import Text, Index, UniqueConstraint, ForeignKey, String
from sqlalchemy.ext.mutable import MutableDict, MutableList
from sqlalchemy.orm import reconstructor

# --- Enums ---

class BookStatus(str, Enum):
    ONGOING = "ongoing"
    COMPLETED = "completed"
    HIATUS = "hiatus"
    ABANDONED = "abandoned"

class ChapterStatus(str, Enum):
    DRAFT = "draft"
    PROCESSING = "processing"
    PROCESSED = "processed"
    SCENARIO_READY = "scenario_ready"
    AUDIO_READY = "audio_ready"
    TTS_READY = "tts_ready"
    COMPLETED = "completed"

class ScenarioEntryType(str, Enum):
    DIALOGUE = "dialogue"
    NARRATION = "narration"
    THOUGHT = "thought"
    IMAGE = "image"

class CharacterType(str, Enum):
    PERSON = "person"
    ANIMAL = "animal"
    OBJECT = "object"
    UNKNOWN = "unknown"

class RoleTier(str, Enum):
    PROTAGONIST = "protagonist"
    MAJOR = "major"
    MINOR = "minor"
    BACKGROUND = "background"

class CharacterGender(str, Enum):
    MALE = "male"
    FEMALE = "female"
    NEUTRAL = "neutral"
    UNKNOWN = "unknown"

# --- Timeline Models (Stored as JSON in DB) ---

class CharacterVoiceState(PydanticBaseModel):
    model_config = ConfigDict(from_attributes=True)
    age_group: str = Field(..., description="Группа: child, teen, adult, elderly")
    age_exact: Optional[str] = Field(None, description="Точный возраст (строкой), если известен.")
    voice_description: str = Field(..., description="Описание звучания для человека (на русском).")
    search_tags: Optional[str] = Field(None, description="Теги для поиска голоса (на английском).")
    assigned_voice_id: Optional[str] = Field(None, description="ID голоса в ElevenLabs.")

class CharacterVisualState(PydanticBaseModel):
    model_config = ConfigDict(from_attributes=True)
    description: str = Field(..., description="Общее описание внешности и одежды в этой главе.")
    image_prompt: Optional[str] = Field(None, description="Готовый промпт для генерации (на английском).")
    reference_image_path: Optional[str] = Field(None, description="Путь к сгенерированному референсу.")

# --- Database Tables (Local to each Book DB) ---

class Book(SQLModel, table=True):
    """Метаданные проекта (в каждой БД будет ровно одна запись)."""
    id: str = Field(primary_key=True)
    title: str = Field(index=True)
    author: Optional[str] = None
    description: Optional[str] = None
    status: BookStatus = Field(default=BookStatus.ONGOING, sa_column=Column(String, default=BookStatus.ONGOING))
    cover_image_path: Optional[str] = None
    source_url: Optional[str] = None
    version: str = Field(default="1.0.0")
    total_duration_ms: int = Field(default=0)
    storage_size_bytes: int = Field(default=0)
    language: str = Field(default="ru")
    tags: List[str] = Field(default_factory=list, sa_column=Column(MutableList.as_mutable(JSON)))
    config: Dict[str, Any] = Field(default_factory=dict, sa_column=Column(MutableDict.as_mutable(JSON)))
    
    # Relationships
    chapters: List["Chapter"] = Relationship(back_populates="book")
    characters: List["Character"] = Relationship(back_populates="book")

class Chapter(SQLModel, table=True):
    """Таблица глав книги."""
    __table_args__ = (
        Index("ix_chapter_book_order", "book_id", "order_index"),
    )
    id: str = Field(primary_key=True, description="Local ID: vol_X_chap_Y")
    book_id: str = Field(
        sa_column=Column(String, ForeignKey("book.id", ondelete="CASCADE"), index=True, nullable=False)
    )
    
    volume_num: int = Field(default=1)
    chapter_num: int
    title: Optional[str] = None
    status: ChapterStatus = Field(default=ChapterStatus.DRAFT, sa_column=Column(String, default=ChapterStatus.DRAFT))
    # Прогресс анализа персонажей: None — не обработана, "done", "skipped" (пропущена вручную).
    # Отдельно от status: сценарий можно сгенерировать и без анализа персонажей.
    characters_state: Optional[str] = Field(default=None)
    order_index: int = Field(default=0)
    raw_text: Optional[str] = Field(default=None, sa_column=Column(Text))
    
    # Relationships
    book: "Book" = Relationship(back_populates="chapters")
    summary: Optional["ChapterSummary"] = Relationship(
        back_populates="chapter",
        sa_relationship_kwargs={"cascade": "all, delete-orphan", "uselist": False}
    )
    cache: Optional["ChapterCache"] = Relationship(
        back_populates="chapter",
        sa_relationship_kwargs={"cascade": "all, delete-orphan", "uselist": False}
    )
    entries: List["ScenarioEntry"] = Relationship(
        back_populates="chapter",
        sa_relationship_kwargs={"cascade": "all, delete-orphan"}
    )

    # Обратная совместимость с кодом, читающим/пишущим кэш через свойства объекта главы
    @property
    def cache_raw_scenario(self) -> Optional[Dict]:
        return self.cache.raw_scenario if self.cache else None

    @cache_raw_scenario.setter
    def cache_raw_scenario(self, val: Optional[Dict]):
        if not self.cache:
            self.cache = ChapterCache(chapter_id=self.id)
        self.cache.raw_scenario = val

    @property
    def cache_ambient(self) -> Optional[List]:
        return self.cache.ambient if self.cache else None

    @cache_ambient.setter
    def cache_ambient(self, val: Optional[List]):
        if not self.cache:
            self.cache = ChapterCache(chapter_id=self.id)
        self.cache.ambient = val

    @property
    def cache_emotion(self) -> Optional[Dict]:
        return self.cache.emotion if self.cache else None

    @cache_emotion.setter
    def cache_emotion(self, val: Optional[Dict]):
        if not self.cache:
            self.cache = ChapterCache(chapter_id=self.id)
        self.cache.emotion = val

class ChapterCache(SQLModel, table=True):
    """Отдельная таблица для промежуточных тяжелых кэшей генерации сценария."""
    chapter_id: str = Field(
        sa_column=Column(String, ForeignKey("chapter.id", ondelete="CASCADE"), primary_key=True)
    )
    raw_scenario: Optional[Dict] = Field(default=None, sa_column=Column(MutableDict.as_mutable(JSON)))
    ambient: Optional[List] = Field(default=None, sa_column=Column(MutableList.as_mutable(JSON)))
    emotion: Optional[Dict] = Field(default=None, sa_column=Column(MutableDict.as_mutable(JSON)))
    
    # Relationships
    chapter: Optional["Chapter"] = Relationship(back_populates="cache")

class ChapterSummary(SQLModel, table=True):
    """Сводка/саммари главы."""
    chapter_id: str = Field(
        sa_column=Column(String, ForeignKey("chapter.id", ondelete="CASCADE"), primary_key=True)
    )
    teaser: str = Field(..., description="Краткий тизер без спойлеров.")
    synopsis: str = Field(sa_column=Column(Text), description="Детальный конспект.")
    
    # Relationships
    chapter: "Chapter" = Relationship(back_populates="summary")

class VolumeSummary(PydanticBaseModel):
    """Глобальный пересказ целого тома."""
    volume_num: int
    summary: str = Field(description="Сжатый пересказ событий всего тома.")

class Character(SQLModel, table=True):
    """Персонажи книги."""
    id: UUID = Field(default_factory=uuid4, primary_key=True)
    book_id: str = Field(
        sa_column=Column(String, ForeignKey("book.id", ondelete="CASCADE"), index=True, nullable=False)
    )
    
    name: str = Field(..., index=True)
    entity_type: CharacterType = Field(default=CharacterType.PERSON)
    aliases: List[str] = Field(default_factory=list, sa_column=Column(MutableList.as_mutable(JSON)))
    gender: Optional[CharacterGender] = Field(default=None, sa_column=Column(String, nullable=True))
    role_tier: RoleTier = Field(default=RoleTier.BACKGROUND, sa_column=Column(String, default=RoleTier.BACKGROUND))
    spoiler_free_description: str = Field(..., sa_column=Column(Text))
    description: str = Field(..., sa_column=Column(Text))
    visual_base: Optional[str] = Field(None)
    # Назначенный голос: id из библиотеки голосов (папка storage/voices/<id>). Выбирает человек
    # или автоподбор; по нему TTS берет референс.
    voice_base: str = Field(default="")
    # Описание голоса от анализа персонажей: теги на английском ("young female, calm, soft voice").
    # Это подсказка для подбора голоса, а не голос.
    voice_profile: str = Field(default="")
    embedding: Optional[List[float]] = Field(default=None, sa_column=Column(MutableList.as_mutable(JSON)))
    
    voice_timeline: Dict[str, CharacterVoiceState] = Field(default_factory=dict, sa_column=Column(MutableDict.as_mutable(JSON)))
    visual_timeline: Dict[str, CharacterVisualState] = Field(default_factory=dict, sa_column=Column(MutableDict.as_mutable(JSON)))
    chapter_mentions: Dict[str, str] = Field(default_factory=dict, sa_column=Column(MutableDict.as_mutable(JSON)))
    # Версии описания: chapter_id -> полное описание на конец этой главы.
    # Версия на главе N собрана только из глав 1..N, поэтому бесспойлерна для слушателя,
    # дошедшего до N. `description` хранит последнюю версию.
    description_timeline: Dict[str, str] = Field(default_factory=dict, sa_column=Column(MutableDict.as_mutable(JSON)))
    
    # Relationships
    book: "Book" = Relationship(back_populates="characters")
    scenario_entries: List["ScenarioEntry"] = Relationship(back_populates="speaker")

    @reconstructor
    def init_on_load(self):
        """Вызывается SQLAlchemy после загрузки объекта из БД."""
        self.coerce_timelines()

    @model_validator(mode='after')
    def coerce_timelines(self) -> "Character":
        """Гарантирует, что данные из JSON-колонок БД станут объектами моделей, а не словарями."""
        self.voice_timeline = {
            k: (CharacterVoiceState.model_validate(v) if isinstance(v, dict) else v)
            for k, v in self.voice_timeline.items()
        }
        self.visual_timeline = {
            k: (CharacterVisualState.model_validate(v) if isinstance(v, dict) else v)
            for k, v in self.visual_timeline.items()
        }
        return self

class ScenarioEntry(SQLModel, table=True):
    """Записи сценария."""
    __table_args__ = (
        Index("ix_scenarioentry_chapter_order", "chapter_id", "order_index"),
    )
    id: UUID = Field(default_factory=uuid4, primary_key=True)
    chapter_id: str = Field(
        sa_column=Column(String, ForeignKey("chapter.id", ondelete="CASCADE"), nullable=False)
    )
    speaker_id: Optional[UUID] = Field(
        default=None,
        sa_column=Column(ForeignKey("character.id", ondelete="SET NULL"), nullable=True, index=True)
    )
    
    type: ScenarioEntryType = Field(default=ScenarioEntryType.NARRATION, sa_column=Column(String, default=ScenarioEntryType.NARRATION), description="dialogue, narration, thought, image")
    text: Optional[str] = Field(default=None, sa_column=Column(Text))
    tts_text: Optional[str] = Field(default=None, sa_column=Column(Text))
    speaker_name: Optional[str] = None
    instruct_prompt: str = Field(default="neutral")
    ambient: str = Field(default="none")
    sfx: Optional[str] = None
    audio_file: Optional[str] = None
    audio_subtitles: Optional[Dict] = Field(default=None, sa_column=Column(MutableDict.as_mutable(JSON)))
    src: Optional[str] = None
    order_index: int = Field(default=0)
    
    # Relationships
    chapter: "Chapter" = Relationship(back_populates="entries")
    speaker: Optional["Character"] = Relationship(back_populates="scenario_entries")

# --- Non-Table Models (Prompts/API DTOs) ---

class CharacterReconResult(PydanticBaseModel):
    mentioned_existing_character_ids: List[UUID] = Field(default_factory=list)
    newly_discovered_names: List[str] = Field(default_factory=list)

class CharacterPatch(PydanticBaseModel):
    id: Optional[UUID] = None
    entity_type: Optional[CharacterType] = None
    naming_reasoning: Optional[str] = None
    name: Optional[str] = None
    role_tier: Optional[RoleTier] = None
    description: Optional[str] = None
    spoiler_free_description: Optional[str] = None
    aliases: Optional[List[str]] = None
    gender: Optional[CharacterGender] = None
    visual_base: Optional[str] = None
    voice_base: Optional[str] = None
    current_chapter_action: Optional[str] = None
    timeline_voice_update: Optional[CharacterVoiceState] = None
    timeline_visual_update: Optional[CharacterVisualState] = None

    @model_validator(mode='before')
    @classmethod
    def validate_and_fix_data(cls, data: Any) -> Any:
        if isinstance(data, dict):
            if data.get('entity_type') and isinstance(data.get('entity_type'), str):
                data['entity_type'] = data['entity_type'].lower()
            if data.get('role_tier') and isinstance(data.get('role_tier'), str):
                data['role_tier'] = data['role_tier'].lower()
            if data.get('gender') and isinstance(data.get('gender'), str):
                data['gender'] = data['gender'].lower()
        return data

class CharacterPatchList(PydanticBaseModel):
    patches: List[CharacterPatch]


# --- Схемы, которые видит LLM при анализе персонажей (без служебных полей и UUID) ---

class LlmReconResult(PydanticBaseModel):
    mentioned_existing_refs: List[str] = Field(default_factory=list, description="ref известных персонажей, которые есть в главе")
    newly_discovered_names: List[str] = Field(default_factory=list, description="Имена новых персонажей")

    @model_validator(mode="before")
    @classmethod
    def coerce(cls, data: Any):
        if isinstance(data, dict):
            refs = data.get("mentioned_existing_refs") or data.get("mentioned_existing_character_ids") or []
            data["mentioned_existing_refs"] = [str(r) for r in refs if r is not None]
        return data


class LlmVoiceState(PydanticBaseModel):
    age_group: str = Field(..., description="child | teen | adult | elderly")
    voice_description: str = Field(..., description="Как звучит голос, на русском")
    search_tags: Optional[str] = Field(None, description="5-7 тегов на английском")


class LlmVisualState(PydanticBaseModel):
    description: str = Field(..., description="Внешность и одежда в этой главе, на русском")
    image_prompt: Optional[str] = Field(None, description="Промпт для генерации, на английском")


class LlmCharacterPatch(PydanticBaseModel):
    ref: Optional[str] = Field(None, description="ref из входных данных; для нового персонажа не пиши")
    name: Optional[str] = Field(None, description="Каноническое имя")
    entity_type: Optional[CharacterType] = None
    role_tier: Optional[RoleTier] = None
    gender: Optional[CharacterGender] = None
    aliases: Optional[List[str]] = Field(None, description="Только новые имена/прозвища/титулы из этой главы")
    current_chapter_action: Optional[str] = Field(None, description="Что делает в этой главе, одно предложение")
    description_update: Optional[str] = Field(None, description="Полное актуальное описание, только если глава добавила новое")
    visual_base: Optional[str] = Field(None, description="Только если у персонажа пусто")
    voice_profile: Optional[str] = Field(None, description="Теги голоса; новому персонажу обязательно, известному — только если пусто")
    timeline_visual_update: Optional[LlmVisualState] = None
    timeline_voice_update: Optional[LlmVoiceState] = None

    @model_validator(mode="before")
    @classmethod
    def coerce(cls, data: Any):
        if not isinstance(data, dict):
            return data
        if "voice_base" in data and "voice_profile" not in data:  # старое имя поля
            data["voice_profile"] = data.pop("voice_base")
        for key in ("entity_type", "role_tier", "gender"):
            if isinstance(data.get(key), str):
                data[key] = data[key].strip().lower()
        if data.get("ref") is not None:
            data["ref"] = str(data["ref"]).strip() or None
        # Неполные или кривые вложенные объекты не должны ронять весь список патчей
        for key, required in (("timeline_voice_update", ("age_group", "voice_description")),
                              ("timeline_visual_update", ("description",))):
            value = data.get(key)
            if value is not None and (not isinstance(value, dict) or any(not value.get(r) for r in required)):
                data[key] = None
        # Неизвестные значения enum -> None (вместо ошибки валидации)
        enums = {"entity_type": CharacterType, "role_tier": RoleTier, "gender": CharacterGender}
        for key, enum in enums.items():
            if data.get(key) is not None and data[key] not in {e.value for e in enum}:
                data[key] = None
        return data


class LlmCharacterPatchList(PydanticBaseModel):
    patches: List[LlmCharacterPatch] = Field(default_factory=list)

    @model_validator(mode="before")
    @classmethod
    def wrap_bare_list(cls, data: Any):
        if isinstance(data, list):
            return {"patches": data}
        return data

class RawChapterSummary(PydanticBaseModel):
    teaser: str = Field(...)
    synopsis: str = Field(...)

class RawScenarioEntry(PydanticBaseModel):
    id: UUID = Field(default_factory=uuid4)
    type: ScenarioEntryType
    speaker: Optional[str] = None
    text: Optional[str] = None
    src: Optional[str] = None

class RawScenario(PydanticBaseModel):
    scenario: List[RawScenarioEntry]

    @model_validator(mode="before")
    @classmethod
    def wrap_bare_list(cls, data: Any):
        # Модель иногда возвращает голый массив записей вместо {"scenario": [...]}
        if isinstance(data, list):
            return {"scenario": data}
        return data

class LlmRawScenarioEntry(PydanticBaseModel):
    type: ScenarioEntryType
    speaker: Optional[str] = None
    text: Optional[str] = None
    src: Optional[str] = None

class LlmRawScenario(PydanticBaseModel):
    scenario: List[LlmRawScenarioEntry]

    @model_validator(mode="before")
    @classmethod
    def wrap_bare_list(cls, data: Any):
        # Модель иногда возвращает голый массив записей вместо {"scenario": [...]}
        if isinstance(data, list):
            return {"scenario": data}
        return data

class SoundDesignItem(PydanticBaseModel):
    entry_id: str = Field(...)
    ambient: Optional[str] = None
    sfx: Optional[str] = None

class SoundDesignResult(PydanticBaseModel):
    design: List[SoundDesignItem] = Field(default_factory=list)

    @model_validator(mode="before")
    @classmethod
    def wrap_list_or_dict(cls, data: Any):
        if isinstance(data, list):
            return {"design": data}
        if isinstance(data, dict) and "design" not in data:
            items = []
            for k, v in data.items():
                if isinstance(v, dict):
                    items.append({"entry_id": k, **v})
            if items:
                return {"design": items}
        return data

# Метки эмоций = набор эмоциональных референсов персонажа (docs/experiments/2026-11-11_voice-research.md,
# «Эмоции на Qwen Base»). Озвучка клонирует голос из референса с этой меткой, если он есть, иначе из нейтрального.
EMOTION_LABELS = ("neutral", "angry", "sad", "happy", "whisper", "scared")


class VoiceDirection(PydanticBaseModel):
    emotion: str = Field(default="neutral", description=f"одна метка из списка: {', '.join(EMOTION_LABELS)}")
    tts_text: Optional[str] = Field(default=None, description="текст реплики с пустыми строками на месте длинных пауз; только если пауза нужна")

    @field_validator("emotion", mode="before")
    @classmethod
    def normalize_emotion(cls, v: Any) -> str:
        label = str(v or "").strip().lower()
        return label if label in EMOTION_LABELS else "neutral"

class EmotionMap(PydanticBaseModel):
    emotions: Dict[UUID, VoiceDirection] = Field(default_factory=dict)

    @model_validator(mode="before")
    @classmethod
    def wrap_dict(cls, data: Any):
        if isinstance(data, dict):
            if "emotions" not in data:
                return {"emotions": data}
        return data

# Legacy Containers (Keep only definitions for type safety, remove logic)
class ChapterSummaryArchive(PydanticBaseModel):
    model_config = ConfigDict(arbitrary_types_allowed=True, from_attributes=True)
    summaries: Dict[str, ChapterSummary] = Field(default_factory=dict)

class CharacterArchive(PydanticBaseModel):
    model_config = ConfigDict(arbitrary_types_allowed=True, from_attributes=True)
    characters: List[Character]
    processed_chapters: List[str] = Field(default_factory=list)

class Scenario(PydanticBaseModel):
    model_config = ConfigDict(arbitrary_types_allowed=True, from_attributes=True)
    entries: List[ScenarioEntry]

class ManifestMeta(PydanticBaseModel):
    title: str = "Без названия"
    author: Optional[str] = "Неизвестный автор"
    description: Optional[str] = ""
    tags: List[str] = Field(default_factory=list)
    status: BookStatus = BookStatus.ONGOING
    source_url: Optional[str] = None
    version: str = "1.0.0"
    total_duration_ms: int = 0
    cover_image: Optional[str] = None
    language: str = "ru"

class ManifestChapterEntry(PydanticBaseModel):
    order: int
    title: str
    vol: int = 1
    chap: int
    status: ChapterStatus = ChapterStatus.DRAFT
    id: Optional[str] = None

class ManifestConfig(PydanticBaseModel):
    default_narrator_voice: str = "narrator_default"
    character_voices: Dict[UUID, str] = Field(default_factory=dict)
    last_run_log: Optional[str] = None

class BookManifest(PydanticBaseModel):
    project_id: str
    meta: ManifestMeta
    structure: List[ManifestChapterEntry] = Field(default_factory=list)
    config: ManifestConfig = Field(default_factory=ManifestConfig)


# --- Metrics Tables for SQLite DB ---
import time

class LLMMetric(SQLModel, table=True):
    """Метрики вызовов LLM для главы."""
    id: Optional[int] = Field(default=None, primary_key=True)
    chapter_id: str = Field(
        sa_column=Column(String, ForeignKey("chapter.id", ondelete="CASCADE"), index=True, nullable=False)
    )
    prompt_type: str
    model_name: str
    input_tokens: int = Field(default=0)
    output_tokens: int = Field(default=0)
    latency_ms: float = Field(default=0.0)
    status: str = Field(default="success")
    error_message: Optional[str] = None
    timestamp: float = Field(default_factory=time.time)

class AudioMetric(SQLModel, table=True):
    """Метрики генерации аудио для реплик сценария."""
    id: Optional[int] = Field(default=None, primary_key=True)
    chapter_id: str = Field(
        sa_column=Column(String, ForeignKey("chapter.id", ondelete="CASCADE"), index=True, nullable=False)
    )
    entry_id: str = Field(index=True)
    speaker: str
    text_length: int
    audio_duration_ms: int
    synthesis_latency_ms: float
    rtf: float
    cer: float
    attempts: int
    status: str = Field(default="success")
    timestamp: float = Field(default_factory=time.time)

class MetricCounter(SQLModel, table=True):
    """Счетчики различных событий/ошибок по главам."""
    __table_args__ = (
        UniqueConstraint("chapter_id", "counter_name", name="uq_metriccounter_chapter_counter"),
    )
    id: Optional[int] = Field(default=None, primary_key=True)
    chapter_id: str = Field(
        sa_column=Column(String, ForeignKey("chapter.id", ondelete="CASCADE"), index=True, nullable=False)
    )
    counter_name: str
    value: int = Field(default=0)
