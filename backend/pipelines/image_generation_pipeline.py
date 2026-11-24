"""
Пайплайн для генерации визуального контента (портретов персонажей) через ComfyUI.
"""
import logging
from typing import Optional, Callable
from pathlib import Path
from core.book_repository import BookRepository
from core import path_manager
from services.comfy_service import ComfyService
from core.errors import PipelineIncompleteError, TaskCancelledException

logger = logging.getLogger(__name__)


class ImageGenerationPipeline:
    def __init__(self):
        self.comfy_service = ComfyService()
        logger.info("✅ Пайплайн ImageGenerationPipeline инициализирован.")

    def run(self, book_name: str, 
            progress_callback: Optional[Callable[[float, str, str], None]] = None, 
            quality: str = "fast"):
        """
        Проходит по архиву персонажей и генерирует изображения.
        Параметр `quality` может быть "fast" (быстрые скетчи) или "hq" (детализированные).
        """
        def update_progress(progress: float, stage: str, message: str):
            logger.info(f"[Progress {progress:.0%}] [{stage}] {message}")
            if progress_callback:
                progress_callback(progress, stage, message)

        if not self.comfy_service.is_reachable():
            update_progress(0.0, "Ошибка", "Сервер ComfyUI недоступен (127.0.0.1:8188). Запустите ComfyUI!")
            return

        repo = BookRepository(book_name)
        update_progress(0.0, "Старт", "Загрузка архива персонажей...")
        archive = repo.get_character_archive()

        tasks = []
        for char in archive.characters:
            for chapter_key, visual_state in char.visual_timeline.items():
                if visual_state.image_prompt and not visual_state.reference_image_path:
                    tasks.append({
                        "char_id": char.id,
                        "char_name": char.name,
                        "chapter": chapter_key,
                        "prompt": visual_state.image_prompt,
                        "state_obj": visual_state
                    })

        total_tasks = len(tasks)
        if total_tasks == 0:
            update_progress(1.0, "Готово", "Нет новых задач для генерации изображений.")
            return

        update_progress(0.05, "Генерация", f"Найдено {total_tasks} задач. Режим: {quality.upper()}")

        generated_count = 0
        failed = {}
        pony_quality_tags = "score_9, score_8_up, score_7_up, score_6_up, source_anime, "
        pony_negative = "ugly, bad quality, blurry, score_6, score_5, score_4"
        
        images_dir = path_manager.get_book_output_dir(book_name) / "images"
        images_dir.mkdir(parents=True, exist_ok=True)

        for i, task in enumerate(tasks):
            char_name = task['char_name']
            prompt = pony_quality_tags + task['prompt']

            if quality == "fast":
                prompt += ", crisp lines, smooth shading, masterpiece"

            progress_val = 0.05 + (i / total_tasks) * 0.9
            update_progress(progress_val, "Генерация", f"[{quality.upper()}] {char_name} ({task['chapter']})...")

            try:
                prompt_id = self.comfy_service.queue_prompt(
                    prompt=prompt,
                    negative=pony_negative,
                    width=832,
                    height=1216,
                    quality=quality
                )

                outputs = self.comfy_service.wait_for_generation(prompt_id)

                if outputs:
                    for node_id, node_data in outputs.items():
                        if 'images' in node_data:
                            img_info = node_data['images'][0]

                            filename = img_info['filename']
                            subfolder = img_info['subfolder']
                            folder_type = img_info['type']

                            ext = Path(filename).suffix
                            safe_name = f"{task['char_id']}_{task['chapter']}{ext}"
                            save_path = images_dir / safe_name

                            success = self.comfy_service.download_and_save_image(
                                filename, subfolder, folder_type, save_path
                            )

                            if success:
                                relative_path = f"images/{safe_name}"
                                task['state_obj'].reference_image_path = relative_path
                                generated_count += 1
                                # Сохраняем прогресс в БД
                                repo.save_characters(archive.characters)
                            break
                else:
                    failed[f"{char_name} ({task['chapter']})"] = "ComfyUI не вернул результат"
                    logger.warning(f"⚠️ Не удалось получить результат для {char_name}")

            except TaskCancelledException:
                raise
            except Exception as e:
                failed[f"{char_name} ({task['chapter']})"] = str(e)[:200]
                logger.error(f"❌ Ошибка при обработке {char_name}: {e}")

        if generated_count < total_tasks:
            if not failed:
                failed["?"] = "изображение не сохранено"
            raise PipelineIncompleteError("Иллюстрации", total_tasks, failed,
                                          message=f"Иллюстрации: сгенерировано {generated_count} из {total_tasks}; "
                                                  + "; ".join(f"{k}: {v}" for k, v in list(failed.items())[:5]))
        update_progress(1.0, "Завершено", f"Сгенерировано изображений: {generated_count} из {total_tasks}")
