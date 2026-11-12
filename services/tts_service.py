import logging
from pathlib import Path
from threading import Lock
import config
from services.qwen_tts_client import QwenTTSClient

logger = logging.getLogger(__name__)


class TTSService:
    """
    Сервис для TTS (Qwen3-TTS API) и C++ Whisper (pywhispercpp Aligner & Transcriber).
    Не требует PyTorch в основном бэкенд-процессе.
    """

    def __init__(self):
        tts_url = getattr(config, "TTS_API_URL", getattr(config, "COSYVOICE_API_URL", "http://localhost:9233"))
        self.tts_client = QwenTTSClient(base_url=tts_url)
        # Обратная совместимость
        self.cosy_client = self.tts_client

        self._whisper_model = None
        self._whisper_load_lock = Lock()
        self._reference_transcription_cache = {}

        logger.info(f"Сервис TTSService инициализирован. Qwen3-TTS URL: {tts_url}")

    @property
    def whisper_model(self):
        """Ленивая загрузка C++ Whisper (pywhispercpp)."""
        if self._whisper_model is None:
            with self._whisper_load_lock:
                if self._whisper_model is None:
                    try:
                        from pywhispercpp.model import Model
                        models_dir = config.OUTPUT_DIR / "whisper_models"
                        models_dir.mkdir(parents=True, exist_ok=True)
                        logger.info(f"⏳ C++ Whisper: Загрузка модели pywhispercpp (base) из {models_dir}...")
                        self._whisper_model = Model(
                            "base",
                            models_dir=str(models_dir),
                            print_realtime=False,
                            print_progress=False
                        )
                        logger.info("✅ C++ Модель pywhispercpp (base) успешно загружена.")
                    except Exception as e:
                        logger.error(f"❌ Ошибка загрузки C++ Whisper (pywhispercpp): {e}", exc_info=True)
                        return None

        return self._whisper_model

    def unload(self):
        """Выгружает C++ модель Whisper из памяти."""
        if self._whisper_model is not None:
            logger.info("Выгрузка C++ модели Whisper...")
            del self._whisper_model
            self._whisper_model = None

    def _get_prompt_text(self, speaker_wav_path: Path) -> str:
        """Получает текст из референсного аудио (кэширует результат на диск)."""
        path_str = str(speaker_wav_path)

        if path_str in self._reference_transcription_cache:
            return self._reference_transcription_cache[path_str]

        # Сначала проверяем reference.txt, затем reference.wav.txt
        txt_path = speaker_wav_path.with_suffix(".txt")
        if not txt_path.exists():
            txt_path = speaker_wav_path.with_suffix(speaker_wav_path.suffix + ".txt")

        if txt_path.exists() and txt_path.is_file():
            try:
                text = txt_path.read_text(encoding="utf-8").strip()
                if text:
                    self._reference_transcription_cache[path_str] = text
                    return text
            except Exception as e:
                logger.error(f"Ошибка чтения файла транскрипции {txt_path}: {e}")

        if not self.whisper_model:
            logger.error(f"Whisper недоступен, текст референса {speaker_wav_path.name} не получен")
            return ""

        logger.info(f"Транскрипция референса через C++ Whisper (pywhispercpp): {speaker_wav_path.name}")
        text = self.transcribe(speaker_wav_path)
        if text:
            try:
                txt_path.write_text(text, encoding="utf-8")
            except Exception as e:
                logger.error(f"Не удалось записать файл транскрипции {txt_path}: {e}")
            self._reference_transcription_cache[path_str] = text
        return text

    def transcribe(self, wav_path: Path) -> str:
        """
        Распознает аудио без кэша и без записи файлов. Для проверки озвучки: повторная
        попытка пишет в тот же файл, и кэш по пути вернул бы текст прошлой попытки.
        """
        model = self.whisper_model
        if not model:
            return ""
        import subprocess
        import tempfile
        tmp_wav_path = None
        try:
            # Whisper требует 16kHz mono WAV
            with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp_wav:
                tmp_wav_path = Path(tmp_wav.name)
            subprocess.run(
                ["ffmpeg", "-y", "-i", str(wav_path), "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", str(tmp_wav_path)],
                check=True,
                capture_output=True
            )
            segments = model.transcribe(str(tmp_wav_path), language="ru")
            return " ".join([s.text for s in segments]).strip()
        except Exception as e:
            logger.error(f"Ошибка транскрипции pywhispercpp ({wav_path.name}): {e}")
            return ""
        finally:
            if tmp_wav_path and tmp_wav_path.exists():
                tmp_wav_path.unlink()

    def synthesize(self, text: str, speaker_wav_path: Path, emotion: str = None) -> bytes | None:
        """Синтез речи через API Qwen3-TTS."""
        if not speaker_wav_path.exists():
            logger.error(f"Файл-образец голоса не найден: {speaker_wav_path}")
            return None

        prompt_text = self._get_prompt_text(speaker_wav_path).strip()
        if not prompt_text:
            # Без текста сервер клонирует только по вектору диктора: манера образца (хрип, шепот) теряется.
            logger.warning(f"Нет текста референса для {speaker_wav_path}: клон только по вектору диктора. "
                           f"Положите рядом {speaker_wav_path.with_suffix('.txt').name} с точным текстом образца.")
        instruct_text = emotion if emotion and emotion.lower() != "neutral" else ""
        voice_id = speaker_wav_path.parent.name if speaker_wav_path.parent.name != "voices" else speaker_wav_path.stem

        return self.tts_client.synthesize(
            text=text,
            prompt_wav_path=speaker_wav_path,
            prompt_text=prompt_text,
            instruct_text=instruct_text,
            voice_id=voice_id,
            language="Russian",
            mode="zero_shot"
        )

    def generate_word_timings(self, text: str, audio_path: Path, language: str = "ru") -> list | None:
        """Генерирует таймкоды (субтитры) через C++ Whisper."""
        model = self.whisper_model
        if not model or not audio_path.exists():
            return None

        try:
            segments = model.transcribe(
                str(audio_path),
                language=language,
                token_timestamps=True,
                max_len=1
            )
            timings = []
            for s in segments:
                word_text = s.text.strip()
                if word_text:
                    timings.append({
                        'word': word_text,
                        'start': round(s.t0 / 100.0, 2),
                        'end': round(s.t1 / 100.0, 2)
                    })
            return timings
        except Exception as e:
            logger.error(f"Ошибка генерирования таймкодов pywhispercpp: {e}", exc_info=True)
            return None