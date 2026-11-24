# Защита от зависаний httpx на TLS-keepalive Cloudflare и OpenRouter Fallbacks

**Дата:** 2026-11-17  
**Статус:** закрыто в кодовой базе, протестировано  
**Связано:** `docs/backlog.md` (пункт 18), `tests/test_llm_backoff.py`, `services/llm_service.py`, `config.py`

---

## 1. Проблема

В ходе долгих прогонов и генераций с reasoning (на этапах `character_recon`, `character_patch`, `chapter_summary`) был зафиксирован инцидент бесконечного зависания API-вызова:

1. **Заминка апстрима (DeepInfra):** После отправки HTTP-заголовков (`HTTP 200 OK`) бэкенд провайдера переставал отдавать токены тела ответа при расчете рассуждений.
2. **TLS-keepalive от Cloudflare:** Прокси-сервер Cloudflare, находящийся между клиентом и OpenRouter, периодически (каждые 8–15 секунд) отправлял пустые TLS-фреймы / TCP keepalive (35–105 байт).
3. **Обнуление сокетного таймаута httpx:** В `httpx` параметр `timeout` является таймаутом неактивности сокета (inactivity read timeout). При получении любого пакета от Cloudflare таймер чтения сбрасывался. В результате сокет никогда не закрывался по таймауту, и процесс зависал бесконечно (до ручного убийства через kill).
4. **Блокировка фолбэков на OpenRouter:** Настройка `_openrouter_routing()` жестко задавала `"allow_fallbacks": False`, из-за чего OpenRouter не переключал запрос на альтернативные эндпоинты (Together, Wafer, Fireworks, Chutes и др.), даже если DeepInfra висел или сбоил.

---

## 2. Реализованное решение

### 2.1. Сквозной дедлайн (`run_with_deadline`)
В [services/llm_service.py](file:///home/lapcevichme/PycharmProjects/BookWeaver/services/llm_service.py) реализована функция `run_with_deadline(fn, deadline)`:
- Вызов `client.chat.completions.create` запускается в потоке-демоне (`daemon=True`).
- Ожидание результата ведется через синхронизированную очередь `queue.Queue.get(timeout=deadline)`.
- Таймаут измеряется строго по настенным часам (wall-clock time) и **не зависит** от сетевых пакетов или keepalive-фреймов Cloudflare.
- При превышении лимита гарантированно выбрасывается стандартный `TimeoutError("Сквозной дедлайн LLM API превышен...")`.

### 2.2. Интеграция с циклом повторов (`_execute_with_retry`)
- В функции `is_transient_error` ошибка `TimeoutError` классифицируется как временный сетевой сбой.
- При срабатывании дедлайна вызов не роняет пайплайн, а фиксируется в логах (`⚠️ Сетевой сбой LLM API (stage, попытка 1/6): Сквозной дедлайн LLM API превышен... Повтор через 2.0 с...`) и повторяется с экспоненциальным backoff и jitter.
- Потоки-демоны от зависших вызовов не блокируют завершение процессов или переход к следующим этапам.

### 2.3. Включение фолбэков на OpenRouter (`OPENROUTER_ALLOW_FALLBACKS`)
- В [config.py](file:///home/lapcevichme/PycharmProjects/BookWeaver/config.py) добавлена настройка `OPENROUTER_ALLOW_FALLBACKS` (по умолчанию `True`).
- В `_openrouter_routing()` передается `"allow_fallbacks": True`. Приоритетным провайдером остается `deepinfra/fp8` (согласно `OPENROUTER_PROVIDER_ORDER`), но если он недоступен, OpenRouter автоматически маршрутизирует запрос на другой активный узел (например, `Wafer`), предотвращая 404/502 сбои.
- Проверено тестовым запросом к OpenRouter: при симуляции недоступного провайдера OpenRouter мгновенно перенаправил запрос на рабочий узел без переплаты (стоимость $0.000025 за вызов).

### 2.4. Калибровка таймаутов этапов
В `config.py`:
- Дефолтный таймаут неизвестного запроса `_LLM_REQUEST_TIMEOUT_DEFAULT` снижен с 1200 с (20 мин) до 300 с (5 мин).
- В `_LLM_TIMEOUT_BY_STAGE_DEFAULT` добавлены недостающие этапы (`volume_summary`, `identity_scan`, `identity_judge`, `identity_scope`), а таймаут `character_patch` снижен с 600 с до 360 с (с учетом перехода на `reasoning: low`).

---

## 3. Верификация

1. **Юнит-тесты (`tests/test_llm_backoff.py`):**
   - `test_run_with_deadline_success`: успешное выполнение быстрых функций.
   - `test_run_with_deadline_timeout`: гарантированный выброс `TimeoutError` при задержке функции.
   - `test_run_with_deadline_exception_propagates`: прозрачная передача исключений из рабочего потока.
   - `test_deadline_timeout_triggers_retry_and_recovers`: первый зависший вызов прерывается по дедлайну, повторная попытка восстанавливает выполнение.
   - `test_openrouter_routing_defaults` & `test_openrouter_routing_fallback_can_be_disabled`: проверка передачи настроек фолбэка.

2. **Тесты конфигурации (`tests/test_llm_settings.py`):**
   - Проверка динамического переопределения `OPENROUTER_ALLOW_FALLBACKS` и базового таймаута 300 с.

3. **Общий прогон тестов:**
   - Все 239 тестов проекта успешно пройдены за 17.96 с (`OK`).
