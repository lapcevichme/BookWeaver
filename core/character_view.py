"""
Персонаж «на момент главы»: то, что слушатель уже знает к концу этой главы.

Анализ персонажей хранит версии описания по главам (`description_timeline`) и действия
по главам (`chapter_mentions`). Поле `description` — последняя версия, то есть знание
всей обработанной книги; показывать его слушателю середины книги — спойлер.

Порядок глав берется из `order_index`, а не из строки id: иначе vol_1_chap_10 < vol_1_chap_2.
"""
from dataclasses import dataclass, field
from typing import Dict, Iterable, List, Optional

from core.data_models import Chapter, Character


def chapter_order(chapters: Iterable[Chapter]) -> Dict[str, int]:
    """id главы -> позиция в книге."""
    return {c.id: (c.order_index if c.order_index is not None else i) for i, c in enumerate(chapters)}


@dataclass
class CharacterAsOf:
    character: Character
    name: str                                # имя, известное к этой главе (до раскрытия — прежнее)
    description: str
    description_from: Optional[str]          # глава, из которой взята версия описания; None — запасное описание
    first_chapter: Optional[str]
    chapter_mentions: Dict[str, str] = field(default_factory=dict)
    aliases: List[str] = field(default_factory=list)  # только те, что читатель знает к этой главе


def _known_chapters(char: Character, order: Dict[str, int], limit: int) -> List[str]:
    keys = set((char.chapter_mentions or {}).keys()) | set((char.description_timeline or {}).keys())
    return sorted((k for k in keys if k in order and order[k] <= limit), key=order.__getitem__)


def character_as_of(char: Character, order: Dict[str, int], as_of: str) -> Optional[CharacterAsOf]:
    """
    Версия персонажа на момент главы `as_of` (включительно).
    None — персонаж к этой главе еще не появлялся.
    """
    if as_of not in order:
        raise KeyError(f"Глава '{as_of}' не найдена в книге.")
    limit = order[as_of]
    known = _known_chapters(char, order, limit)
    if not known:
        return None

    versions = [k for k in (char.description_timeline or {}) if k in order and order[k] <= limit]
    if versions:
        latest = max(versions, key=order.__getitem__)
        description, description_from = char.description_timeline[latest], latest
    else:
        # Проекты до версионирования: первое описание персонажа, оно не знает будущего так,
        # как последнее (`description`).
        description, description_from = (char.spoiler_free_description or ""), None

    mentions = {k: v for k, v in (char.chapter_mentions or {}).items() if k in order and order[k] <= limit}
    return CharacterAsOf(character=char, name=name_as_of(char, order, limit),
                         description=description, description_from=description_from,
                         first_chapter=known[0], chapter_mentions=mentions,
                         aliases=_aliases_as_of(char, order, limit))


def name_as_of(char: Character, order: Dict[str, int], limit: int) -> str:
    """Последнее имя из name_timeline не позже главы; без истории — текущее имя.
    Если вся история позже главы — самое раннее имя (текущее было бы спойлером)."""
    timeline = {k: v for k, v in (char.name_timeline or {}).items() if k in order}
    if not timeline:
        return char.name
    known = [k for k in timeline if order[k] <= limit]
    pick = max(known, key=order.__getitem__) if known else min(timeline, key=order.__getitem__)
    return timeline[pick]


def _aliases_as_of(char: Character, order: Dict[str, int], limit: int) -> List[str]:
    """
    Алиасы, известные к главе: «Ка Зуйгэцу» у Джинши раскрыт в 3.20 — раньше его показывать нельзя.
    Алиас без записи в alias_meta (проекты до версионирования) или с неизвестной главой считается
    известным с первого появления персонажа.
    """
    meta = char.alias_meta or {}
    name = name_as_of(char, order, limit)
    result = []
    for alias in char.aliases or []:
        if alias == name:
            continue
        since = (meta.get(alias) or {}).get("since")
        if since is None or since not in order or order[since] <= limit:
            result.append(alias)
    return result


def characters_as_of(chars: Iterable[Character], order: Dict[str, int], as_of: str) -> List[CharacterAsOf]:
    """Только персонажи, уже появившиеся к главе `as_of`."""
    if as_of not in order:
        raise KeyError(f"Глава '{as_of}' не найдена в книге.")
    return [v for v in (character_as_of(c, order, as_of) for c in chars) if v is not None]
