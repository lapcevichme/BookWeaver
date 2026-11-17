import shutil
import logging
from pathlib import Path
from typing import List, Union, Optional
from fastapi import APIRouter, HTTPException, UploadFile, File
from fastapi.responses import FileResponse
from sqlmodel import select
from pydantic import BaseModel

import config
from core.book_repository import BookRepository
from core import path_manager
from core.data_models import Book, Chapter, ChapterStatus
from api.models import UpdateBookRequest, ProjectDetailsResponse, MessageResponse, ImportProjectResponse
from utils.book_converter import BookConverter
from utils.exporter import BookExporter

logger = logging.getLogger(__name__)

router = APIRouter()


class ChapterStructureItem(BaseModel):
    id: str
    volume_num: int
    chapter_num: Union[int, float]
    title: str


class BookStructureUpdate(BaseModel):
    title: str
    author: str
    chapters: List[ChapterStructureItem]


@router.get("/{book_name}", response_model=ProjectDetailsResponse)
async def get_project_details(book_name: str):
    """Возвращает детали книги из её локальной БД."""
    repo = BookRepository(book_id=book_name)
    book = repo.get_book()
    if not book:
        raise HTTPException(status_code=404, detail="Книга или база данных проекта не найдены.")

    chapters = repo.get_all_chapters()

    chapters_status = []
    for chap in chapters:
        audio_dir = path_manager.get_chapter_audio_dir(book_name, chap.id)
        has_audio = audio_dir.exists() and any(audio_dir.iterdir())

        chapters_status.append({
            "volume_num": chap.volume_num,
            "chapter_num": chap.chapter_num,
            "id": chap.id,
            "title": chap.title,
            "status": chap.status,
            "has_audio": has_audio
        })

    return {
        "book_id": book.id,
        "title": book.title,
        "author": book.author,
        "chapters": chapters_status
    }


@router.patch("/{book_name}", response_model=Book)
async def update_project_metadata(book_name: str, req: UpdateBookRequest):
    """Обновляет метаданные книги в БД."""
    repo = BookRepository(book_id=book_name)
    with repo.get_session() as session:
        book = session.get(Book, book_name)
        if not book:
            raise HTTPException(status_code=404, detail="Книга не найдена.")

        update_data = req.model_dump(exclude_unset=True)
        for key, value in update_data.items():
            setattr(book, key, value)

        session.add(book)
        session.commit()
        session.refresh(book)
        return book


@router.delete("/{book_name}", response_model=MessageResponse)
async def delete_project(book_name: str):
    """Полностью удаляет проект (БД, вывод и входные файлы)."""
    from core.database import dispose_engine
    book_output_dir = path_manager.get_book_output_dir(book_name)
    book_input_dir = path_manager.get_book_dir(book_name)

    dispose_engine(book_output_dir)

    if book_output_dir.exists():
        shutil.rmtree(book_output_dir)
        logger.info(f"Удалена папка вывода: {book_output_dir}")

    if book_input_dir.exists():
        shutil.rmtree(book_input_dir)
        logger.info(f"Удалена папка входа: {book_input_dir}")

    return {"message": f"Проект '{book_name}' успешно удален."}


@router.post("/import", response_model=ImportProjectResponse)
async def import_project(file: UploadFile = File(...)):
    """Загружает и конвертирует новую книгу."""
    temp_dir = config.BASE_DIR / "temp_uploads"
    temp_dir.mkdir(exist_ok=True)
    temp_file_path = temp_dir / file.filename
    try:
        contents = await file.read()
        with open(temp_file_path, "wb") as buffer:
            buffer.write(contents)

        converter = BookConverter(input_file=temp_file_path)
        converter.run()
        return {
            "message": f"Проект '{temp_file_path.stem}' успешно импортирован.",
            "book_id": converter.book_name
        }
    except Exception as e:
        logger.error(f"Import error: {e}")
        raise HTTPException(status_code=500, detail=str(e))
    finally:
        if temp_file_path.exists():
            temp_file_path.unlink()


@router.get("/{book_name}/export", response_class=FileResponse)
async def export_project(book_name: str):
    """Собирает .bw архив для мобилки."""
    try:
        exporter = BookExporter(book_name=book_name)
        archive_path = exporter.export()
        if not archive_path:
            raise HTTPException(status_code=500, detail="Ошибка при создании архива.")
        return FileResponse(path=archive_path, filename=archive_path.name)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{book_name}/cover")
async def get_cover(book_name: str):
    repo = BookRepository(book_id=book_name)
    book = repo.get_book()
    if not book or not book.cover_image_path:
        raise HTTPException(status_code=404, detail="Обложка не найдена.")

    path = Path(book.cover_image_path)
    if not path.is_absolute():
        path = path_manager.get_book_output_dir(book_name) / path

    if not path.exists():
        raise HTTPException(status_code=404, detail="Файл обложки не найден.")
    return FileResponse(path)


@router.post("/{book_name}/structure", response_model=MessageResponse)
async def update_project_structure(book_name: str, req: BookStructureUpdate):
    """Обновляет метаданные книги и структуру глав, удаляя лишние главы."""
    repo = BookRepository(book_id=book_name)
    with repo.get_session() as session:
        book = session.get(Book, book_name)
        if not book:
            raise HTTPException(status_code=404, detail="Книга не найдена.")

        book.title = req.title
        book.author = req.author
        session.add(book)

        statement = select(Chapter).where(Chapter.book_id == book_name)
        current_chapters = session.exec(statement).all()
        current_chapters_dict = {c.id: c for c in current_chapters}

        keep_ids = set()
        for idx, item in enumerate(req.chapters, 1):
            keep_ids.add(item.id)
            if item.id in current_chapters_dict:
                chap = current_chapters_dict[item.id]
                chap.volume_num = item.volume_num
                chap.chapter_num = item.chapter_num
                chap.title = item.title
                chap.order_index = idx
                session.add(chap)
            else:
                new_chap = Chapter(
                    id=item.id,
                    book_id=book_name,
                    volume_num=item.volume_num,
                    chapter_num=item.chapter_num,
                    title=item.title,
                    order_index=idx,
                    status=ChapterStatus.DRAFT
                )
                session.add(new_chap)

        for c_id, chap in current_chapters_dict.items():
            if c_id not in keep_ids:
                session.delete(chap)
                try:
                    vol_dir = path_manager.get_book_dir(book_name) / f"vol_{chap.volume_num}"
                    file_path = vol_dir / f"chapter_{chap.chapter_num}.md"
                    if file_path.exists():
                        file_path.unlink()
                        logger.info(f"Удален файл главы: {file_path}")
                    if vol_dir.exists() and not any(vol_dir.iterdir()):
                        vol_dir.rmdir()
                        logger.info(f"Удалена пустая папка тома: {vol_dir}")
                except Exception as e:
                    logger.warning(f"Не удалось удалить файл для удаленной главы {c_id}: {e}")

        session.commit()
    return {"message": "Структура книги успешно обновлена."}
