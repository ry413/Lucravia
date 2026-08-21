package com.cheatcat.cheat_cat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VlmOrderResponseParserTest {
    @Test
    fun parsesMultipleOrdersAndChoosesHighestAmapHourlyIncome() {
        val result = VlmOrderResponseParser.parse(
            """
            {
              "orders": [
                {
                  "screen_rank": 1,
                  "bbox_1000": [28, 190, 972, 520],
                  "is_fully_visible": true,
                  "occluded_fields": [],
                  "price": 28.71,
                  "pickup_distance_km": 0.7,
                  "pickup_minutes": 1,
                  "trip_distance_km": 8.6,
                  "pickup_name": "深圳市业园",
                  "destination_name": "南山区",
                  "route_status": "ok",
                  "pickup_route_minutes": 2,
                  "trip_route_minutes": 16,
                  "waiting_minutes": 3,
                  "total_occupied_minutes": 21,
                  "effective_hourly_income": 82
                },
                {
                  "screen_rank": 2,
                  "bbox_1000": [28, 535, 972, 910],
                  "is_fully_visible": true,
                  "occluded_fields": [],
                  "price": "30.98",
                  "pickup_distance_km": "1.5",
                  "pickup_minutes": 3,
                  "trip_distance_km": "8.1",
                  "pickup_name": "深圳大学城",
                  "destination_name": "福田区",
                  "route_status": "ok",
                  "pickup_route_minutes": 4,
                  "pickup_route_distance_km": 1.8,
                  "trip_route_minutes": 15,
                  "trip_route_distance_km": 8.3,
                  "pickup_route_congestion_distance_km": 0.4,
                  "trip_route_congestion_distance_km": 0.8,
                  "pickup_route_severe_congestion_distance_km": 0.1,
                  "trip_route_severe_congestion_distance_km": 0.2,
                  "pickup_route_traffic_lights": 3,
                  "trip_route_traffic_lights": 12,
                  "pickup_route_tolls_yuan": 0,
                  "trip_route_tolls_yuan": 6,
                  "pickup_route_toll_distance_km": 0,
                  "trip_route_toll_distance_km": 4.2,
                  "trip_route_toll_road": "南坪快速",
                  "waiting_minutes": 3,
                  "total_occupied_minutes": 22,
                  "effective_hourly_income": 84
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(2, result.completeOrders.size)
        assertEquals(30.98, result.bestOrder!!.price!!, 0.001)
        assertEquals("深圳大学城", result.bestOrder!!.pickupName)
        assertEquals(2, result.bestOrder!!.screenRank)
        assertEquals(535.0, result.bestOrder!!.boundingBox!!.top, 0.001)
        assertEquals(0.4, result.bestOrder!!.pickupRouteCongestionDistanceKm!!, 0.001)
        assertEquals(12, result.bestOrder!!.tripRouteTrafficLights)
        assertEquals(6.0, result.bestOrder!!.tripRouteTollsYuan!!, 0.001)
        assertEquals("南坪快速", result.bestOrder!!.tripRouteTollRoad)
    }

    @Test
    fun keepsPartialCardsButDoesNotOfferThemAsBestOrder() {
        val result = VlmOrderResponseParser.parse(
            """
            {"orders":[{
              "is_fully_visible": false,
              "occluded_fields": ["pickup_distance_km", "pickup_name"],
              "price": 51.22,
              "pickup_distance_km": null,
              "pickup_minutes": 2,
              "trip_distance_km": 64.6,
              "pickup_name": null,
              "destination_name": "东莞市"
            }]}
            """.trimIndent(),
        )

        assertEquals(1, result.orders.size)
        assertNull(result.bestOrder)
    }

    @Test
    fun acceptsJsonCodeFenceDefensively() {
        val result = VlmOrderResponseParser.parse("```json\n{\"orders\":[]}\n```")

        assertEquals(0, result.orders.size)
    }

    @Test
    fun eventContainsEveryCompleteOrderSortedByAmapHourlyIncome() {
        val result = VlmOrderResponseParser.parse(
            """
            {"orders":[
              {"is_fully_visible":true,"occluded_fields":[],"price":28.71,"pickup_distance_km":0.7,"pickup_minutes":1,"trip_distance_km":8.6,"pickup_name":"甲地","destination_name":"乙地","route_status":"ok","effective_hourly_income":82},
              {"is_fully_visible":true,"occluded_fields":[],"price":30.98,"pickup_distance_km":1.5,"pickup_minutes":3,"trip_distance_km":8.1,"pickup_name":"丙地","destination_name":"丁地","route_status":"ok","effective_hourly_income":84}
            ]}
            """.trimIndent(),
        )

        @Suppress("UNCHECKED_CAST")
        val orders = result.toEvent("ok")["orders"] as List<Map<String, Any>>

        assertEquals(2, orders.size)
        assertEquals(30.98, orders.first()["price"] as Double, 0.001)
        assertEquals(28.71, orders.last()["price"] as Double, 0.001)
    }

    @Test
    fun rejectsInvalidBoundingBoxWithoutDiscardingOrder() {
        val result = VlmOrderResponseParser.parse(
            """{"orders":[{"screen_rank":1,"bbox_1000":[900,100,100,500],"is_fully_visible":true,"occluded_fields":[],"price":20,"pickup_distance_km":1,"pickup_minutes":2,"trip_distance_km":6,"pickup_name":"甲地","destination_name":"乙地"}]}""",
        )

        assertEquals(1, result.completeOrders.size)
        assertNull(result.bestOrder!!.boundingBox)
    }

    @Test
    fun requiresBothVisibleAddressesAndExplicitVisibilityAudit() {
        val missingDestination = VlmOrderResponseParser.parse(
            """{"orders":[{"is_fully_visible":true,"occluded_fields":[],"price":20,"pickup_distance_km":1,"pickup_minutes":2,"trip_distance_km":6,"pickup_name":"甲地","destination_name":null}]}""",
        )
        val occludedDigit = VlmOrderResponseParser.parse(
            """{"orders":[{"is_fully_visible":true,"occluded_fields":["pickup_distance_km"],"price":20,"pickup_distance_km":1.5,"pickup_minutes":2,"trip_distance_km":6,"pickup_name":"甲地","destination_name":"乙地"}]}""",
        )

        assertNull(missingDestination.bestOrder)
        assertNull(occludedDigit.bestOrder)
    }

    @Test
    fun usesConservativeNormalizedAddress() {
        val result = VlmOrderResponseParser.parse(
            """{"orders":[{"is_fully_visible":true,"occluded_fields":[],"price":20,"pickup_distance_km":1,"pickup_minutes":2,"trip_distance_km":6,"pickup_name":"科技技园","pickup_name_normalized":"科技园","destination_name":"南山 区","destination_name_normalized":"南山区"}]}""",
        )

        assertEquals("科技园", result.bestOrder!!.pickupName)
        assertEquals("南山区", result.bestOrder!!.destinationName)
        assertEquals("科技技园", result.bestOrder!!.pickupNameRaw)
    }
}
