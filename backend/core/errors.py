"""Исключения пайплайнов."""
from typing import Dict, Optional


class TaskCancelledException(Exception):
    """Задачу отменил пользователь. Никогда не глотать: всегда пробрасывать наверх."""


class PipelineIncompleteError(Exception):
    """
    Этап отработал, но не для всех глав.

    Успешные главы уже сохранены.
    Сообщение — человекочитаемый итог («готово 7 из 9, упали: ...»), оно попадает
    в статус задачи.
    """

    def __init__(self, stage: str, total: int, failed: Dict[str, str], message: Optional[str] = None):
        self.stage = stage
        self.total = total
        self.failed = dict(failed)
        shown = "; ".join(f"{ch}: {reason}" for ch, reason in list(self.failed.items())[:5])
        more = f" и еще {len(self.failed) - 5}" if len(self.failed) > 5 else ""
        super().__init__(message or (
            f"{stage}: готово {total - len(self.failed)} из {total}, "
            f"упали {len(self.failed)} — {shown}{more}"
        ))
