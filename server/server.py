#!/usr/bin/env python3
from __future__ import annotations

import argparse
import base64
import json
import logging
from logging.handlers import RotatingFileHandler
from pathlib import Path
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
import uuid

try:
    from server.amap import AmapClient, AmapOrderEnricher, Point
except ModuleNotFoundError:  # Supports `python3 server/server.py` from repo root.
    from amap import AmapClient, AmapOrderEnricher, Point


ROOT = Path(__file__).resolve().parent.parent
MODEL = "qwen3.7-flash"
DASHSCOPE_ENDPOINT = (
    "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
)
MAX_IMAGE_BYTES = 8 * 1024 * 1024
MAX_VISION_PIXELS = 1_310_720  # 1280 visual tokens at 32x32 pixels/token.

PROMPT = """你是网约车司机端订单截图解析器。忽略状态栏、刷新倒计时、底部导航和“跑单助手”悬浮窗。
识别画面中每一张订单卡片，严格保持从上到下顺序，禁止把相邻卡片的字段混合。
只读取画面真实可见内容，严禁根据上下文、常见数值或相邻卡片补全任何字符。
关键字段包括 price、pickup_distance_km、pickup_minutes、trip_distance_km、pickup_name、destination_name：
- 只要一个数字的任意一位（例如 x.y 公里的 x）被裁切、浮层遮挡、模糊或没有完整显示，该字段必须填 null；
- 起点或终点允许轻微裁到字形最下沿或边缘，只要所有字符仍能从可见笔画中可靠辨认且没有字符缺失，就可照常读取；
  只有存在字符缺失、关键笔画被挡或地址含义不确定时，对应字段才填 null，禁止靠常识补出不可辨认字符；
- 把所有不完整字段名写入 occluded_fields。只有六个关键字段都完整可见且 occluded_fields 为空时，is_fully_visible 才能为 true。
pickup_name 和 destination_name 必须忠实抄录画面原文。对应的 *_normalized 可利用语言知识做极保守规范化：
只修正非常明确的低级错别字、重复字或异常空格；不得扩写简称、改成另一个 POI 或猜测被遮挡文字；
没有十足把握时 normalized 必须与原文相同，原文字段为 null 时 normalized 也必须为 null。
同时返回每张卡片在整张输入图片中的可见外接矩形 bbox_1000，格式为 [left,top,right,bottom]，
整张图片左上角为 [0,0]、右下角为 [1000,1000]。矩形应覆盖卡片白色背景，不要覆盖相邻卡片；
如果无法可靠判断卡片边界则填 null。screen_rank 是卡片在当前画面中从上到下的序号，从 1 开始。
请按照 JSON 格式输出且只能输出 JSON：
{"orders":[{"screen_rank":integer,"bbox_1000":[number,number,number,number]|null,"is_fully_visible":boolean,"occluded_fields":[string],"price":number|null,"pickup_distance_km":number|null,"pickup_minutes":integer|null,"trip_distance_km":number|null,"pickup_name":string|null,"pickup_name_normalized":string|null,"destination_name":string|null,"destination_name_normalized":string|null}]}
数字字段不要带单位。没有订单时返回 {"orders":[]}。"""


def load_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.exists():
        return values
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        values[key] = value
    return values


def build_dashscope_payload(image_bytes: bytes) -> dict[str, Any]:
    encoded = base64.b64encode(image_bytes).decode("ascii")
    return {
        "model": MODEL,
        "enable_thinking": False,
        "response_format": {"type": "json_object"},
        "messages": [
            {
                "role": "user",
                "content": [
                    {
                        "type": "image_url",
                        "image_url": {
                            "url": f"data:image/jpeg;base64,{encoded}",
                            "detail": "high",
                        },
                        "max_pixels": MAX_VISION_PIXELS,
                    },
                    {"type": "text", "text": PROMPT},
                ],
            }
        ],
    }


def parse_model_content(content: str) -> dict[str, Any]:
    text = content.strip()
    if text.startswith("```json"):
        text = text[len("```json") :]
    elif text.startswith("```"):
        text = text[3:]
    if text.endswith("```"):
        text = text[:-3]
    result = json.loads(text.strip())
    if not isinstance(result, dict) or not isinstance(result.get("orders"), list):
        raise ValueError("模型响应缺少 orders 数组")
    return result


class DashScopeError(RuntimeError):
    pass


class RequestCancelled(RuntimeError):
    pass


class CancellationRegistry:
    """Shares cancellation state between concurrent analyze and cancel handlers."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._requests: dict[str, threading.Event] = {}

    def register(self, request_id: str) -> threading.Event:
        with self._lock:
            return self._requests.setdefault(request_id, threading.Event())

    def cancel(self, request_id: str) -> None:
        with self._lock:
            self._requests.setdefault(request_id, threading.Event()).set()

    def finish(self, request_id: str) -> None:
        with self._lock:
            self._requests.pop(request_id, None)


class DashScopeClient:
    def __init__(self, api_key: str, timeout_seconds: int = 60) -> None:
        self.api_key = api_key
        self.timeout_seconds = timeout_seconds

    def analyze(
        self,
        image_bytes: bytes,
        cancelled: threading.Event | None = None,
    ) -> tuple[dict[str, Any], dict[str, Any]]:
        if cancelled is not None and cancelled.is_set():
            raise RequestCancelled()
        analyze_started = time.monotonic()
        payload = json.dumps(
            build_dashscope_payload(image_bytes),
            ensure_ascii=False,
            separators=(",", ":"),
        ).encode("utf-8")
        request = Request(
            DASHSCOPE_ENDPOINT,
            data=payload,
            method="POST",
            headers={
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type": "application/json",
                "Accept": "application/json",
            },
        )
        payload_ready = time.monotonic()
        try:
            with urlopen(request, timeout=self.timeout_seconds) as response:
                response_body = response.read().decode("utf-8")
        except HTTPError as error:
            detail = error.read().decode("utf-8", errors="replace")[:1000]
            raise DashScopeError(f"DashScope HTTP {error.code}: {detail}") from error
        except URLError as error:
            raise DashScopeError(f"DashScope 网络错误: {error.reason}") from error

        http_finished = time.monotonic()
        if cancelled is not None and cancelled.is_set():
            raise RequestCancelled()
        envelope = json.loads(response_body)
        try:
            content = envelope["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError) as error:
            raise DashScopeError("DashScope 响应缺少 choices[0].message.content") from error
        orders = parse_model_content(content)
        metadata = {
            "dashscope_request_id": envelope.get("id"),
            "usage": envelope.get("usage", {}),
            "payload_ms": round((payload_ready - analyze_started) * 1000),
            "dashscope_http_ms": round((http_finished - payload_ready) * 1000),
            "response_parse_ms": round((time.monotonic() - http_finished) * 1000),
        }
        return orders, metadata


def configure_logging() -> logging.Logger:
    logger = logging.getLogger("cheat_cat_vlm")
    logger.setLevel(logging.INFO)
    logger.handlers.clear()
    formatter = logging.Formatter(
        "%(asctime)s %(levelname)s %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )
    console = logging.StreamHandler()
    console.setFormatter(formatter)
    logger.addHandler(console)

    log_dir = Path(__file__).resolve().parent / "logs"
    log_dir.mkdir(parents=True, exist_ok=True)
    file_handler = RotatingFileHandler(
        log_dir / "vlm_server.log",
        maxBytes=5 * 1024 * 1024,
        backupCount=3,
        encoding="utf-8",
    )
    file_handler.setFormatter(formatter)
    logger.addHandler(file_handler)
    return logger


class VlmRequestHandler(BaseHTTPRequestHandler):
    client: DashScopeClient
    route_enricher: AmapOrderEnricher | None = None
    logger: logging.Logger
    cancellations = CancellationRegistry()

    def do_GET(self) -> None:  # noqa: N802
        if self.path != "/health":
            self._json_response(404, {"error": "not_found"})
            return
        self._json_response(
            200,
            {
                "status": "ok",
                "model": MODEL,
                "amap_routing": self.route_enricher is not None,
            },
        )

    def do_POST(self) -> None:  # noqa: N802
        if self.path == "/v1/cancel":
            request_id = self.headers.get("X-Request-Id", "")
            if not re.fullmatch(r"[A-Za-z0-9_-]{8,64}", request_id):
                self._json_response(400, {"error": "invalid_request_id"})
                return
            self.cancellations.cancel(request_id)
            self.logger.info("request_cancel_signal request_id=%s", request_id)
            self._json_response(202, {"status": "cancelling", "request_id": request_id})
            return
        if self.path != "/v1/analyze":
            self._json_response(404, {"error": "not_found"})
            return
        client_request_id = self.headers.get("X-Request-Id", "")
        request_id = (
            client_request_id
            if re.fullmatch(r"[A-Za-z0-9_-]{8,64}", client_request_id)
            else uuid.uuid4().hex[:12]
        )
        started = time.monotonic()
        content_type = self.headers.get("Content-Type", "")
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except (TypeError, ValueError):
            length = 0
        if not content_type.startswith("image/jpeg"):
            self._json_response(
                415,
                {"error": "content_type_must_be_image_jpeg", "request_id": request_id},
            )
            return
        if length <= 0 or length > MAX_IMAGE_BYTES:
            self._json_response(
                413,
                {"error": "invalid_image_size", "request_id": request_id},
            )
            return

        image_bytes = self.rfile.read(length)
        cancelled = self.cancellations.register(request_id)
        received_at = time.monotonic()
        capture_width = self.headers.get("X-Capture-Width", "-")
        capture_height = self.headers.get("X-Capture-Height", "-")
        jpeg_quality = self.headers.get("X-Jpeg-Quality", "-")
        jpeg_encoding_ms = self.headers.get("X-Jpeg-Encoding-Ms", "-")
        scan_top = self.headers.get("X-Scan-Top", "-")
        scan_bottom = self.headers.get("X-Scan-Bottom", "-")
        driver_gps = self._driver_location()
        self.logger.info(
            "request_start request_id=%s client=%s bytes=%d image=%sx%s "
            "jpeg_quality=%s client_encode_ms=%s scan=%s-%s location=%s",
            request_id,
            self.client_address[0],
            len(image_bytes),
            capture_width,
            capture_height,
            jpeg_quality,
            jpeg_encoding_ms,
            scan_top,
            scan_bottom,
            "yes" if driver_gps else "no",
        )
        try:
            result, metadata = self.client.analyze(image_bytes, cancelled)
            if cancelled.is_set():
                raise RequestCancelled()
            route_metadata: dict[str, Any] = {}
            if self.route_enricher is not None and driver_gps is not None:
                result, route_metadata = self.route_enricher.enrich(result, driver_gps)
            if cancelled.is_set():
                raise RequestCancelled()
            latency_ms = round((time.monotonic() - started) * 1000)
            usage = metadata.get("usage") or {}
            response = dict(result)
            response["_meta"] = {
                "request_id": request_id,
                "model": MODEL,
                "latency_ms": latency_ms,
                "lan_receive_ms": round((received_at - started) * 1000),
                "dashscope_http_ms": metadata.get("dashscope_http_ms"),
                "amap_ms": route_metadata.get("amap_ms"),
            }
            self.logger.info(
                "request_ok request_id=%s total_ms=%d lan_receive_ms=%d "
                "payload_ms=%s dashscope_http_ms=%s response_parse_ms=%s "
                "amap_ms=%s amap_routed_orders=%s orders=%d "
                "input_tokens=%s output_tokens=%s dashscope_request_id=%s",
                request_id,
                latency_ms,
                round((received_at - started) * 1000),
                metadata.get("payload_ms", "-"),
                metadata.get("dashscope_http_ms", "-"),
                metadata.get("response_parse_ms", "-"),
                route_metadata.get("amap_ms", "-"),
                route_metadata.get("amap_routed_orders", "-"),
                len(result["orders"]),
                usage.get("prompt_tokens", usage.get("input_tokens", "-")),
                usage.get("completion_tokens", usage.get("output_tokens", "-")),
                metadata.get("dashscope_request_id") or "-",
            )
            self.logger.info(
                "orders_result request_id=%s json=%s",
                request_id,
                json.dumps(result, ensure_ascii=False, separators=(",", ":")),
            )
            self._json_response(200, response)
        except RequestCancelled:
            latency_ms = round((time.monotonic() - started) * 1000)
            self.logger.info(
                "request_cancelled request_id=%s total_ms=%d",
                request_id,
                latency_ms,
            )
            try:
                self._json_response(
                    409,
                    {"error": "request_cancelled", "request_id": request_id},
                )
            except (BrokenPipeError, ConnectionResetError):
                pass
        except Exception as error:  # Keep the LAN API response predictable.
            latency_ms = round((time.monotonic() - started) * 1000)
            self.logger.exception(
                "request_error request_id=%s total_ms=%d error=%s",
                request_id,
                latency_ms,
                error,
            )
            try:
                self._json_response(
                    502,
                    {"error": str(error), "request_id": request_id},
                )
            except (BrokenPipeError, ConnectionResetError):
                pass
        finally:
            self.cancellations.finish(request_id)

    def _driver_location(self) -> Point | None:
        try:
            latitude = float(self.headers.get("X-Driver-Latitude", ""))
            longitude = float(self.headers.get("X-Driver-Longitude", ""))
        except ValueError:
            return None
        if not (-90 <= latitude <= 90 and -180 <= longitude <= 180):
            return None
        return Point(longitude=longitude, latitude=latitude)

    def log_message(self, _format: str, *args: object) -> None:
        return

    def _json_response(self, status: int, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main() -> None:
    parser = argparse.ArgumentParser(description="Cheat Cat LAN VLM server")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", default=8765, type=int)
    args = parser.parse_args()

    env = load_env(ROOT / ".env")
    api_key = env.get("DASHSCOPE_API_KEY", "").strip()
    if not api_key:
        raise SystemExit("缺少 DASHSCOPE_API_KEY：请配置工程根目录 .env")
    amap_api_key = env.get("AMAP_API_KEY", "").strip()
    if not amap_api_key:
        raise SystemExit("缺少 AMAP_API_KEY：请配置高德 Web 服务 API Key")

    logger = configure_logging()
    VlmRequestHandler.client = DashScopeClient(api_key)
    VlmRequestHandler.route_enricher = AmapOrderEnricher(AmapClient(amap_api_key))
    VlmRequestHandler.logger = logger
    server = ThreadingHTTPServer((args.host, args.port), VlmRequestHandler)
    logger.info(
        "server_start listen=http://%s:%d model=%s log=%s",
        args.host,
        args.port,
        MODEL,
        Path(__file__).resolve().parent / "logs" / "vlm_server.log",
    )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        logger.info("server_stop reason=keyboard_interrupt")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
