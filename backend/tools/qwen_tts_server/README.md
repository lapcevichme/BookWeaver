# Qwen3-TTS Dedicated Microservice

Микросервис синтеза речи на базе **Qwen3-TTS** (Alibaba Group) под лицензией Apache 2.0.
Служит заменой CosyVoice для проекта BookWeaver.

## Возможности
- **Zero-Shot Voice Cloning**: Точное клонирование любого персонажа за 3 секунды референсного аудио.
- **Поддержка Русского языка**: 10 языков мира, включая русский.
- **Кэширование профилей голоса**: Повторно использует сжатый эмбеддинг спикера без лишней перегрузки GPU.
- **REST API**: Совместим с `POST /api/tts` и `GET /api/status`.

---

## Варианты запуска

### Вариант 1: Через Docker (рекомендуется для сервера/друга с GPU)

```bash
cd tools/qwen_tts_server
docker compose up --build -d
```

Сервер поднимется на порту `http://localhost:9233`.

### Вариант 2: Локально через Python (Conda / Venv)

Требуется Python 3.10-3.12 и видеокарта NVIDIA.

```bash
cd tools/qwen_tts_server
pip install -r requirements.txt
python server.py
```

### Настройка модели
В `docker-compose.yml` или в переменных окружения можно выбрать модель:
- `Qwen/Qwen3-TTS-12Hz-1.7B-Base` — максимальное качество (рекомендуется для 8GB+ VRAM).
- `Qwen/Qwen3-TTS-12Hz-0.6B-Base` — легковесная версия (поместится в 4-6GB VRAM).
