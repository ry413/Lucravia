package com.cheatcat.cheat_cat

import org.json.JSONObject

object VlmOrderResponseParser {
    fun parse(content: String): VlmScreenAnalysis {
        val json = content.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val root = JSONObject(json)
        val array = root.optJSONArray("orders")
            ?: return VlmScreenAnalysis(emptyList(), json)
        val orders = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val price = item.decimalOrNull("price")?.takeIf { it in 3.0..3000.0 }
                val pickupKm = item.decimalOrNull("pickup_distance_km")
                    ?.takeIf { it in 0.0..100.0 }
                val tripKm = item.decimalOrNull("trip_distance_km")
                    ?.takeIf { it in 0.1..999.0 }
                val pickupMinutes = item.decimalOrNull("pickup_minutes")
                    ?.toInt()
                    ?.takeIf { it in 0..180 }
                val pickupNameRaw = item.stringOrNull("pickup_name")
                val destinationNameRaw = item.stringOrNull("destination_name")
                val noOccludedFields = item.optJSONArray("occluded_fields")
                    ?.let { it.length() == 0 }
                    ?: false
                val isFullyVisible = item.opt("is_fully_visible") == true &&
                    noOccludedFields
                add(
                    ParsedOrder.fromExtracted(
                        screenRank = item.decimalOrNull("screen_rank")
                            ?.toInt()
                            ?.takeIf { it in 1..100 }
                            ?: index + 1,
                        boundingBox = item.normalizedBoxOrNull("bbox_1000"),
                        isFullyVisible = isFullyVisible,
                        price = price,
                        pickupDistanceKm = pickupKm,
                        tripDistanceKm = tripKm,
                        pickupMinutes = pickupMinutes,
                        pickupNameRaw = pickupNameRaw,
                        destinationNameRaw = destinationNameRaw,
                        pickupNameNormalized = safeNormalizedAddress(
                            pickupNameRaw,
                            item.stringOrNull("pickup_name_normalized"),
                        ),
                        destinationNameNormalized = safeNormalizedAddress(
                            destinationNameRaw,
                            item.stringOrNull("destination_name_normalized"),
                        ),
                        routeStatus = item.stringOrNull("route_status"),
                        pickupRouteMinutes = item.validMinutes("pickup_route_minutes"),
                        pickupRouteDistanceKm = item.validDistance(
                            "pickup_route_distance_km",
                        ),
                        tripRouteMinutes = item.validMinutes("trip_route_minutes"),
                        tripRouteDistanceKm = item.validDistance(
                            "trip_route_distance_km",
                        ),
                        pickupRouteCongestionDistanceKm = item.validDistance(
                            "pickup_route_congestion_distance_km",
                        ),
                        tripRouteCongestionDistanceKm = item.validDistance(
                            "trip_route_congestion_distance_km",
                        ),
                        pickupRouteSevereCongestionDistanceKm = item.validDistance(
                            "pickup_route_severe_congestion_distance_km",
                        ),
                        tripRouteSevereCongestionDistanceKm = item.validDistance(
                            "trip_route_severe_congestion_distance_km",
                        ),
                        pickupRouteTrafficLights = item.validCount(
                            "pickup_route_traffic_lights",
                        ),
                        tripRouteTrafficLights = item.validCount(
                            "trip_route_traffic_lights",
                        ),
                        pickupRouteTollsYuan = item.validMoney("pickup_route_tolls_yuan"),
                        tripRouteTollsYuan = item.validMoney("trip_route_tolls_yuan"),
                        pickupRouteTollDistanceKm = item.validDistance(
                            "pickup_route_toll_distance_km",
                        ),
                        tripRouteTollDistanceKm = item.validDistance(
                            "trip_route_toll_distance_km",
                        ),
                        pickupRouteTollRoad = item.stringOrNull("pickup_route_toll_road"),
                        tripRouteTollRoad = item.stringOrNull("trip_route_toll_road"),
                        waitingMinutes = item.validMinutes("waiting_minutes"),
                        pickupMatchName = item.stringOrNull("pickup_match_name"),
                        destinationMatchName = item.stringOrNull(
                            "destination_match_name",
                        ),
                        totalOccupiedMinutes = item.validMinutes(
                            "total_occupied_minutes",
                        ),
                        effectiveHourlyIncome = item.decimalOrNull(
                            "effective_hourly_income",
                        )?.takeIf { it in 0.0..10000.0 },
                        rawText = json,
                    ),
                )
            }
        }
        return VlmScreenAnalysis(orders, json)
    }

    private fun JSONObject.validMinutes(key: String): Int? =
        decimalOrNull(key)?.toInt()?.takeIf { it in 0..1440 }

    private fun JSONObject.validDistance(key: String): Double? =
        decimalOrNull(key)?.takeIf { it in 0.0..2000.0 }

    private fun JSONObject.validCount(key: String): Int? =
        decimalOrNull(key)?.toInt()?.takeIf { it in 0..10000 }

    private fun JSONObject.validMoney(key: String): Double? =
        decimalOrNull(key)?.takeIf { it in 0.0..100000.0 }

    private fun JSONObject.decimalOrNull(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return when (val value = get(key)) {
            is Number -> value.toDouble()
            is String -> value.trim().replace(',', '.').toDoubleOrNull()
            else -> null
        }
    }

    private fun JSONObject.stringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).trim().takeIf { it.isNotEmpty() }
    }

    private fun safeNormalizedAddress(raw: String?, candidate: String?): String? {
        if (raw == null) return null
        val normalized = candidate?.trim()?.takeIf { it.isNotEmpty() } ?: return raw
        if (kotlin.math.abs(normalized.length - raw.length) > 2) return raw
        return normalized
    }

    private fun JSONObject.normalizedBoxOrNull(key: String): NormalizedOrderBox? {
        val array = optJSONArray(key) ?: return null
        if (array.length() != 4) return null
        val values = DoubleArray(4) { index ->
            when (val value = array.opt(index)) {
                is Number -> value.toDouble()
                is String -> value.trim().toDoubleOrNull() ?: return null
                else -> return null
            }
        }
        return try {
            NormalizedOrderBox(values[0], values[1], values[2], values[3])
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
