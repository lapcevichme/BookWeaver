# BookWeaverWeb

Веб-интерфейс для **BookWeaver Lite**: читалка с настройками типографики и закладками, спойлер-безопасный кодекс персонажей (`as-of`), дельта главы, режим пересказов (`Recap`) и быстрый поиск (`Ctrl+K`).

## Локальная разработка

```bash
npm install
npm run dev
```

По умолчанию запросы `/api/*` проксируются на `http://localhost:8000` (см. `vite.config.ts`).

## Продакшен-сборка (Docker)

В корне бэкенда (`../BookWeaver`) предусмотрен общий `docker-compose.lite.yml`:

```bash
cd ../BookWeaver
docker compose -f docker-compose.lite.yml up --build -d
```

## Тесты и проверка типов

```bash
npm test
npm run build
```
