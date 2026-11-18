import os
from pathlib import Path
from dotenv import load_dotenv

load_dotenv()

BASE_DIR = Path(__file__).parent.resolve()

# Input / Output
INPUT_DIR = BASE_DIR / "input"
OUTPUT_DIR = BASE_DIR / "output"
EXPORT_DIR = BASE_DIR / "export"
TEMP_DIR = BASE_DIR / "temp"
STORAGE_DIR = BASE_DIR / "storage"
BOOKS_DIR_NAME = "books"

# RAW
RAW_AUDIO_DIR = INPUT_DIR / "raw_audio"
RAW_TEXT_DIR = INPUT_DIR / "raw_text"

# Logging
LOGS_DIR = BASE_DIR / "logs"
LOG_APP_FILE = LOGS_DIR / "app.log"
LOG_DEBUG_FILE = LOGS_DIR / "debug.log"

# Settings
SETTINGS_FILE = BASE_DIR / "settings.json"

# Assets
ASSETS_DIR = BASE_DIR / "assets"
AMBIENT_DIR = ASSETS_DIR / "ambient"
SFX_DIR = ASSETS_DIR / "sfx"
PRONUNCIATION_DICT_FILE = ASSETS_DIR / "pronunciation_dictionary.json"
AMBIENT_LIBRARY_FILE = ASSETS_DIR / "ambient_library.json"
EMOTION_REFERENCE_LIBRARY_FILE = ASSETS_DIR / "emotion_reference_library.json"
SFX_LIBRARY_FILE = ASSETS_DIR / "sfx_library.json"
COMFY_WORKFLOW_FAST = ASSETS_DIR / "workflow_fast.json"
COMFY_WORKFLOW_HQ = ASSETS_DIR / "workflow_hq.json"
VOICES_DIR = STORAGE_DIR / "voices"

# Api (Deprecated)
SERVER_PORT = int(os.environ.get("SERVER_PORT", 8000))

# LLM Settings Defaults
# Проверенная конфигурация (ноябрь 2026): DeepSeek V4.1 Flash через OpenRouter, провайдер DeepInfra fp8.
# Замеры и обоснование — docs/experiments/. Настройки в output/system.db переопределяют эти значения.
_LLM_PROVIDER_DEFAULT = os.environ.get("LLM_PROVIDER", "openrouter")  # openrouter / google
_FAST_MODEL_NAME_DEFAULT = os.environ.get("FAST_MODEL_NAME", "deepseek/deepseek-v4.1-flash-1017")
_POWERFUL_MODEL_NAME_DEFAULT = os.environ.get("POWERFUL_MODEL_NAME", "deepseek/deepseek-v4.1-flash-1017")
_OPENROUTER_PROVIDER_ORDER_DEFAULT = os.environ.get("OPENROUTER_PROVIDER_ORDER", "deepinfra/fp8")  # flex в 2-3 раза медленнее
_OPENROUTER_ALLOW_FALLBACKS_DEFAULT = os.environ.get("OPENROUTER_ALLOW_FALLBACKS", "true").lower() in ("true", "1", "yes")
_OPENROUTER_REASONING_EFFORT_DEFAULT = os.environ.get("OPENROUTER_REASONING_EFFORT", "")
# emotion_analysis без reasoning: -42% стоимости без потери качества (2026-11-06_reasoning-by-stage.md);
# sound_design с reasoning: без него хуже подбор SFX.
_OPENROUTER_REASONING_BY_STAGE_DEFAULT = os.environ.get(
    "OPENROUTER_REASONING_BY_STAGE",
    '{"emotion_analysis": "none", "character_recon": "low", "character_patch": "low", "identity_scan": "low", "identity_judge": "low", "identity_scope": "low"}'
)
_SCENARIO_MIN_COVERAGE_DEFAULT = 0.90
# TTS Settings Defaults
_TTS_ENGINE_DEFAULT = os.environ.get("TTS_ENGINE", "qwen")
_TTS_AUDIO_GAP_MS_DEFAULT = int(os.environ.get("TTS_AUDIO_GAP_MS", 450))
_SFX_DEFAULT_GAIN_DB_DEFAULT = float(os.environ.get("SFX_DEFAULT_GAIN_DB", -6.0))
# Проверка озвучки Whisper base: на хорошем аудио CER до ~0.25, на именах до 0.43; дефекты (утечка
# образца, повторы, мусор) — 0.3–2.6. Калибровка 2026-11-12 на 149 репликах (docs/experiments/2026-11-12_from-zero-cli.md).
_TTS_CER_THRESHOLD_DEFAULT = 0.35   # выше — попытка бракуется и повторяется
_TTS_CER_ACCEPT_MAX_DEFAULT = 0.50  # после всех попыток лучшая принимается, если не хуже этого
# Синтез одной реплики: на GPU секунды, на CPU (0.6B, RTF ~8) реплика на 12 с идет ~100 с — 90 с не хватало
_TTS_REQUEST_TIMEOUT_DEFAULT = 600
_SCENARIO_MAX_ATTEMPTS_DEFAULT = 3
_SCENARIO_PARALLEL_CHUNKS_DEFAULT = 4
_CHARACTER_REF_STYLE_DEFAULT = "name"  # numeric: c1, c2... | name: каноническое имя персонажа
_CHARACTER_PATCH_BATCH_DEFAULT = 8
# rewrite: патч переписывает описание каждого упомянутого персонажа (до 2026-11-12);
# card: описание — карточка личности без событий, патч только для тех, у кого глава ее изменила;
# facts: card + короткий портрет (бюджет) и факты отдельным списком (docs/experiments/2026-11-13_character-cards.md)
_CHARACTER_DESCRIPTION_MODE_DEFAULT = os.environ.get("CHARACTER_DESCRIPTION_MODE", "facts")
_CHARACTER_CALL_ATTEMPTS_DEFAULT = 3  # попыток на разведку и на каждую пачку патча
_LLM_REQUEST_TIMEOUT_DEFAULT = 300  # сек (5 минут); главы обычно генерируются от 15 до 90 с
_LLM_MAX_NETWORK_RETRIES_DEFAULT = 6  # попыток при сетевых/транзиентных ошибках (окно ожидания ~3-4 мин)
_LLM_RETRY_INITIAL_DELAY_DEFAULT = 2.0
_LLM_RETRY_MAX_DELAY_DEFAULT = 60.0
_LLM_RETRY_BACKOFF_FACTOR_DEFAULT = 2.0
_LLM_RETRY_JITTER_DEFAULT = 0.2
# Таймаут по этапам ≈ 2x максимума реальных вызовов в прогонах ноября 2026 (DeepSeek, DeepInfra):
# recon макс. 108 с, patch (с reasoning: low) 20-60 с, summary 65 с, sound_design (с reasoning) 159 с.
_LLM_TIMEOUT_BY_STAGE_DEFAULT = {
    "character_recon": 240,
    "character_patch": 360,
    "chapter_summary": 180,
    "volume_summary": 180,
    "emotion_analysis": 180,
    "sound_design": 360,
    "speaker_resolution": 300,
    "casting": 300,
    "ambient_design": 120,
    "character_card_compact": 120,
    "identity_scan": 360,
    "identity_judge": 300,
    "identity_scope": 240,
}
_SCENARIO_CHUNK_SIZE_DEFAULT = 45_000  # страховка для гигантских глав; см. docs/experiments/2026-11-06_chunking.md

# Identity Links Defaults
_IDENTITY_AUTO_CONFIRM_DEFAULT = True
_IDENTITY_AUTO_CONFIRM_THRESHOLD_DEFAULT = 0.95
_IDENTITY_DEFAULT_VOICE_POLICY_DEFAULT = "switch_after_reveal"

# Temp Defaults
_ANALYZER_LLM_TEMPERATURE_DEFAULT = 0.5
_GENERATOR_LLM_TEMPERATURE_DEFAULT = 0.5
_SUMMARY_LLM_TEMPERATURE_DEFAULT = 0.5

# ComfyUI Settings Defaults
_COMFY_SERVER_ADDRESS_DEFAULT = os.environ.get("COMFY_SERVER_ADDRESS", "127.0.0.1:8188")
COMFY_DEFAULT_WIDTH = 512
COMFY_DEFAULT_HEIGHT = 768
COMFY_NODE_MAPPING = {
    "positive_prompt_node_id": "3",
    "negative_prompt_node_id": "4",
    "empty_latent_node_id": "5",
    "ksampler_node_id": "6"
}

# TTS Defaults
_TTS_API_URL_DEFAULT = os.environ.get("TTS_API_URL") or os.environ.get("COSYVOICE_API_URL", "http://localhost:9233")
_COSYVOICE_API_URL_DEFAULT = _TTS_API_URL_DEFAULT

# Ranobelib Defaults
RANOBELIB_API_BASE_URL = "https://api.cdnlibs.org/api"
RANOBELIB_IMAGE_BASE_URL = "https://lib.social"
RANOBELIB_USER_TOKEN = os.environ.get("RANOBELIB_USER_TOKEN", "")

RANOBELIB_HEADERS = {
    'User-Agent': 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36',
    'Referer': 'https://ranobelib.me/',
    'Origin': 'https://ranobelib.me',
    'Site-Id': '3',
    'Client-Time-Zone': 'Asia/Novosibirsk',
    'Content-Type': 'application/json',
    'sec-ch-ua-platform': '"Linux"',
    'sec-ch-ua': '"Chromium";v="140", "Not=A?Brand";v="24", "Google Chrome";v="140"',
    'sec-ch-ua-mobile': '?0'
}

# ElevenLabs Defaults
_ELEVENLABS_API_KEY_DEFAULT = os.environ.get("ELEVENLABS_API_KEY", "")
_GOOGLE_API_KEY_DEFAULT = os.environ.get("GOOGLE_API_KEY", "")
_OPENROUTER_API_KEY_DEFAULT = os.environ.get("OPENROUTER_API_KEY", "")
ELEVENLABS_API_URL = "https://api.elevenlabs.io/v1/shared-voices"
TEMP_PREVIEWS_DIR = TEMP_DIR / "voice_previews"
SELECTED_VOICES_DIR = VOICES_DIR / "selected_refs"

import time

_db_settings_cache = {}
_db_settings_cache_time = 0.0
_CACHE_TTL_SECONDS = 2.0

def invalidate_settings_cache():
    global _db_settings_cache, _db_settings_cache_time
    _db_settings_cache.clear()
    _db_settings_cache_time = 0.0

def _get_db_setting(key: str, default):
    global _db_settings_cache, _db_settings_cache_time
    now = time.time()
    if now - _db_settings_cache_time < _CACHE_TTL_SECONDS and key in _db_settings_cache:
        return _db_settings_cache[key]

    db_path = OUTPUT_DIR / "system.db"
    if not db_path.exists():
        return default
    import sqlite3
    import json
    conn = None
    try:
        conn = sqlite3.connect(str(db_path), timeout=5.0)
        cursor = conn.cursor()
        cursor.execute("SELECT value_json FROM systemsetting WHERE key = ?", (key,))
        row = cursor.fetchone()
        if row:
            val = json.loads(row[0])
            _db_settings_cache[key] = val
            _db_settings_cache_time = now
            return val
    except (sqlite3.OperationalError, sqlite3.DatabaseError, json.JSONDecodeError):
        pass
    finally:
        if conn:
            conn.close()
    return default

def __getattr__(name: str):
    if name == "LLM_PROVIDER":
        return _get_db_setting("llm_provider", _LLM_PROVIDER_DEFAULT)
    elif name == "FAST_MODEL_NAME":
        return _get_db_setting("fast_model", _FAST_MODEL_NAME_DEFAULT)
    elif name == "POWERFUL_MODEL_NAME":
        return _get_db_setting("powerful_model", _POWERFUL_MODEL_NAME_DEFAULT)
    elif name == "OPENROUTER_PROVIDER_ORDER":
        return _get_db_setting("openrouter_provider_order", _OPENROUTER_PROVIDER_ORDER_DEFAULT)
    elif name == "OPENROUTER_ALLOW_FALLBACKS":
        val = _get_db_setting("openrouter_allow_fallbacks", _OPENROUTER_ALLOW_FALLBACKS_DEFAULT)
        return val if isinstance(val, bool) else str(val).lower() in ("true", "1", "yes")
    elif name == "OPENROUTER_REASONING_EFFORT":
        return _get_db_setting("openrouter_reasoning_effort", _OPENROUTER_REASONING_EFFORT_DEFAULT)
    elif name == "OPENROUTER_REASONING_BY_STAGE":
        return _get_db_setting("openrouter_reasoning_by_stage", _OPENROUTER_REASONING_BY_STAGE_DEFAULT)
    elif name == "TTS_CER_THRESHOLD":
        return float(_get_db_setting("tts_cer_threshold", _TTS_CER_THRESHOLD_DEFAULT))
    elif name == "TTS_CER_ACCEPT_MAX":
        return float(_get_db_setting("tts_cer_accept_max", _TTS_CER_ACCEPT_MAX_DEFAULT))
    elif name == "SCENARIO_MIN_COVERAGE":
        return float(_get_db_setting("scenario_min_coverage", _SCENARIO_MIN_COVERAGE_DEFAULT))
    elif name == "SCENARIO_MAX_ATTEMPTS":
        return int(_get_db_setting("scenario_max_attempts", _SCENARIO_MAX_ATTEMPTS_DEFAULT))
    elif name == "CHARACTER_REF_STYLE":
        return _get_db_setting("character_ref_style", _CHARACTER_REF_STYLE_DEFAULT)
    elif name == "CHARACTER_CALL_ATTEMPTS":
        return int(_get_db_setting("character_call_attempts", _CHARACTER_CALL_ATTEMPTS_DEFAULT))
    elif name == "CHARACTER_DESCRIPTION_MODE":
        return os.environ.get("CHARACTER_DESCRIPTION_MODE") or _get_db_setting(
            "character_description_mode", _CHARACTER_DESCRIPTION_MODE_DEFAULT)
    elif name == "CHARACTER_PATCH_BATCH":
        return int(_get_db_setting("character_patch_batch", _CHARACTER_PATCH_BATCH_DEFAULT))
    elif name == "SCENARIO_PARALLEL_CHUNKS":
        return int(_get_db_setting("scenario_parallel_chunks", _SCENARIO_PARALLEL_CHUNKS_DEFAULT))
    elif name == "LLM_TIMEOUT_BY_STAGE":
        value = _get_db_setting("llm_timeout_by_stage", _LLM_TIMEOUT_BY_STAGE_DEFAULT)
        if isinstance(value, str):
            import json as _json
            try:
                value = _json.loads(value) if value.strip() else {}
            except ValueError:
                value = {}
        return value if isinstance(value, dict) else {}
    elif name == "TTS_REQUEST_TIMEOUT":
        return float(_get_db_setting("tts_request_timeout", _TTS_REQUEST_TIMEOUT_DEFAULT))
    elif name == "TTS_ENGINE":
        return str(_get_db_setting("tts_engine", _TTS_ENGINE_DEFAULT))
    elif name == "TTS_AUDIO_GAP_MS":
        return int(_get_db_setting("tts_audio_gap_ms", _TTS_AUDIO_GAP_MS_DEFAULT))
    elif name == "SFX_DEFAULT_GAIN_DB":
        return float(_get_db_setting("sfx_default_gain_db", _SFX_DEFAULT_GAIN_DB_DEFAULT))
    elif name == "LLM_REQUEST_TIMEOUT":
        return float(_get_db_setting("llm_request_timeout", _LLM_REQUEST_TIMEOUT_DEFAULT))
    elif name == "LLM_MAX_NETWORK_RETRIES":
        return int(_get_db_setting("llm_max_network_retries", _LLM_MAX_NETWORK_RETRIES_DEFAULT))
    elif name == "LLM_RETRY_INITIAL_DELAY":
        return float(_get_db_setting("llm_retry_initial_delay", _LLM_RETRY_INITIAL_DELAY_DEFAULT))
    elif name == "LLM_RETRY_MAX_DELAY":
        return float(_get_db_setting("llm_retry_max_delay", _LLM_RETRY_MAX_DELAY_DEFAULT))
    elif name == "LLM_RETRY_BACKOFF_FACTOR":
        return float(_get_db_setting("llm_retry_backoff_factor", _LLM_RETRY_BACKOFF_FACTOR_DEFAULT))
    elif name == "LLM_RETRY_JITTER":
        return float(_get_db_setting("llm_retry_jitter", _LLM_RETRY_JITTER_DEFAULT))
    elif name == "SCENARIO_CHUNK_SIZE":
        return int(_get_db_setting("chunk_size", _SCENARIO_CHUNK_SIZE_DEFAULT))
    elif name == "IDENTITY_AUTO_CONFIRM":
        val = _get_db_setting("identity_auto_confirm", _IDENTITY_AUTO_CONFIRM_DEFAULT)
        return val if isinstance(val, bool) else str(val).lower() in ("true", "1", "yes")
    elif name == "IDENTITY_AUTO_CONFIRM_THRESHOLD":
        return float(_get_db_setting("identity_auto_confirm_threshold", _IDENTITY_AUTO_CONFIRM_THRESHOLD_DEFAULT))
    elif name == "IDENTITY_DEFAULT_VOICE_POLICY":
        return str(_get_db_setting("identity_default_voice_policy", _IDENTITY_DEFAULT_VOICE_POLICY_DEFAULT))
    elif name == "ANALYZER_LLM_TEMPERATURE":
        return float(_get_db_setting("analyzer_temp", _ANALYZER_LLM_TEMPERATURE_DEFAULT))
    elif name == "GENERATOR_LLM_TEMPERATURE":
        return float(_get_db_setting("generator_temp", _GENERATOR_LLM_TEMPERATURE_DEFAULT))
    elif name == "SUMMARY_LLM_TEMPERATURE":
        return float(_get_db_setting("summary_temp", _SUMMARY_LLM_TEMPERATURE_DEFAULT))
    elif name == "COMFY_SERVER_ADDRESS":
        return _get_db_setting("comfy_server_address", _COMFY_SERVER_ADDRESS_DEFAULT)
    elif name in ("TTS_API_URL", "COSYVOICE_API_URL"):
        db_val = _get_db_setting("tts_url", None)
        if db_val is None:
            db_val = _get_db_setting("cosyvoice_url", _TTS_API_URL_DEFAULT)
        return db_val
    elif name == "ELEVENLABS_API_KEY":
        return _get_db_setting("elevenlabs_key", _ELEVENLABS_API_KEY_DEFAULT)
    elif name == "GOOGLE_API_KEY":
        return _get_db_setting("google_api_key", _GOOGLE_API_KEY_DEFAULT)
    elif name == "OPENROUTER_API_KEY":
        return _get_db_setting("openrouter_api_key", _OPENROUTER_API_KEY_DEFAULT)
        
    raise AttributeError(f"module '{__name__}' has no attribute '{name}'")


for path in [INPUT_DIR, OUTPUT_DIR, EXPORT_DIR, TEMP_DIR, STORAGE_DIR]:
    path.mkdir(parents=True, exist_ok=True)