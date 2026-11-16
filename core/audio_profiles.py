"""
Профили TTS-движков и утилиты работы с аудио (герцовка, адаптер для Whisper).
"""
import logging
import os
import re
import subprocess
import tempfile
import wave
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Generator, List, Optional, Tuple

logger = logging.getLogger(__name__)

WHISPER_SAMPLE_RATE = 16000
WHISPER_CHANNELS = 1


@dataclass
class TTSEngineProfile:
    name: str                  # "qwen", "fish", "cosyvoice"
    native_sample_rate: int    # 24000 (Qwen), 44100 (Fish), 22050 (CosyVoice)
    native_channels: int = 1   # 1 (mono), 2 (stereo)
    format: str = "wav"
    whisper_sample_rate: int = WHISPER_SAMPLE_RATE
    whisper_channels: int = WHISPER_CHANNELS
    loudnorm_lufs: float = -16.0
    narrator_gain_db: float = 0.0  # e.g. +6.0 dB for quiet models
    max_chunk_chars: int = 0       # 0 = do not chunk; > 0 = chunk long lines to prevent pitch drift


# Пресеты для известных движков
QWEN_PROFILE = TTSEngineProfile(
    name="qwen",
    native_sample_rate=24000,
    native_channels=1,
    format="wav",
    loudnorm_lufs=-16.0,
    narrator_gain_db=6.0,
    max_chunk_chars=250,  # Защита от уползания тона на репликах >15с
)

FISH_AUDIO_PROFILE = TTSEngineProfile(
    name="fish",
    native_sample_rate=44100,
    native_channels=1,
    format="wav",
    loudnorm_lufs=-16.0,
    narrator_gain_db=0.0,
    max_chunk_chars=0,    # Fish Audio не страдает уползанием тона
)

COSYVOICE_PROFILE = TTSEngineProfile(
    name="cosyvoice",
    native_sample_rate=24000,
    native_channels=1,
    format="wav",
    loudnorm_lufs=-16.0,
    narrator_gain_db=0.0,
    max_chunk_chars=0,
)

TTS_PROFILES: Dict[str, TTSEngineProfile] = {
    "qwen": QWEN_PROFILE,
    "fish": FISH_AUDIO_PROFILE,
    "cosyvoice": COSYVOICE_PROFILE,
}


def get_tts_profile(engine_name: Optional[str] = None) -> TTSEngineProfile:
    """Возвращает профиль TTS по названию движка или из config.TTS_ENGINE."""
    import config
    name = (engine_name or getattr(config, "TTS_ENGINE", "qwen")).lower().strip()
    return TTS_PROFILES.get(name, QWEN_PROFILE)


def get_audio_info(audio_path: Path) -> Tuple[int, int, float]:
    """
    Быстро возвращает (sample_rate, channels, duration_sec) через wave или ffprobe.
    """
    if not audio_path.exists():
        return 0, 0, 0.0

    # Быстрый путь для WAV через стандартный wave
    if audio_path.suffix.lower() == ".wav":
        try:
            with wave.open(str(audio_path), "rb") as w:
                rate = w.getframerate()
                channels = w.getnchannels()
                frames = w.getnframes()
                duration = frames / float(rate) if rate > 0 else 0.0
                return rate, channels, duration
        except Exception:
            pass

    # Универсальный путь через ffprobe для любых форматов (mp3, ogg, flac)
    try:
        cmd = [
            "ffprobe", "-v", "error", "-select_streams", "a:0",
            "-show_entries", "stream=sample_rate,channels,duration",
            "-of", "csv=p=0:s=,", str(audio_path)
        ]
        res = subprocess.run(cmd, capture_output=True, text=True, check=True)
        parts = res.stdout.strip().split(",")
        rate = int(parts[0]) if len(parts) > 0 and parts[0] else 0
        channels = int(parts[1]) if len(parts) > 1 and parts[1] else 0
        duration = float(parts[2]) if len(parts) > 2 and parts[2] else 0.0
        return rate, channels, duration
    except Exception as e:
        logger.debug(f"ffprobe не смог прочитать {audio_path}: {e}")
        return 0, 0, 0.0


@contextmanager
def as_whisper_wav(audio_path: Path) -> Generator[Path, None, None]:
    """
    Контекст-менеджер: гарантирует аудиофайл формата 16kHz mono 16-bit PCM WAV для Whisper.
    Если входящий файл уже 16kHz mono WAV — отдает его напрямую без конвертации и временных файлов.
    Если входящий файл 24kHz (Qwen), 44.1kHz (Fish), MP3 или стерео — создает временный 16kHz mono WAV,
    отдает его и автоматически удаляет при выходе из блока `with`.
    """
    if not audio_path.exists():
        raise FileNotFoundError(f"Аудиофайл не найден: {audio_path}")

    rate, channels, _ = get_audio_info(audio_path)
    # Если уже 16kHz mono WAV — отдаем напрямую
    if rate == WHISPER_SAMPLE_RATE and channels == WHISPER_CHANNELS and audio_path.suffix.lower() == ".wav":
        yield audio_path
        return

    # Иначе конвертируем во временный файл
    tmp_wav_path = None
    try:
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
            tmp_wav_path = Path(tmp.name)

        cmd = [
            "ffmpeg", "-y", "-loglevel", "error",
            "-i", str(audio_path),
            "-ar", str(WHISPER_SAMPLE_RATE),
            "-ac", str(WHISPER_CHANNELS),
            "-c:a", "pcm_s16le",
            str(tmp_wav_path)
        ]
        subprocess.run(cmd, check=True, capture_output=True)
        yield tmp_wav_path
    finally:
        if tmp_wav_path and tmp_wav_path.exists():
            try:
                tmp_wav_path.unlink()
            except Exception:
                pass


def split_text_into_chunks(text: str, max_chars: int) -> List[str]:
    """
    Разбивает текст на смысловые фрагменты длиной не более max_chars по границам предложений
    (или пунктуации), чтобы избежать уползания тона (pitch drift) в авторегрессионных TTS-моделях.
    """
    text = text.strip()
    if not text or max_chars <= 0 or len(text) <= max_chars:
        return [text] if text else []

    raw_sentences = [s.strip() for s in re.split(r'(?<=[.!?…\n])\s+', text) if s.strip()]

    chunks: List[str] = []
    current_parts: List[str] = []
    current_len = 0

    def flush_current():
        nonlocal current_parts, current_len
        if current_parts:
            chunks.append(" ".join(current_parts))
            current_parts = []
            current_len = 0

    for sentence in raw_sentences:
        if len(sentence) <= max_chars:
            added_len = len(sentence) + (1 if current_parts else 0)
            if current_len + added_len <= max_chars:
                current_parts.append(sentence)
                current_len += added_len
            else:
                flush_current()
                current_parts.append(sentence)
                current_len = len(sentence)
        else:
            flush_current()
            sub_clauses = [c.strip() for c in re.split(r'(?<=[,;:—\-])\s+', sentence) if c.strip()]
            for clause in sub_clauses:
                if len(clause) <= max_chars:
                    added_len = len(clause) + (1 if current_parts else 0)
                    if current_len + added_len <= max_chars:
                        current_parts.append(clause)
                        current_len += added_len
                    else:
                        flush_current()
                        current_parts.append(clause)
                        current_len = len(clause)
                else:
                    flush_current()
                    words = clause.split()
                    for word in words:
                        added_len = len(word) + (1 if current_parts else 0)
                        if current_len + added_len <= max_chars:
                            current_parts.append(word)
                            current_len += added_len
                        else:
                            flush_current()
                            current_parts.append(word)
                            current_len = len(word)
            flush_current()

    flush_current()
    return chunks


def concat_wav_bytes(wav_chunks: List[bytes], pause_ms: int = 150) -> bytes:
    """
    Склеивает несколько WAV файлов (байты) в один поток с добавлением тишины между ними.
    Параметры аудио (частота, каналы, битность) берутся из первого чанка.
    """
    if not wav_chunks:
        return b""
    if len(wav_chunks) == 1:
        return wav_chunks[0]

    import io
    import wave

    first_io = io.BytesIO(wav_chunks[0])
    with wave.open(first_io, "rb") as w:
        channels = w.getnchannels()
        sampwidth = w.getsampwidth()
        framerate = w.getframerate()

    silence_frames = b"\x00" * int(framerate * (pause_ms / 1000.0) * channels * sampwidth)

    out_io = io.BytesIO()
    with wave.open(out_io, "wb") as out_w:
        out_w.setnchannels(channels)
        out_w.setsampwidth(sampwidth)
        out_w.setframerate(framerate)

        for idx, chunk_bytes in enumerate(wav_chunks):
            if not chunk_bytes:
                continue
            chunk_io = io.BytesIO(chunk_bytes)
            with wave.open(chunk_io, "rb") as chunk_w:
                frames = chunk_w.readframes(chunk_w.getnframes())
                out_w.writeframes(frames)
            if idx < len(wav_chunks) - 1 and silence_frames:
                out_w.writeframes(silence_frames)

    return out_io.getvalue()

