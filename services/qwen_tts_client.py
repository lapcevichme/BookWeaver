import logging
import requests
from pathlib import Path
from typing import Optional

logger = logging.getLogger(__name__)


class QwenTTSClient:
    """
    HTTP-клиент для взаимодействия с микросервисом Qwen3-TTS.
    Поддерживает Zero-shot Voice Cloning, Voice Design и кэширование профилей голоса.
    """

    def __init__(self, base_url: str):
        self.base_url = base_url.rstrip("/")
        self.tts_endpoint = f"{self.base_url}/api/tts"
        self.status_endpoint = f"{self.base_url}/api/status"

    def check_health(self) -> bool:
        """Проверяет доступность сервиса Qwen3-TTS."""
        try:
            resp = requests.get(self.status_endpoint, timeout=3)
            return resp.status_code == 200
        except Exception:
            return False

    def synthesize(
            self,
            text: str,
            prompt_wav_path: Optional[Path] = None,
            prompt_text: Optional[str] = None,
            language: str = "Russian",
            instruct_text: str = "",
            voice_id: Optional[str] = None,
            speed: float = 1.0,
            mode: str = "zero_shot"  # для обратной совместимости
    ) -> Optional[bytes]:
        """
        Отправляет запрос на синтез речи в Qwen3-TTS.

        Args:
            text: Текст для озвучки.
            prompt_wav_path: Путь к референсному аудиофайлу (голос персонажа).
            prompt_text: Текст референсного аудио (Whisper транскрипция).
            language: Целевой язык (по умолчанию Russian).
            instruct_text: Эмоциональная или стилистическая инструкция.
            voice_id: Идентификатор персонажа для серверного кэширования эмбеддинга.
            speed: Скорость воспроизведения.
            mode: Режим клонирования (совместимость).
        """
        if prompt_wav_path:
            prompt_wav_path = Path(prompt_wav_path)
            if not prompt_wav_path.exists():
                logger.error(f"Референс голоса не найден: {prompt_wav_path}")
                return None

        files = {}
        opened_file = None
        if prompt_wav_path:
            opened_file = open(prompt_wav_path, 'rb')
            files['prompt_wav'] = (prompt_wav_path.name, opened_file, 'audio/wav')

        data = {
            'text': text,
            'language': language,
            'speed': speed,
        }
        if prompt_text:
            data['prompt_text'] = prompt_text
        if instruct_text:
            data['instruct_text'] = instruct_text
        if voice_id:
            data['voice_id'] = voice_id

        try:
            logger.debug(
                f"Запрос Qwen3-TTS: {len(text)} симв | Язык: {language} | "
                f"Voice: {voice_id or (prompt_wav_path.name if prompt_wav_path else 'None')} | Instruct: '{instruct_text}'"
            )
            response = requests.post(self.tts_endpoint, data=data, files=files if files else None, timeout=90)

            if response.status_code == 200:
                return response.content
            else:
                logger.error(f"Ошибка API Qwen3-TTS ({response.status_code}): {response.text}")
                return None

        except requests.exceptions.ConnectionError:
            logger.critical(f"Не удалось подключиться к Qwen3-TTS API ({self.base_url}). Проверьте контейнер!")
            return None
        except Exception as e:
            logger.error(f"Исключение при запросе к Qwen3-TTS: {e}", exc_info=True)
            return None
        finally:
            if opened_file:
                opened_file.close()


    def design_voice(self, text: str, instruct: str, language: str = "Russian",
                     seed: Optional[int] = None, timeout: float = 900) -> Optional[dict]:
        """
        Голос по описанию (VoiceDesign) для кастинга. Возвращает {"wav": bytes, "f0": float|None, "seconds": float}.
        На CPU один образец ~2-4 минуты, отсюда длинный таймаут.
        """
        data = {"text": text, "instruct": instruct, "language": language}
        if seed is not None:
            data["seed"] = seed
        try:
            response = requests.post(f"{self.base_url}/api/voice_design", data=data, timeout=timeout)
        except requests.exceptions.ConnectionError:
            logger.critical(f"Не удалось подключиться к Qwen3-TTS API ({self.base_url}).")
            return None
        if response.status_code != 200:
            logger.error(f"Ошибка VoiceDesign ({response.status_code}): {response.text[:300]}")
            return None
        f0 = response.headers.get("X-F0-Median")
        return {"wav": response.content, "f0": float(f0) if f0 else None,
                "seconds": float(response.headers.get("X-Audio-Seconds", 0) or 0)}


# Псевдоним для полной обратной совместимости
CosyVoiceClient = QwenTTSClient
