# BookWeaver

**Читалка для длинных книг и ранобе с ИИ-справочником персонажей и пересказами без спойлеров.**

Когда в книге сотни героев, десятки томов и куча прозвищ, легко забыть, кто есть кто и что было сто глав назад. BookWeaver помнит, на какой главе ты остановился, и показывает только то, что уже произошло по сюжету.

---

## Что внутри

- **Справочник персонажей без спойлеров:** карточки героев, факты из биографии, прозвища и раскрытие тайн личности привязаны к текущей главе. Все, что случится дальше, скрыто.
- **Удобная читалка:** запоминает позицию чтения и закладки, подсвечивает имена персонажей прямо в тексте (по клику открывается краткая карточка), настраивается шрифт, размер и ширина колонки, есть быстрый поиск по главам и героям (`Ctrl+K`).
- **Пересказы («Вспомнить сюжет»):** краткое содержание прочитанных глав и завершенных томов - удобно, если вернулся к книге после долгого перерыва.
- **Режим только для чтения (`BOOKWEAVER_READ_ONLY=true`):** позволяет открыть библиотеку как публичное демо без возможности что-то изменить или запустить генерацию.

---

## Быстрый запуск

```bash
git clone https://github.com/lapcevichme/BookWeaver.git
cd BookWeaver

# 1. Импортируйте и обработайте книгу через CLI (или положите готовые проекты в ./output)
cd backend
uv run python cli.py import ../input/books/my_book.epub my_book
uv run python cli.py run my_book --skip-scenario --skip-speakers
cd ..

# 2. Запустите сервер и веб-читалку
docker compose -f docker-compose.lite.yml up --build -d
```

После запуска читалка откроется на `http://localhost:8080`.

### Как делиться

Если хотите выложить уже обработанные книги из `./output` в открытый доступ:

```bash
BOOKWEAVER_READ_ONLY=true WEB_PORT=80 docker compose -f docker-compose.lite.yml up --build -d
```

Так сделали потому что на бэке нет авторизации. А если кто-то посторонний сможет у вас запустить анализ какой-то книги, будет весело)

---

## Настройка `.env`

Скопируйте `.env.example` в `.env`:

```bash
cp .env.example .env
```

Основные параметры:

| Переменная | Зачем нужна |
| :--- | :--- |
| `LLM_PROVIDER` | Провайдер моделей: `openrouter` (по умолчанию) или `google` |
| `OPENROUTER_API_KEY` | API-ключ OpenRouter |
| `GOOGLE_API_KEY` | API-ключ Google AI Studio |
| `FAST_MODEL_NAME` / `POWERFUL_MODEL_NAME` | Модели LLM (по умолчанию `deepseek/deepseek-v4.1-flash-1017`) |
| `BOOKWEAVER_READ_ONLY` | `true` — запретить любые изменения через API (`false` по умолчанию) |

---

## Структура проекта

```text
BookWeaver/
├── backend/                  # Python 3.12+, uv, FastAPI, SQLModel, AI-пайплайны
│   ├── api/                  # REST API для библиотеки, читалки, персонажей и пересказов
│   ├── core/                 # Работа с БД книги (SQLite), модели данных, очередь задач
│   ├── pipelines/            # Извлечение персонажей, пересказы, связывание личностей
│   ├── cli.py                # Консольная утилита для импорта и обработки книг
│   └── Dockerfile.lite       # Легкая сборка бэкенда для веб-читалки
├── frontend/                 # React 19, TypeScript, Vite, Tailwind, Material Design 3
│   ├── src/features/         # library, reader, codex, recap, book
│   └── Dockerfile            # Сборка фронтенда и раздача через Nginx
└── docker-compose.lite.yml   # Единый запуск бэкенда и веб-читалки
```

## Тесты

```bash
# Бэкенд
cd backend && uv run python tests/run_all_tests.py

# Фронтенд
cd frontend && npm test && npm run build
```

> Предыдущая мобильная версия проекта (Android / Kotlin) сохранена в ветке [`legacy-mobile`](../../tree/legacy-mobile) и релизе `v1.0.0`.
