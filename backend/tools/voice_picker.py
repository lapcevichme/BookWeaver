import os
import shutil
import logging
import requests
from typing import List, Dict, Optional
from pathlib import Path
try:
    os.environ['PYGAME_HIDE_SUPPORT_PROMPT'] = "hide"
    import pygame
except ImportError:
    pygame = None

import config
from utils.setup_logging import setup_logging

logger = logging.getLogger(__name__)


class VoicePicker:
    def __init__(self):
        self.api_key = config.ELEVENLABS_API_KEY
        self.api_url = config.ELEVENLABS_API_URL
        self.temp_dir = config.TEMP_PREVIEWS_DIR
        self.final_dir = config.SELECTED_VOICES_DIR
        self.temp_dir.mkdir(parents=True, exist_ok=True)
        self.final_dir.mkdir(parents=True, exist_ok=True)

        if not self.api_key:
            logger.warning("⚠️ ELEVENLABS_API_KEY не найден в конфигурации!")
        else:
            logger.info("✅ VoicePicker инициализирован.")

    def _build_search_query(self, llm_tags: str) -> str:
        """Добавляет 'russian' к запросу, если его там нет."""
        query = llm_tags.lower()
        if "russian" not in query:
            query = f"russian {query}"
        return query

    def search_voice_library(self, search_query: str, gender: Optional[str] = None, age: Optional[str] = None,
                             limit: int = 3) -> List[Dict]:
        """Ищет голоса в библиотеке ElevenLabs."""
        headers = {
            "xi-api-key": self.api_key,
            "Content-Type": "application/json"
        }
        params = {
            "search": search_query,
            "page_size": limit,
            "sort": "cloned_by_count",
            "language": "russian"
        }
        if gender: params["gender"] = gender.lower()
        if age: params["age"] = age.lower()

        logger.info(f"🔎 Поиск: '{search_query}' (Gender: {gender})")

        try:
            response = requests.get(self.api_url, headers=headers, params=params, timeout=10)

            if response.status_code in [401, 403]:
                logger.warning("Токен не принят, пробую публичный поиск...")
                response = requests.get(self.api_url, params=params, timeout=10)

            if response.status_code != 200:
                logger.error(f"Ошибка API: {response.status_code} - {response.text}")
                return []

            voices = response.json().get("voices", [])
            logger.info(f"Найдено голосов: {len(voices)}")
            return voices

        except Exception as e:
            logger.error(f"Ошибка соединения: {e}")
            return []

    def download_preview(self, preview_url: str, voice_name: str, voice_id: str) -> Optional[Path]:
        """Скачивает превью голоса."""
        if not preview_url:
            return None

        safe_name = "".join([c for c in voice_name if c.isalnum() or c in (' ', '_')]).strip()
        filename = f"temp_{safe_name}_{voice_id}.mp3".replace(" ", "_")
        filepath = self.temp_dir / filename

        # Если уже скачано - не качаем снова
        if filepath.exists():
            return filepath

        try:
            response = requests.get(preview_url, timeout=15)
            if response.status_code == 200:
                filepath.write_bytes(response.content)
                return filepath
        except Exception as e:
            logger.error(f"Ошибка скачивания превью {voice_name}: {e}")
            return None
        return None

    def play_audio(self, filepath: Path):
        """Проигрывает аудио файл в фоне через pygame."""
        try:
            pygame.mixer.init()
            pygame.mixer.music.load(str(filepath))
            pygame.mixer.music.play()
            print(f"🔊 Проигрывание: {filepath.name} (Enter - стоп)", end="\r")
        except Exception as e:
            logger.error(f"Ошибка воспроизведения: {e}")

    def stop_audio(self):
        """Останавливает воспроизведение."""
        if pygame.mixer.get_init():
            pygame.mixer.music.stop()
            pygame.mixer.quit()

    def run_interactive(self, tasks: List[Dict]):
        """
        Запускает интерактивную сессию выбора голосов для списка персонажей.
        tasks: list of dicts {'char_name': str, 'llm_tags': str, 'gender': str}
        """
        if not self.api_key:
            print("❌ ОШИБКА: Не задан ELEVENLABS_API_KEY в .env")
            return

        for task in tasks:
            char_name = task.get('char_name', 'Unknown')
            llm_tags = task.get('llm_tags', '')

            print(f"\n" + "=" * 60)
            print(f"🎭 ПЕРСОНАЖ: {char_name}")
            print(f"📝 ТЕГИ: {llm_tags}")
            print("=" * 60)

            query = self._build_search_query(llm_tags)
            found_voices = self.search_voice_library(query, gender=task.get('gender'))

            if not found_voices:
                print("❌ По вашему запросу ничего не найдено.")
                continue

            # Скачиваем превью
            downloaded_variants = []
            for voice in found_voices:
                path = self.download_preview(
                    voice.get('preview_url'),
                    voice.get('name'),
                    voice.get('public_id')
                )
                if path:
                    downloaded_variants.append({
                        "path": path,
                        "name": voice.get('name'),
                        "id": voice.get('public_id')
                    })

            winner_path = None
            while not winner_path:
                print("\nДоступные варианты:")
                for i, v in enumerate(downloaded_variants, 1):
                    print(f"  [{i}] {v['name']}")

                choice = input(
                    f"\nВведите номер (1-{len(downloaded_variants)}) послушать, 's' пропустить: "
                ).lower().strip()

                if choice == 's':
                    break

                if choice.isdigit() and 1 <= int(choice) <= len(downloaded_variants):
                    idx = int(choice) - 1
                    variant = downloaded_variants[idx]

                    self.play_audio(variant['path'])
                    confirm = input(f"   👉 Выбрать '{variant['name']}'? (y - да / Enter - слушать дальше): ").lower()
                    self.stop_audio()

                    if confirm == 'y':
                        winner_path = variant['path']

                        final_filename = f"{char_name}_ref.mp3"
                        final_path = self.final_dir / final_filename

                        shutil.copy(winner_path, final_path)

                        (self.final_dir / f"{char_name}_info.txt").write_text(
                            f"Name: {variant['name']}\nID: {variant['id']}\nTags: {llm_tags}",
                            encoding='utf-8'
                        )

                        print(f"⭐ УРА! Голос сохранен: {final_path.name}")
                else:
                    print("❌ Неверный ввод.")

        try:
            if self.temp_dir.exists():
                shutil.rmtree(self.temp_dir)
                logger.info("Временные файлы очищены.")
        except Exception as e:
            logger.warning(f"Не удалось удалить временную папку: {e}")

        print(f"\n✅ Сессия завершена. Референсы в: {self.final_dir}")


if __name__ == "__main__":
    setup_logging()

    tasks_example = [
        {
            "char_name": "Grumpy_Grandpa",
            "llm_tags": "Old male, raspy, angry, russian accent",
            "gender": "male"
        },
        {
            "char_name": "Seductress",
            "llm_tags": "Young female, seductive, whispering, russian accent",
            "gender": "female"
        }
    ]

    picker = VoicePicker()
    picker.run_interactive(tasks_example)
