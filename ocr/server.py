"""In-memory PNG OCR over a loopback-published local HTTP endpoint."""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from io import BytesIO
import json
import logging
import os
from socket import SHUT_WR
from threading import BoundedSemaphore

from adapter import adapt
from models import model_parameters
from network_guard import apply_network_guard


MAX_REQUEST_BYTES = 32 * 1024 * 1024
MAX_RESPONSE_BYTES = 8 * 1024 * 1024
MAX_PIXELS = 24_000_000
MAX_SIDE = 10_000
REQUEST_SOCKET_TIMEOUT = 15
PORT = 8080

logger = logging.getLogger("roch.ocr")


class InputError(Exception):
    def __init__(self, status, reason):
        self.status = status
        self.reason = reason


def decode_png(data):
    import numpy as np
    from PIL import Image

    try:
        with Image.open(BytesIO(data), formats=("PNG",)) as image:
            width, height = image.size
            if width * height > MAX_PIXELS or max(width, height) > MAX_SIDE:
                raise InputError(413, "image_dimensions_exceed_limit")
            if getattr(image, "n_frames", 1) != 1:
                raise InputError(400, "expected_one_png_page")
            rgba = image.convert("RGBA")
            white = Image.new("RGBA", image.size, "white")
            white.alpha_composite(rgba)
            return np.ascontiguousarray(np.asarray(white.convert("RGB"))[:, :, ::-1])
    except Image.DecompressionBombError:
        raise InputError(413, "image_dimensions_exceed_limit") from None
    except (OSError, SyntaxError, ValueError):
        raise InputError(400, "invalid_png") from None


def load_engine():
    # Disable remote probing before importing PaddleX and require every active model path.
    os.environ["PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK"] = "True"
    weights = model_parameters()
    from paddleocr import PPStructureV3

    return PPStructureV3(
        device="cpu",
        cpu_threads=4,
        enable_mkldnn=False,
        use_doc_orientation_classify=True,
        use_doc_unwarping=False,
        use_textline_orientation=False,
        use_seal_recognition=False,
        use_table_recognition=True,
        use_formula_recognition=False,
        use_chart_recognition=False,
        use_region_detection=False,
        **weights,
    )


class OcrServer(ThreadingHTTPServer):
    request_queue_size = 8

    def __init__(self, address, engine):
        super().__init__(address, OcrHandler)
        self.engine = engine
        self.inference = BoundedSemaphore(1)
        self.connections = BoundedSemaphore(self.request_queue_size)

    def get_request(self):
        request, client_address = super().get_request()
        request.settimeout(REQUEST_SOCKET_TIMEOUT)
        return request, client_address

    def process_request(self, request, client_address):
        if not self.connections.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.connections.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.connections.release()

    def handle_error(self, request, client_address):
        logger.warning("OCR connection failed")


class OcrHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        # Avoid logging request targets, headers or parser messages containing user data.
        pass

    def send_error(self, code, message=None, explain=None):
        self.respond(code, {"error": "invalid_http_request"})

    def respond(self, status, body):
        data = json.dumps(body, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")
        if len(data) > MAX_RESPONSE_BYTES:
            status, data = 500, b'{"error":"ocr_response_exceeds_limit"}'
        self.close_connection = True
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            pass

    def do_GET(self):
        if self.path != "/health":
            self.respond(404, {"error": "route_not_found"})
            return
        self.respond(200, {"status": "ready"})

    def reject_before_reading(self, status, reason):
        self.respond(status, {"error": reason})
        # Send the response before closing a connection with a body still in flight.
        try:
            self.connection.shutdown(SHUT_WR)
            self.connection.settimeout(0.1)
            self.rfile.read1(64 * 1024)
        except OSError:
            pass

    def do_POST(self):
        if self.path != "/v1/recognize":
            self.reject_before_reading(404, "route_not_found")
            return
        if self.headers.get_content_type() != "image/png":
            self.reject_before_reading(415, "expected_png")
            return
        sizes = self.headers.get_all("Content-Length", [])
        if len(sizes) != 1 or self.headers.get("Transfer-Encoding"):
            self.reject_before_reading(400, "invalid_content_length")
            return
        size = sizes[0]
        if not size.isascii() or not size.isdecimal() or len(size) > 12:
            self.reject_before_reading(400, "invalid_content_length")
            return
        size = int(size)
        if size > MAX_REQUEST_BYTES:
            self.reject_before_reading(413, "request_exceeds_limit")
            return
        if not self.server.inference.acquire(blocking=False):
            self.reject_before_reading(429, "ocr_busy")
            return
        try:
            data = self.rfile.read(size)
            if len(data) != size:
                raise InputError(400, "incomplete_request")
            image = decode_png(data)
            prediction, = self.server.engine.predict(
                image,
                use_table_orientation_classify=False,
                use_ocr_results_with_table_cells=False,
            )
            height, width = image.shape[:2]
            self.respond(200, adapt(prediction, width, height))
        except InputError as error:
            self.respond(error.status, {"error": error.reason})
        except TimeoutError:
            self.respond(408, {"error": "request_timeout"})
        except Exception as error:
            logger.error("OCR inference failed (%s)", type(error).__name__)
            self.respond(500, {"error": "ocr_inference_failed"})
        finally:
            self.server.inference.release()


def main():
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    try:
        apply_network_guard()
        engine = load_engine()
    except Exception as error:
        logger.error("OCR model initialization failed (%s)", type(error).__name__)
        raise SystemExit(1) from None
    with OcrServer(("0.0.0.0", PORT), engine) as server:
        logger.info("OCR model ready")
        server.serve_forever()


if __name__ == "__main__":
    main()
