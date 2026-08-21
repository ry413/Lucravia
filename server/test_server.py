import json
import logging
from pathlib import Path
import tempfile
import threading
import time
import unittest
from http.server import ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from urllib.parse import parse_qs, urlparse

from server.amap import (
    AmapClient,
    AmapOrderEnricher,
    Point,
    RouteDistanceMismatch,
    validate_route_distance,
)

from server.server import (
    CancellationRegistry,
    MAX_VISION_PIXELS,
    SharedSecretAuthenticator,
    VlmRequestHandler,
    build_dashscope_payload,
    load_env,
    parse_model_content,
    sign_request,
)
from server.app_updates import UpdateRepository, sha256_file


TEST_SHARED_SECRET = "test-shared-secret-with-at-least-32-chars"


def signed_headers(
    path: str,
    body: bytes,
    request_id: str,
    method: str = "POST",
) -> dict[str, str]:
    timestamp = str(int(time.time()))
    return {
        "X-Request-Id": request_id,
        "X-Request-Timestamp": timestamp,
        "X-Request-Signature": sign_request(
            TEST_SHARED_SECRET,
            method,
            path,
            timestamp,
            request_id,
            body,
        ),
    }


class ServerTest(unittest.TestCase):
    def test_authenticated_update_manifest_and_apk_download(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            update_dir = Path(directory)
            apk = update_dir / "lucravia-2.apk"
            apk.write_bytes(b"signed-apk")
            (update_dir / "latest.json").write_text(
                json.dumps({
                    "version_code": 2,
                    "version_name": "1.0.1",
                    "apk_file": apk.name,
                    "apk_sha256": sha256_file(apk),
                    "apk_size_bytes": apk.stat().st_size,
                    "release_notes": "修复路线匹配",
                    "required": False,
                }),
                encoding="utf-8",
            )
            VlmRequestHandler.updates = UpdateRepository(update_dir)
            VlmRequestHandler.authenticator = SharedSecretAuthenticator(TEST_SHARED_SECRET)
            VlmRequestHandler.logger = logging.getLogger("server_update_test")
            server = ThreadingHTTPServer(("127.0.0.1", 0), VlmRequestHandler)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            base_url = f"http://127.0.0.1:{server.server_port}"
            try:
                manifest_request = Request(
                    f"{base_url}/v1/update/latest",
                    method="GET",
                    headers=signed_headers(
                        "/v1/update/latest",
                        b"",
                        "update-check-123",
                        method="GET",
                    ),
                )
                with urlopen(manifest_request) as response:
                    manifest = json.load(response)
                self.assertEqual(2, manifest["version_code"])
                self.assertEqual("修复路线匹配", manifest["release_notes"])
                self.assertNotIn("apk_file", manifest)

                apk_request = Request(
                    f"{base_url}/v1/update/apk",
                    method="GET",
                    headers=signed_headers(
                        "/v1/update/apk",
                        b"",
                        "update-apk-123",
                        method="GET",
                    ),
                )
                with urlopen(apk_request) as response:
                    self.assertEqual(b"signed-apk", response.read())

                with self.assertRaises(HTTPError) as caught:
                    urlopen(f"{base_url}/v1/update/latest")
                self.assertEqual(401, caught.exception.code)
            finally:
                server.shutdown()
                server.server_close()
                thread.join(timeout=2)

    def test_route_distance_audit_allows_real_long_trip_but_rejects_scale_error(self) -> None:
        validate_route_distance("行程", actual_km=480.0, platform_km=500.0)
        validate_route_distance("接驾", actual_km=7.0, platform_km=2.0)

        with self.assertRaises(RouteDistanceMismatch):
            validate_route_distance("接驾", actual_km=274.9, platform_km=2.0)
        with self.assertRaises(RouteDistanceMismatch):
            validate_route_distance("行程", actual_km=297.4, platform_km=34.3)

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
        VlmRequestHandler.authenticator = SharedSecretAuthenticator(TEST_SHARED_SECRET)
        VlmRequestHandler.logger = logging.getLogger("server_test")
        server = ThreadingHTTPServer(("127.0.0.1", 0), VlmRequestHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base_url = f"http://127.0.0.1:{server.server_port}"
        try:
            with urlopen(f"{base_url}/health") as response:
                health = json.load(response)
            self.assertEqual("ok", health["status"])

            headers = {"Content-Type": "image/jpeg"}
            headers.update(signed_headers("/v1/analyze", b"jpeg", "analyze-test-123"))
            request = Request(
                f"{base_url}/v1/analyze",
                data=b"jpeg",
                method="POST",
                headers=headers,
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
        VlmRequestHandler.authenticator = SharedSecretAuthenticator(TEST_SHARED_SECRET)
        VlmRequestHandler.logger = logging.getLogger("server_cancel_test")
        server = ThreadingHTTPServer(("127.0.0.1", 0), VlmRequestHandler)
        server_thread = threading.Thread(target=server.serve_forever, daemon=True)
        server_thread.start()
        base_url = f"http://127.0.0.1:{server.server_port}"
        analyze_result = {}

        def analyze() -> None:
            headers = {"Content-Type": "image/jpeg"}
            headers.update(
                signed_headers("/v1/analyze", b"jpeg", "cancel-test-123")
            )
            request = Request(
                f"{base_url}/v1/analyze",
                data=b"jpeg",
                method="POST",
                headers=headers,
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
                headers=signed_headers("/v1/cancel", b"", "cancel-test-123"),
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

    def test_shared_secret_rejects_invalid_stale_and_replayed_signatures(self) -> None:
        authenticator = SharedSecretAuthenticator(
            TEST_SHARED_SECRET,
            max_age_seconds=300,
            time_provider=lambda: 1_000,
        )
        body = b"jpeg"
        valid = sign_request(
            TEST_SHARED_SECRET,
            "POST",
            "/v1/analyze",
            "1000",
            "signed-test-123",
            body,
        )

        self.assertFalse(
            authenticator.verify(
                "POST", "/v1/analyze", "1000", "signed-test-123", body, "bad"
            )
        )
        self.assertFalse(
            authenticator.verify(
                "POST",
                "/v1/analyze",
                "699",
                "stale-test-123",
                body,
                sign_request(
                    TEST_SHARED_SECRET,
                    "POST",
                    "/v1/analyze",
                    "699",
                    "stale-test-123",
                    body,
                ),
            )
        )
        self.assertTrue(
            authenticator.verify(
                "POST", "/v1/analyze", "1000", "signed-test-123", body, valid
            )
        )
        self.assertFalse(
            authenticator.verify(
                "POST", "/v1/analyze", "1000", "signed-test-123", body, valid
            )
        )

    def test_unsigned_analyze_is_rejected_before_dashscope(self) -> None:
        class FailIfCalledClient:
            def analyze(self, _image_bytes: bytes, cancelled=None):
                raise AssertionError("unauthorized request reached DashScope")

        VlmRequestHandler.client = FailIfCalledClient()
        VlmRequestHandler.route_enricher = None
        VlmRequestHandler.authenticator = SharedSecretAuthenticator(TEST_SHARED_SECRET)
        VlmRequestHandler.logger = logging.getLogger("server_auth_test")
        server = ThreadingHTTPServer(("127.0.0.1", 0), VlmRequestHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            request = Request(
                f"http://127.0.0.1:{server.server_port}/v1/analyze",
                data=b"jpeg",
                method="POST",
                headers={"Content-Type": "image/jpeg"},
            )
            with self.assertRaises(HTTPError) as caught:
                urlopen(request)
            self.assertEqual(401, caught.exception.code)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

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
            if parsed.path.endswith("/place/around"):
                keyword = query["keywords"][0]
                is_pickup = keyword == "科技园"
                return {
                    "status": "1",
                    "pois": [{
                        "id": "pickup-poi" if is_pickup else "destination-poi",
                        "name": keyword,
                        "location": (
                            "113.950000,22.540000"
                            if is_pickup
                            else "113.960000,22.550000"
                        ),
                        "distance": "1200" if is_pickup else "6300",
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

    def test_amap_rejects_cross_city_match_that_conflicts_with_platform_distance(self) -> None:
        search_keywords = []

        def fake_transport(url: str):
            parsed = urlparse(url)
            query = parse_qs(parsed.query)
            if parsed.path.endswith("/coordinate/convert"):
                return {"status": "1", "locations": "113.900000,22.550000"}
            if parsed.path.endswith("/place/around"):
                keyword = query["keywords"][0]
                search_keywords.append(keyword)
                if keyword == "柏林建材-兴华一路":
                    return {"status": "1", "pois": []}
                if keyword == "柏林建材":
                    return {
                        "status": "1",
                        "pois": [{
                            "id": "wrong-pickup",
                            "name": "广东省云浮市罗定市兴华一路",
                            "location": "111.570000,22.770000",
                            "distance": "274000",
                            "adcode": "445381",
                        }],
                    }
                return {
                    "status": "1",
                    "pois": [{
                        "id": "destination",
                        "name": "广东省深圳市龙岗区乐荟中心",
                        "location": "114.250000,22.720000",
                        "distance": "297000",
                        "adcode": "440307",
                    }],
                }
            if parsed.path.endswith("/direction/driving"):
                is_pickup = query["destination"][0] == "111.570000,22.770000"
                return {
                    "status": "1",
                    "route": {"paths": [{
                        "distance": "274900" if is_pickup else "297400",
                        "cost": {"duration": "11940" if is_pickup else "15660"},
                        "steps": [],
                    }]},
                }
            raise AssertionError(url)

        enricher = AmapOrderEnricher(AmapClient("test-key", transport=fake_transport))
        result, metadata = enricher.enrich(
            {"orders": [{
                "is_fully_visible": True,
                "occluded_fields": [],
                "price": 38.33,
                "pickup_distance_km": 2.0,
                "trip_distance_km": 34.3,
                "pickup_name": "柏林建材-兴华一路",
                "pickup_name_normalized": "柏林建材-兴华一路",
                "destination_name": "乐荟中心乐糖公寓2栋",
                "destination_name_normalized": "乐荟中心乐糖公寓2栋",
            }]},
            Point(113.89, 22.54),
        )

        order = result["orders"][0]
        self.assertEqual("route_mismatch", order["route_status"])
        self.assertIn("平台 2.0km，地图 274.9km", order["route_error"])
        self.assertEqual("广东省云浮市罗定市兴华一路", order["pickup_match_name"])
        self.assertEqual(274.9, order["pickup_route_distance_km"])
        self.assertNotIn("effective_hourly_income", order)
        self.assertEqual(0, metadata["amap_routed_orders"])
        self.assertEqual(
            ["柏林建材-兴华一路", "柏林建材"],
            search_keywords,
        )

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
