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


def generic_pool(category: str) -> list:
    """Общие голоса категории в библиотеке: generic_female, generic_female_2, ... (что реально создано)."""
    base = GENERIC_VOICES[category]
    voices_dir = path_manager.get_voice_path(base).parent.parent
    found = [base] if path_manager.get_voice_path(base).exists() else []
    if voices_dir.exists():
        extra = [d.name for d in voices_dir.iterdir() if re.fullmatch(rf"{re.escape(base)}_\d+", d.name)
                 and path_manager.get_voice_path(d.name).exists()]
        found += sorted(extra, key=lambda n: int(n.rsplit("_", 1)[1]))
    return found


def link_category(link) -> Optional[str]:
    """Категория общего голоса для массовки из SpeakerLink (пол и возраст от LLM)."""
    gender, age = getattr(link, "gender", None), getattr(link, "age_group", None)
    if age == "child":
        return "child"
    if gender not in ("female", "male"):
        return None
    return ("old_" if age == "old" else "") + gender


def extra_indexes(links: Iterable, label_order: list) -> dict:
    """
    Подпись -> номер голоса в пуле категории. Разные люди одной категории в главе (Служанка 1 и 2)
    получают разные номера по порядку первого появления; подписи одного человека (общий person_key) — один номер.
    """
    by_label = {l.speaker_label: l for l in links if _is_usable_extra(l)}
    seen, result = {}, {}
    for label in label_order:
        link = by_label.get(label)
        if link is None or label in result:
            continue
        cat = link_category(link)
        key = (cat, link.person_key or label)
        if key not in seen:
            seen[key] = sum(1 for k in seen if k[0] == cat)
        result[label] = seen[key]
    return result


def _is_usable_extra(link) -> bool:
    return link is not None and link.kind == "extra" and link.status != "rejected"


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


def resolve_voice(speaker_name: Optional[str], characters: Iterable, book_config, speaker_id=None,
                  link=None, extra_index: int = 0) -> VoiceChoice:
    """
    Персонаж ищется по speaker_id реплики, имя — запасной путь: после переименования
    («Евнух» → «Джинши») старые реплики хранят прежнее имя, но тот же id.
    link — SpeakerLink подписи в этой главе: подтвержденная связь с персонажем работает как его speaker_id,
    массовка берет пол/возраст из связи, а голос — из пула по extra_index.
    Порядок: рассказчик → voice_base персонажа (если папка голоса есть) →
    config.character_voices[id персонажа] → общий голос по полу/возрасту (если создан) →
    голос рассказчика (откат).
    """
    narrator = book_config.default_narrator_voice

    def choice(voice_id: str, source: str, wanted: str = "") -> VoiceChoice:
        return VoiceChoice(voice_id, source, path_manager.get_voice_path(voice_id), wanted)

    characters = list(characters)
    if speaker_id is None and link is not None and link.kind == "character" and link.status == "confirmed":
        speaker_id = link.character_id
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

    if char:
        category = character_category(char)
    elif _is_usable_extra(link) and link_category(link):
        category = link_category(link)
    else:
        category = role_category(speaker_name)
    if category:
        pool = generic_pool(category) if not char else []
        if pool:
            return choice(pool[extra_index % len(pool)], SOURCE_GENERIC)
        generic = GENERIC_VOICES[category]
        if path_manager.get_voice_path(generic).exists():
            return choice(generic, SOURCE_GENERIC)
        return choice(narrator, SOURCE_FALLBACK, wanted=generic)

    return choice(narrator, SOURCE_FALLBACK)


def link_context(links: Iterable, entries: Iterable) -> dict:
    """
    Связи одной главы для resolve_voice: подпись -> (SpeakerLink, extra_index).
    Номер голоса массовки считается по порядку первого появления подписи в главе.
    """
    links = list(links)
    label_order = list(dict.fromkeys(e.speaker_name for e in entries if e.speaker_name))
    indexes = extra_indexes(links, label_order)
    return {l.speaker_label: (l, indexes.get(l.speaker_label, 0)) for l in links}
