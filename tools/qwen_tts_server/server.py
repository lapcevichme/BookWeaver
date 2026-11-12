import os
import io
import time
import logging
import tempfile
from pathlib import Path
from typing import Optional, Dict

import torch
import soundfile as sf
from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from fastapi.responses import Response

logging.basicConfig(level=logging.INFO, format="%(asctime)s - %(name)s - %(levelname)s - %(message)s")
logger = logging.getLogger("qwen3-tts-server")

DEVICE = os.getenv("DEVICE", "cuda:0" if torch.cuda.is_available() else "cpu")
DEFAULT_MODEL = "Qwen/Qwen3-TTS-12Hz-0.6B-Base" if "cpu" in DEVICE else "Qwen/Qwen3-TTS-12Hz-1.7B-Base"
MODEL_ID = os.getenv("QWEN_TTS_MODEL", DEFAULT_MODEL)
# Модель для создания голосов по описанию (кастинг); грузится при первом запросе /api/voice_design
DESIGN_MODEL_ID = os.getenv("QWEN_TTS_DESIGN_MODEL", "Qwen/Qwen3-TTS-12Hz-1.7B-VoiceDesign")
DEFAULT_LANGUAGE = os.getenv("DEFAULT_LANGUAGE", "Russian")

# Auto-detect precision
if "cuda" in DEVICE:
    DTYPE = torch.bfloat16 if torch.cuda.is_bf16_supported() else torch.float16
else:
    DTYPE = torch.float32

app = FastAPI(
    title="Qwen3-TTS Audio Service",
    description="Dedicated microservice for Qwen3-TTS speech synthesis and zero-shot voice cloning",
    version="1.0.0"
)

# Global model container and prompt cache
model = None
design_model = None
voice_prompt_cache: Dict[str, object] = {}


def load_model():
    global model
    if model is None:
        logger.info(f"⏳ Loading Qwen3-TTS model '{MODEL_ID}' on {DEVICE} ({DTYPE})...")
        from qwen_tts import Qwen3TTSModel
        
        load_kwargs = {
            "device_map": DEVICE,
            "dtype": DTYPE,
        }
        if "cuda" in DEVICE:
            try:
                load_kwargs["attn_implementation"] = "flash_attention_2"
                model = Qwen3TTSModel.from_pretrained(MODEL_ID, **load_kwargs)
            except Exception as e:
                logger.warning(f"FlashAttention 2 not available ({e}), falling back to default attention.")
                load_kwargs.pop("attn_implementation", None)
                model = Qwen3TTSModel.from_pretrained(MODEL_ID, **load_kwargs)
        else:
            model = Qwen3TTSModel.from_pretrained(MODEL_ID, **load_kwargs)
            
        logger.info(f"✅ Qwen3-TTS model successfully loaded on {DEVICE}!")
    return model


def load_design_model():
    global design_model
    if design_model is None:
        logger.info(f"⏳ Loading VoiceDesign model '{DESIGN_MODEL_ID}' on {DEVICE} ({DTYPE})...")
        from qwen_tts import Qwen3TTSModel
        design_model = Qwen3TTSModel.from_pretrained(DESIGN_MODEL_ID, device_map=DEVICE, dtype=DTYPE)
        logger.info("✅ VoiceDesign model loaded")
    return design_model


def _f0_median(audio, sr: int) -> Optional[float]:
    """Медиана F0 образца (pyin) — кастинг проверяет, что голос попал в норму для пола и возраста."""
    try:
        import librosa
        import numpy as np
        y = librosa.resample(np.asarray(audio, dtype="float32"), orig_sr=sr, target_sr=16000)
        f0, voiced, _ = librosa.pyin(y, fmin=60, fmax=600, sr=16000)
        return float(np.nanmedian(f0[voiced])) if voiced.any() else None
    except Exception as e:
        logger.warning(f"F0 не посчитан: {e}")
        return None


@app.post("/api/voice_design")
def voice_design(
    text: str = Form(...),
    instruct: str = Form(...),
    language: Optional[str] = Form(None),
    seed: Optional[int] = Form(None),
):
    """Голос по описанию (VoiceDesign). Ответ — WAV; X-F0-Median и X-Audio-Seconds в заголовках."""
    if not text.strip() or not instruct.strip():
        raise HTTPException(status_code=400, detail="text и instruct обязательны")
    m = load_design_model()
    if seed is not None:
        torch.manual_seed(seed)
    start = time.time()
    wavs, sr = m.generate_voice_design(text=text, language=language or DEFAULT_LANGUAGE, instruct=instruct)
    seconds = len(wavs[0]) / sr
    f0 = _f0_median(wavs[0], sr)
    logger.info(f"🎨 VoiceDesign: {time.time() - start:.0f}s на {seconds:.1f}s аудио, F0 {f0}, instruct='{instruct[:80]}'")
    buf = io.BytesIO()
    sf.write(buf, wavs[0], sr, format="WAV")
    headers = {"X-Audio-Seconds": f"{seconds:.2f}"}
    if f0 is not None:
        headers["X-F0-Median"] = f"{f0:.1f}"
    return Response(content=buf.getvalue(), media_type="audio/wav", headers=headers)


@app.on_event("startup")
async def startup_event():
    # Warm up / lazy load on startup
    try:
        load_model()
    except Exception as e:
        logger.error(f"❌ Failed to load model on startup: {e}. Model will load on first request.")


@app.get("/health")
@app.get("/api/status")
def health_check():
    cuda_mem = None
    if torch.cuda.is_available():
        allocated = torch.cuda.memory_allocated() / (1024 ** 2)
        reserved = torch.cuda.memory_reserved() / (1024 ** 2)
        cuda_mem = {"allocated_mb": round(allocated, 1), "reserved_mb": round(reserved, 1)}

    return {
        "status": "ready" if model is not None else "loading",
        "service": "qwen3-tts",
        "model": MODEL_ID,
        "device": DEVICE,
        "cached_voices": list(voice_prompt_cache.keys()),
        "cuda_memory": cuda_mem
    }


@app.post("/api/cache/clear")
@app.get("/api/cache/clear")
def clear_voice_cache():
    voice_prompt_cache.clear()
    logger.info("🧹 Voice prompt cache cleared.")
    return {"status": "cleared"}


@app.post("/api/tts")
async def synthesize_speech(
    text: str = Form(...),
    prompt_wav: Optional[UploadFile] = File(None),
    prompt_text: Optional[str] = Form(None),
    language: Optional[str] = Form(None),
    instruct_text: Optional[str] = Form(""),
    voice_id: Optional[str] = Form(None),
    speed: float = Form(1.0)
):
    """
    Основной эндпоинт синтеза речи, совместимый с BookWeaver.
    Поддерживает:
    - Zero-Shot Voice Cloning (с референсным аудио и текстом)
    - Кэширование сэмплов голоса (Voice Clone Prompts)
    - Выбор языка (по умолчанию Russian)
    """
    tts_model = load_model()
    target_lang = language or DEFAULT_LANGUAGE

    if not text.strip():
        raise HTTPException(status_code=400, detail="Text cannot be empty")

    logger.info(f"🎙️ Синтез текста ({len(text)} симв) на языке '{target_lang}'...")
    start_time = time.time()

    temp_ref_path = None
    try:
        # Без текста референса клон идет только по вектору диктора (x_vector_only): манера образца
        # (хрип, шепот) теряется — проверено на «больном» Джинши (docs/experiments/2026-11-11_voice-research.md).
        # Поэтому режим входит в ключ кэша и не включается молча.
        ref_transcript = prompt_text.strip() if prompt_text else None
        x_vector_only = not ref_transcript
        cache_key = f"{voice_id}:{'xvec' if x_vector_only else 'icl'}" if voice_id else None
        if x_vector_only and (prompt_wav is not None or voice_prompt_cache.get(cache_key) is not None):
            logger.warning(f"⚠️ Нет текста референса для '{voice_id or prompt_wav.filename}': клон только по вектору "
                           f"диктора (x_vector_only), манера образца не переносится.")

        # 1. Проверяем наличие готового закэшированного эмбеддинга голоса
        cached_prompt = voice_prompt_cache.get(cache_key) if cache_key else None

        if cached_prompt is not None:
            logger.debug(f"Использование закэшированного voice_clone_prompt для '{voice_id}'")
            wavs, sr = tts_model.generate_voice_clone(
                text=text,
                language=target_lang,
                voice_clone_prompt=cached_prompt
            )
        elif prompt_wav is not None:
            # Сохраняем загруженный сэмпл во временный файл
            suffix = Path(prompt_wav.filename or "ref.wav").suffix or ".wav"
            with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
                content = await prompt_wav.read()
                tmp.write(content)
                temp_ref_path = tmp.name

            # Если передан voice_id, кэшируем эмбеддинг для последующих реплик
            if voice_id and hasattr(tts_model, "create_voice_clone_prompt"):
                try:
                    logger.info(f"Создание и кэширование voice_clone_prompt для '{voice_id}'...")
                    created_prompt = tts_model.create_voice_clone_prompt(
                        ref_audio=temp_ref_path,
                        ref_text=ref_transcript,
                        x_vector_only_mode=x_vector_only
                    )
                    voice_prompt_cache[cache_key] = created_prompt
                    cached_prompt = created_prompt
                except Exception as ce:
                    logger.warning(f"Не удалось создать reusable prompt: {ce}")

            if cached_prompt is not None:
                wavs, sr = tts_model.generate_voice_clone(
                    text=text,
                    language=target_lang,
                    voice_clone_prompt=cached_prompt
                )
            else:
                wavs, sr = tts_model.generate_voice_clone(
                    text=text,
                    language=target_lang,
                    ref_audio=temp_ref_path,
                    ref_text=ref_transcript,
                    x_vector_only_mode=x_vector_only
                )
        else:
            # Если нет референса, пробуем VoiceDesign (если модель поддерживает)
            if hasattr(tts_model, "generate_voice_design") and instruct_text:
                logger.info("Генерация через VoiceDesign по инструкции...")
                wavs, sr = tts_model.generate_voice_design(
                    text=text,
                    language=target_lang,
                    instruct=instruct_text
                )
            else:
                raise HTTPException(
                    status_code=400,
                    detail="Either 'prompt_wav' (for voice cloning) or 'instruct_text' (for voice design) must be provided."
                )

        # 2. Экспортируем полученное аудио в WAV байты
        audio_buffer = io.BytesIO()
        audio_data = wavs[0]
        
        # Если задана скорость отличная от 1.0, можно применить интерполяцию/ресэмплинг при необходимости
        sf.write(audio_buffer, audio_data, sr, format="WAV")
        audio_bytes = audio_buffer.getvalue()

        elapsed = time.time() - start_time
        logger.info(f"✅ Синтез завершен за {elapsed:.2f} сек. Размер аудио: {len(audio_bytes)} байт.")

        return Response(content=audio_bytes, media_type="audio/wav")

    except torch.cuda.OutOfMemoryError:
        logger.critical("❌ CUDA Out Of Memory! Очистка кэша VRAM...")
        if torch.cuda.is_available():
            torch.cuda.empty_cache()
        raise HTTPException(status_code=507, detail="GPU Out Of Memory while synthesizing audio")
    except Exception as e:
        logger.error(f"❌ Ошибка синтеза Qwen3-TTS: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"TTS Generation Error: {str(e)}")
    finally:
        if temp_ref_path and os.path.exists(temp_ref_path):
            try:
                os.remove(temp_ref_path)
            except Exception:
                pass


if __name__ == "__main__":
    import uvicorn
    port = int(os.getenv("PORT", 9233))
    host = os.getenv("HOST", "0.0.0.0")
    logger.info(f"Запуск Qwen3-TTS API сервера на http://{host}:{port}...")
    uvicorn.run("server:app", host=host, port=port, reload=False)
