"""
Связи личностей (IdentityLink): две записи о персонажах — одна личность. docs/design/identity-links.md

Кандидаты приходят из двух источников:
- alias_collision — анализ персонажей хотел дать персонажу имя, занятое другим («Лоулан» для Шисуй, 4.14):
  бесплатно и ровно в главе раскрытия, но подмену (двойник) не видит;
- secret_scan — один вызов LLM по всем secret-фактам книги: находит и «один человек», и подмены.

Каждая пара оценивается отдельно: вероятности вариантов A-H по первому токену (дешево, дает уверенность),
и если ответ «связь есть» или уверенность ниже порога — перепроверка судьей с reasoning.
Проверено на Кусурии т. 1-4: docs/experiments/2026-11-13_identity-signals.md.

Автоматически ничего не подтверждается: связь меняет голос и показ раскрытия, решает человек.
"""
import json
import logging
import math
import re
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from typing import Dict, List, Optional, Set, Tuple
from uuid import UUID

from pydantic import BaseModel, Field
from sqlmodel import select

from core.book_repository import BookRepository
from core.character_view import chapter_order
from core.data_models import Character, IdentityLink, IDENTITY_VOICE_POLICIES
from utils.metrics import metrics_collector

logger = logging.getLogger(__name__)

CONFIDENCE_THRESHOLD = 0.95  # ниже — перепроверка судьей с reasoning (единственная ошибка эксперимента — 0.847)
CONFIDENCE_SUGGEST_MIN = 0.85  # ниже — связь помечается dismissed и видна только с --all (отсечение шума)
IDENTITY_SIMILARITY_MIN = 0.78  # целевой коридор ECAPA: ниже — чужой голос, нет узнаваемости
IDENTITY_SIMILARITY_MAX = 0.88  # выше — голос не отличим от оригинала (маска не работает)

# Буква -> (relation, кто персона). X, Y — порядок записей в вопросе.
OPTIONS: Dict[str, Tuple[Optional[str], Optional[str]]] = {
    "A": ("same_person", "x"),     # X — маска/другое имя; настоящий — Y
    "B": ("same_person", "y"),
    "C": ("impersonation", "y"),   # X выдает себя за Y: видят Y, на деле X
    "D": ("impersonation", "x"),
    "E": ("possession", "x"),      # в теле X — Y
    "F": ("possession", "y"),
    "G": ("duplicate", None),      # база завела одного человека дважды
    "H": (None, None),             # связи личностей нет
}
LETTERS = tuple(OPTIONS)


def _ch(chapter_id: Optional[str]) -> str:
    return (chapter_id or "").replace("vol_", "").replace("_chap_", ".")


def _profile(char: Character) -> str:
    facts = [f for f in (char.facts or []) if f.get("kind") in ("secret", "status")][:30]
    lines = "\n".join(f"  - [{f['kind']}, гл. {_ch(f.get('chapter'))}] {f['fact']}" for f in facts)
    aliases = ", ".join(char.aliases or []) or "—"
    return f"«{char.name}» (алиасы: {aliases})\nКарточка: {char.description}\nФакты:\n{lines or '  —'}"


def pair_prompt(x: Character, y: Character) -> str:
    return f"""В базе персонажей книги есть две записи. Иногда это один человек (маска, псевдоним, двойник,
вселение), потому что читатель долго не знал правду, или база по ошибке завела человека дважды.

Запись X: {_profile(x)}

Запись Y: {_profile(y)}

Как связаны X («{x.name}») и Y («{y.name}») как личности?
A — один человек: X — маска или другое имя, настоящая личность — Y
B — один человек: Y — маска или другое имя, настоящая личность — X
C — X выдает себя за Y (двойник, подмена), это разные люди
D — Y выдает себя за X (двойник, подмена), это разные люди
E — в теле X находится Y (вселение)
F — в теле Y находится X (вселение)
G — база по ошибке завела одного человека дважды (другой перевод имени или титула)
H — это разные люди, связи личностей нет (родство, дружба, служба, общий титул — не связь личностей)
"""


class LlmIdentityJudgement(BaseModel):
    answer: str = Field(..., description="Буква A-H")
    evidence: str = Field("", description="Коротко, на что опирается ответ, с номерами глав")


class LlmImpersonationScope(BaseModel):
    chapters: List[str] = Field(default_factory=list, description="id глав, где самозванец действует под видом персоны")
    evidence: str = ""


class LlmSecretLink(BaseModel):
    fact_id: int
    a: str = Field(..., description="Точное имя записи базы")
    b: str = Field(..., description="Точное имя записи базы")
    evidence: str = ""


class LlmSecretScan(BaseModel):
    links: List[LlmSecretLink] = Field(default_factory=list)


def score_pair(llm, x: Character, y: Character) -> dict:
    """Оценка пары: {'letter', 'scores', 'confidence', 'judged', 'evidence'}."""
    prompt = pair_prompt(x, y)
    scores = llm.choice_probabilities(prompt + "Ответь одной буквой.", LETTERS, prompt_type="identity_score") or {}
    top = next(iter(scores), None)
    letter, evidence, judged = top, "", False
    if top is None or top != "H" or scores[top] < CONFIDENCE_THRESHOLD:
        verdict = llm.call_for_pydantic(
            LlmIdentityJudgement,
            prompt + 'Подумай, затем ответь JSON: {"answer": "<буква>", "evidence": "коротко, с номерами глав"}',
            prompt_type="identity_judge")
        if verdict and verdict.answer.strip().upper()[:1] in OPTIONS:
            letter, evidence, judged = verdict.answer.strip().upper()[:1], verdict.evidence, True
    return {"letter": letter, "scores": scores, "confidence": scores.get(letter) if letter else None,
            "judged": judged, "evidence": evidence}


def scan_secret_facts(llm, chars: List[Character]) -> List[dict]:
    """Один вызов по всем secret-фактам книги: какие из них связывают две записи как личности."""
    items, by_id = [], {}
    for c in chars:
        for f in c.facts or []:
            if f.get("kind") == "secret":
                by_id[len(items)] = (c, f)
                items.append({"fact_id": len(items), "chapter": f.get("chapter"), "owner": c.name, "fact": f["fact"]})
    if not items:
        return []
    names = sorted(c.name for c in chars)
    prompt = f"""Ты анализируешь базу персонажей книги. В базе могут быть ДВЕ записи об одном человеке
(маска, псевдоним, двойник, подмена, вселение), потому что читатель долго не знал, что это один человек.

Ниже — все секретные факты персонажей (owner — чей факт). Найди ТОЛЬКО факты, которые говорят,
что две записи базы связаны как личности: один человек под разными именами, один выдает себя за другого,
один в теле другого, или база завела человека дважды. a и b — ТОЧНЫЕ имена записей из списка.
Родство, знание чужих тайн, подозрения, заговоры — НЕ связь личностей. Если связи нет — пустой список.

Записи базы: {json.dumps(names, ensure_ascii=False)}

Факты:
{json.dumps(items, ensure_ascii=False, indent=0)}

Ответ — JSON: {{"links": [{{"fact_id": 0, "a": "...", "b": "...", "evidence": "коротко"}}]}}"""
    result = llm.call_for_pydantic(LlmSecretScan, prompt, prompt_type="identity_scan")
    if result is None:
        raise ValueError("Поиск связей по secret-фактам: LLM не вернула валидный ответ")
    by_name = {c.name: c for c in chars}
    found = []
    for link in result.links:
        a, b = by_name.get(link.a), by_name.get(link.b)
        if not a or not b or a.id == b.id or link.fact_id not in by_id:
            logger.warning(f"Связь из secret-фактов пропущена: {link.a!r} ~ {link.b!r} (fact {link.fact_id})")
            continue
        owner, fact = by_id[link.fact_id]
        found.append({"x": a, "y": b, "chapter": fact.get("chapter"),
                      "evidence": f"{owner.name}, гл. {_ch(fact.get('chapter'))}: {fact['fact']}"})
    return found


DEDUP_STOP_WORDS = {
    'и', 'в', 'во', 'не', 'что', 'он', 'на', 'я', 'с', 'со', 'как', 'а', 'то', 'все', 'она', 'так',
    'его', 'но', 'да', 'ты', 'к', 'у', 'же', 'вы', 'за', 'бы', 'по', 'только', 'ее', 'мне', 'было',
    'вот', 'от', 'меня', 'еще', 'нет', 'о', 'из', 'ему', 'теперь', 'когда', 'даже', 'ну', 'вдруг',
    'ли', 'если', 'уже', 'или', 'ни', 'быть', 'был', 'была', 'были', 'него', 'до', 'вас', 'нибудь',
    'опять', 'уж', 'вам', 'ведь', 'там', 'потом', 'себя', 'ничего', 'ей', 'может', 'они', 'тут',
    'где', 'есть', 'надо', 'ней', 'для', 'мы', 'тебя', 'их', 'чем', 'была', 'сам', 'чтоб', 'без',
    'будто', 'чего', 'раз', 'тоже', 'себе', 'под', 'будет', 'ж', 'тогда', 'кто', 'этот', 'того',
    'потому', 'этого', 'какой', 'совсем', 'ним', 'здесь', 'этом', 'один', 'почти', 'мой', 'тем',
    'чтобы', 'нее', 'сейчас', 'были', 'куда', 'зачем', 'всех', 'никогда', 'можно', 'при', 'наконец',
    'два', 'об', 'другой', 'хоть', 'после', 'над', 'больше', 'тот', 'через', 'эти', 'нас', 'про',
    'всего', 'них', 'какая', 'много', 'разве', 'три', 'эту', 'моя', 'впрочем', 'хорошо', 'свою',
    'этой', 'перед', 'иногда', 'лучше', 'чуть', 'том', 'нельзя', 'такой', 'им', 'более', 'всегда',
    'конечно', 'всю', 'между', 'глава', 'гл', 'том', 'персонаж', 'одна', 'один', 'одно', 'одного'
}

DEDUP_ROLE_WORDS = {
    'супруга', 'супруги', 'супругой', 'супругу', 'супруг',
    'наложница', 'наложницы', 'наложницей', 'наложницу', 'наложниц',
    'куртизанка', 'куртизанки', 'куртизанкой', 'куртизанку',
    'евнух', 'евнуха', 'евнухом', 'евнухе',
    'служанка', 'служанки', 'служанкой', 'служанку',
    'фрейлина', 'фрейлины', 'фрейлиной', 'фрейлину',
    'хозяйка', 'хозяйки', 'хозяйкой', 'хозяйку',
    'доктор', 'доктора', 'доктором', 'врач', 'врача',
    'командир', 'командира', 'страж', 'стража', 'стражник',
    'посланница', 'посланницы', 'эмиссар', 'принц', 'принцесса'
}


def _tokenize_for_dedup(text: str) -> Set[str]:
    return {w for w in re.findall(r'[a-zA-Zа-яА-ЯёЁ0-9]+', text.lower())
            if len(w) > 2 and w not in DEDUP_STOP_WORDS}


def _character_chapters(c: Character) -> Set[str]:
    """Все главы активности персонажа: упоминания, версии описания, факты, первая глава."""
    res = set((c.chapter_mentions or {}).keys())
    res.update((c.description_timeline or {}).keys())
    first_seen = getattr(c, "first_seen_chapter_id", None)
    if first_seen:
        res.add(first_seen)
    for f in (c.facts or []):
        ch = f.get("chapter")
        if ch:
            res.add(ch)
    return res


def find_duplicate_candidates(chars: List[Character], order: Dict[str, int],
                              max_candidates: int = 15) -> List[dict]:
    """
    Находит кандидатов на дубликаты базы (ошибки перевода/парсинга) по поведенческим паттернам и фактам.

    Дисквалификаторы (антипаттерны):
    - Совместное появление в одной главе (co-occurrence);
    - Параллельная долгая жизнь (оба персонажа > 3 глав);
    - Конфликт сущностей (PERSON vs ANIMAL) или несовпадающий пол (male vs female).

    Положительные поведенческие паттерны:
    - gap_fill: эпизодический персонаж (<= 2 глав) появляется строго внутри интервала активного персонажа;
    - relay: передача эстафеты (один персонаж заканчивается, другой начинается в пределах 3 глав без пересечения);
    - flash: одноразовый персонаж (<= 2 глав) с совпадением редких фактов-биографических якорей.
    """
    doc_freq = Counter()
    char_tokens: Dict[UUID, Set[str]] = {}
    char_roles: Dict[UUID, Set[str]] = {}

    for c in chars:
        texts = [c.name, c.description or ''] + (c.aliases or [])
        for f in (c.facts or []):
            texts.append(f.get('fact', ''))
        tokens = _tokenize_for_dedup(' '.join(texts))
        char_tokens[c.id] = tokens
        for t in tokens:
            doc_freq[t] += 1
        name_words = _tokenize_for_dedup(c.name + ' ' + ' '.join(c.aliases or []))
        char_roles[c.id] = {w for w in name_words if w in DEDUP_ROLE_WORDS}

    N = len(chars)
    idf = {t: math.log((N - df + 0.5) / (df + 0.5) + 1.0) for t, df in doc_freq.items()}

    char_chaps: Dict[UUID, List[str]] = {}
    for c in chars:
        ch_list = [ch for ch in _character_chapters(c) if ch in order]
        char_chaps[c.id] = sorted(ch_list, key=order.__getitem__)

    candidates = []
    for i in range(len(chars)):
        for j in range(i + 1, len(chars)):
            c1, c2 = chars[i], chars[j]
            ch1, ch2 = char_chaps.get(c1.id, []), char_chaps.get(c2.id, [])
            if not ch1 or not ch2:
                continue
            if set(ch1) & set(ch2):
                continue
            if len(ch1) > 3 and len(ch2) > 3:
                continue
            if c1.entity_type != c2.entity_type and c1.entity_type and c2.entity_type:
                continue

            g1 = getattr(c1.gender, 'value', c1.gender)
            g2 = getattr(c2.gender, 'value', c2.gender)
            if g1 and g2 and g1 != g2:
                continue

            if len(ch1) <= len(ch2):
                minor, major = c1, c2
                minor_ch, major_ch = ch1, ch2
            else:
                minor, major = c2, c1
                minor_ch, major_ch = ch2, ch1

            pattern = None
            if len(major_ch) >= 4 and len(minor_ch) <= 2:
                min_maj, max_maj = order[major_ch[0]], order[major_ch[-1]]
                if all(min_maj < order[ch] < max_maj for ch in minor_ch):
                    pattern = 'gap_fill'
            if not pattern:
                if order[minor_ch[-1]] < order[major_ch[0]] and (order[major_ch[0]] - order[minor_ch[-1]]) <= 3:
                    pattern = 'relay'
                elif order[major_ch[-1]] < order[minor_ch[0]] and (order[minor_ch[0]] - order[major_ch[-1]]) <= 3:
                    pattern = 'relay'
            if not pattern and len(minor_ch) <= 2:
                pattern = 'flash'

            if not pattern:
                continue

            common = char_tokens.get(minor.id, set()) & char_tokens.get(major.id, set())
            rare = [t for t in common if doc_freq[t] <= 3]
            rare_score = sum(idf[t] for t in rare)
            shared_roles = char_roles.get(minor.id, set()) & char_roles.get(major.id, set())
            role_bonus = 3.5 if shared_roles else 0.0
            total_score = rare_score + role_bonus

            if pattern == 'gap_fill' and (total_score >= 6.0 or (shared_roles and rare)):
                pass
            elif pattern == 'relay' and total_score >= 6.0:
                pass
            elif pattern == 'flash' and (total_score >= 8.0 or (len(rare) >= 2 and total_score >= 6.0)):
                pass
            else:
                continue

            evidence = f"паттерн {pattern} (гл. {_ch(minor_ch[0])})"
            if shared_roles:
                evidence += f", общая роль: {list(shared_roles)[0]}"
            if rare:
                evidence += f", редкие факты: {', '.join(sorted(rare)[:4])}"

            candidates.append({
                'x': minor,
                'y': major,
                'chapter': minor_ch[0],
                'pattern': pattern,
                'score': total_score,
                'evidence': evidence
            })

    candidates.sort(key=lambda x: x['score'], reverse=True)
    return candidates[:max_candidates]


def scope_prompt(persona: Character, impostor: Character, order: Dict[str, int]) -> str:
    """Действия обоих по главам (где появляется хоть один) + их secret-факты."""
    p_chaps = _character_chapters(persona)
    i_chaps = _character_chapters(impostor)
    chapters = sorted((p_chaps | i_chaps) & set(order.keys()),
                      key=lambda k: order.get(k, 1 << 30))
    rows = []
    for k in chapters:
        rows.append(f"{k}:")
        p_act = (persona.chapter_mentions or {}).get(k) or (persona.description_timeline or {}).get(k)
        if p_act:
            rows.append(f"  «{persona.name}»: {p_act}")
        i_act = (impostor.chapter_mentions or {}).get(k) or (impostor.description_timeline or {}).get(k)
        if i_act:
            rows.append(f"  «{impostor.name}»: {i_act}")
    secrets = [f"  - {c.name}, {f.get('chapter')}: {f['fact']}" for c in (persona, impostor)
               for f in (c.facts or []) if f.get("kind") == "secret"]
    return f"""В книге «{impostor.name}» выдает себя за «{persona.name}» (двойник, подмена). Читатель видит «{persona.name}»,
а на деле это «{impostor.name}». Определи ГЛАВЫ, в которых под видом «{persona.name}» действует «{impostor.name}»:
- в том числе главы ДО раскрытия, если текст потом объясняет, что подмена уже шла (сцены «{persona.name}» там — это «{impostor.name}»);
- НЕ включай главы, где «{persona.name}» — сам(а) себя, или где о подмене только вспоминают.
Опирайся на действия по главам и тайны. Используй только id глав из списка.

Тайны:
{chr(10).join(secrets) or '  —'}

Действия по главам:
{chr(10).join(rows)}

Ответ — JSON: {{"chapters": ["vol_N_chap_M", ...], "evidence": "коротко, почему эти главы"}}"""


def scope_impersonation(llm, persona: Character, impostor: Character, order: Dict[str, int]) -> Optional[LlmImpersonationScope]:
    result = llm.call_for_pydantic(LlmImpersonationScope, scope_prompt(persona, impostor, order),
                                   prompt_type="identity_scope")
    if result is None:
        return None
    known = _character_chapters(persona) | _character_chapters(impostor)
    dropped = [c for c in result.chapters if c not in order]
    if dropped:
        logger.warning(f"Период подмены {impostor.name} -> {persona.name}: неизвестные главы {dropped}")
    result.chapters = sorted({c for c in result.chapters if c in order}, key=order.__getitem__)
    outside = [c for c in result.chapters if c not in known]
    if outside:
        logger.info(f"Период подмены {impostor.name} -> {persona.name}: главы без упоминаний обоих {outside}")
    return result


def update_scopes(repo: BookRepository, llm, prefix: Optional[str] = None, force: bool = False) -> List[dict]:
    """Период для подмен без него (или для одной связи по id). Решение человека о статусе не трогает."""
    metrics_collector.start_book(repo.book_id)
    chars = {c.id: c for c in repo.get_characters()}
    order = chapter_order(repo.get_all_chapters())
    with repo.get_session() as session:
        links = session.exec(select(IdentityLink).where(IdentityLink.book_id == repo.book_id,
                                                        IdentityLink.relation == "impersonation")).all()
    if prefix:
        links = [l for l in links if str(l.id).startswith(prefix)]
    else:
        links = [l for l in links if l.status in ("suggested", "confirmed") and (force or not l.active_chapters)]
    links = [l for l in links if l.persona_id in chars and l.true_id in chars]
    with ThreadPoolExecutor(max_workers=4) as ex:
        results = list(ex.map(lambda l: (l, scope_impersonation(llm, chars[l.persona_id], chars[l.true_id], order)), links))
    done = []
    with repo.get_session() as session:
        for link, r in results:
            if r is None:
                logger.warning(f"Период подмены {str(link.id)[:8]}: LLM не вернула валидный ответ")
                continue
            link = session.get(IdentityLink, link.id)
            link.active_chapters = r.chapters
            if r.evidence:
                link.evidence = f"{link.evidence}\nпериод: {r.evidence}".strip()
            session.add(link)
            done.append({"id": str(link.id)[:8], "chapters": r.chapters})
        session.commit()
    return done


def set_scope(book_name: str, prefix: str, chapters: List[str]) -> IdentityLink:
    repo = BookRepository(book_name)
    order = chapter_order(repo.get_all_chapters())
    unknown = [c for c in chapters if c not in order]
    if unknown:
        raise KeyError(f"Нет глав: {unknown}")
    with repo.get_session() as session:
        link = _get_by_prefix(session, book_name, prefix)
        link.active_chapters = sorted(set(chapters), key=order.__getitem__)
        session.add(link)
        session.commit()
        session.refresh(link)
        return link


def _find_pair(session, book_id: str, a: UUID, b: UUID) -> Optional[IdentityLink]:
    return session.exec(select(IdentityLink).where(
        IdentityLink.book_id == book_id,
        ((IdentityLink.persona_id == a) & (IdentityLink.true_id == b))
        | ((IdentityLink.persona_id == b) & (IdentityLink.true_id == a)))).first()


def add_candidate(repo: BookRepository, x: Character, y: Character, chapter: Optional[str], source: str,
                  evidence: str, order: Optional[Dict[str, int]] = None) -> IdentityLink:
    """Кандидат на пару (без оценки). Для существующей пары — добавляет довод и более раннюю главу раскрытия."""
    order = order or chapter_order(repo.get_all_chapters())
    with repo.get_session() as session:
        link = _find_pair(session, repo.book_id, x.id, y.id)
        if link is None:
            link = IdentityLink(book_id=repo.book_id, persona_id=x.id, true_id=y.id, revealed_in=chapter,
                                source=source, evidence=evidence)
        else:
            if evidence and evidence not in link.evidence:
                link.evidence = f"{link.evidence}\n{evidence}".strip()
            if chapter and (not link.revealed_in or order.get(chapter, 1 << 30) < order.get(link.revealed_in, 1 << 30)):
                link.revealed_in = chapter
            if source not in link.source.split("+"):
                link.source = f"{link.source}+{source}"
        session.add(link)
        session.commit()
        session.refresh(link)
        return link


class IdentitySuggestPipeline:
    def __init__(self, model_manager):
        self.model_manager = model_manager

    def run(self, book_name: str, scan: bool = True, dedup: bool = True, rescore: bool = False,
            volume_num: Optional[int] = None, auto_confirm: Optional[bool] = None) -> dict:
        import config
        if auto_confirm is None:
            auto_confirm = getattr(config, "IDENTITY_AUTO_CONFIRM", True)
        auto_confirm_threshold = getattr(config, "IDENTITY_AUTO_CONFIRM_THRESHOLD", 0.95)

        repo = BookRepository(book_name)
        chars = repo.get_characters()
        by_id = {c.id: c for c in chars}
        order = chapter_order(repo.get_all_chapters())
        llm = self.model_manager.get_llm_service('character_analyzer')
        metrics_collector.start_book(book_name)  # этап уровня книги: вызовы без главы

        scanned = 0
        if scan:
            for found in scan_secret_facts(llm, chars):
                add_candidate(repo, found["x"], found["y"], found["chapter"], "secret_scan", found["evidence"], order)
                scanned += 1

        dedup_found = 0
        if dedup:
            for cand in find_duplicate_candidates(chars, order):
                add_candidate(repo, cand["x"], cand["y"], cand["chapter"], "dedup_scan", cand["evidence"], order)
                dedup_found += 1

        with repo.get_session() as session:
            todo = session.exec(select(IdentityLink).where(IdentityLink.book_id == book_name)).all()
        todo = [l for l in todo if l.status == "candidate" or (rescore and l.status in ("suggested", "dismissed"))]
        todo = [l for l in todo if l.persona_id in by_id and l.true_id in by_id]

        def work(link):
            return link, score_pair(llm, by_id[link.persona_id], by_id[link.true_id])

        with ThreadPoolExecutor(max_workers=6) as ex:
            results = list(ex.map(work, todo))

        counts = {"scanned": scanned, "dedup_found": dedup_found, "scored": 0, "suggested": 0,
                  "auto_confirmed": 0, "dismissed": 0, "judged": 0}
        with repo.get_session() as session:
            for link, r in results:
                link = session.get(IdentityLink, link.id)
                counts["scored"] += 1
                counts["judged"] += r["judged"]
                x, y = by_id[link.persona_id], by_id[link.true_id]
                relation, persona = OPTIONS.get(r["letter"], (None, None))
                link.scores, link.confidence = r["scores"], r["confidence"]
                if r["evidence"]:
                    link.evidence = f"{link.evidence}\nсудья: {r['evidence']}".strip()
                if relation is None:
                    link.status = "dismissed"
                    counts["dismissed"] += 1
                else:
                    if relation == "duplicate":  # основная запись — та, что чаще встречается
                        persona = "x" if len(_character_chapters(x)) <= len(_character_chapters(y)) else "y"
                    link.relation = relation
                    link.persona_id, link.true_id = (x.id, y.id) if persona == "x" else (y.id, x.id)
                    if link.confidence is not None and link.confidence < CONFIDENCE_SUGGEST_MIN:
                        link.status = "dismissed"
                        counts["dismissed"] += 1
                    elif auto_confirm and link.confidence is not None and link.confidence >= auto_confirm_threshold:
                        link.status = "confirmed"
                        link.decided_at = datetime.now(timezone.utc)
                        link.source = f"{link.source}+auto_confirmed" if "+auto_confirmed" not in link.source else link.source
                        if link.relation == "duplicate":
                            link.voice_policy = "shared"
                        elif link.revealed_in and link.voice_policy == "separate":
                            link.voice_policy = getattr(config, "IDENTITY_DEFAULT_VOICE_POLICY", "switch_after_reveal")
                        counts["auto_confirmed"] += 1
                    else:
                        link.status = "suggested"
                        counts["suggested"] += 1
                session.add(link)
            session.commit()
        counts["scoped"] = len(update_scopes(repo, llm))
        vol_str = f" (том {volume_num})" if volume_num is not None else ""
        logger.info(f"Связи личностей {book_name}{vol_str}: {counts}")
        return counts


def same_person_targets(repo: BookRepository) -> Dict[str, UUID]:
    """Персона -> настоящая личность по подтвержденным same_person/possession: голос персоны делается похожим."""
    with repo.get_session() as session:
        links = session.exec(select(IdentityLink).where(IdentityLink.book_id == repo.book_id,
                                                        IdentityLink.status == "confirmed")).all()
    return {str(l.persona_id): l.true_id for l in links if l.relation in ("same_person", "possession")}


def identity_voices(repo: BookRepository):
    """Резолвер голоса по подтвержденным связям книги (core.voices.IdentityVoices)."""
    from core.voices import IdentityVoices
    with repo.get_session() as session:
        links = session.exec(select(IdentityLink).where(IdentityLink.book_id == repo.book_id,
                                                        IdentityLink.status == "confirmed")).all()
    return IdentityVoices(links, chapter_order(repo.get_all_chapters()))


def list_links(repo: BookRepository, include_all: bool = False, blind: bool = True) -> List[dict]:
    chars = {c.id: c for c in repo.get_characters()}
    names = {c_id: c.name for c_id, c in chars.items()}
    order = chapter_order(repo.get_all_chapters())
    with repo.get_session() as session:
        links = session.exec(select(IdentityLink).where(IdentityLink.book_id == repo.book_id)).all()
    if not include_all:
        links = [l for l in links if l.status not in ("dismissed", "rejected")]
    links.sort(key=lambda l: (l.status != "suggested", -(l.confidence or 0), order.get(l.revealed_in, 1 << 30)))
    rows = []
    for l in links:
        p_char = chars.get(l.persona_id)
        vc = (p_char.voice_casting or {}) if p_char else {}
        sim_val = vc.get("identity_similarity")
        passed = vc.get("guardrail_passed", True)
        sim_str = ""
        if sim_val is not None:
            sim_str = f"{sim_val:.3f}" + ("" if passed else " ⚠️")

        persona_name = names.get(l.persona_id, "?")
        true_name = names.get(l.true_id, "?")
        evidence_text = " | ".join(x for x in (l.evidence or "").splitlines() if x)

        if blind:
            true_display = "[🔒 Скрыто спойлер-щитом]"
            evidence_display = f"[🔒 Скрыто: cli.py identity reveal --id {str(l.id)[:8]} или --spoilers]"
        else:
            true_display = true_name
            evidence_display = evidence_text

        rows.append({
            "id": str(l.id)[:8],
            "persona": persona_name,
            "true": true_display,
            "true_actual": true_name,
            "relation": l.relation,
            "revealed_in": l.revealed_in or "",
            "confidence": f"{l.confidence:.3f}" if l.confidence is not None else "",
            "status": l.status,
            "source": l.source,
            "voice_policy": l.voice_policy,
            "shift": f"{l.voice_shift:+g}" if l.voice_shift is not None else "",
            "sim": sim_str,
            "chapters": _ranges(l.active_chapters, order),
            "evidence": evidence_display,
            "evidence_actual": evidence_text,
            "is_blind": blind,
        })
    return rows


def reveal_link(repo: BookRepository, prefix: str) -> dict:
    """Возвращает информацию о связи со снятым спойлер-щитом (открытые имена и доказательства)."""
    chars = {c.id: c for c in repo.get_characters()}
    names = {c_id: c.name for c_id, c in chars.items()}
    order = chapter_order(repo.get_all_chapters())
    with repo.get_session() as session:
        link = _get_by_prefix(session, repo.book_id, prefix)
    p_char = chars.get(link.persona_id)
    vc = (p_char.voice_casting or {}) if p_char else {}
    sim_val = vc.get("identity_similarity")
    passed = vc.get("guardrail_passed", True)
    sim_str = ""
    if sim_val is not None:
        sim_str = f"{sim_val:.3f}" + ("" if passed else " ⚠️")

    return {
        "id": str(link.id)[:8],
        "persona": names.get(link.persona_id, "?"),
        "true": names.get(link.true_id, "?"),
        "relation": link.relation,
        "revealed_in": link.revealed_in or "",
        "confidence": f"{link.confidence:.3f}" if link.confidence is not None else "",
        "status": link.status,
        "source": link.source,
        "voice_policy": link.voice_policy,
        "shift": f"{link.voice_shift:+g}" if link.voice_shift is not None else "",
        "sim": sim_str,
        "chapters": _ranges(link.active_chapters, order),
        "evidence": " | ".join(x for x in (link.evidence or "").splitlines() if x),
        "is_blind": False,
        "spoiler_warning": True,
    }


def _ranges(chapters: List[str], order: Dict[str, int]) -> str:
    """['vol_3_chap_3', 'vol_3_chap_4', 'vol_3_chap_8'] -> '3.3–3.4, 3.8' (подряд по порядку книги — диапазоном)."""
    if not chapters:
        return ""
    by_pos = {v: k for k, v in order.items()}
    pos = sorted(order[c] for c in chapters if c in order)
    out, start = [], None
    for i, p in enumerate(pos):
        start = p if start is None else start
        if i + 1 == len(pos) or pos[i + 1] != p + 1:
            out.append(_ch(by_pos[start]) + ("" if start == p else f"–{_ch(by_pos[p])}"))
            start = None
    return ", ".join(out)


def _get_by_prefix(session, book_id: str, prefix: str) -> IdentityLink:
    links = [l for l in session.exec(select(IdentityLink).where(IdentityLink.book_id == book_id)).all()
             if str(l.id).startswith(prefix)]
    if len(links) != 1:
        raise KeyError(f"Связь '{prefix}': {'не найдена' if not links else 'неоднозначный id'}")
    return links[0]


def set_status(book_name: str, prefix: str, status: str) -> IdentityLink:
    repo = BookRepository(book_name)
    with repo.get_session() as session:
        link = _get_by_prefix(session, book_name, prefix)
        link.status, link.decided_at = status, datetime.now(timezone.utc)
        session.add(link)
        session.commit()
        session.refresh(link)
    if link.status == "confirmed" and link.voice_shift is not None:
        apply_voice_shift(repo, link)
    return link


def set_policy(book_name: str, prefix: str, policy: str) -> IdentityLink:
    return set_voice(book_name, prefix, policy=policy)


def set_voice(book_name: str, prefix: str, policy: Optional[str] = None,
              shift: Optional[float] = None) -> IdentityLink:
    if policy is not None and policy not in IDENTITY_VOICE_POLICIES:
        raise ValueError(f"voice_policy: {', '.join(IDENTITY_VOICE_POLICIES)}")
    if shift is not None and not (-12.0 <= shift <= 12.0):
        raise ValueError("shift: сдвиг в полутонах должен быть от -12.0 до +12.0")
    repo = BookRepository(book_name)
    with repo.get_session() as session:
        link = _get_by_prefix(session, book_name, prefix)
        if policy is not None:
            link.voice_policy = policy
        if shift is not None:
            link.voice_shift = shift
        session.add(link)
        session.commit()
        session.refresh(link)
    if link.voice_shift is not None and link.status == "confirmed":
        apply_voice_shift(repo, link)
    return link


def verify_identity_voice_similarity(source_path, target_path, tts_client=None) -> Optional[dict]:
    """
    Проверяет сходство двух голосов через ECAPA-TDNN (косинусное сходство нормированных векторов).
    Целевой коридор для масок/альтер-эго одного человека: [0.78, 0.88].
    """
    if tts_client is None:
        try:
            from services.qwen_tts_client import QwenTTSClient
            client = QwenTTSClient(base_url="http://localhost:9233")
            if client.check_health():
                tts_client = client
        except Exception:
            tts_client = None
    if tts_client is None:
        return None

    from pathlib import Path
    source_path, target_path = Path(source_path), Path(target_path)
    if not source_path.exists() or not target_path.exists():
        return None

    try:
        emb_src = tts_client.speaker_embedding(source_path)
        emb_tgt = tts_client.speaker_embedding(target_path)
    except Exception as e:
        logger.warning(f"ECAPA guardrail: ошибка при получении эмбеддингов ({e})")
        return None
    if not emb_src or not emb_tgt:
        return None

    sim = round(float(sum(x * y for x, y in zip(emb_src, emb_tgt))), 3)
    if sim < IDENTITY_SIMILARITY_MIN:
        verdict = "too_different"
        passed = False
    elif sim > IDENTITY_SIMILARITY_MAX:
        verdict = "too_similar"
        passed = False
    else:
        verdict = "ok"
        passed = True

    return {
        "similarity": sim,
        "passed": passed,
        "verdict": verdict,
        "min": IDENTITY_SIMILARITY_MIN,
        "max": IDENTITY_SIMILARITY_MAX,
    }


def apply_voice_shift(repo: BookRepository, link: IdentityLink, tts_client=None):
    """
    Создает образец голоса для маски: сдвиг высоты образца истинной личности через rubberband
    (с сохранением формант: formant=preserved). Сохраняет reference.wav и reference.txt
    в storage/voices/<id>/, валидирует через ECAPA guardrail и назначает persona.voice_base.
    """
    if link.voice_shift is None:
        return None
    import subprocess
    from pathlib import Path
    import config
    from core import casting, path_manager
    with repo.get_session() as session:
        true_char = session.get(Character, link.true_id)
        persona_char = session.get(Character, link.persona_id)
        if not true_char or not persona_char:
            return None
        if not true_char.voice_base:
            logger.info(f"Сдвиг голоса: у '{true_char.name}' еще нет voice_base — будет применен при кастинге")
            return None
        source_path = path_manager.get_voice_path(true_char.voice_base)
        if not source_path.exists():
            logger.info(f"Сдвиг голоса: файл референса '{source_path}' не найден")
            return None

        target_voice_id = casting.voice_id_for(repo.book_id, persona_char.name)
        target_dir = config.VOICES_DIR / target_voice_id
        target_dir.mkdir(parents=True, exist_ok=True)
        target_path = target_dir / "reference.wav"

        pitch_ratio = 2 ** (link.voice_shift / 12)
        rb_filter = f"rubberband=pitch={pitch_ratio:.4f}:formant=preserved"
        cmd = ["ffmpeg", "-y", "-loglevel", "error", "-i", str(source_path), "-af", rb_filter, str(target_path)]
        try:
            subprocess.run(cmd, check=True)
        except Exception as e:
            logger.error(f"Ошибка при создании сдвинутого голоса через ffmpeg rubberband: {e}")
            return None

        txt_source = source_path.with_suffix(".txt")
        if txt_source.exists():
            target_path.with_suffix(".txt").write_text(txt_source.read_text(encoding="utf-8"), encoding="utf-8")
        else:
            target_path.with_suffix(".txt").write_text(casting.SAMPLE_TEXT, encoding="utf-8")

        persona_char.voice_base = target_voice_id
        vc = dict(persona_char.voice_casting or {})
        vc.update(status=casting.STATUS_DESIGNED, voice_id=target_voice_id,
                  shift=link.voice_shift, source_voice=true_char.voice_base)

        guardrail = verify_identity_voice_similarity(source_path, target_path, tts_client)
        if guardrail:
            vc.update(identity_similarity=guardrail["similarity"],
                      guardrail_passed=guardrail["passed"],
                      guardrail_verdict=guardrail["verdict"])
            if not guardrail["passed"]:
                logger.warning(
                    f"⚠️ Guardrail ECAPA: сходство голоса маски '{persona_char.name}' с '{true_char.name}' "
                    f"составляет {guardrail['similarity']:.3f} ({guardrail['verdict']}) — вне целевого коридора [{IDENTITY_SIMILARITY_MIN}, {IDENTITY_SIMILARITY_MAX}]"
                )
            else:
                logger.info(
                    f"✅ Guardrail ECAPA: сходство голоса маски '{persona_char.name}' с '{true_char.name}' "
                    f"= {guardrail['similarity']:.3f} (в коридоре [{IDENTITY_SIMILARITY_MIN}, {IDENTITY_SIMILARITY_MAX}])"
                )

        persona_char.voice_casting = vc
        session.add(persona_char)
        session.commit()
        logger.info(f"Голос для маски '{persona_char.name}' создан со сдвигом {link.voice_shift:+g} из '{true_char.name}' ({target_voice_id})")
        return target_path


def check_identity_guardrails(book_name: str, tts_client=None) -> dict:
    """
    Проверяет все подтвержденные связи масок (same_person, possession) в книге на соответствие
    коридору схожести ECAPA [0.78, 0.88].
    """
    repo = BookRepository(book_name)
    chars = {c.id: c for c in repo.get_characters()}
    with repo.get_session() as session:
        links = session.exec(select(IdentityLink).where(
            IdentityLink.book_id == book_name,
            IdentityLink.status == "confirmed"
        )).all()

    from core import path_manager
    rows = []
    for l in links:
        if l.relation not in ("same_person", "possession"):
            continue
        persona = chars.get(l.persona_id)
        true_char = chars.get(l.true_id)
        if not persona or not true_char:
            continue
        p_voice = persona.voice_base
        t_voice = true_char.voice_base
        p_path = path_manager.get_voice_path(p_voice) if p_voice else None
        t_path = path_manager.get_voice_path(t_voice) if t_voice else None
        has_audio = bool(p_path and p_path.exists() and t_path and t_path.exists())
        guardrail = None
        if has_audio:
            guardrail = verify_identity_voice_similarity(t_path, p_path, tts_client)

        rows.append({
            "id": str(l.id)[:8],
            "persona": persona.name,
            "true": true_char.name,
            "shift": f"{l.voice_shift:+g}" if l.voice_shift is not None else "—",
            "similarity": guardrail["similarity"] if guardrail else None,
            "status": ("ok" if guardrail["passed"] else guardrail["verdict"]) if guardrail else ("нет аудио" if not has_audio else "ошибка"),
            "passed": guardrail["passed"] if guardrail else False,
        })
    return {"book": book_name, "checks": rows}


def add_human_link(book_name: str, persona: str, true: str, relation: str,
                   revealed_in: Optional[str], shift: Optional[float] = None) -> IdentityLink:
    if shift is not None and not (-12.0 <= shift <= 12.0):
        raise ValueError("shift: сдвиг в полутонах должен быть от -12.0 до +12.0")
    repo = BookRepository(book_name)
    chars = {c.name: c for c in repo.get_characters()}
    if persona not in chars or true not in chars:
        raise KeyError(f"Нет персонажа: {[n for n in (persona, true) if n not in chars]}")
    with repo.get_session() as session:
        link = _find_pair(session, book_name, chars[persona].id, chars[true].id) or IdentityLink(book_id=book_name)
        link.persona_id, link.true_id = chars[persona].id, chars[true].id
        link.relation, link.revealed_in, link.source = relation, revealed_in, "human"
        link.status, link.decided_at = "confirmed", datetime.now(timezone.utc)
        if shift is not None:
            link.voice_shift = shift
        session.add(link)
        session.commit()
        session.refresh(link)
    if link.voice_shift is not None:
        apply_voice_shift(repo, link)
    return link
