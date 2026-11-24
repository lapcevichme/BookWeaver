import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

import config
from api_server import create_app


class TestReadOnlyMode(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(create_app())

    def test_read_only_blocks_mutations_and_allows_reads(self):
        with patch.object(config, "IS_READ_ONLY", True):
            res_get = self.client.get("/")
            self.assertEqual(res_get.status_code, 200)
            self.assertTrue(res_get.json()["read_only"])

            for method, path in (
                ("POST", "/api/v1/tasks/ai/analyze-characters"),
                ("PATCH", "/api/v1/projects/any_book"),
                ("DELETE", "/api/v1/projects/any_book"),
                ("PUT", "/api/v1/projects/any_book"),
            ):
                res = self.client.request(method, path, json={})
                self.assertEqual(res.status_code, 403, f"{method} {path} should return 403")
                self.assertEqual(res.json()["detail"], "Server is running in read-only demo mode.")

    def test_read_only_disabled_allows_reaching_handlers(self):
        with patch.object(config, "IS_READ_ONLY", False):
            res_get = self.client.get("/")
            self.assertEqual(res_get.status_code, 200)
            self.assertFalse(res_get.json()["read_only"])

            res_post = self.client.post("/api/v1/tasks/ai/analyze-characters", json={})
            self.assertEqual(res_post.status_code, 422)


if __name__ == "__main__":
    unittest.main()
