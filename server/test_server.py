import json
import logging
from pathlib import Path
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer
from urllib.request import Request, urlopen
from urllib.parse import parse_qs, urlparse

from server.amap import AmapClient, AmapOrderEnricher, Point

from server.server import (
    CancellationRegistry,
    MAX_VISION_PIXELS,
    VlmRequestHandler,
    build_dashscope_payload,
    load_env,
    parse_model_content,
)


class ServerTest(unittest.TestCase):
    def test_load_env_supports_comments_and_quotes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env"
            path.write_text(
                "# comment\nDASHSCOPE_API_KEY='sk-test'\nVLM_SERVER_URL=http://x:1\n",
                encoding="utf-8",
            )

            values = load_env(path)

        self.assertEqual("sk-test", values["DASHSCOPE_API_KEY"])
        self.assertEqual("http://x:1", values["VLM_SERVER_URL"])

    def test_payload_uses_qwen_json_mode_and_jpeg(self) -> None:
        payload = build_dashscope_payload(b"jpeg")

        self.assertEqual("qwen3.7-flash", payload["model"])
        self.assertFalse(payload["enable_thinking"])
        self.assertEqual({"type": "json_object"}, payload["response_format"])
        image_url = payload["messages"][0]["content"][0]["image_url"]["url"]
        self.assertTrue(image_url.startswith("data:image/jpeg;base64,"))
        self.assertEqual(
            MAX_VISION_PIXELS,
            payload["messages"][0]["content"][0]["max_pixels"],
        )
        prompt = payload["messages"][0]["content"][1]["text"]
        self.assertIn("bbox_1000", prompt)
        self.assertIn("screen_rank", prompt)
        self.assertIn("is_fully_visible", prompt)
        self.assertIn("occluded_fields", prompt)
        self.assertIn("pickup_name_normalized", prompt)
        self.assertIn("允许轻微裁到字形最下沿或边缘", prompt)

    def test_parse_model_content_requires_orders_array(self) -> None:
        parsed = parse_model_content('```json\n{"orders":[]}\n```')
        self.assertEqual([], parsed["orders"])

        with self.assertRaises(ValueError):
            parse_model_content(json.dumps({"result": []}))

    def test_health_and_analyze_http_endpoints(self) -> None:
        class FakeClient:
            def analyze(self, image_bytes: bytes, cancelled=None):
                self.image_bytes = image_bytes
                return {"orders": [{"price": 20.0}]}, {"usage": {}}

        fake = FakeClient()
        VlmRequestHandler.client = fake
        VlmRequestHandler.logger = logging.getLogger("server_test")
        server = ThreadingHTTPServer(("127.0.0.1", 0), VlmRequestHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base_url = f"http://127.0.0.1:{server.server_port}"
        try:
            with urlopen(f"{base_url}/health") as response:
                health = json.load(response)
            self.assertEqual("ok", health["status"])

            request = Request(
                f"{base_url}/v1/analyze",
                data=b"jpeg",
                method="POST",
                headers={"Content-Type": "image/jpeg"},
            )
            with urlopen(request) as response:
                result = json.load(response)
            self.assertEqual(20.0, result["orders"][0]["price"])
            self.assertEqual(b"jpeg", fake.image_bytes)
            self.assertIn("request_id", result["_meta"])
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    def test_cancel_endpoint_marks_analyze_request(self) -> None:
        started = threading.Event()

        class BlockingClient:
            def analyze(self, _image_bytes: bytes, cancelled=None):
                started.set()
                self.cancelled = cancelled
                self.cancelled.wait(timeout=2)
                return {"orders": []}, {"usage": {}}

        fake = BlockingClient()
        VlmRequestHandler.client = fake
        VlmRequestHandler.route_enricher = None
        VlmRequestHandler.cancellations = CancellationRegistry()
        VlmRequestHandler.logger = logging.getLogger("server_cancel_test")
        server = ThreadingHTTPServer(("127.0.0.1", 0), VlmRequestHandler)
        server_thread = threading.Thread(target=server.serve_forever, daemon=True)
        server_thread.start()
        base_url = f"http://127.0.0.1:{server.server_port}"
        analyze_result = {}

        def analyze() -> None:
            request = Request(
                f"{base_url}/v1/analyze",
                data=b"jpeg",
                method="POST",
                headers={
                    "Content-Type": "image/jpeg",
                    "X-Request-Id": "cancel-test-123",
                },
            )
            try:
                urlopen(request)
            except Exception as error:
                analyze_result["error"] = error

        analyze_thread = threading.Thread(target=analyze)
        analyze_thread.start()
        try:
            self.assertTrue(started.wait(timeout=1))
            cancel_request = Request(
                f"{base_url}/v1/cancel",
                data=b"",
                method="POST",
                headers={"X-Request-Id": "cancel-test-123"},
            )
            with urlopen(cancel_request) as response:
                self.assertEqual(202, response.status)
            analyze_thread.join(timeout=2)
            self.assertFalse(analyze_thread.is_alive())
            self.assertTrue(fake.cancelled.is_set())
            self.assertIn("error", analyze_result)
        finally:
            server.shutdown()
            server.server_close()
            server_thread.join(timeout=2)

    def test_amap_enrichment_uses_two_real_routes_for_hourly_income(self) -> None:
        def fake_transport(url: str):
            parsed = urlparse(url)
            query = parse_qs(parsed.query)
            if parsed.path.endswith("/coordinate/convert"):
                return {"status": "1", "locations": "113.940000,22.530000"}
            if parsed.path.endswith("/geocode/geo"):
                address = query["address"][0]
                if address == "科技园":
                    return {
                        "status": "1",
                        "geocodes": [{
                            "formatted_address": "科技园",
                            "location": "113.950000,22.540000",
                            "adcode": "440305",
                        }],
                    }
                return {
                    "status": "1",
                    "geocodes": [{
                        "formatted_address": "深圳大学",
                        "location": "113.960000,22.550000",
                        "adcode": "440305",
                    }],
                }
            if parsed.path.endswith("/direction/driving"):
                is_pickup = query["destination"][0] == "113.950000,22.540000"
                self.assertEqual(["cost,tmcs"], query["show_fields"])
                return {
                    "status": "1",
                    "route": {"paths": [{
                        "distance": "1500" if is_pickup else "8100",
                        "cost": {
                            "duration": "240" if is_pickup else "780",
                            "traffic_lights": "3" if is_pickup else "12",
                            "tolls": "0" if is_pickup else "6",
                            "toll_distance": "0" if is_pickup else "4200",
                            "toll_road": "" if is_pickup else "南坪快速",
                        },
                        "steps": [{"tmcs": [
                            {
                                "tmc_status": "拥堵" if is_pickup else "畅通",
                                "tmc_distance": "400" if is_pickup else "7000",
                            },
                            {
                                "tmc_status": "严重拥堵" if is_pickup else "拥堵",
                                "tmc_distance": "200" if is_pickup else "600",
                            },
                        ]}],
                    }]},
                }
            raise AssertionError(url)

        enricher = AmapOrderEnricher(
            AmapClient("test-key", transport=fake_transport),
        )
        result, metadata = enricher.enrich(
            {"orders": [{
                "is_fully_visible": True,
                "occluded_fields": [],
                "price": 32,
                "pickup_distance_km": 1.5,
                "trip_distance_km": 8.1,
                "pickup_name": "科技园",
                "pickup_name_normalized": "科技园",
                "destination_name": "深圳大学",
                "destination_name_normalized": "深圳大学",
            }]},
            Point(113.93, 22.52),
        )

        order = result["orders"][0]
        self.assertEqual("ok", order["route_status"])
        self.assertEqual(4, order["pickup_route_minutes"])
        self.assertEqual(13, order["trip_route_minutes"])
        self.assertEqual(20, order["total_occupied_minutes"])
        self.assertAlmostEqual(96.0, order["effective_hourly_income"])
        self.assertEqual(3, order["pickup_route_traffic_lights"])
        self.assertEqual(12, order["trip_route_traffic_lights"])
        self.assertEqual(0.6, order["pickup_route_congestion_distance_km"])
        self.assertEqual(0.2, order["pickup_route_severe_congestion_distance_km"])
        self.assertEqual(0.6, order["trip_route_congestion_distance_km"])
        self.assertEqual(6.0, order["trip_route_tolls_yuan"])
        self.assertEqual(4.2, order["trip_route_toll_distance_km"])
        self.assertEqual("南坪快速", order["trip_route_toll_road"])
        self.assertEqual(1, metadata["amap_routed_orders"])

    def test_amap_retries_account_qps_error(self) -> None:
        responses = [
            {"status": "0", "info": "CUQPS_HAS_EXCEEDED_THE_LIMIT", "infocode": "10021"},
            {"status": "1", "locations": "113.940000,22.530000"},
        ]
        sleeps = []
        client = AmapClient(
            "test-key",
            transport=lambda _url: responses.pop(0),
            sleeper=sleeps.append,
        )

        point = client.convert_gps(Point(113.93, 22.52))

        self.assertEqual(Point(113.94, 22.53), point)
        self.assertEqual([0.6], sleeps)


if __name__ == "__main__":
    unittest.main()
