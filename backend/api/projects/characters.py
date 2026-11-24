import logging
from pathlib import Path
from uuid import UUID
from fastapi import APIRouter, HTTPException
from fastapi.responses import FileResponse

from typing import List
from core.book_repository import BookRepository
from core import path_manager
from core.data_models import Character
from api.models import UpdateCharacterRequest, MessageResponse

logger = logging.getLogger(__name__)

router = APIRouter()


@router.get("/{book_name}/characters", response_model=List[Character])
async def get_book_characters(book_name: str):
    """Возвращает список всех персонажей книги."""
    repo = BookRepository(book_id=book_name)
    return repo.get_characters()


@router.patch("/{book_name}/characters/{char_id}", response_model=Character)
async def update_character(
    book_name: str,
    char_id: UUID,
    req: UpdateCharacterRequest
):
    """Обновляет данные персонажа в БД книги."""
    repo = BookRepository(book_id=book_name)
    with repo.get_session() as session:
        char = session.get(Character, char_id)
        if not char:
            raise HTTPException(status_code=404, detail="Персонаж не найден.")

        update_data = req.model_dump(exclude_unset=True)
        for key, value in update_data.items():
            setattr(char, key, value)

        session.add(char)
        session.commit()
        session.refresh(char)
        return char


@router.delete("/{book_name}/characters/{char_id}", response_model=MessageResponse)
async def delete_character(book_name: str, char_id: UUID):
    """Удаляет персонажа из БД книги."""
    repo = BookRepository(book_id=book_name)
    with repo.get_session() as session:
        char = session.get(Character, char_id)
        if not char:
            raise HTTPException(status_code=404, detail="Персонаж не найден.")

        session.delete(char)
        session.commit()
        return {"message": "Персонаж удален."}


@router.get("/{book_name}/characters/{char_id}/portrait")
async def get_character_portrait(book_name: str, char_id: UUID):
    """Возвращает файл портрета персонажа."""
    repo = BookRepository(book_id=book_name)
    img_path = None
    with repo.get_session() as session:
        char = session.get(Character, char_id)
        if not char:
            raise HTTPException(status_code=404, detail="Персонаж не найден.")

        if char.visual_timeline:
            if "global" in char.visual_timeline and char.visual_timeline["global"].reference_image_path:
                img_path = char.visual_timeline["global"].reference_image_path
            else:
                for state in char.visual_timeline.values():
                    if state.reference_image_path:
                        img_path = state.reference_image_path
                        break

    if not img_path:
        images_dir = path_manager.get_book_output_dir(book_name) / "images"
        if images_dir.exists():
            for f in images_dir.iterdir():
                if f.name.startswith(str(char_id)) and f.suffix.lower() in ['.png', '.jpg', '.jpeg']:
                    img_path = f
                    break

    if not img_path:
        raise HTTPException(status_code=404, detail="Портрет персонажа не найден.")

    path = Path(img_path)
    if not path.is_absolute():
        path = path_manager.get_book_output_dir(book_name) / path

    if not path.exists():
        raise HTTPException(status_code=404, detail="Файл портрета не найден.")
    return FileResponse(path)
