import logging
from fastapi import APIRouter, BackgroundTasks, HTTPException

import config
from api import state
from api.models import TaskStatusResponse, BookTaskRequest, ChapterTaskRequest
from core.task_queue import TaskType

logger = logging.getLogger(__name__)

router = APIRouter(
    prefix="/api/v1/tasks/ai",
    tags=["AI Processing Tasks"]
)


def _require_full_mode(feature: str) -> None:
    if config.IS_LITE_MODE:
        raise HTTPException(
            status_code=501,
            detail=f"{feature} недоступно в режиме BOOKWEAVER_MODE=lite (только персонажи и пересказы).",
        )

@router.post("/analyze-characters", response_model=TaskStatusResponse, status_code=202)
async def start_character_analysis(req: BookTaskRequest):
    """Анализирует персонажей во всей (или первых N) книге (LLM)."""
    return state.start_task(
        task_type=TaskType.PROCESS_BOOK,
        book_id=req.book_name,
        book_name=req.book_name,
        max_chapters=req.max_chapters
    )


@router.post("/generate-summaries", response_model=TaskStatusResponse, status_code=202)
async def start_summary_generation(req: BookTaskRequest):
    """Генерирует пересказы для всех (или первых N) глав (LLM)."""
    return state.start_task(
        task_type=TaskType.GENERATE_SUMMARY,
        book_id=req.book_name,
        book_name=req.book_name,
        max_chapters=req.max_chapters
    )


@router.post("/generate-scenario", response_model=TaskStatusResponse, status_code=202)
async def start_scenario_generation(req: ChapterTaskRequest):
    """Генерирует сценарий для одной главы (LLM + Sound Design)."""
    _require_full_mode("Генерация сценария")
    return state.start_task(
        task_type=TaskType.GENERATE_SCENARIO,
        book_id=req.book_name,
        book_name=req.book_name,
        volume_num=req.volume_num,
        chapter_num=req.chapter_num
    )


@router.post("/synthesize-tts", response_model=TaskStatusResponse, status_code=202)
async def start_tts_synthesis(req: ChapterTaskRequest):
    """Озвучивает главу (TTS)."""
    _require_full_mode("Синтез речи (TTS)")
    return state.start_task(
        task_type=TaskType.GENERATE_TTS,
        book_id=req.book_name,
        book_name=req.book_name,
        volume_num=req.volume_num,
        chapter_num=req.chapter_num
    )

@router.post("/full-cycle", response_model=TaskStatusResponse, status_code=202)
async def start_full_cycle(req: BookTaskRequest):
    """Запускает полный цикл: Саммари -> Персонажи -> Сценарии (с опциональным ограничением max_chapters)."""
    return state.start_task(
        task_type=TaskType.FULL_CYCLE,
        book_id=req.book_name,
        book_name=req.book_name,
        max_chapters=req.max_chapters
    )


# VC Pipeline пока не интегрирован в БД полноценно или отключен в main.py
# @router.post("/apply-voice-conversion", response_model=TaskStatusResponse, status_code=202)
