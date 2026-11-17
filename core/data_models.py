"""
Центральный модуль, определяющий все основные структуры данных проекта.
Поддерживает SQLModel для БД. В этой архитектуре каждая книга имеет свою БД.
"""
import json
import logging
from datetime import datetime, timezone
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

    @classmethod
    def parse(cls, value: Any) -> "BookStatus":
        """Статус из любого источника: наши значения или русские статусы сайтов (ranobelib: «Онгоинг»)."""
        if isinstance(value, cls):
            return value
        key = str(value or "").strip().lower()
        if not key:  # epub/txt статуса не знают — это не ошибка
            return cls.ONGOING
        for status in cls:
            if key == status.value:
                return status
        for status, words in _BOOK_STATUS_ALIASES.items():
            if any(key.startswith(w) for w in words):
                return status
        logging.getLogger(__name__).warning(f"Неизвестный статус книги {value!r}, считаем ongoing")
        return cls.ONGOING


_BOOK_STATUS_ALIASES = {
    BookStatus.ONGOING: ("онгоинг", "продолжается", "выходит", "в процессе"),
    BookStatus.COMPLETED: ("завершен", "завершён", "закончен", "окончен"),
    BookStatus.HIATUS: ("заморожен", "приостановлен"),
    BookStatus.ABANDONED: ("заброшен", "брошен"),
}

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
    chapter_num: float = Field(default=0.0, description="Номер главы (может быть дробным, например 0.5, 0.1)")
    title: Optional[str] = None
    status: ChapterStatus = Field(default=ChapterStatus.DRAFT, sa_column=Column(String, default=ChapterStatus.DRAFT))
    # Прогресс анализа персонажей: None — не обработана, "done", "skipped" (пропущена вручную).
    # Отдельно от status: сценарий можно сгенерировать и без анализа персонажей.
    characters_state: Optional[str] = Field(default=None)
    is_service: bool = Field(default=False, description="Флаг служебной главы (дисклеймеры, глоссарии, пустые страницы)")
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
    """Глобальный пересказ целого тома (DTO)."""
    volume_num: int
    summary: str = Field(description="Сжатый пересказ событий всего тома.")

class VolumeSummaryRecord(SQLModel, table=True):
    """Таблица глобальных пересказов томов книги в SQLite."""
    __tablename__ = "volumesummary"
    id: str = Field(primary_key=True, description="vol_{volume_num}")
    book_id: str = Field(
        sa_column=Column(String, ForeignKey("book.id", ondelete="CASCADE"), index=True, nullable=False)
    )
    volume_num: int = Field(index=True)
    summary: str = Field(sa_column=Column(Text, nullable=False))
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))

class Character(SQLModel, table=True):
    """Персонажи книги."""
    id: UUID = Field(default_factory=uuid4, primary_key=True)
    book_id: str = Field(
        sa_column=Column(String, ForeignKey("book.id", ondelete="CASCADE"), index=True, nullable=False)
    )
    
    name: str = Field(..., index=True)
    entity_type: CharacterType = Field(default=CharacterType.PERSON)
    aliases: List[str] = Field(default_factory=list, sa_column=Column(MutableList.as_mutable(JSON)))
    # Алиас -> {"kind": ALIAS_KINDS | "previous_name", "since": id главы}: с какой главы читатель знает это имя.
    # Алиасы без записи — из проектов до версионирования, считаются известными с первого появления.
    alias_meta: Dict[str, Dict[str, Optional[str]]] = Field(default_factory=dict, sa_column=Column(MutableDict.as_mutable(JSON)))
    # Глава -> имя с этой главы: «Добродетельная супруга» (1.15) -> «Лишу» (1.17). Пусто — имя не менялось.
    name_timeline: Dict[str, str] = Field(default_factory=dict, sa_column=Column(MutableDict.as_mutable(JSON)))
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
    # Кастинг (core/casting.py): профиль голоса от LLM, описание для VoiceDesign, статус и id созданного голоса.
    voice_casting: Dict[str, Any] = Field(default_factory=dict, sa_column=Column(MutableDict.as_mutable(JSON)))
    # Факты о персонаже (режим character_description_mode=facts): {"id", "fact", "kind", "chapter", "until"}.
    # Карточка (`description`) — короткий портрет; факты копятся без предела и фильтруются по главе.
    facts: List[Dict[str, Any]] = Field(default_factory=list, sa_column=Column(MutableList.as_mutable(JSON)))

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

class SpeakerLinkKind(str, Enum):
    CHARACTER = "character"  # за подписью стоит известный персонаж
    EXTRA = "extra"          # массовка: свой голос по полу/возрасту


class SpeakerLinkStatus(str, Enum):
    SUGGESTED = "suggested"  # предложено LLM, голос не меняет (кроме массовки)
    CONFIRMED = "confirmed"  # подтверждено человеком (или авто-подтверждением)
    REJECTED = "rejected"    # отклонено: подпись озвучивается как массовка/рассказчик


AGE_GROUPS = ("child", "adult", "old")


class SpeakerLink(SQLModel, table=True):
    """
    Кто стоит за подписью говорящего без speaker_id в главе: «Красавец-евнух» в главе 4 = Джинши,
    «Служанка 1» — молодая женщина, не та же, что «Служанка 2».
    Сценарий не меняется: подпись на экране остается прежней (без спойлеров), связь влияет только на голос.
    """
    __table_args__ = (UniqueConstraint("chapter_id", "speaker_label", name="uq_speakerlink_chapter_label"),)
    id: UUID = Field(default_factory=uuid4, primary_key=True)
    chapter_id: str = Field(sa_column=Column(String, ForeignKey("chapter.id", ondelete="CASCADE"), nullable=False, index=True))
    speaker_label: str
    kind: str = Field(default=SpeakerLinkKind.EXTRA.value)
    character_id: Optional[UUID] = Field(
        default=None, sa_column=Column(ForeignKey("character.id", ondelete="SET NULL"), nullable=True)
    )
    confidence: str = Field(default="low")      # high | medium | low
    identity_hidden: bool = Field(default=False)  # автор намеренно скрывает личность (раскрытие позже)
    evidence: str = Field(default="", sa_column=Column(Text))
    gender: Optional[str] = None                # для массовки: male | female
    age_group: Optional[str] = None             # для массовки: child | adult | old
    person_key: str = Field(default="")         # одинаковый ключ в главе — один и тот же человек
    status: str = Field(default=SpeakerLinkStatus.SUGGESTED.value)


IDENTITY_RELATIONS = ("same_person", "impersonation", "possession", "duplicate")
IDENTITY_STATUSES = ("candidate", "suggested", "dismissed", "confirmed", "rejected")
IDENTITY_VOICE_POLICIES = ("separate", "shared", "switch_after_reveal")


class IdentityLink(SQLModel, table=True):
    """
    Две записи о персонажах — одна личность (docs/design/identity-links.md): Шисуй = Лоулан, Рэмпу выдает себя
    за Лоулан. Персонажи не сливаются: сценарий, speaker_id и описания по главам остаются, связь — слой поверх.

    persona_id — кого видит читатель (маска, псевдоним, тело, лишний дубль); true_id — кто это на самом деле
    (настоящая личность, самозванец, вселившийся, основная запись). Для impersonation: персона — Лоулан, true — Рэмпу.
    Статусы: candidate (сигнал без оценки) -> suggested | dismissed (LLM: связь есть / нет) -> confirmed | rejected (человек).
    """
    id: UUID = Field(default_factory=uuid4, primary_key=True)
    book_id: str = Field(sa_column=Column(String, ForeignKey("book.id", ondelete="CASCADE"), index=True, nullable=False))
    persona_id: UUID = Field(sa_column=Column(ForeignKey("character.id", ondelete="CASCADE"), nullable=False))
    true_id: UUID = Field(sa_column=Column(ForeignKey("character.id", ondelete="CASCADE"), nullable=False))
    relation: str = Field(default="same_person")
    revealed_in: Optional[str] = None           # глава, где узнает читатель; пусто — подозрение без раскрытия
    # Главы, где самозванец действует под видом персоны (impersonation): «брата Императора» в 4.9 играет А-Дуо,
    # в остальных главах — сам Джинши. Пусто — без ограничения (same_person, duplicate) или еще не определено.
    active_chapters: List[str] = Field(default_factory=list, sa_column=Column(MutableList.as_mutable(JSON)))
    confidence: Optional[float] = None          # вероятность выбранного варианта (logprobs); None — не оценено
    scores: Dict[str, float] = Field(default_factory=dict, sa_column=Column(MutableDict.as_mutable(JSON)))
    evidence: str = Field(default="", sa_column=Column(Text))
    status: str = Field(default="candidate")
    voice_policy: str = Field(default="separate")
    voice_shift: Optional[float] = None        # сдвиг высоты в полутонах (+0.75, -0.5) для похожих голосов
    source: str = Field(default="human")        # alias_collision | secret_scan | human
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    decided_at: Optional[datetime] = None


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


class LlmReconMention(PydanticBaseModel):
    ref: str = Field(..., description="ref персонажа из списка, без изменений")
    action: str = Field(..., description="Что персонаж делает в этой главе, одно предложение")
    card_changed: bool = Field(False, description="true, если глава меняет карточку личности (см. правила)")
    why: Optional[str] = Field(None, description="Если card_changed — что именно новое, коротко")

    @model_validator(mode="before")
    @classmethod
    def coerce(cls, data: Any):
        if isinstance(data, dict) and data.get("ref") is not None:
            data["ref"] = str(data["ref"]).strip()
        return data


class LlmReconCardResult(PydanticBaseModel):
    """Разведка в режиме карточек: кто есть в главе, что делает и у кого изменилась карточка."""
    mentioned: List[LlmReconMention] = Field(default_factory=list, description="Известные персонажи из списка, которые есть в главе")
    newly_discovered_names: List[str] = Field(default_factory=list, description="Имена новых персонажей")


class LlmVoiceState(PydanticBaseModel):
    age_group: str = Field(..., description="child | teen | adult | elderly")
    voice_description: str = Field(..., description="Как звучит голос, на русском")
    search_tags: Optional[str] = Field(None, description="5-7 тегов на английском")


class LlmVisualState(PydanticBaseModel):
    description: str = Field(..., description="Внешность и одежда в этой главе, на русском")
    image_prompt: Optional[str] = Field(None, description="Промпт для генерации, на английском")


FACT_KINDS_STATE = ("status", "relation", "secret")          # могут устареть: патч их видит и закрывает
FACT_KINDS_DETAIL = ("trait", "skill", "appearance", "past", "health", "quirk")  # копятся вслепую
FACT_KINDS = FACT_KINDS_STATE + FACT_KINDS_DETAIL


class LlmFact(PydanticBaseModel):
    fact: str = Field(..., description="Один факт, коротко, на русском")
    kind: str = Field(..., description=" | ".join(FACT_KINDS))

    @model_validator(mode="before")
    @classmethod
    def coerce(cls, data: Any):
        if isinstance(data, dict) and isinstance(data.get("kind"), str):
            kind = data["kind"].strip().lower()
            data["kind"] = kind if kind in FACT_KINDS else "quirk"
        return data


class LlmCardCompact(PydanticBaseModel):
    card: str = Field(..., description="Сжатая карточка")


ALIAS_KINDS = ("name", "nickname", "cover", "description")
ALIAS_KINDS_PERSONAL = ("name", "nickname", "cover")  # принадлежат человеку; description может перейти к другому


class LlmAlias(PydanticBaseModel):
    """Алиас с видом: титул или роль («Целомудренная супруга») — не имя, может перейти к другому человеку."""
    alias: str = Field(..., description="Как назван в тексте")
    kind: Optional[str] = Field(None, description=" | ".join(ALIAS_KINDS))

    @model_validator(mode="before")
    @classmethod
    def coerce(cls, data: Any):
        if isinstance(data, str):  # старый формат: строка без вида
            return {"alias": data, "kind": None}
        if isinstance(data, dict) and isinstance(data.get("kind"), str):
            kind = data["kind"].strip().lower()
            data["kind"] = kind if kind in ALIAS_KINDS else None
        return data


class LlmCharacterPatch(PydanticBaseModel):
    ref: Optional[str] = Field(None, description="ref из входных данных; для нового персонажа не пиши")
    name: Optional[str] = Field(None, description="Каноническое имя")
    entity_type: Optional[CharacterType] = None
    role_tier: Optional[RoleTier] = None
    gender: Optional[CharacterGender] = None
    aliases: Optional[List[LlmAlias]] = Field(None, description="Только новые имена из этой главы, каждое с видом")
    current_chapter_action: Optional[str] = Field(None, description="Что делает в этой главе, одно предложение")
    description_update: Optional[str] = Field(None, description="Полное актуальное описание, только если глава добавила новое")
    visual_base: Optional[str] = Field(None, description="Только если у персонажа пусто")
    voice_profile: Optional[str] = Field(None, description="Теги голоса; новому персонажу обязательно, известному — только если пусто")
    timeline_visual_update: Optional[LlmVisualState] = None
    timeline_voice_update: Optional[LlmVoiceState] = None
    # Режим facts
    personal_name: Optional[str] = Field(None, description="Личное имя, если глава ВПЕРВЫЕ его назвала, а персонаж записан ролью")
    new_facts: Optional[List[LlmFact]] = Field(None, description="Новые факты этой главы")
    retired_facts: Optional[List[str]] = Field(None, description="id фактов из state_facts, которые перестали быть верными")

    @model_validator(mode="before")
    @classmethod
    def coerce(cls, data: Any):
        if not isinstance(data, dict):
            return data
        if isinstance(data.get("aliases"), list):
            data["aliases"] = [a for a in data["aliases"]
                               if (isinstance(a, str) and a.strip()) or (isinstance(a, dict) and str(a.get("alias") or "").strip())]
        if data.get("retired_facts") is not None:
            data["retired_facts"] = [str(x).strip() for x in data["retired_facts"] if x is not None]
        if isinstance(data.get("new_facts"), list):
            data["new_facts"] = [f for f in data["new_facts"] if isinstance(f, dict) and str(f.get("fact") or "").strip()]
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


def alias_texts(patch: "LlmCharacterPatch") -> List[str]:
    """Все алиасы патча строками (с описаниями)."""
    return [a.alias.strip() for a in (patch.aliases or []) if a.alias and a.alias.strip()]


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

class LlmSpeakerResolution(PydanticBaseModel):
    label: str = Field(..., description="подпись говорящего ровно как во входных данных")
    kind: Literal["character", "extra"] = Field(..., description="character — это известный персонаж из списка; extra — массовка")
    character: Optional[str] = Field(default=None, description="для character: имя персонажа ровно как в списке персонажей")
    confidence: Literal["high", "medium", "low"] = Field(default="low", description="уверенность в связи с персонажем")
    identity_hidden: bool = Field(default=False, description="true, если автор намеренно скрывает личность и раскрывает позже")
    evidence: str = Field(default="", description="1-2 коротких довода с опорой на текст")
    gender: Optional[Literal["male", "female"]] = Field(default=None, description="для extra: пол")
    age_group: Optional[Literal["child", "adult", "old"]] = Field(default=None, description="для extra: возраст")
    person_key: str = Field(default="", description="одинаковый у подписей, за которыми один и тот же человек (например, 'eunuch1')")

    @field_validator("confidence", mode="before")
    @classmethod
    def normalize_confidence(cls, v: Any) -> str:
        v = str(v or "").strip().lower()
        return v if v in ("high", "medium", "low") else "low"


class LlmSpeakerResolutionList(PydanticBaseModel):
    speakers: List[LlmSpeakerResolution] = Field(default_factory=list)

    @model_validator(mode="before")
    @classmethod
    def wrap_bare_list(cls, data: Any):
        if isinstance(data, list):
            return {"speakers": data}
        return data


class LlmVoiceCast(PydanticBaseModel):
    """Базовый голос персонажа для VoiceDesign: без эмоций, только кто говорит."""
    name: str = Field(..., description="имя персонажа ровно как во входных данных")
    gender: Literal["male", "female"] = Field(..., description="пол голоса")
    age: int = Field(..., description="возраст голоса в годах (по тексту; если не сказан — правдоподобная оценка)")
    pitch: Literal["low", "medium-low", "medium", "medium-high", "high"] = Field(..., description="высота голоса относительно нормы для пола и возраста")
    timbre: str = Field(..., description="тембр по-английски, 2-4 слова: 'warm, slightly husky'")
    pace: Literal["slow", "moderate", "fast"] = Field(default="moderate", description="обычный темп речи")
    manner: str = Field(..., description="манера речи по-английски, 3-6 слов, без эмоций момента: 'dry, matter-of-fact, precise'")
    distinct_from: str = Field(default="", description="по-английски: чем голос отличается от похожих персонажей состава")
    instruct_zh: str = Field(default="", description="то же описание голоса одной строкой на китайском (пол, возраст, высота, тембр, темп, манера)")

    @field_validator("pitch", mode="before")
    @classmethod
    def normalize_pitch(cls, v: Any) -> str:
        v = str(v or "").strip().lower().replace(" ", "-").replace("_", "-")
        return v if v in ("low", "medium-low", "medium", "medium-high", "high") else "medium"

    @field_validator("pace", mode="before")
    @classmethod
    def normalize_pace(cls, v: Any) -> str:
        v = str(v or "").strip().lower()
        return v if v in ("slow", "moderate", "fast") else "moderate"


class LlmVoiceCastList(PydanticBaseModel):
    voices: List[LlmVoiceCast] = Field(default_factory=list)

    @model_validator(mode="before")
    @classmethod
    def wrap_bare_list(cls, data: Any):
        if isinstance(data, list):
            return {"voices": data}
        return data


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

    @field_validator("status", mode="before")
    @classmethod
    def normalize_status(cls, v: Any) -> BookStatus:
        return BookStatus.parse(v)
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
    """Метрики вызовов LLM. chapter_id пустой — этап уровня книги (кастинг)."""
    id: Optional[int] = Field(default=None, primary_key=True)
    chapter_id: Optional[str] = Field(
        default=None,
        sa_column=Column(String, ForeignKey("chapter.id", ondelete="CASCADE"), index=True, nullable=True)
    )
    prompt_type: str
    model_name: str
    input_tokens: int = Field(default=0)
    output_tokens: int = Field(default=0)
    latency_ms: float = Field(default=0.0)
    status: str = Field(default="success")
    error_message: Optional[str] = None
    timestamp: float = Field(default_factory=time.time)
    cost_usd: Optional[float] = None    # цена от провайдера (OpenRouter usage.cost); None — неизвестна
    # server_default — чтобы INSERT без этих колонок (миграция старых строк) не падал на NOT NULL
    attempts: int = Field(default=1, sa_column_kwargs={"server_default": "1"})  # попыток внутри вызова (429, таймауты)
    cached_tokens: int = Field(default=0, sa_column_kwargs={"server_default": "0"})
    reasoning_tokens: int = Field(default=0, sa_column_kwargs={"server_default": "0"})

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
