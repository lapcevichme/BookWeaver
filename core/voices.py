"""
Выбор голоса для реплики. Одна функция и для озвучки, и для `cli.py inspect voices`,
чтобы проверка показывала ровно то, что сделает TTS.
"""
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Optional

from core import path_manager

NARRATOR = "Рассказчик"

# Откуда взят голос
SOURCE_NARRATOR = "narrator"      # реплика рассказчика
SOURCE_VOICE_BASE = "voice_base"  # назначен персонажу в БД (папка голоса существует)
SOURCE_CONFIG = "config"          # назначен в config.character_voices книги
SOURCE_GENERIC = "generic"        # общий голос по полу/возрасту (безымянная роль или персонаж без голоса)
SOURCE_FALLBACK = "fallback"      # подходящего голоса нет — звучит голос рассказчика

# Общие голоса: id папки в библиотеке голосов (storage/voices/<id>), создаются кастингом.
GENERIC_VOICES = {
    "female": "generic_female",
    "male": "generic_male",
    "old_female": "generic_old_female",
    "old_male": "generic_old_male",
    "child": "generic_child",
}

# Безымянные роли из сценария («Служанка 1», «Старый стражник»). Пол у русских ролей
# почти всегда виден по слову, поэтому словарь, а не LLM. Чего нет в словаре — к рассказчику.
_ROLE_WORDS = {
    "female": """служанка служанки горничная горничные фрейлина фрейлины супруга наложница наложницы рабыня
        женщина женщины девушка девушки госпожа хозяйка мать матушка торговка крестьянка жительница горожанка
        няня кормилица императрица королева принцесса леди дама монахиня повитуха куртизанка гейша жена сестра
        дочь тётя тетя придворная""",
    "male": """слуга слуги стражник стражники страж стража солдат солдаты офицер генерал охранник охранники воин
        воины евнух евнухи император король принц повар кучер бандит бандиты вор убийца доктор врач лекарь целитель
        крестьянин крестьяне житель жители горожанин горожане мужчина мужчины парень незнакомец господин хозяин
        торговец купец монах чиновник министр советник посланник гонец рыцарь капитан муж отец брат сын дядя юноша
        раб придворный лакей дворецкий""",
    "old_female": "старуха старушка бабушка бабка",
    "old_male": "старик старики старец дед дедушка",
    "child": "мальчик девочка ребёнок ребенок малыш дитя дети",
}
ROLE_CATEGORY = {word: cat for cat, words in _ROLE_WORDS.items() for word in words.split()}
_OLD_MODIFIERS = ("стар", "пожил")      # «старый слуга», «пожилая женщина»
_CHILD_MODIFIERS = ("маленьк",)


@dataclass(frozen=True)
class VoiceChoice:
    voice_id: str
    source: str
    path: Path
    wanted: str = ""  # для fallback: какой общий голос подошел бы, но еще не создан

    @property
    def exists(self) -> bool:
        return self.path.exists()


def role_category(speaker_name: str) -> Optional[str]:
    """Категория общего голоса для безымянной роли или None, если роль не узнали."""
    words = re.findall(r"[а-яё]+", (speaker_name or "").lower())
    category = next((ROLE_CATEGORY[w] for w in words if w in ROLE_CATEGORY), None)
    if category in ("female", "male"):
        if any(w.startswith(_OLD_MODIFIERS) and not w.startswith("старш") for w in words):  # «старший евнух» — должность
            return "old_" + category
        if any(w.startswith(_CHILD_MODIFIERS) for w in words):
            return "child"
    return category


def character_category(character) -> Optional[str]:
    """Категория общего голоса по полю gender и возрасту из voice_profile («young female, calm»)."""
    profile = (getattr(character, "voice_profile", "") or "").lower()
    if re.search(r"\b(infant|baby|child|kid|boy|girl)\b", profile):
        return "child"
    gender = getattr(getattr(character, "gender", None), "value", getattr(character, "gender", None))
    if gender not in ("female", "male"):
        return None
    old = re.search(r"\b(old|elderly|aged)\b", profile)
    return ("old_" if old else "") + gender


def resolve_voice(speaker_name: Optional[str], characters: Iterable, book_config, speaker_id=None) -> VoiceChoice:
    """
    Персонаж ищется по speaker_id реплики, имя — запасной путь: после переименования
    («Евнух» → «Джинши») старые реплики хранят прежнее имя, но тот же id.
    Порядок: рассказчик → voice_base персонажа (если папка голоса есть) →
    config.character_voices[id персонажа] → общий голос по полу/возрасту (если создан) →
    голос рассказчика (откат).
    """
    narrator = book_config.default_narrator_voice

    def choice(voice_id: str, source: str, wanted: str = "") -> VoiceChoice:
        return VoiceChoice(voice_id, source, path_manager.get_voice_path(voice_id), wanted)

    characters = list(characters)
    char = next((c for c in characters if speaker_id and str(c.id) == str(speaker_id)), None)
    if char is None and (not speaker_name or speaker_name == NARRATOR):
        return choice(narrator, SOURCE_NARRATOR)
    if char is None:
        char = next((c for c in characters if c.name == speaker_name), None)
    if char and char.voice_base and path_manager.get_voice_path(char.voice_base).exists():
        return choice(char.voice_base, SOURCE_VOICE_BASE)

    if char:
        configured = book_config.character_voices.get(char.id)
        if configured:
            return choice(configured, SOURCE_CONFIG)

    category = character_category(char) if char else role_category(speaker_name)
    if category:
        generic = GENERIC_VOICES[category]
        if path_manager.get_voice_path(generic).exists():
            return choice(generic, SOURCE_GENERIC)
        return choice(narrator, SOURCE_FALLBACK, wanted=generic)

    return choice(narrator, SOURCE_FALLBACK)
