# BookWeaver Backend — правила разработки

- **Стек:** Python 3.12+, `uv`, FastAPI, SQLModel, Pydantic.
- **Типизация:** обязательны type hints, избегать `Any` кроме крайних случаев.
- **FastAPI и SQLModel:** соблюдать `async/await` для I/O, чётко разделять схемы Pydantic (Request/Response) и модели БД, валидировать входные данные на уровне Pydantic.
- **Тестирование:** писать юнит- и интеграционные тесты (`unittest` + `fastapi.testclient.TestClient` для эндпоинтов API), запускать `python tests/run_all_tests.py` из `backend/`.
