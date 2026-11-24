import logging
from typing import List
from fastapi import APIRouter
from sqlmodel import select
from sqlalchemy import func

import config
from core.book_repository import BookRepository
from core import path_manager
from core.data_models import ChapterStatus
from pathlib import Path
from api.models import DashboardSummaryResponse, DashboardRecentProject, DashboardSystemStatus, ProjectCatalogItem

logger = logging.getLogger(__name__)

router = APIRouter()


@router.get("/dashboard/summary", response_model=DashboardSummaryResponse)
async def get_dashboard_summary():
    """Возвращает глобальную статистику по всей библиотеке."""
    from api import state

    output_dir = config.OUTPUT_DIR
    if not output_dir.exists():
        return {"total_books": 0, "recent_projects": []}

    # 1. Считаем книги
    all_projects = [d for d in output_dir.iterdir() if d.is_dir() and (d / "project.db").exists()]

    # 2. Находим последние активные
    recent_dirs = sorted(all_projects, key=lambda d: d.stat().st_mtime, reverse=True)[:4]
    recent_projects = []
    for d in recent_dirs:
        repo = BookRepository(book_id=d.name)
        book = repo.get_book()
        if book:
            chapters = repo.get_all_chapters()
            total = len(chapters)
            ready = 0
            for chap in chapters:
                if chap.status in (ChapterStatus.AUDIO_READY, ChapterStatus.COMPLETED):
                    ready += 1
                else:
                    audio_dir = path_manager.get_chapter_audio_dir(d.name, chap.id)
                    if audio_dir.exists() and any(audio_dir.iterdir()):
                        ready += 1
            recent_projects.append({
                "id": d.name,
                "title": book.title,
                "progress": round((ready / total * 100), 1) if total > 0 else 0,
                "total_chapters": total
            })

    # 3. Общие метрики и дисковое пространство из баз данных всех книг
    total_tokens = 0
    total_audio_sec = 0
    total_bytes = 0
    for d in all_projects:
        repo = BookRepository(book_id=d.name)
        try:
            with repo.get_session() as session:
                from core.data_models import LLMMetric, AudioMetric, Book
                tokens = session.exec(select(func.sum(LLMMetric.input_tokens + LLMMetric.output_tokens))).first() or 0
                total_tokens += tokens
                audio_ms = session.exec(select(func.sum(AudioMetric.audio_duration_ms))).first() or 0
                total_audio_sec += audio_ms / 1000
                book = session.get(Book, d.name)
                if book and book.storage_size_bytes:
                    total_bytes += book.storage_size_bytes
        except Exception as e:
            logger.warning(f"Failed to read metrics from {d.name} DB: {e}")

    total_audio_min = round(total_audio_sec / 60, 1)
    storage_gb = round(total_bytes / (1024**3), 2)

    # 5. Статус сервисов
    llm_ok = state.app_pipelines is not None and state.app_pipelines.character_pipeline is not None
    tts_ok = state.app_pipelines is not None and state.app_pipelines.tts_pipeline is not None

    # Активные задачи (реальные)
    from core.task_queue import list_tasks, TaskStatus
    active_tasks_count = sum(1 for t in list_tasks() if t.status == TaskStatus.PROCESSING)

    return {
        "total_books": len(all_projects),
        "total_tokens": total_tokens,
        "total_audio_minutes": total_audio_min,
        "recent_projects": recent_projects,
        "system": {
            "storage_gb": storage_gb,
            "llm_status": "Online" if llm_ok else "Offline",
            "tts_status": "Online" if tts_ok else "Offline",
            "active_tasks": active_tasks_count
        }
    }


@router.get("/", response_model=List[str])
async def list_projects():
    """Сканирует папку output и возвращает список проектов с базами данных."""
    books_dir = config.OUTPUT_DIR
    if not books_dir.exists():
        return []

    projects = []
    for d in books_dir.iterdir():
        if d.is_dir() and (d / "project.db").exists():
            projects.append(d.name)
    return sorted(projects)


@router.get("/catalog", response_model=List[ProjectCatalogItem])
async def list_projects_catalog():
    """Возвращает обогащённый список книг для библиотеки (название, автор, главы, наличие обложки)."""
    books_dir = config.OUTPUT_DIR
    if not books_dir.exists():
        return []

    catalog: List[ProjectCatalogItem] = []
    project_dirs = sorted(
        (d for d in books_dir.iterdir() if d.is_dir() and (d / "project.db").exists()),
        key=lambda d: d.name,
    )

    for d in project_dirs:
        book_id = d.name
        try:
            repo = BookRepository(book_id=book_id)
            book = repo.get_book()
            if not book:
                continue
            chapters = repo.get_all_chapters()
            volumes = {c.volume_num for c in chapters} if chapters else set()

            has_cover = False
            if book.cover_image_path:
                cover_path = Path(book.cover_image_path)
                if not cover_path.is_absolute():
                    cover_path = path_manager.get_book_output_dir(book_id) / cover_path
                has_cover = cover_path.exists()

            catalog.append(ProjectCatalogItem(
                id=book_id,
                title=book.title or book_id,
                author=book.author,
                chapters_count=len(chapters),
                volumes_count=len(volumes),
                has_cover=has_cover,
            ))
        except Exception as e:
            logger.warning(f"Failed to load catalog entry for {book_id}: {e}")

    return catalog
