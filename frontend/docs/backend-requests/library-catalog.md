# Backend Request: Каталог книг с метаданными для Библиотеки

- **Связанная фича в бэклоге:** `F-02` (Библиотека)
- **Статус:** `implemented`
- **Дата:** 2026-11-22

## 1. Суть и зачем нужно
Текущий `GET /api/v1/projects/` возвращает только список имён папок (`string[]`), из-за чего в Библиотеке видны сырые slug'и (`kusuriya-no-hitorigoto__char_new_c`) без названия, автора и числа глав, а каждый `<img src="/cover">` спамит `404 Not Found` в Network, если обложки нет.

## 2. Предлагаемый контракт API

### `GET /api/v1/projects/catalog`

**Ответ (`200 OK`):**
```json
[
  {
    "id": "kusuriya_v1_test",
    "title": "Монолог фармацевта (LN)",
    "author": "Natsu Hyuuga",
    "chapters_count": 62,
    "volumes_count": 4,
    "has_cover": false
  }
]
```

## 3. Пояснение параметров
- `has_cover` (`boolean`) — `true` только если файл обложки реально существует на диске. Фронт запрашивает `/cover` только при `has_cover === true`, убирая спам 404.
- Старый `GET /api/v1/projects/` сохранён без изменений для обратной совместимости.
