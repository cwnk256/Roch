import json
from threading import Thread
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from unittest.mock import patch

from server import MAX_REQUEST_BYTES, OcrServer


class FakeImage:
    shape = (100, 100, 3)


class FakeEngine:
    def predict(self, image, **options):
        return [{
            "overall_ocr_res": {
                "rec_texts": ["Test"],
                "rec_boxes": [[10, 20, 50, 40]],
                "rec_scores": [0.96],
            },
            "layout_det_res": {"boxes": []},
            "table_res_list": [],
        }]


class ServerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = OcrServer(("127.0.0.1", 0), FakeEngine())
        cls.base = f"http://127.0.0.1:{cls.server.server_port}"
        cls.thread = Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    def test_service_health_is_ready(self):
        with urlopen(self.base + "/health", timeout=4) as response:
            self.assertEqual(response.status, 200)
            self.assertEqual(json.load(response), {"status": "ready"})

    def test_recognition_returns_normalized_blocks(self):
        with patch("server.decode_png", return_value=FakeImage()) as decode:
            with urlopen(self.png_request(), timeout=4) as response:
                result = json.load(response)
        decode.assert_called_once_with(b"fixture")
        self.assertEqual(result["blocks"], [
            {"text": "Test", "box": [0.1, 0.2, 0.5, 0.4], "confidence": 0.96},
        ])
        self.assertEqual(result["tables"], [])

    def test_rejects_unsupported_body_types_and_oversized_requests(self):
        for expected, headers in (
            (415, {"Content-Type": "application/pdf"}),
            (413, {"Content-Length": str(MAX_REQUEST_BYTES + 1)}),
        ):
            with self.subTest(status=expected):
                with self.assertRaises(HTTPError) as error:
                    urlopen(self.png_request(headers), timeout=4)
                self.assertEqual(error.exception.code, expected)
                error.exception.close()

    def test_rejects_a_concurrent_inference_request(self):
        with self.server.inference:
            with self.assertRaises(HTTPError) as error:
                urlopen(self.png_request(), timeout=4)
            self.assertEqual(error.exception.code, 429)
            error.exception.close()

    def png_request(self, headers=None):
        request_headers = {"Content-Type": "image/png"}
        request_headers.update(headers or {})
        return Request(self.base + "/v1/recognize", data=b"fixture", headers=request_headers)


if __name__ == "__main__":
    unittest.main()
