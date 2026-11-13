import json
import logging
import re
import socket
from typing import List, Optional
from pathlib import Path

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, PlainTextResponse

import config
from api import state
from api.security import verify_token
from core.book_repository import BookRepository
from core.data_models import Chapter, ChapterSummary
from core import path_manager
from core.character_view import chapter_order, character_as_of
from utils.audio_merger import merge_chapter_audio, ranged_file_response

from api.mobile.mobile_api_models import (
    BookStructureResponseDto,
    BookManifestStructureDto,
    CharacterListEntryDto,
    CharacterDetailsDto,
    ChapterInfoDto,
    ChapterStubDto,
    OnboardingDataDto,
    PingResponseDto,
    PlaybackDataResponseDto,
    SyncMapEntryDto,
    BookManifestDto
)

logger = logging.getLogger(__name__)

# Роутеры
api_router = APIRouter(prefix="/api", tags=["Mobile API (JSON)"])
static_router = APIRouter(prefix="/static", tags=["Mobile API (Static Files)"], dependencies=[Depends(verify_token)])
download_router = APIRouter(prefix="/download", tags=["Mobile API (Downloads)"], dependencies=[Depends(verify_token)])


def get_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def parse_chapter_id(chapter_id: str) -> (int, int):
    match = re.match(r"vol_(\d+)_chap_(\d+)", chapter_id)
    if not match:
        raise HTTPException(status_code=400, detail=f"Invalid chapterId format: {chapter_id}")
    return int(match.group(1)), int(match.group(2))


@api_router.get("/show-qr", response_class=HTMLResponse)
async def show_qr_code_page():
    html_content = """
    <!DOCTYPE html>
    <html lang="ru">
    <head><title>QR Connect</title><script src="https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js"></script></head>
    <body style="display:flex;justify-content:center;align-items:center;height:100vh;font-family:sans-serif;">
        <div style="text-align:center;">
            <h1>Scan to Connect</h1>
            <div id="qrcode"></div>
            <script>
                fetch('/api/onboarding-data').then(r=>r.json()).then(d=>{
                    new QRCode(document.getElementById("qrcode"), JSON.stringify(d));
                });
            </script>
        </div>
    </body>
    </html>
    """
    return HTMLResponse(content=html_content)


@api_router.get("/onboarding-data", response_model=OnboardingDataDto)
async def get_onboarding_data():
    return OnboardingDataDto(
        ip=get_local_ip(),
        port=config.SERVER_PORT,
        token=state.SERVER_TOKEN,
        serverName="BookWeaver Server"
    )


@api_router.get("/ping", response_model=PingResponseDto)
async def ping():
    return PingResponseDto(status="ok", server_name="BookWeaver Server")


# --- Книги и Структура ---

@api_router.get("/books", response_model=List[BookManifestDto], dependencies=[Depends(verify_token)])
async def get_all_books():
    books_list = []
    books_dir = config.OUTPUT_DIR
    if not books_dir.exists():
        return []

    for book_dir in books_dir.iterdir():
        if book_dir.is_dir() and (book_dir / "project.db").exists():
            try:
                repo = BookRepository(book_id=book_dir.name)
                book = repo.get_book()
                if not book:
                    continue

                manifest = repo.load_manifest()
                character_voices_dto = {str(uuid): voice for uuid, voice in manifest.config.character_voices.items()}

                books_list.append(BookManifestDto(
                    book_name=book.id,
                    author=book.author or "Неизвестный автор",
                    character_voices=character_voices_dto,
                    default_narrator_voice=manifest.config.default_narrator_voice
                ))
            except Exception as e:
                logger.warning(f"Не удалось загрузить данные книги '{book_dir.name}': {e}")
                continue

    return books_list


@api_router.get("/books/{bookId}/structure", response_model=BookStructureResponseDto,
                dependencies=[Depends(verify_token)])
async def get_book_structure(bookId: str):
    try:
        repo = BookRepository(book_id=bookId)
        book = repo.get_book()
        if not book:
            raise HTTPException(status_code=404, detail="Книга не найдена в БД.")

        manifest_structure = BookManifestStructureDto(
            book_name=book.id,
            title=book.title or book.id.replace("_", " ").title(),
            author=book.author or "Неизвестный автор",
            version=1,
            poster_url=f"/static/books/{bookId}/cover.jpg"
        )

        chapters_dto = []
        db_chapters = repo.get_all_chapters()
        for chap in db_chapters:
            audio_dir = path_manager.get_chapter_audio_dir(bookId, chap.id)
            has_audio = audio_dir.exists() and any(audio_dir.iterdir())

            chapters_dto.append(ChapterStubDto(
                id=chap.id,
                title=chap.title or f"Том {chap.volume_num}, Глава {chap.chapter_num}",
                version=1,
                volume_number=chap.volume_num,
                has_audio=has_audio
            ))

        return BookStructureResponseDto(
            manifest=manifest_structure,
            chapters=chapters_dto
        )

    except HTTPException as e:
        raise e
    except Exception as e:
        logger.error(f"Ошибка /structure для {bookId}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@api_router.get("/books/{bookId}/{chapterId}/originalText", response_class=PlainTextResponse,
                dependencies=[Depends(verify_token)])
async def get_original_chapter_text(bookId: str, chapterId: str):
    """
    Возвращает оригинальный текст главы (Raw Text) из БД или файла.
    """
    try:
        repo = BookRepository(book_id=bookId)
        content = repo.get_chapter_text(chapterId)
        return PlainTextResponse(content=content, media_type="text/plain")
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except FileNotFoundError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except Exception as e:
        logger.error(f"Error serving original text for {bookId}/{chapterId}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


# --- Персонажи ---

def _chapter_order_or_404(repo: BookRepository, chapter_id: str) -> dict:
    order = chapter_order(repo.get_all_chapters())
    if chapter_id not in order:
        raise HTTPException(status_code=404, detail=f"Глава '{chapter_id}' не найдена.")
    return order


@api_router.get("/books/{bookId}/characters", response_model=List[CharacterListEntryDto],
                dependencies=[Depends(verify_token)])
async def get_book_characters(
        bookId: str,
        asOfChapter: Optional[str] = Query(None, description="Только персонажи, уже появившиеся к этой главе")):
    try:
        repo = BookRepository(book_id=bookId)
        db_chars = repo.get_characters()
        if asOfChapter:
            order = _chapter_order_or_404(repo, asOfChapter)
            views = {c.id: character_as_of(c, order, asOfChapter) for c in db_chars}
            db_chars = [c for c in db_chars if views[c.id] is not None]
        result_list = []

        for char in db_chars:
            avatar_url = f"/static/books/{bookId}/chars/{char.id}.jpg"
            result_list.append(CharacterListEntryDto(
                id=str(char.id),
                name=views[char.id].name if asOfChapter else char.name,
                avatar_url=avatar_url,
                short_role=char.role_tier
            ))

        return result_list
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Ошибка /characters для {bookId}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@api_router.get("/books/{bookId}/characters/{characterId}", response_model=CharacterDetailsDto,
                dependencies=[Depends(verify_token)])
async def get_character_details(
        bookId: str, characterId: str,
        asOfChapter: Optional[str] = Query(None, description="Описание и упоминания на момент этой главы")):
    try:
        from uuid import UUID
        from core.data_models import Character
        try:
            char_uuid = UUID(characterId)
        except ValueError:
            raise HTTPException(status_code=404, detail="Некорректный ID персонажа.")

        repo = BookRepository(book_id=bookId)
        with repo.get_session() as session:
            target_char = session.get(Character, char_uuid)

        if not target_char:
            raise HTTPException(status_code=404, detail="Персонаж не найден.")

        if asOfChapter:
            view = character_as_of(target_char, _chapter_order_or_404(repo, asOfChapter), asOfChapter)
            if view is None:
                raise HTTPException(status_code=404, detail="Персонаж к этой главе еще не появлялся.")
            return CharacterDetailsDto(
                id=str(target_char.id),
                name=view.name,
                avatar_url=f"/static/books/{bookId}/chars/{target_char.id}.jpg",
                description=view.description,
                spoiler_free_description=target_char.spoiler_free_description,
                aliases=view.aliases,
                chapter_mentions=view.chapter_mentions,
                as_of_chapter=asOfChapter,
                description_from_chapter=view.description_from,
                first_chapter=view.first_chapter,
            )

        return CharacterDetailsDto(
            id=str(target_char.id),
            name=target_char.name,
            avatar_url=f"/static/books/{bookId}/chars/{target_char.id}.jpg",
            description=target_char.description,
            spoiler_free_description=target_char.spoiler_free_description,
            aliases=target_char.aliases,
            chapter_mentions=target_char.chapter_mentions
        )
    except HTTPException as e:
        raise e
    except Exception as e:
        logger.error(f"Ошибка получения персонажа {characterId}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


# --- Детали главы ---

@api_router.get("/books/{bookId}/chapters/{chapterId}/info", response_model=ChapterInfoDto,
                dependencies=[Depends(verify_token)])
async def get_chapter_info(bookId: str, chapterId: str):
    try:
        vol, chap = parse_chapter_id(chapterId)
        repo = BookRepository(book_id=bookId)
        with repo.get_session() as session:
            db_chap = session.get(Chapter, chapterId)
            summary = session.get(ChapterSummary, chapterId)

            if summary:
                return ChapterInfoDto(
                    chapter_id=chapterId,
                    title=(db_chap.title if db_chap and db_chap.title else f"Том {vol}, Глава {chap}"),
                    teaser=summary.teaser,
                    synopsis=summary.synopsis
                )
            else:
                return ChapterInfoDto(
                    chapter_id=chapterId,
                    title=(db_chap.title if db_chap and db_chap.title else f"Том {vol}, Глава {chap}"),
                    teaser="Описание пока не готово.",
                    synopsis=""
                )
    except Exception as e:
        logger.error(f"Ошибка /chapters/.../info: {e}")
        raise HTTPException(status_code=500, detail=str(e))


# --- Playback Data ---

@api_router.get("/books/{bookId}/{chapterId}/playbackData", response_model=PlaybackDataResponseDto,
                dependencies=[Depends(verify_token)])
async def get_chapter_playback_data(bookId: str, chapterId: str, force_rebuild: bool = False):
    """
    Возвращает данные для воспроизведения из БД книги: единый файл + карта синхронизации.
    """
    try:
        repo = BookRepository(book_id=bookId)
        chapter_audio_dir = path_manager.get_chapter_audio_dir(bookId, chapterId)

        full_audio_path = chapter_audio_dir / "full_chapter.mp3"
        sync_map_path = chapter_audio_dir / "full_chapter_map.json"

        if full_audio_path.exists() and sync_map_path.exists() and not force_rebuild:
            logger.info(f"Serving cached playback data for {chapterId}")
            try:
                sync_data = json.loads(sync_map_path.read_text("utf-8"))
                duration_ms = sync_data[-1]["end_ms"] if sync_data else 0

                return PlaybackDataResponseDto(
                    audio_url=f"/static/books/{bookId}/{chapterId}/audio/full_chapter.mp3",
                    duration_ms=duration_ms,
                    sync_map=[SyncMapEntryDto(**item) for item in sync_data]
                )
            except Exception as e:
                logger.warning(f"Cache corrupted for {chapterId}, rebuilding... {e}")

        entries = repo.get_scenario_entries(chapterId)
        if not entries:
            raise HTTPException(status_code=404, detail=f"Сценарий для {chapterId} не найден в БД.")

        has_source_audio = False
        if chapter_audio_dir.exists():
            for f in chapter_audio_dir.iterdir():
                if f.is_file() and f.name != "full_chapter.mp3" and f.suffix.lower() in ['.wav', '.mp3', '.ogg', '.flac']:
                    has_source_audio = True
                    break

        if not has_source_audio:
            logger.info(f"Audio not found for {chapterId}. Returning text-only sync map.")
            text_only_map = [
                SyncMapEntryDto(
                    text=entry.text or "",
                    start_ms=0,
                    end_ms=0,
                    speaker=entry.speaker_name or "Сказитель",
                    ambient=entry.ambient if entry.ambient else "none"
                ) for entry in entries
            ]
            return PlaybackDataResponseDto(
                audio_url=None,
                duration_ms=0,
                sync_map=text_only_map
            )

        # Сбор субтитров из реплик сценария в БД
        subtitles_map = {}
        for entry in entries:
            if entry.audio_subtitles:
                subtitles_map[str(entry.id)] = entry.audio_subtitles

        total_duration, sync_map_raw = merge_chapter_audio(
            scenario=entries,
            audio_dir=chapter_audio_dir,
            output_file_path=full_audio_path,
            subtitles_map=subtitles_map
        )

        with open(sync_map_path, "w", encoding="utf-8") as f:
            json.dump(sync_map_raw, f, ensure_ascii=False, indent=2)

        return PlaybackDataResponseDto(
            audio_url=f"/static/books/{bookId}/{chapterId}/audio/full_chapter.mp3",
            duration_ms=total_duration,
            sync_map=[SyncMapEntryDto(**item) for item in sync_map_raw]
        )

    except HTTPException as e:
        raise e
    except Exception as e:
        logger.error(f"Playback generation error: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


# --- Static Files ---

@static_router.get("/books/{bookId}/chars/{charId}.jpg")
async def get_character_avatar(bookId: str, charId: str):
    char_img_path = path_manager.get_book_output_dir(bookId) / "images" / f"{charId}.jpg"
    if not char_img_path.exists():
        raise HTTPException(status_code=404, detail="Avatar not found")
    return FileResponse(char_img_path)


@static_router.get("/books/{bookId}/cover.jpg")
async def get_book_cover(bookId: str):
    repo = BookRepository(book_id=bookId)
    book = repo.get_book()
    if book and book.cover_image_path:
        cover_path = Path(book.cover_image_path)
        if not cover_path.is_absolute():
            cover_path = path_manager.get_book_output_dir(bookId) / cover_path
        if cover_path.exists():
            return FileResponse(cover_path)
    raise HTTPException(status_code=404, detail="Cover image not found")


@static_router.get("/books/{bookId}/{chapterId}/audio/")
async def get_chapter_audio_empty_check(bookId: str, chapterId: str):
    logger.warning(f"Client requested empty audio file for {bookId}/{chapterId}")
    return JSONResponse(status_code=404, content={"detail": "Missing filename"})


@static_router.get("/books/{bookId}/{chapterId}/audio/{audioFileName}")
async def get_chapter_audio(request: Request, bookId: str, chapterId: str, audioFileName: str):
    try:
        audio_dir = path_manager.get_chapter_audio_dir(bookId, chapterId)
        audio_path = audio_dir / audioFileName

        if audio_path.exists():
            return ranged_file_response(request, audio_path)

        stem = audio_path.stem
        for ext in ['.wav', '.mp3', '.ogg', '.flac']:
            alt_path = audio_dir / f"{stem}{ext}"
            if alt_path.exists():
                return ranged_file_response(request, alt_path)

        raise HTTPException(status_code=404, detail="Audio file not found")

    except HTTPException as e:
        raise e
    except Exception as e:
        logger.error(f"Error serving audio {audioFileName}: {e}")
        raise HTTPException(status_code=404)


@static_router.get("/ambient/{ambientName}")
async def get_global_ambient_file(request: Request, ambientName: str):
    p = config.AMBIENT_DIR / ambientName
    if not p.exists():
        for ext in ['.mp3', '.wav', '.ogg']:
            if (config.AMBIENT_DIR / (ambientName + ext)).exists():
                p = config.AMBIENT_DIR / (ambientName + ext)
                break
    if p.exists():
        return ranged_file_response(request, p)
    logger.warning(f"Эмбиент не найден: {ambientName}")
    raise HTTPException(status_code=404, detail="Ambient file not found")


@static_router.get("/books/{bookId}/ambient/{ambientName}")
async def get_ambient_file_legacy(request: Request, bookId: str, ambientName: str):
    return await get_global_ambient_file(request, ambientName)
