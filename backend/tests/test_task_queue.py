import unittest
import tempfile
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

import core.task_queue as task_queue
from core.task_queue import TaskStatus, add_task, get_task, list_tasks, cancel_task, pause_task, resume_task


class TestTaskQueue(unittest.TestCase):
    def setUp(self):
        self.temp_dir = Path(tempfile.mkdtemp())
        task_queue.TASKS_DB_PATH = self.temp_dir / "tasks_test.db"
        from sqlmodel import create_engine
        task_queue.engine = create_engine(f"sqlite:///{task_queue.TASKS_DB_PATH}", connect_args={"check_same_thread": False})
        task_queue.init_tasks_db()

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_task_lifecycle(self):
        task_id = add_task("book_abc", "process_book", param="value")
        self.assertIsNotNone(task_id)

        task = get_task(task_id)
        self.assertIsNotNone(task)
        self.assertEqual(task.book_id, "book_abc")
        self.assertEqual(task.type, "process_book")
        self.assertEqual(task.status, TaskStatus.QUEUED)
        self.assertEqual(task.kwargs, {"param": "value"})

        all_tasks = list_tasks()
        self.assertGreaterEqual(len(all_tasks), 1)

        cancel_task(task_id)
        task_after_cancel = get_task(task_id)
        self.assertEqual(task_after_cancel.status, TaskStatus.CANCELLED)

    def test_pause_resume_task(self):
        task_id = add_task("book_xyz", "generate_summary")
        with task_queue.Session(task_queue.engine) as session:
            t = session.get(task_queue.TaskRecord, task_id)
            t.status = TaskStatus.PROCESSING
            session.add(t)
            session.commit()

        pause_task(task_id)
        t_paused = get_task(task_id)
        self.assertEqual(t_paused.status, TaskStatus.PAUSED)

        resume_task(task_id)
        t_resumed = get_task(task_id)
        self.assertEqual(t_resumed.status, TaskStatus.PROCESSING)

    def test_task_stream_endpoint(self):
        from fastapi.testclient import TestClient
        from api_server import create_app
        task_id = add_task("book_stream", "generate_scenario")
        cancel_task(task_id)  # Mark cancelled so stream closes immediately

        app = create_app()
        client = TestClient(app)
        res = client.get(f"/api/v1/tasks/{task_id}/stream")
        self.assertEqual(res.status_code, 200)
        self.assertIn("data: ", res.text)


    def test_task_indexes_created(self):
        from sqlalchemy import text
        with task_queue.engine.connect() as conn:
            indexes = [r[0] for r in conn.execute(text("SELECT name FROM sqlite_master WHERE type='index'")).fetchall()]
            self.assertIn("ix_taskrecord_status_created_at", indexes)
            self.assertIn("ix_taskrecord_created_at", indexes)

    def test_prune_tasks_terminal_only(self):
        from datetime import datetime, timezone, timedelta
        # Создаем активные задачи с датой 30 дней назад
        old_time = datetime.now(timezone.utc) - timedelta(days=30)
        with task_queue.Session(task_queue.engine) as session:
            t1 = task_queue.TaskRecord(book_id="b1", type="t", status=TaskStatus.QUEUED, created_at=old_time, updated_at=old_time)
            t2 = task_queue.TaskRecord(book_id="b1", type="t", status=TaskStatus.PROCESSING, created_at=old_time, updated_at=old_time)
            t3 = task_queue.TaskRecord(book_id="b1", type="t", status=TaskStatus.PAUSED, created_at=old_time, updated_at=old_time)
            session.add_all([t1, t2, t3])
            session.commit()
            t1_id, t2_id, t3_id = t1.id, t2.id, t3.id

        # Ротация не должна трогать активные задачи вообще
        pruned = task_queue.prune_tasks(max_age_days=1, keep_last=0, min_keep=0)
        self.assertEqual(pruned, 0)
        self.assertIsNotNone(get_task(t1_id))
        self.assertIsNotNone(get_task(t2_id))
        self.assertIsNotNone(get_task(t3_id))

    def test_prune_tasks_rotation_and_limits(self):
        from datetime import datetime, timezone, timedelta
        old_time = datetime.now(timezone.utc) - timedelta(days=20)
        now = datetime.now(timezone.utc)

        # Создаем 5 старых завершенных задач и 5 свежих
        with task_queue.Session(task_queue.engine) as session:
            for i in range(5):
                session.add(task_queue.TaskRecord(book_id="b", type="t", status=TaskStatus.COMPLETE, created_at=old_time, updated_at=old_time))
            for i in range(5):
                session.add(task_queue.TaskRecord(book_id="b", type="t", status=TaskStatus.COMPLETE, created_at=now, updated_at=now))
            session.commit()

        # max_age_days=7, min_keep=2:
        # Всего 10 задач (5 свежих, 5 старых).
        # Свежие (индексы 0..4) защищены. Из старых (индексы 5..9) защищено 0 (так как idx >= min_keep=2).
        # Все 5 старых старше cutoff -> 5 удаляются, 5 свежих остаются.
        pruned = task_queue.prune_tasks(max_age_days=7, keep_last=500, min_keep=2)
        self.assertEqual(pruned, 5)

        # Проверим keep_last лимит: создадим еще 15 свежих задач (всего станет 20)
        with task_queue.Session(task_queue.engine) as session:
            for i in range(15):
                session.add(task_queue.TaskRecord(book_id="b", type="t", status=TaskStatus.COMPLETE, created_at=now, updated_at=now))
            session.commit()

        # keep_last=10 -> должно удалить 10 из 20
        pruned_excess = task_queue.prune_tasks(max_age_days=None, keep_last=10, min_keep=0)
        self.assertEqual(pruned_excess, 10)

        remaining = list_tasks(limit=100)
        self.assertEqual(len(remaining), 10)

    def test_prune_tasks_endpoint(self):
        from fastapi.testclient import TestClient
        from api_server import create_app
        app = create_app()
        client = TestClient(app)

        res = client.post("/api/v1/tasks/prune?max_age_days=7&keep_last=100")
        self.assertEqual(res.status_code, 200)
        data = res.json()
        self.assertIn("pruned_count", data)
        self.assertIn("message", data)


if __name__ == "__main__":
    unittest.main()
