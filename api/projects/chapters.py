import logging
from uuid import UUID
from fastapi import APIRouter, HTTPException
from fastapi.responses import FileResponse
from sqlmodel import select

from typing import List
from core.book_repository import BookRepository
from core import path_manager
from core.data_models import ScenarioEntry
from api.models import UpdateScenarioEntryRequest, ChapterPreviewResponse

logger = logging.getLogger(__name__)

router = APIRouter()


def _format_chap_param(chapter_num: float) -> str:
    if chapter_num.is_integer() and str(chapter_num) != "0.0":
        return str(int(chapter_num))
    return str(chapter_num)


@router.get("/{book_name}/chapters/{volume_num}/{chapter_num}/entries", response_model=List[ScenarioEntry])
async def get_chapter_entries(
    book_name: str,
    volume_num: int,
    chapter_num: float,
    offset: int = 0,
    limit: int = 100
):
    """Возвращает записи сценария для главы с поддержкой пагинации."""
    chapter_id = f"vol_{volume_num}_chap_{_format_chap_param(chapter_num)}"
    repo = BookRepository(book_id=book_name)
    with repo.get_session() as session:
        statement = select(ScenarioEntry).where(
            ScenarioEntry.chapter_id == chapter_id
        ).order_by(ScenarioEntry.order_index).offset(offset).limit(limit)

        entries = session.exec(statement).all()
        return entries


@router.patch("/{book_name}/entries/{entry_id}", response_model=ScenarioEntry)
async def update_scenario_entry(
    book_name: str,
    entry_id: UUID,
    req: UpdateScenarioEntryRequest
):
    """Обновляет одну запись сценария в БД книги."""
    repo = BookRepository(book_id=book_name)
    with repo.get_session() as session:
        entry = session.get(ScenarioEntry, entry_id)
        if not entry:
            raise HTTPException(status_code=404, detail="Запись не найдена.")

        update_data = req.model_dump(exclude_unset=True)
        for key, value in update_data.items():
            setattr(entry, key, value)

        session.add(entry)
        session.commit()
        session.refresh(entry)
        return entry


@router.get("/{book_name}/chapters/{volume_num}/{chapter_num}/preview", response_model=ChapterPreviewResponse)
async def get_chapter_preview(book_name: str, volume_num: int, chapter_num: float):
    """Возвращает текстовый превью главы (первые 1000 символов)."""
    file_path = path_manager.get_chapter_text_path(book_name, volume_num, chapter_num)
    if not file_path.exists():
        raise HTTPException(status_code=404, detail="Файл главы не найден.")
    try:
        text = file_path.read_text(encoding="utf-8")
        preview = text[:1000]
        if len(text) > 1000:
            preview += "\n..."
        return {"preview": preview}
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Ошибка чтения файла главы: {e}")


from fastapi import APIRouter, HTTPException, Request
from utils.audio_merger import ranged_file_response


@router.get("/{book_name}/chapters/{volume_num}/{chapter_num}/audio/{audio_file_name}")
async def get_audio_file(
    request: Request,
    book_name: str,
    volume_num: int,
    chapter_num: float,
    audio_file_name: str
):
    chapter_id = f"vol_{volume_num}_chap_{_format_chap_param(chapter_num)}"
    path = path_manager.get_chapter_audio_dir(book_name, chapter_id) / audio_file_name
    return ranged_file_response(request, path)
