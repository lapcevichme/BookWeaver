# BookWeaver Utilities

Набор утилит для автоматизации сбора контента, очистки данных и подготовки ассетов (аудио/текст) для проекта.

## Начало работы

### 1. Установка зависимостей

1. **Python-библиотеки:**
   ```bash
   pip install requests pydub pygame
   ```

2. **FFmpeg** (обязательно для аудио):
    * Windows: `winget install -e --id Gyan.FFmpeg`
    * Linux: `sudo apt install ffmpeg`
    * macOS: `brew install ffmpeg`

### 2. Настройка .env

Добавьте в `.env` в корне проекта ключи:

```bash
RANOBELIB_USER_TOKEN=твой_bearer
ELEVENLABS_API_KEY=твой_api_key
```

> [!TIP]
> Токен Ranobelib можно достать из большинства запросов с их сайта. Посмотреть код страницы -> Network ->
> Перезагрузить станицу. Ориентируйтесь на xhr запросы -> Тык -> Снизу Request Headers где будет Bearer (очень длинная
> строка)
---

## Утилиты

### 1. Загрузчик Ранобэ (ranobelib_loader.py)

Парсит книги, сохраняя структуру, главы, обложку и метаданные.

**Запуск:**

```bash
# Интерактивно
python ranobelib_loader.py

# По прямой ссылке
python ranobelib_loader.py https://ranobelib.me/ru/book/12345--title
```

**Результат:** Папка в `input/` с файлами `chapter_X.txt` и `metadata.json`.

> [!IMPORTANT]
> Не очень стабильный инструмент. RanobeLib любят поменять адрес для картинок или пути в API. Также они нередко банят
> VPN (или наоборот не пускают без него).

---

### 2. Очистка Текста (text_cleaner.py)

Нормализует текст: удаляет NBSP, zero-width spaces, чистит абзацы.

**Запуск:**

```bash
# Очистить папку input/raw_text (по умолчанию)
python text_cleaner.py

# Указать папку и искать рекурсивно
python text_cleaner.py "путь/к/книгам" -r
```

---

### 3. Импорт Аудио (import_audio.py)

Подготавливает аудио (эмбиент, SFX): нормализует громкость, обрезает тишину, делает Fade-In/Out.

**Запуск:**

```bash
# Обработать input/raw_audio -> assets/ambient
python import_audio.py

# Задать громкость (например, -14dB для голоса)
python import_audio.py --db -14.0
```

---

### 4. Подбор Голосов (voice_picker.py)

Кастинг голосов через ElevenLabs по тегам.

**Запуск:**

```bash
python voice_picker.py
```

Следуйте инструкциям в консоли для прослушивания и сохранения вариантов.

### 5. Менеджер Зависимостей (dependencies.py)

Автоматически сканирует импорты в проекте и обновляет `requirements.txt`

**Запуск:**

```bash
# Просто собрать список библиотек
python dependencies.py

# Собрать и зафиксировать текущие версии (рекомендуется)
python dependencies.py --freeze
```