import json
import re
import shutil
from pathlib import Path
from typing import List, Dict

from fastapi import APIRouter, HTTPException, UploadFile, File, Form
from fastapi.responses import JSONResponse, FileResponse

import config
from api.models import AmbientMetadata

router = APIRouter(
    prefix="/api/v1",
    tags=["Asset Library"]
)

# Управление голосами

@router.get("/voices")
async def get_voices_library():
    """Возвращает список всех доступных голосов."""
    voices = []
    if not config.VOICES_DIR.exists():
        return []
    for voice_dir in config.VOICES_DIR.iterdir():
        if voice_dir.is_dir() and voice_dir.name != "was_in_root":
            files = [
                f.name for f in voice_dir.iterdir() 
                if f.is_file() and f.suffix.lower() in (".wav", ".mp3", ".ogg", ".m4a")
            ]
            voices.append({"voice_id": voice_dir.name, "files": files})
    return voices


@router.post("/voices")
async def upload_voice(
    voice_id: str = Form(..., description="Уникальный ID для голоса, например, 'author_male'"),
    file: UploadFile = File(..., description="WAV файл с образцом голоса.")
):
    """Загружает новый голос в библиотеку."""
    if not re.match(r"^[a-zA-Z0-9_-]+$", voice_id):
        raise HTTPException(status_code=400, detail="Voice ID может содержать только буквы, цифры, _ и -.")

    voice_dir = config.VOICES_DIR / voice_id
    voice_dir.mkdir(exist_ok=True)

    file_path = voice_dir / file.filename
    if not file_path.name.lower().endswith(".wav"):
         raise HTTPException(status_code=400, detail="Поддерживаются только WAV файлы.")

    try:
        with open(file_path, "wb") as buffer:
            shutil.copyfileobj(file.file, buffer)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Не удалось сохранить файл: {e}")

    return JSONResponse(status_code=201, content={"voice_id": voice_id, "filename": file.filename})


@router.get("/voices/{voice_id}/file/{filename}")
async def get_voice_file(voice_id: str, filename: str):
    """Возвращает аудиофайл голоса для воспроизведения."""
    file_path = config.VOICES_DIR / voice_id / filename
    if not file_path.exists() or not file_path.is_file():
        raise HTTPException(status_code=404, detail=f"Файл {filename} для голоса {voice_id} не найден.")
    return FileResponse(file_path)


from pydantic import BaseModel

class UpdateTranscriptionRequest(BaseModel):
    text: str

@router.delete("/voices/{voice_id}")
async def delete_voice(voice_id: str):
    """Удаляет голос из библиотеки."""
    voice_dir = config.VOICES_DIR / voice_id
    if not voice_dir.exists() or not voice_dir.is_dir():
        raise HTTPException(status_code=404, detail=f"Голос с ID '{voice_id}' не найден.")

    try:
        shutil.rmtree(voice_dir)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Ошибка при удалении папки голоса: {e}")

    return JSONResponse(status_code=200, content={"message": f"Голос '{voice_id}' успешно удален."})


@router.get("/voices/{voice_id}/transcription")
async def get_voice_transcription(voice_id: str):
    """Возвращает транскрипцию первого файла голоса."""
    from api import state
    voice_dir = config.VOICES_DIR / voice_id
    if not voice_dir.exists() or not voice_dir.is_dir():
        raise HTTPException(status_code=404, detail="Голос не найден.")
    
    audio_files = [f for f in voice_dir.iterdir() if f.is_file() and f.suffix.lower() in (".wav", ".mp3", ".ogg", ".m4a")]
    if not audio_files:
        return {"transcription": ""}
        
    audio_file = audio_files[0]
    txt_path = audio_file.with_suffix(audio_file.suffix + ".txt")
    
    if txt_path.exists() and txt_path.is_file():
        return {"transcription": txt_path.read_text(encoding="utf-8").strip()}
        
    # Если файла нет на диске, попробуем использовать синглтон TTS сервиса для транскрипции через Whisper
    try:
        if state.app_pipelines:
            tts_service = state.app_pipelines.model_manager.get_tts_service()
            transcription = tts_service._get_prompt_text(audio_file)
            return {"transcription": transcription}
    except Exception as e:
        import logging
        logging.getLogger(__name__).error(f"Ошибка автоматической транскрипции: {e}")
        
    return {"transcription": ""}


@router.post("/voices/{voice_id}/transcription")
async def update_voice_transcription(voice_id: str, req: UpdateTranscriptionRequest):
    """Обновляет транскрипцию первого файла голоса."""
    from api import state
    voice_dir = config.VOICES_DIR / voice_id
    if not voice_dir.exists() or not voice_dir.is_dir():
        raise HTTPException(status_code=404, detail="Голос не найден.")
    
    audio_files = [f for f in voice_dir.iterdir() if f.is_file() and f.suffix.lower() in (".wav", ".mp3", ".ogg", ".m4a")]
    if not audio_files:
        raise HTTPException(status_code=400, detail="В папке голоса нет аудиофайлов.")
        
    audio_file = audio_files[0]
    txt_path = audio_file.with_suffix(audio_file.suffix + ".txt")
    
    try:
        txt_path.write_text(req.text.strip(), encoding="utf-8")
        
        # Сбрасываем кэш в памяти TTS сервиса
        try:
            if state.app_pipelines:
                tts_service = state.app_pipelines.model_manager.get_tts_service()
                tts_service._reference_transcription_cache[str(audio_file)] = req.text.strip()
        except Exception:
            pass
            
        return {"message": "Транскрипция успешно обновлена."}
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Не удалось сохранить транскрипцию: {e}")


# Управление эмбиентом

@router.get("/ambient")
async def get_ambient_library():
    """
    Возвращает список фоновых звуков из БД,
    проверяя наличие соответствующих аудиофайлов.
    """
    from core.database import get_system_session
    from core.system_models import AmbientTrack
    from sqlmodel import select
    
    with get_system_session() as session:
        tracks = session.exec(select(AmbientTrack)).all()
        library_entries = [t.model_dump() for t in tracks]
        
    audio_files = {f.stem for f in config.AMBIENT_DIR.iterdir() if f.is_file()}

    for entry in library_entries:
        entry["has_audio_file"] = entry.get("id") in audio_files
    return library_entries


@router.post("/ambient")
async def upload_ambient(
    metadata: str = Form(..., description="JSON-строка с метаданными: {'id': '...', 'description': '...', 'tags': [...] }"),
    file: UploadFile = File(..., description="Аудиофайл (mp3, wav, ogg).")
):
    """
    Загружает новый фоновый звук: аудиофайл + метаданные в БД.
    """
    from core.database import get_system_session
    from core.system_models import AmbientTrack
    
    try:
        meta_obj = AmbientMetadata.model_validate_json(metadata)
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Некорректный формат JSON в поле metadata: {e}")

    file_extension = Path(file.filename).suffix.lower()
    if file_extension not in ['.mp3', '.wav', '.ogg']:
         raise HTTPException(status_code=400, detail="Поддерживаемые форматы: mp3, wav, ogg.")

    file_path = config.AMBIENT_DIR / f"{meta_obj.id}{file_extension}"

    try:
        with open(file_path, "wb") as buffer:
            shutil.copyfileobj(file.file, buffer)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Не удалось сохранить аудиофайл: {e}")

    with get_system_session() as session:
        new_track = AmbientTrack(
            id=meta_obj.id,
            description=meta_obj.description,
            tags=meta_obj.tags
        )
        session.merge(new_track)
        session.commit()

    return JSONResponse(status_code=201, content={
        "message": "Эмбиент успешно добавлен.",
        "metadata": meta_obj.model_dump()
    })


@router.delete("/ambient/{ambient_id}")
async def delete_ambient(ambient_id: str):
    """
    Удаляет эмбиент из библиотеки (запись в БД и соответствующий аудиофайл).
    """
    from core.database import get_system_session
    from core.system_models import AmbientTrack
    
    if ambient_id == "none":
        raise HTTPException(status_code=400, detail="Нельзя удалить базовый эмбиент 'none'.")

    with get_system_session() as session:
        track = session.get(AmbientTrack, ambient_id)
        if not track:
             raise HTTPException(status_code=404, detail=f"Эмбиент с ID '{ambient_id}' не найден.")
        session.delete(track)
        session.commit()

    deleted_files = []
    for f in config.AMBIENT_DIR.glob(f"{ambient_id}.*"):
        if f.is_file():
            try:
                f.unlink()
                deleted_files.append(f.name)
            except Exception as e:
                logger.warning(f"Could not delete audio file {f.name}: {e}")

    return JSONResponse(status_code=200, content={
        "message": f"Эмбиент '{ambient_id}' успешно удален.",
        "deleted_audio_files": deleted_files
    })
