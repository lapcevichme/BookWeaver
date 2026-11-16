import logging
import subprocess
import json
import os
import shutil
import tempfile
from pathlib import Path
from typing import List, Dict, Tuple, Optional, Any
from core.data_models import Scenario
import config

from fastapi import HTTPException, Request
from fastapi.responses import FileResponse, StreamingResponse

logger = logging.getLogger(__name__)


def normalize_lines(src_dir: Path, dst_dir: Path, lufs: float = -20.0, sample_rate: Optional[int] = None) -> int:
    """Копии реплик с одинаковой громкостью (loudnorm)."""
    from core.audio_profiles import get_tts_profile
    rate = sample_rate or get_tts_profile().native_sample_rate
    count = 0
    dst_dir.mkdir(parents=True, exist_ok=True)
    for wav in sorted(src_dir.glob("*.wav")):
        subprocess.run([
            "ffmpeg", "-y", "-loglevel", "error",
            "-i", str(wav),
            "-af", f"loudnorm=I={lufs}:TP=-2:LRA=11",
            "-ar", str(rate),
            "-ac", "1",
            "-c:a", "pcm_s16le",
            str(dst_dir / wav.name)
        ], check=True)
        count += 1
    return count


def ranged_file_response(request: Request, file_path: Path, content_type: Optional[str] = None):
    """
    Отдает файл с поддержкой HTTP Range Header (206 Partial Content).
    Необходимо для мгновенной перемотки аудиофайлов в мобильном плеере (iOS AVPlayer, Android ExoPlayer).
    """
    if not file_path.exists():
        raise HTTPException(status_code=404, detail="Файл не найден.")

    if not content_type:
        ext = file_path.suffix.lower()
        mime_map = {
            ".mp3": "audio/mpeg",
            ".wav": "audio/wav",
            ".ogg": "audio/ogg",
            ".flac": "audio/flac",
            ".m4a": "audio/mp4",
            ".aac": "audio/aac",
            ".jpg": "image/jpeg",
            ".jpeg": "image/jpeg",
            ".png": "image/png"
        }
        content_type = mime_map.get(ext, "audio/mpeg")

    file_size = file_path.stat().st_size
    range_header = request.headers.get("range")

    if not range_header:
        return FileResponse(
            file_path,
            media_type=content_type,
            headers={"Accept-Ranges": "bytes"}
        )

    try:
        unit, ranges = range_header.strip().split("=")
        if unit != "bytes":
            raise ValueError

        start_str, end_str = ranges.split("-")
        if not start_str and not end_str:
            raise ValueError

        if not start_str:
            # Suffix byte range: e.g. bytes=-500 (last 500 bytes)
            length = int(end_str)
            start = max(0, file_size - length)
            end = file_size - 1
        elif not end_str:
            # Open-ended range: e.g. bytes=500- (from 500 to end)
            start = int(start_str)
            end = file_size - 1
        else:
            start = int(start_str)
            end = int(end_str)

        if start >= file_size or end >= file_size or start > end:
            raise HTTPException(
                status_code=416,
                detail="Requested Range Not Satisfiable",
                headers={"Content-Range": f"bytes */{file_size}"}
            )

        chunk_size = end - start + 1

        def stream_file():
            with open(file_path, "rb") as f:
                f.seek(start)
                remaining = chunk_size
                buffer_size = 64 * 1024
                while remaining > 0:
                    read_size = min(buffer_size, remaining)
                    data = f.read(read_size)
                    if not data:
                        break
                    remaining -= len(data)
                    yield data

        headers = {
            "Content-Range": f"bytes {start}-{end}/{file_size}",
            "Accept-Ranges": "bytes",
            "Content-Length": str(chunk_size),
        }
        return StreamingResponse(
            stream_file(),
            status_code=206,
            headers=headers,
            media_type=content_type
        )
    except HTTPException:
        raise
    except Exception:
        return FileResponse(
            file_path,
            media_type=content_type,
            headers={"Accept-Ranges": "bytes"}
        )

def get_audio_duration_ms(file_path: Path) -> int:
    """Получает длительность аудиофайла в миллисекундах через ffprobe."""
    try:
        cmd = [
            'ffprobe', '-v', 'error', '-show_entries', 'format=duration',
            '-of', 'default=noprint_wrappers=1:nokey=1', str(file_path)
        ]
        result = subprocess.run(cmd, capture_output=True, text=True, check=True)
        duration_sec = float(result.stdout.strip())
        return int(duration_sec * 1000)
    except Exception as e:
        logger.error(f"Ошибка при получении длительности {file_path.name}: {e}")
        return 0


def normalize_audio_file(
    src_path: Path,
    dst_path: Path,
    target_db: float = -25.0,
    trim_silence: bool = True,
    fade_ms: int = 50
) -> bool:
    """
    Обрабатывает и нормализует аудиофайл (громкость, обрезка тишины, fade in/out).
    """
    try:
        from pydub import AudioSegment
        sound = AudioSegment.from_file(src_path)

        if trim_silence:
            def detect_leading_silence(snd, silence_threshold=-50.0, chunk_size=10):
                trim_ms = 0
                while snd[trim_ms:trim_ms + chunk_size].dBFS < silence_threshold and trim_ms < len(snd):
                    trim_ms += chunk_size
                return trim_ms

            start_trim = detect_leading_silence(sound)
            end_trim = detect_leading_silence(sound.reverse())
            if start_trim + end_trim < len(sound):
                sound = sound[start_trim: len(sound) - end_trim]

        change_in_dBFS = target_db - sound.dBFS
        sound = sound.apply_gain(change_in_dBFS)

        if fade_ms > 0 and len(sound) > fade_ms * 2:
            sound = sound.fade_in(fade_ms).fade_out(fade_ms)

        dst_path.parent.mkdir(parents=True, exist_ok=True)
        fmt = dst_path.suffix.lstrip('.') or "mp3"
        sound.export(dst_path, format=fmt, bitrate="192k")
        return True
    except Exception as e:
        logger.error(f"Ошибка нормализации аудио {src_path.name}: {e}")
        return False

def merge_chapter_audio(
        scenario: Any,
        audio_dir: Path,
        output_file_path: Path,
        gap_ms: Optional[int] = None,
        sfx_gain_db: Optional[float] = None,
        normalize: bool = False,
        lufs: float = -20.0,
        subtitles_map: Optional[dict] = None,
        **kwargs
) -> Tuple[int, List[dict]]:
    """
    Склеивает аудиофайлы главы через ffmpeg (стриминг, экономно по памяти).
    Создает карту синхронизации на основе метаданных файлов и данных из БД.
    gap_ms — тишина между репликами (по умолчанию из config.TTS_AUDIO_GAP_MS: 450 мс).
    sfx_gain_db — регулировка громкости SFX (по умолчанию из config.SFX_DEFAULT_GAIN_DB: -6.0 дБ).
    normalize — выравнивание громкости реплик (loudnorm) перед склейкой.
    """
    if gap_ms is None:
        gap_ms = int(getattr(config, "TTS_AUDIO_GAP_MS", 450))
    if sfx_gain_db is None:
        sfx_gain_db = float(getattr(config, "SFX_DEFAULT_GAIN_DB", -6.0))

    temp_norm_dir = None
    working_audio_dir = audio_dir
    if normalize:
        temp_norm_dir = Path(tempfile.mkdtemp(prefix="bw_norm_"))
        normalize_lines(audio_dir, temp_norm_dir, lufs=lufs)
        working_audio_dir = temp_norm_dir

    sync_map = []
    current_offset_ms = 0
    audio_files_to_concat = []
    sfx_events = []
    
    missing_files_count = 0
    logger.info(f"Начинаем оптимизированную склейку аудио для {output_file_path.name}")

    entries = scenario.entries if hasattr(scenario, "entries") else (scenario or [])

    for entry in entries:
        eid = str(entry.id)
        instruct = getattr(entry, 'instruct_prompt', 'neutral')

        sync_item = {
            "id": eid,
            "text": entry.text,
            "speaker": entry.speaker_name if hasattr(entry, 'speaker_name') else getattr(entry, 'speaker', 'Unknown'),
            "type": entry.type,
            "emotion": instruct,
            "ambient": entry.ambient if entry.ambient else "none",
        }

        if entry.type == "image":
            sync_item["src"] = entry.src
            sync_item["start_ms"] = current_offset_ms
            sync_item["end_ms"] = current_offset_ms
            sync_map.append(sync_item)
            continue

        audio_filename = f"{eid}.wav"
        file_path = working_audio_dir / audio_filename

        segment_duration = 0
        if file_path.exists():
            segment_duration = get_audio_duration_ms(file_path)
            if segment_duration > 0:
                if gap_ms > 0 and audio_files_to_concat:
                    audio_files_to_concat.append(("gap", file_path))
                    current_offset_ms += gap_ms
                audio_files_to_concat.append(file_path)
        else:
            missing_files_count += 1
            if missing_files_count <= 5:
                logger.warning(f"Аудиофайл не найден: {audio_filename}")

        entry_start = current_offset_ms
        entry_end = current_offset_ms + segment_duration

        sync_item["start_ms"] = entry_start
        sync_item["end_ms"] = entry_end

        sfx_name = getattr(entry, 'sfx', None)
        if sfx_name and str(sfx_name).lower() != 'none':
            sync_item["sfx"] = sfx_name
            for ext in ['.wav', '.mp3', '.ogg', '.flac']:
                sfx_path = config.SFX_DIR / f"{sfx_name}{ext}"
                if sfx_path.exists():
                    sfx_events.append((entry_start, sfx_path))
                    break

        # Alignment (субтитры по словам) - читаем из БД или subtitles_map
        subs = getattr(entry, 'audio_subtitles', None)
        if not subs and subtitles_map:
            subs = subtitles_map.get(eid)
        if subs and 'words' in subs:
            relative_words = subs['words']
            # Конвертируем относительные тайминги в глобальные
            global_words = []
            for w in relative_words:
                global_words.append({
                    "word": w["word"],
                    "start": w["start"] + entry_start,
                    "end": w["end"] + entry_start
                })
            sync_item["words"] = global_words

        sync_map.append(sync_item)
        current_offset_ms = entry_end

    if not audio_files_to_concat:
        logger.warning("Нет аудиофайлов для склейки.")
        return 0, sync_map

    # Тишина для пауз — в формате первой реплики (concat требует одинаковых параметров)
    gap_path = None
    if any(isinstance(p, tuple) for p in audio_files_to_concat):
        import wave
        with wave.open(str(audio_files_to_concat[0]), "rb") as w:
            rate, channels, width = w.getframerate(), w.getnchannels(), w.getsampwidth()
        gap_path = Path(tempfile.mkstemp(suffix=".wav")[1])
        with wave.open(str(gap_path), "wb") as w:
            w.setnchannels(channels)
            w.setsampwidth(width)
            w.setframerate(rate)
            w.writeframes(b"\x00" * (rate * gap_ms // 1000) * channels * width)
    audio_files_to_concat = [gap_path if isinstance(p, tuple) else p for p in audio_files_to_concat]

    # Создаем временный файл для ffmpeg concat
    with tempfile.NamedTemporaryFile(mode='w', suffix='.txt', delete=False, encoding='utf-8') as f:
        for p in audio_files_to_concat:
            # Пути в concat файле должны быть экранированы
            safe_path = str(p.absolute()).replace("'", "'\\''")
            f.write(f"file '{safe_path}'\n")
        concat_list_path = f.name

    try:
        output_file_path.parent.mkdir(parents=True, exist_ok=True)
        
        # Запускаем ffmpeg
        # -f concat: используем демуксер конкатенации
        # -safe 0: разрешаем любые пути
        # -y: перезаписывать выходной файл
        cmd = [
            'ffmpeg', '-y', '-f', 'concat', '-safe', '0', 
            '-i', concat_list_path, 
            '-acodec', 'libmp3lame', '-b:a', '192k', 
            str(output_file_path)
        ]
        
        subprocess.run(cmd, check=True, capture_output=True)

        # Подмешивание SFX поверх трека
        if sfx_events:
            try:
                from pydub import AudioSegment
                main_track = AudioSegment.from_file(output_file_path)
                for start_ms, sfx_path in sfx_events:
                    try:
                        sfx_track = AudioSegment.from_file(sfx_path)
                        if sfx_gain_db != 0.0:
                            sfx_track = sfx_track.apply_gain(sfx_gain_db)
                        main_track = main_track.overlay(sfx_track, position=start_ms)
                    except Exception as se:
                        logger.error(f"Ошибка подмешивания SFX {sfx_path.name}: {se}")
                main_track.export(output_file_path, format="mp3", bitrate="192k")
                logger.info(f"✅ Успешно подмешано {len(sfx_events)} SFX эффектов в {output_file_path.name} (gain {sfx_gain_db} dB)")
            except Exception as e:
                logger.error(f"Ошибка обработки SFX: {e}")

        logger.info(f"✅ Успешно склеено в {output_file_path.name}. Итого: {current_offset_ms} мс")
        
    except subprocess.CalledProcessError as e:
        logger.error(f"❌ Ошибка ffmpeg: {e.stderr.decode()}")
        return 0, sync_map
    finally:
        if os.path.exists(concat_list_path):
            os.remove(concat_list_path)
        if gap_path and gap_path.exists():
            gap_path.unlink()
        if temp_norm_dir and temp_norm_dir.exists():
            shutil.rmtree(temp_norm_dir, ignore_errors=True)

    return current_offset_ms, sync_map
