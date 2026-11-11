"""
Выбор голоса для реплики. Одна функция и для озвучки, и для `cli.py inspect voices`,
чтобы проверка показывала ровно то, что сделает TTS.
"""
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Optional

from core import path_manager

NARRATOR = "Рассказчик"

# Откуда взят голос
SOURCE_NARRATOR = "narrator"      # реплика рассказчика
SOURCE_VOICE_BASE = "voice_base"  # назначен персонажу в БД (папка голоса существует)
SOURCE_CONFIG = "config"          # назначен в config.character_voices книги
SOURCE_FALLBACK = "fallback"      # не назначен — персонаж звучит голосом рассказчика


@dataclass(frozen=True)
class VoiceChoice:
    voice_id: str
    source: str
    path: Path

    @property
    def exists(self) -> bool:
        return self.path.exists()


def resolve_voice(speaker_name: Optional[str], characters: Iterable, book_config) -> VoiceChoice:
    """
    Порядок: рассказчик → voice_base персонажа (если папка голоса есть) →
    config.character_voices[id персонажа] → голос рассказчика (откат).
    """
    narrator = book_config.default_narrator_voice

    def choice(voice_id: str, source: str) -> VoiceChoice:
        return VoiceChoice(voice_id, source, path_manager.get_voice_path(voice_id))

    if not speaker_name or speaker_name == NARRATOR:
        return choice(narrator, SOURCE_NARRATOR)

    char = next((c for c in characters if c.name == speaker_name), None)
    if char and char.voice_base and path_manager.get_voice_path(char.voice_base).exists():
        return choice(char.voice_base, SOURCE_VOICE_BASE)

    if char:
        configured = book_config.character_voices.get(char.id)
        if configured:
            return choice(configured, SOURCE_CONFIG)

    return choice(narrator, SOURCE_FALLBACK)
