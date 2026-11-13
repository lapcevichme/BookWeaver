"""
Выбор голоса для реплики. Одна функция и для озвучки, и для `cli.py inspect voices`,
чтобы проверка показывала ровно то, что сделает TTS.
"""
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, Optional

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
    via: str = ""     # голос взят через связь личностей: «Шисуй → Лоулан (switch_after_reveal с vol_4_chap_14)»

    @property
    def exists(self) -> bool:
        return self.path.exists()


def generic_pool(category: str) -> list:
    """Общие голоса категории в библиотеке: generic_female, generic_female_2, ... (что реально создано)."""
    base = GENERIC_VOICES[category]
    voices_dir = path_manager.get_voice_path(base).parent.parent
    found = [base] if path_manager.get_voice_path(base).exists() else []
    names = set()
    if voices_dir.exists():  # папки storage/voices/<id>/ и плоские storage/voices/selected_refs/<id>.wav
        names |= {d.name for d in voices_dir.iterdir() if d.is_dir()}
        selected = voices_dir / "selected_refs"
        if selected.exists():
            names |= {f.stem for f in selected.iterdir() if f.is_file()}
    extra = [n for n in names if re.fullmatch(rf"{re.escape(base)}_\d+", n) and path_manager.get_voice_path(n).exists()]
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


class IdentityVoices:
    """
    Чьим голосом звучит персонаж в главе с учетом подтвержденных IdentityLink (docs/design/identity-links.md).
    Без связей и при voice_policy=separate — своим. Персонажи не сливаются: меняется только владелец голоса.
    - duplicate: всегда голос основной записи (это ошибка базы, а не тайна книги);
    - same_person, possession: shared — голос true всегда; switch_after_reveal — с главы revealed_in;
    - impersonation: только в active_chapters и если политика не separate (shared — всегда в них,
      switch_after_reveal — с revealed_in).
    Цепочки (A = B, B = C) разворачиваются; циклы обрываются.
    """
    MAX_HOPS = 5

    def __init__(self, links: Iterable, order: Dict[str, int]):
        self.order = order
        self.by_persona: Dict[str, list] = {}
        for l in links:
            if l.status == "confirmed":
                self.by_persona.setdefault(str(l.persona_id), []).append(l)

    def _after_reveal(self, link, chapter_id: Optional[str]) -> bool:
        if not link.revealed_in or chapter_id not in self.order or link.revealed_in not in self.order:
            return False
        return self.order[chapter_id] >= self.order[link.revealed_in]

    def _applies(self, link, chapter_id: Optional[str]) -> bool:
        if link.relation == "duplicate":
            return True
        if link.voice_policy == "separate":
            return False
        if link.relation == "impersonation" and chapter_id not in (link.active_chapters or []):
            return False
        return link.voice_policy == "shared" or (link.voice_policy == "switch_after_reveal"
                                                 and self._after_reveal(link, chapter_id))

    def owner(self, character_id, chapter_id: Optional[str]):
        """(id владельца голоса, пояснение) — id исходного персонажа и "" если связь не действует."""
        current, seen, notes = str(character_id), {str(character_id)}, []
        for _ in range(self.MAX_HOPS):
            link = next((l for l in self.by_persona.get(current, []) if self._applies(l, chapter_id)), None)
            if link is None or str(link.true_id) in seen:
                break
            notes.append(f"{link.relation}/{link.voice_policy}"
                         + (f" с {link.revealed_in}" if link.voice_policy == "switch_after_reveal" else ""))
            current = str(link.true_id)
            seen.add(current)
        return current, "; ".join(notes)


def resolve_voice(speaker_name: Optional[str], characters: Iterable, book_config, speaker_id=None,
                  link=None, extra_index: int = 0, identity: Optional[IdentityVoices] = None,
                  chapter_id: Optional[str] = None) -> VoiceChoice:
    """
    Персонаж ищется по speaker_id реплики, имя — запасной путь: после переименования
    («Евнух» → «Джинши») старые реплики хранят прежнее имя, но тот же id.
    link — SpeakerLink подписи в этой главе: подтвержденная связь с персонажем работает как его speaker_id,
    массовка берет пол/возраст из связи, а голос — из пула по extra_index.
    Порядок: рассказчик → voice_base персонажа (если папка голоса есть) →
    config.character_voices[id персонажа] → общий голос по полу/возрасту (если создан) →
    голос рассказчика (откат).
    identity + chapter_id — связи личностей: персонаж звучит голосом владельца (IdentityVoices.owner).
    """
    narrator = book_config.default_narrator_voice
    via = ""

    def choice(voice_id: str, source: str, wanted: str = "") -> VoiceChoice:
        return VoiceChoice(voice_id, source, path_manager.get_voice_path(voice_id), wanted, via)

    characters = list(characters)
    if speaker_id is None and link is not None and link.kind == "character" and link.status == "confirmed":
        speaker_id = link.character_id
    char = next((c for c in characters if speaker_id and str(c.id) == str(speaker_id)), None)
    if char is None and (not speaker_name or speaker_name == NARRATOR):
        return choice(narrator, SOURCE_NARRATOR)
    if char is None:
        char = next((c for c in characters if c.name == speaker_name), None)
    if char and identity is not None:
        owner_id, note = identity.owner(char.id, chapter_id)
        owner = next((c for c in characters if str(c.id) == owner_id), None) if owner_id != str(char.id) else None
        if owner is not None:
            via = f"{char.name} → {owner.name} ({note})"
            char = owner
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
