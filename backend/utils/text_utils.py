import json
import re
from pathlib import Path
import math
from typing import List, Tuple


def clean_raw_text(content: str) -> str:
    """
    Очищает сырой текст книги от типографического мусора:
    - Удаляет неразрывные пробелы (\u00A0) и нульширинные пробелы (\u200b).
    - Обрезает пробелы в конце каждой строки.
    - Схлопывает 3+ переноса строк в двойной перенос.
    """
    if not content:
        return ""
    content = content.replace('\u00A0', ' ').replace('\u200b', '')
    lines = [line.rstrip() for line in content.splitlines()]
    content = '\n'.join(lines)
    content = re.sub(r'\n{3,}', '\n\n', content)
    return content.strip() + '\n'


def cleanup_filename(name: str) -> str:
    """
    Очищает строку, чтобы ее можно было безопасно использовать в качестве имени файла.
    - Удаляет недопустимые символы.
    - Заменяет пробелы на подчеркивания.
    - Приводит к нижнему регистру.
    """
    if not name:
        return "unknown"
    name = re.sub(r'[\\/*?:"<>|]', "", name)
    name = re.sub(r'\s+', '_', name)
    name = re.sub(r'_+', '_', name)
    name = name.strip('_')
    name = name.lower()
    return name if name else "unknown"


def load_pronunciation_dictionary(path: Path) -> dict:
    """
    Загружает словарь произношений из JSON файла.
    """
    if not path.exists():
        return {}
    with open(path, 'r', encoding='utf-8') as f:
        return json.load(f)


_TTS_TAG_RE = re.compile(r'<\|\s*([a-zA-Z_]+)\s*\|>')


def convert_tts_tags(text: str) -> str:
    """
    Приводит теги эмоций (<|pause|>, <|sigh|> …) к тому, что понимает Qwen3-TTS.

    Тегов Qwen не знает и читает их вслух («Поза», «Сих»). Пауза — пустая строка:
    так получается ~0.4 с против 0.04 с у точки (docs/experiments/2026-11-11_voice-research.md).
    Остальные теги вырезаются: на клоне их нечем выразить.
    """
    if '<|' not in text:
        return text
    text = _TTS_TAG_RE.sub(lambda m: '\n\n' if m.group(1).lower() == 'pause' else ' ', text)
    text = re.sub(r'[ \t]*\n\s*\n[ \t]*', '\n\n', text)
    text = re.sub(r'[ \t]{2,}', ' ', text)
    return text.strip()


def _cer_normalize(text: str) -> str:
    """Только буквы и цифры, нижний регистр, ё = е: пунктуацию и регистр Whisper ставит как хочет."""
    return re.sub(r"[\W_]+", "", (text or "").lower().replace("ё", "е"))


def calculate_cer(reference: str, hypothesis: str) -> float:
    """
    Доля ошибок по символам (расстояние Левенштейна / длина эталона) после нормализации.
    Используется для проверки озвучки: эталон — текст реплики, гипотеза — распознанное Whisper.
    """
    ref, hyp = _cer_normalize(reference), _cer_normalize(hypothesis)
    if not ref:
        return 0.0 if not hyp else 1.0
    prev = list(range(len(hyp) + 1))
    for i, rc in enumerate(ref, 1):
        cur = [i]
        for j, hc in enumerate(hyp, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (rc != hc)))
        prev = cur
    return prev[-1] / len(ref)


_LATIN_STRESSED = str.maketrans("áéóúíýÁÉÓÚÍÝ", "аеоуиуАЕОУИУ")
_CYR = "а-яёА-ЯЁ"


def strip_stress_marks(text: str) -> str:
    """
    Убирает знаки ударения: Qwen3-TTS их не понимает и читает ударную гласную как «йо»/«ю»
    («го́род» → «гойород», docs/experiments/2026-11-12_from-zero-cli.md). Латинские á/é/ó
    внутри русских слов (старый словарь: «Маомáо») заменяются русскими буквами.
    """
    text = text.replace("\u0301", "").replace("\u0300", "")
    return re.sub(rf"(?<=[{_CYR}])[áéóúíýÁÉÓÚÍÝ]|[áéóúíýÁÉÓÚÍÝ](?=[{_CYR}])",
                  lambda m: m.group(0).translate(_LATIN_STRESSED), text)


def preprocess_text_for_tts(text: str, dictionary: dict) -> str:
    """
    Полный конвейер предобработки текста для TTS:
    1. Заменяет теги пауз переносом строки, остальные теги вырезает.
    2. Применяет словарь произношений.
    3. Убирает знаки ударения (Qwen их не понимает).
    4. Очищает от нежелательных символов и пунктуационных дублей.
    """
    text = convert_tts_tags(text)
    for word, pronunciation in dictionary.items():
        text = re.sub(r'\b' + re.escape(word) + r'\b', pronunciation, text, flags=re.IGNORECASE)

    text = strip_stress_marks(text)
    text = text.replace('«', '').replace('»', '').replace('"', '')
    text = text.replace('!.', '!').replace('.!', '!')
    text = text.replace('?.', '?').replace('.?', '?')
    text = text.strip()

    return text


def smart_split_text(text: str, chunk_size: int = 10000, overlap: int = 300) -> List[str]:
    """
    УСТАРЕЛО: используйте split_text_for_llm.
    Ищет абзацы по пустым строкам, но в raw_text абзацы разделены одиночным \n,
    поэтому на практике вся глава режется ровно по chunk_size посреди предложения.
    Оставлено для сравнения в экспериментах.

    Разбивает текст на чанки, стараясь не разрывать абзацы.

    Args:
        text: Исходный текст.
        chunk_size: Максимальный размер чанка.
        overlap: Размер перекрытия из конца предыдущего чанка для сохранения контекста.
    """
    if not text:
        return []

    if len(text) <= chunk_size:
        return [text]

    paragraphs = re.split(r'\n\s*\n', text)
    if not paragraphs:
        paragraphs = text.split('\n')

    chunks = []
    current_chunk = []
    current_length = 0

    for para in paragraphs:
        para_len = len(para)

        if para_len > chunk_size:
            if current_chunk:
                chunks.append("\n\n".join(current_chunk))
                current_chunk = []
                current_length = 0

            sub_chunks = [para[i:i + chunk_size] for i in range(0, len(para), chunk_size)]
            chunks.extend(sub_chunks)
            continue

        if current_length + para_len > chunk_size and current_chunk:
            full_chunk_text = "\n\n".join(current_chunk)
            chunks.append(full_chunk_text)

            overlap_text = ""
            if 0 < overlap < len(full_chunk_text):
                overlap_text = f"...{full_chunk_text[-overlap:]}\n--- (контекст) ---\n"

            current_chunk = [overlap_text + para]
            current_length = len(overlap_text) + para_len
        else:
            current_chunk.append(para)
            current_length += para_len + 2

    if current_chunk:
        chunks.append("\n\n".join(current_chunk))

    return chunks

_SENTENCE_BOUNDARY = re.compile(r'(?<=[.!?…])["»”)]*\s+')


def _split_long_line(line: str, chunk_size: int) -> List[str]:
    """Режет слишком длинную строку по границам предложений (одно предложение не режется)."""
    parts, buf = [], ""
    for sentence in _SENTENCE_BOUNDARY.split(line):
        if buf and len(buf) + 1 + len(sentence) > chunk_size:
            parts.append(buf)
            buf = sentence
        else:
            buf = f"{buf} {sentence}" if buf else sentence
    if buf:
        parts.append(buf)
    return parts


def _context_tail(chunk: str, context_chars: int) -> str:
    """Хвост чанка для контекста, начинающийся с границы строки или предложения."""
    if context_chars <= 0 or not chunk:
        return ""
    tail = chunk[-context_chars:]
    if len(tail) == len(chunk):
        return tail.strip()
    newline = tail.find("\n")
    if newline != -1:
        return tail[newline + 1:].strip()
    match = _SENTENCE_BOUNDARY.search(tail)
    return tail[match.end():].strip() if match else tail.strip()


def split_text_for_llm(text: str, chunk_size: int = 15000, context_chars: int = 600) -> List[Tuple[str, str]]:
    """
    Делит главу на чанки для LLM, не разрывая строки (абзацы) и предложения.

    - Единица деления — строка (в raw_text абзацы разделены одиночным \n).
      Строки длиннее chunk_size режутся по границам предложений.
    - Чанки выравниваются по размеру: глава на 43k при chunk_size=15k дает три
      чанка по ~14k, а не 15k + 15k + 13k или, хуже, 15k + 15k + 300.
    - Возвращает пары (previous_context, chunk_text). previous_context — хвост
      предыдущего чанка: его передают модели отдельно, как справку, а не как
      часть текста, иначе он попадет в сценарий дважды.
    """
    if not text:
        return []
    if len(text) <= chunk_size:
        return [("", text)]

    units: List[str] = []
    for line in text.split("\n"):
        units.extend([line] if len(line) <= chunk_size else _split_long_line(line, chunk_size))

    target = len(text) / math.ceil(len(text) / chunk_size)
    chunks: List[str] = []
    current: List[str] = []
    current_len = 0
    for unit in units:
        added = len(unit) + (1 if current else 0)
        if current and (current_len + added > chunk_size or current_len >= target):
            chunks.append("\n".join(current))
            current, current_len = [unit], len(unit)
        else:
            current.append(unit)
            current_len += added
    if current:
        chunks.append("\n".join(current))

    result = []
    for i, chunk in enumerate(chunks):
        context = _context_tail(chunks[i - 1], context_chars) if i > 0 else ""
        result.append((context, chunk))
    return result
