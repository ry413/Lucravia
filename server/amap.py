from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
import json
import math
import threading
import time
from typing import Any, Callable
from urllib.parse import urlencode
from urllib.request import urlopen


AMAP_ROOT = "https://restapi.amap.com"
WAITING_MINUTES = 3
ROUTE_DISTANCE_MIN_TOLERANCE_KM = 5.0
ROUTE_DISTANCE_RELATIVE_TOLERANCE = 0.6


class AmapError(RuntimeError):
    pass


class RouteDistanceMismatch(AmapError):
    pass


@dataclass(frozen=True)
class Point:
    longitude: float
    latitude: float

    def parameter(self) -> str:
        return f"{self.longitude:.6f},{self.latitude:.6f}"


@dataclass(frozen=True)
class PlaceCandidate:
    point: Point
    name: str
    poi_id: str | None
    distance_m: float | None
    adcode: str | None


class AmapClient:
    def __init__(
        self,
        api_key: str,
        timeout_seconds: int = 10,
        transport: Callable[[str], dict[str, Any]] | None = None,
        sleeper: Callable[[float], None] = time.sleep,
    ) -> None:
        self.api_key = api_key
        self.timeout_seconds = timeout_seconds
        self.transport = transport or self._http_get
        self.sleeper = sleeper
        self._minimum_interval_seconds = 0.38 if transport is None else 0.0
        self._rate_lock = threading.Lock()
        self._last_request_at: dict[str, float] = {}

    def convert_gps(self, point: Point) -> Point:
        data = self._get(
            "/v3/assistant/coordinate/convert",
            {"locations": point.parameter(), "coordsys": "gps"},
        )
        return parse_point(str(data.get("locations", "")))

    def search_nearby(
        self,
        keyword: str,
        center: Point,
        expected_distance_km: float | None,
    ) -> PlaceCandidate:
        if expected_distance_km is not None and expected_distance_km > 48:
            return self.geocode(keyword)
        candidates: list[PlaceCandidate] = []
        for search_keyword in nearby_search_keywords(keyword):
            data = self._get(
                "/v3/place/around",
                {
                    "location": center.parameter(),
                    "keywords": search_keyword,
                    "radius": 50000,
                    "sortrule": "distance",
                    "offset": 20,
                    "extensions": "base",
                },
            )
            for item in data.get("pois") or []:
                if not isinstance(item, dict):
                    continue
                try:
                    point = parse_point(str(item.get("location", "")))
                except (TypeError, ValueError):
                    continue
                distance = number_or_none(item.get("distance"))
                candidates.append(
                    PlaceCandidate(
                        point=point,
                        name=str(item.get("name") or search_keyword),
                        poi_id=string_or_none(item.get("id")),
                        distance_m=distance,
                        adcode=string_or_none(item.get("adcode")),
                    )
                )
            if candidates:
                break
        if not candidates:
            return self.geocode(keyword)
        if expected_distance_km is None:
            return candidates[0]
        # Road distance is normally longer than straight-line POI distance.
        expected_straight_m = expected_distance_km * 1000 * 0.78
        return min(
            candidates,
            key=lambda item: abs(
                (item.distance_m if item.distance_m is not None else math.inf)
                - expected_straight_m
            ),
        )

    def resolve_address(
        self,
        address: str,
        fallback_center: Point,
        expected_distance_km: float | None,
    ) -> PlaceCandidate:
        # Real driver cards already expose pickup and trip distances. Search around the known
        # origin first so a generic road suffix cannot geocode to a same-named road in another
        # city. Unrestricted geocoding remains only as a last resort and is audited after routing.
        return self.search_nearby(address, fallback_center, expected_distance_km)

    def geocode(self, address: str) -> PlaceCandidate:
        data = self._get("/v3/geocode/geo", {"address": address})
        geocodes = data.get("geocodes") or []
        if not geocodes or not isinstance(geocodes[0], dict):
            raise AmapError(f"地点未找到：{address}")
        item = geocodes[0]
        return PlaceCandidate(
            point=parse_point(str(item.get("location", ""))),
            name=str(item.get("formatted_address") or address),
            poi_id=None,
            distance_m=None,
            adcode=string_or_none(item.get("adcode")),
        )

    def driving(
        self,
        origin: Point,
        destination: PlaceCandidate,
        origin_id: str | None = None,
    ) -> dict[str, Any]:
        params: dict[str, Any] = {
            "origin": origin.parameter(),
            "destination": destination.point.parameter(),
            "destination_id": destination.poi_id,
            "strategy": 32,
            "show_fields": "cost,tmcs",
        }
        if origin_id:
            params["origin_id"] = origin_id
        data = self._get("/v5/direction/driving", params)
        paths = ((data.get("route") or {}).get("paths") or [])
        if not paths or not isinstance(paths[0], dict):
            raise AmapError("高德未返回驾车路线")
        path = paths[0]
        cost = path.get("cost") or {}
        duration = number_or_none(cost.get("duration"))
        distance = number_or_none(path.get("distance"))
        if duration is None or duration <= 0 or distance is None or distance < 0:
            raise AmapError("高德驾车路线缺少耗时或里程")
        traffic_meters: dict[str, float] = {}
        traffic_details_available = False
        for step in path.get("steps") or []:
            if not isinstance(step, dict):
                continue
            for tmc in step.get("tmcs") or []:
                if not isinstance(tmc, dict):
                    continue
                status = string_or_none(tmc.get("tmc_status") or tmc.get("status"))
                tmc_distance = number_or_none(
                    tmc.get("tmc_distance") or tmc.get("distance")
                )
                if status is None or tmc_distance is None or tmc_distance < 0:
                    continue
                traffic_details_available = True
                traffic_meters[status] = traffic_meters.get(status, 0.0) + tmc_distance
        return {
            "duration_seconds": duration,
            "distance_meters": distance,
            "traffic_lights": nonnegative_number_or_none(cost.get("traffic_lights")),
            "tolls_yuan": nonnegative_number_or_none(cost.get("tolls")),
            "toll_distance_meters": nonnegative_number_or_none(
                cost.get("toll_distance")
            ),
            "toll_road": string_or_none(cost.get("toll_road")),
            "traffic_details_available": traffic_details_available,
            "traffic_meters": traffic_meters,
        }

    def _get(self, path: str, params: dict[str, Any]) -> dict[str, Any]:
        query = {"key": self.api_key, "output": "JSON"}
        query.update({key: value for key, value in params.items() if value is not None})
        url = f"{AMAP_ROOT}{path}?{urlencode(query)}"
        for attempt in range(3):
            self._throttle(path)
            data = self.transport(url)
            if str(data.get("status")) == "1":
                return data
            if str(data.get("infocode")) == "10021" and attempt < 2:
                self.sleeper(0.6 * (attempt + 1))
                continue
            raise AmapError(
                f"高德 API 失败：{data.get('info') or 'unknown'}"
                f" ({data.get('infocode') or '-'})"
            )
        raise AmapError("高德 API QPS 重试耗尽")

    def _throttle(self, path: str) -> None:
        if self._minimum_interval_seconds <= 0:
            return
        if "/direction/" in path:
            service = "route"
        elif "/place/" in path:
            service = "search"
        else:
            service = "basic"
        with self._rate_lock:
            now = time.monotonic()
            wait_seconds = max(
                0.0,
                self._last_request_at.get(service, 0.0)
                + self._minimum_interval_seconds
                - now,
            )
            if wait_seconds:
                self.sleeper(wait_seconds)
            self._last_request_at[service] = time.monotonic()

    def _http_get(self, url: str) -> dict[str, Any]:
        with urlopen(url, timeout=self.timeout_seconds) as response:
            return json.loads(response.read().decode("utf-8"))


class AmapOrderEnricher:
    def __init__(self, client: AmapClient) -> None:
        self.client = client
        self._gps_cache: dict[tuple[float, float], Point] = {}

    def enrich(
        self,
        result: dict[str, Any],
        driver_gps: Point,
    ) -> tuple[dict[str, Any], dict[str, Any]]:
        started = time.monotonic()
        orders = result.get("orders") or []
        try:
            driver = self._convert_cached(driver_gps)
        except Exception as error:
            response = dict(result)
            response["orders"] = [
                {
                    **order,
                    "route_status": "route_failed",
                    "route_error": str(error),
                }
                if isinstance(order, dict)
                else {"route_status": "invalid_order"}
                for order in orders
            ]
            return response, {
                "amap_ms": round((time.monotonic() - started) * 1000),
                "amap_routed_orders": 0,
            }
        with ThreadPoolExecutor(max_workers=min(3, max(1, len(orders)))) as executor:
            enriched = list(executor.map(lambda order: self._enrich_order(order, driver), orders))
        response = dict(result)
        response["orders"] = enriched
        successful = sum(order.get("route_status") == "ok" for order in enriched)
        return response, {
            "amap_ms": round((time.monotonic() - started) * 1000),
            "amap_routed_orders": successful,
        }

    def _convert_cached(self, gps: Point) -> Point:
        key = (round(gps.longitude, 4), round(gps.latitude, 4))
        point = self._gps_cache.get(key)
        if point is None:
            point = self.client.convert_gps(gps)
            self._gps_cache[key] = point
        return point

    def _enrich_order(self, raw_order: Any, driver: Point) -> dict[str, Any]:
        if not isinstance(raw_order, dict):
            return {"route_status": "invalid_order"}
        order = dict(raw_order)
        required = (
            order.get("is_fully_visible") is True,
            not (order.get("occluded_fields") or []),
            number_or_none(order.get("price")) is not None,
            string_or_none(order.get("pickup_name_normalized") or order.get("pickup_name")) is not None,
            string_or_none(order.get("destination_name_normalized") or order.get("destination_name")) is not None,
        )
        if not all(required):
            order["route_status"] = "incomplete_order"
            return order
        try:
            pickup_name = str(order.get("pickup_name_normalized") or order["pickup_name"])
            destination_name = str(
                order.get("destination_name_normalized") or order["destination_name"]
            )
            platform_pickup_km = number_or_none(order.get("pickup_distance_km"))
            platform_trip_km = number_or_none(order.get("trip_distance_km"))
            pickup = self.client.resolve_address(
                pickup_name,
                driver,
                platform_pickup_km,
            )
            order["pickup_match_name"] = pickup.name
            pickup_route = self.client.driving(driver, pickup)
            pickup_route_km = pickup_route["distance_meters"] / 1000
            order["pickup_route_distance_km"] = round(pickup_route_km, 1)
            validate_route_distance(
                "接驾",
                actual_km=pickup_route_km,
                platform_km=platform_pickup_km,
            )

            # Do not spend another POI and route lookup after an obviously wrong pickup match.
            destination = self.client.resolve_address(
                destination_name,
                pickup.point,
                platform_trip_km,
            )
            order["destination_match_name"] = destination.name
            trip_route = self.client.driving(
                pickup.point,
                destination,
                origin_id=pickup.poi_id,
            )
            trip_route_km = trip_route["distance_meters"] / 1000
            order["trip_route_distance_km"] = round(trip_route_km, 1)
            validate_route_distance(
                "行程",
                actual_km=trip_route_km,
                platform_km=platform_trip_km,
            )
            pickup_seconds = pickup_route["duration_seconds"]
            trip_seconds = trip_route["duration_seconds"]
            occupied_seconds = pickup_seconds + WAITING_MINUTES * 60 + trip_seconds
            price = float(order["price"])
            route_fields: dict[str, Any] = {
                "route_status": "ok",
                "route_source": "amap_driving_v5",
                "route_strategy": 32,
                "pickup_route_minutes": math.ceil(pickup_seconds / 60),
                "pickup_route_distance_km": round(pickup_route_km, 1),
                "trip_route_minutes": math.ceil(trip_seconds / 60),
                "trip_route_distance_km": round(trip_route_km, 1),
                "waiting_minutes": WAITING_MINUTES,
                "total_occupied_minutes": math.ceil(occupied_seconds / 60),
                "effective_hourly_income": round(price / occupied_seconds * 3600, 2),
            }
            route_fields.update(route_detail_fields("pickup", pickup_route))
            route_fields.update(route_detail_fields("trip", trip_route))
            order.update(route_fields)
        except RouteDistanceMismatch as error:
            order["route_status"] = "route_mismatch"
            order["route_error"] = str(error)
        except Exception as error:
            order["route_status"] = "route_failed"
            order["route_error"] = str(error)
        return order


def parse_point(value: str) -> Point:
    longitude, latitude = value.split(",", 1)
    return Point(float(longitude), float(latitude))


def nearby_search_keywords(address: str) -> list[str]:
    keywords = [address.strip()]
    for separator in ("-", "—", "–"):
        if separator in address:
            poi_name = address.split(separator, 1)[0].strip()
            if poi_name and poi_name not in keywords:
                keywords.append(poi_name)
            break
    return keywords


def validate_route_distance(
    label: str,
    actual_km: float,
    platform_km: float | None,
) -> None:
    if platform_km is None or platform_km <= 0:
        return
    tolerance_km = max(
        ROUTE_DISTANCE_MIN_TOLERANCE_KM,
        platform_km * ROUTE_DISTANCE_RELATIVE_TOLERANCE,
    )
    if abs(actual_km - platform_km) > tolerance_km:
        raise RouteDistanceMismatch(
            f"{label}地图里程与平台不一致：平台 {platform_km:.1f}km，"
            f"地图 {actual_km:.1f}km"
        )


def number_or_none(value: Any) -> float | None:
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def nonnegative_number_or_none(value: Any) -> float | None:
    number = number_or_none(value)
    return number if number is not None and number >= 0 else None


def route_detail_fields(prefix: str, route: dict[str, Any]) -> dict[str, Any]:
    fields: dict[str, Any] = {}
    traffic_lights = route.get("traffic_lights")
    if traffic_lights is not None:
        fields[f"{prefix}_route_traffic_lights"] = round(float(traffic_lights))
    tolls = route.get("tolls_yuan")
    if tolls is not None:
        fields[f"{prefix}_route_tolls_yuan"] = round(float(tolls), 2)
    toll_distance = route.get("toll_distance_meters")
    if toll_distance is not None:
        fields[f"{prefix}_route_toll_distance_km"] = round(
            float(toll_distance) / 1000,
            1,
        )
    toll_road = route.get("toll_road")
    if toll_road:
        fields[f"{prefix}_route_toll_road"] = toll_road

    if route.get("traffic_details_available"):
        traffic = route.get("traffic_meters") or {}
        severe = float(traffic.get("严重拥堵", 0.0))
        congested = float(traffic.get("拥堵", 0.0)) + severe
        fields[f"{prefix}_route_congestion_distance_km"] = round(
            congested / 1000,
            1,
        )
        fields[f"{prefix}_route_severe_congestion_distance_km"] = round(
            severe / 1000,
            1,
        )
        fields[f"{prefix}_route_slow_distance_km"] = round(
            float(traffic.get("缓行", 0.0)) / 1000,
            1,
        )
    return fields


def string_or_none(value: Any) -> str | None:
    if isinstance(value, str) and value.strip():
        return value.strip()
    return None
