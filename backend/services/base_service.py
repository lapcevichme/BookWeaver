import logging
import gc
from abc import ABC, abstractmethod
from threading import Lock

logger = logging.getLogger(__name__)


class BaseTorchService(ABC):
    """
    Базовый класс для сервисов, использующих PyTorch.
    Реализует логику определения устройства, ленивой инициализации
    и управление памятью (VRAM Cleaning).
    """

    def __init__(self):
        self._device = None
        self._service_lock = Lock()
        self._is_loaded = False

    @property
    def device(self) -> str:
        """
        Ленивое определение устройства (CPU/CUDA).
        Безопасно импортирует torch только при первом обращении.
        """
        if self._device is None:
            try:
                import torch
                if torch.cuda.is_available():
                    self._device = "cuda"
                    logger.info("BaseTorchService: Обнаружена CUDA. Используем GPU.")
                else:
                    self._device = "cpu"
                    logger.info("BaseTorchService: CUDA не найдена. Используем CPU.")
            except ImportError:
                logger.warning("BaseTorchService: PyTorch не найден. Устанавливаем device='cpu'.")
                self._device = "cpu"
        return self._device

    @abstractmethod
    def unload(self):
        """
        Метод для принудительной выгрузки модели из памяти.
        Должен быть реализован в наследниках.
        """
        pass

    def _clear_cuda_cache(self):
        """
        Физическая очистка видеопамяти.
        Вызывается после удаления ссылок на модели.
        """
        if self._device == "cuda":
            try:
                import torch
                gc.collect()
                torch.cuda.empty_cache()
                torch.cuda.ipc_collect()
                logger.info("VRAM: Кэш CUDA очищен (Garbage Collection + Empty Cache).")
            except Exception as e:
                logger.error(f"Ошибка при очистке VRAM: {e}")