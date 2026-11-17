from pydantic import BaseModel, Field
from typing import List, Literal, Optional, Dict, Any, Union
from enum import Enum
from uuid import UUID

from core.data_models import (
    ChapterStatus, BookStatus, RoleTier, CharacterGender, CharacterType, ScenarioEntryType
)

# --- Server State ---

class ServerStateEnum(str, Enum):
    INITIALIZING = "INITIALIZING"
    READY = "READY"
    ERROR = "ERROR"

class ServerStatus(BaseModel):
    status: ServerStateEnum
    message: str = ""

# --- Dashboard DTOs ---

class DashboardRecentProject(BaseModel):
    id: str
    title: str
    progress: float
    total_chapters: int

class DashboardSystemStatus(BaseModel):
    storage_gb: float
    llm_status: str
    tts_status: str
    active_tasks: int

class DashboardSummaryResponse(BaseModel):
    total_books: int
    total_tokens: int
    total_audio_minutes: float
    recent_projects: List[DashboardRecentProject]
    system: DashboardSystemStatus

# --- Projects DTOs ---

class ChapterStatusDto(BaseModel):
    volume_num: int
    chapter_num: Union[int, float]
    id: str
    title: Optional[str] = None
    status: ChapterStatus
    has_audio: bool

class ProjectDetailsResponse(BaseModel):
    book_id: str
    title: str
    author: Optional[str] = None
    chapters: List[ChapterStatusDto]

class ImportProjectResponse(BaseModel):
    message: str
    book_id: str

class MessageResponse(BaseModel):
    message: str

class ChapterPreviewResponse(BaseModel):
    preview: str

# --- Metrics DTOs ---

class ChapterMetricsDto(BaseModel):
    chapter_id: str
    llm_calls: int
    total_tokens: int
    audio_units: int
    total_audio_duration_sec: float
    avg_rtf: float
    avg_cer: float
    errors: Dict[str, int]

class ProjectMetricsResponse(BaseModel):
    book_id: str
    total_tokens: int
    counters: Dict[str, int]
    chapters: List[ChapterMetricsDto]
    character_activity: Dict[str, Dict[str, int]]

# --- Tasks ---

class ChapterTaskRequest(BaseModel):
    book_name: str
    volume_num: int
    chapter_num: Union[int, float]

class BookTaskRequest(BaseModel):
    book_name: str
    max_chapters: Optional[int] = Field(None, description="Ограничение количества глав для частичного анализа LLM (например 3 или 4)")

class TaskStatusResponse(BaseModel):
    task_id: str
    status: Literal["queued", "processing", "complete", "failed", "cancelled", "paused"]
    progress: float
    stage: str
    message: str

# --- Artifacts & Metadata ---

class BookArtifactName(str, Enum):
    MANIFEST = "manifest"
    CHARACTER_ARCHIVE = "character_archive"
    CHAPTER_SUMMARIES = "summary_archive"

class ChapterArtifactName(str, Enum):
    SCENARIO = "scenario"
    SUBTITLES = "subtitles"
    CACHE_RAW_SCENARIO = "cache_raw_scenario"
    CACHE_AMBIENT = "cache_ambient"

class AmbientMetadata(BaseModel):
    id: str
    description: str
    tags: List[str]

class BookStatusResponse(BaseModel):
    book_name: str
    total_chapters: int = 0
    chapters_with_scenario: int = 0
    chapters_with_tts: int = 0
    is_ready_for_export: bool = Field(
        False,
        description="True, если хотя бы одна глава полностью готова (сценарий + TTS)."
    )

# --- UI / Streaming ---

class PlaylistEntry(BaseModel):
    """Одна запись в плейлисте главы, соответствует одной реплике."""
    id: Optional[UUID] = None
    audio_file: Optional[str] = Field(None, description="Имя аудиофайла.")
    text: Optional[str] = Field(None, description="Текст реплики.")
    speaker: str = Field(description="Имя говорящего персонажа.")
    ambient: Optional[str] = Field(None, description="ID эмбиент-звука.")

class ChapterPlaylistResponse(BaseModel):
    """Модель ответа для плейлиста главы."""
    chapter_id: str
    entries: List[PlaylistEntry]

class UpdateScenarioEntryRequest(BaseModel):
    text: Optional[str] = None
    tts_text: Optional[str] = None
    instruct_prompt: Optional[str] = None
    ambient: Optional[str] = None
    sfx: Optional[str] = None

class UpdateCharacterRequest(BaseModel):
    name: Optional[str] = None
    entity_type: Optional[CharacterType] = None
    gender: Optional[CharacterGender] = None
    role_tier: Optional[RoleTier] = None
    spoiler_free_description: Optional[str] = None
    description: Optional[str] = None
    visual_base: Optional[str] = None
    voice_base: Optional[str] = None
    voice_profile: Optional[str] = None
    aliases: Optional[List[str]] = None

class UpdateBookRequest(BaseModel):
    title: Optional[str] = None
    author: Optional[str] = None
    description: Optional[str] = None
    status: Optional[BookStatus] = None
    tags: Optional[List[str]] = None
