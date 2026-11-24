"""Миграции базы книги: старые данные должны пережить пересборку таблиц."""
import sqlite3
import tempfile
import unittest
from pathlib import Path

from sqlalchemy import create_engine

import core.database as db

OLD_SCHEMA = """
CREATE TABLE chapter (id VARCHAR PRIMARY KEY);
INSERT INTO chapter VALUES ('c1');
CREATE TABLE llmmetric (id INTEGER NOT NULL PRIMARY KEY, chapter_id VARCHAR NOT NULL REFERENCES chapter(id) ON DELETE CASCADE,
 prompt_type VARCHAR NOT NULL, model_name VARCHAR NOT NULL, input_tokens INTEGER NOT NULL, output_tokens INTEGER NOT NULL,
 latency_ms FLOAT NOT NULL, status VARCHAR NOT NULL, error_message VARCHAR, timestamp FLOAT NOT NULL);
CREATE INDEX ix_llmmetric_chapter_id ON llmmetric (chapter_id);
INSERT INTO llmmetric VALUES (1,'c1','x','m',10,20,1.0,'success',NULL,1.0),(2,'c1','y','m',5,6,2.0,'success',NULL,2.0);
"""


class TestLlmMetricMigration(unittest.TestCase):
    def setUp(self):
        self.path = Path(tempfile.mkdtemp()) / "book.db"
        con = sqlite3.connect(self.path)
        con.executescript(OLD_SCHEMA)
        con.commit()
        con.close()
        self.engine = create_engine(f"sqlite:///{self.path}")

    def _migrate(self):
        with self.engine.connect() as conn:
            db._migrate_llmmetric(conn, "test")

    def _rows(self):
        con = sqlite3.connect(self.path)
        try:
            tables = {r[0] for r in con.execute("select name from sqlite_master where type='table'")}
            rows = con.execute("select id, chapter_id, input_tokens, attempts, cost_usd from llmmetric order by id").fetchall()
            notnull = {r[1]: r[3] for r in con.execute("PRAGMA table_info(llmmetric)")}
            return tables, rows, notnull
        finally:
            con.close()

    def test_rows_survive_rebuild(self):
        self._migrate()
        tables, rows, notnull = self._rows()
        self.assertNotIn("llmmetric_old", tables)
        self.assertEqual(rows, [(1, "c1", 10, 1, None), (2, "c1", 5, 1, None)])
        self.assertEqual(notnull["chapter_id"], 0)
        self._migrate()  # повторный запуск ничего не ломает
        self.assertEqual(self._rows()[1], rows)

    def test_recovers_half_done_rebuild(self):
        # Состояние после сбоя 2026-11-12: новая пустая llmmetric рядом с llmmetric_old с данными
        from core.data_models import LLMMetric
        con = sqlite3.connect(self.path)
        con.executescript("DROP INDEX ix_llmmetric_chapter_id; ALTER TABLE llmmetric RENAME TO llmmetric_old;")
        con.commit()
        con.close()
        with self.engine.connect() as conn:
            LLMMetric.__table__.create(conn)
            conn.commit()
        self._migrate()
        tables, rows, _ = self._rows()
        self.assertNotIn("llmmetric_old", tables)
        self.assertEqual([r[0] for r in rows], [1, 2])


class TestBackfillCost(unittest.TestCase):
    def test_parse_usage_lines(self):
        from tools.backfill_llm_cost import parse_logs
        log = Path(tempfile.mkdtemp()) / "app.log"
        log.write_text(
            "2026-11-12 01:10:38,1 - INFO - services.llm_service - LLM usage [chapter_summary | deepseek/x @ DeepInfra]: "
            "in=3346 (cached=0), out=888 (reasoning=402), cost=$0.0012\n"
            "01:13:17 - INFO - LLM usage [sound_design | deepseek/x @ DeepInfra]: in=4695 (cached=4608), "
            "out=9142 (reasoning=6597), cost=$0.0026827233048\n"
            "LLM usage [x | m @ p]: in=1 (cached=0), out=2 (reasoning=0), cost=$?\n", encoding="utf-8")
        found = parse_logs([log])
        self.assertEqual(found[("chapter_summary", 3346, 888)], [(0.0012, 0, 402)])
        self.assertEqual(found[("sound_design", 4695, 9142)][0][1:], (4608, 6597))
        self.assertNotIn(("x", 1, 2), found)


if __name__ == "__main__":
    unittest.main()
