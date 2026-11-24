import json
import logging
import urllib.request
import urllib.parse
import uuid
import time
import copy
from pathlib import Path
from typing import Optional, Dict
import config

logger = logging.getLogger(__name__)


class ComfyService:
    """
    Сервис для взаимодействия с ComfyUI API.
    Поддерживает мульти-шаблоны (fast / hq).
    """

    def __init__(self):
        self.server_address = config.COMFY_SERVER_ADDRESS
        self.client_id = str(uuid.uuid4())

        self.templates = {
            "fast": self._load_workflow_template(getattr(config, 'COMFY_WORKFLOW_FAST', Path("workflow_fast.json"))),
            "hq": self._load_workflow_template(getattr(config, 'COMFY_WORKFLOW_HQ', Path("workflow_hq.json")))
        }

    def _load_workflow_template(self, path: Path) -> Dict:
        """Загружает шаблон workflow из указанного файла."""
        if not path.exists():
            logger.error(f"❌ Файл ComfyUI workflow не найден: {path}")
            return {}
        try:
            return json.loads(path.read_text("utf-8"))
        except Exception as e:
            logger.error(f"❌ Ошибка чтения JSON workflow {path}: {e}")
            return {}

    def is_reachable(self) -> bool:
        """Проверяет доступность сервера ComfyUI."""
        try:
            urllib.request.urlopen(f"http://{self.server_address}/system_stats", timeout=1)
            return True
        except Exception:
            return False

    def _prepare_workflow(self, prompt: str, negative: str, seed: int, width: int, height: int,
                          quality: str = "fast") -> Dict:
        """
        Берет нужный шаблон и подставляет значения по ID из конфига.
        """
        template = self.templates.get(quality)
        if not template:
            logger.warning(f"Шаблон '{quality}' не найден, используется 'fast' по умолчанию.")
            template = self.templates.get("fast")

        if not template:
            raise FileNotFoundError("Шаблоны workflow не загружены. Проверьте пути в config.py")

        workflow = copy.deepcopy(template)
        mapping = config.COMFY_NODE_MAPPING or {}

        pos_id = mapping.get("positive_prompt_node_id")
        if pos_id and pos_id in workflow:
            workflow[pos_id]["inputs"]["text"] = prompt

        neg_id = mapping.get("negative_prompt_node_id")
        if neg_id and neg_id in workflow:
            workflow[neg_id]["inputs"]["text"] = negative

        latent_id = mapping.get("empty_latent_node_id")
        if latent_id and latent_id in workflow:
            workflow[latent_id]["inputs"]["width"] = width
            workflow[latent_id]["inputs"]["height"] = height

        ksampler_id = mapping.get("ksampler_node_id")
        if ksampler_id and ksampler_id in workflow:
            inputs = workflow[ksampler_id]["inputs"]
            if "seed" in inputs:
                inputs["seed"] = seed
            elif "noise_seed" in inputs:
                inputs["noise_seed"] = seed

        return workflow

    def queue_prompt(self, prompt: str, negative: str = "ugly, bad quality, blurry, score_6, score_5, score_4",
                     width: int = 832, height: int = 1216, quality: str = "fast") -> str:
        """
        Отправляет задачу в ComfyUI. Возвращает prompt_id.
        """
        seed = int(uuid.uuid4().int % 1000000000)

        logger.info(f"🎨 Подготовка workflow (Quality: {quality}, Seed: {seed})...")

        try:
            workflow = self._prepare_workflow(prompt, negative, seed, width, height, quality)
        except Exception as e:
            logger.error(f"Ошибка подготовки workflow: {e}")
            raise

        p = {"prompt": workflow, "client_id": self.client_id}
        data = json.dumps(p).encode('utf-8')

        try:
            req = urllib.request.Request(f"http://{self.server_address}/prompt", data=data)
            resp = json.loads(urllib.request.urlopen(req).read())
            return resp['prompt_id']
        except Exception as e:
            logger.error(f"Не удалось связаться с ComfyUI по адресу {self.server_address}: {e}")
            raise e

    def wait_for_generation(self, prompt_id: str, timeout: int = 300) -> Optional[Dict]:
        """Блокирующее ожидание завершения генерации."""
        start_time = time.time()
        last_log_time = start_time

        logger.info(f"⏳ Ожидание генерации (ID: {prompt_id})...")

        while time.time() - start_time < timeout:
            try:
                if time.time() - last_log_time > 10:
                    elapsed = int(time.time() - start_time)
                    logger.info(f"... ждем ответа от ComfyUI ({elapsed} сек) ...")
                    last_log_time = time.time()

                with urllib.request.urlopen(f"http://{self.server_address}/history/{prompt_id}") as response:
                    history = json.loads(response.read())
                    if prompt_id in history:
                        logger.info(f"✅ Генерация завершена за {int(time.time() - start_time)} сек.")
                        return history[prompt_id]['outputs']
            except Exception:
                pass
            time.sleep(1)

        logger.error(f"❌ Timeout waiting for prompt {prompt_id} after {timeout} seconds.")
        return None

    def download_and_save_image(self, filename: str, subfolder: str, folder_type: str, save_path: Path):
        """Скачивает картинку из ComfyUI и сохраняет локально."""
        data = {"filename": filename, "subfolder": subfolder, "type": folder_type}
        url_values = urllib.parse.urlencode(data)

        try:
            with urllib.request.urlopen(f"http://{self.server_address}/view?{url_values}") as response:
                image_data = response.read()

            save_path.parent.mkdir(parents=True, exist_ok=True)
            save_path.write_bytes(image_data)
            logger.info(f"💾 Изображение скачано и сохранено: {save_path.name}")
            return True
        except Exception as e:
            logger.error(f"Ошибка сохранения изображения: {e}")
            return False