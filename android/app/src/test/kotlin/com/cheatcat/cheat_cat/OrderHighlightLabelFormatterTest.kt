package com.cheatcat.cheat_cat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OrderHighlightLabelFormatterTest {
    @Test
    fun usesCompleteDriverFacingChineseLabels() {
        val order = ParsedOrder(
            screenRank = 1,
            boundingBox = null,
            isFullyVisible = true,
            price = 41.0,
            pickupDistanceKm = 4.5,
            tripDistanceKm = 10.5,
            pickupMinutes = 14,
            pickupRouteMinutes = 32,
            pickupRouteDistanceKm = 7.0,
            tripRouteMinutes = 25,
            tripRouteDistanceKm = 10.5,
            pickupRouteCongestionDistanceKm = 2.7,
            tripRouteCongestionDistanceKm = 0.0,
            pickupRouteSevereCongestionDistanceKm = 1.6,
            tripRouteSevereCongestionDistanceKm = 0.0,
            pickupRouteTrafficLights = 2,
            tripRouteTrafficLights = 16,
            pickupRouteTollsYuan = 0.0,
            tripRouteTollsYuan = 0.0,
            pickupRouteTollDistanceKm = 0.0,
            tripRouteTollDistanceKm = 0.0,
            waitingMinutes = 3,
            estimatedTotalMinutes = 60,
            estimatedHourlyIncome = 42.0,
            rawText = "{}",
        )

        val label = OrderHighlightLabelFormatter.format(order, 0)

        assertEquals(
            """
            推荐订单 · 预计毛时薪 42元/小时
            时间：接驾32分钟 + 等客3分钟 + 载客25分钟
            里程：接驾7.0公里 + 载客10.5公里 = 总行驶17.5公里
            红绿灯：接驾2个 + 载客16个 = 共18个
            拥堵：接驾2.7公里 + 载客0.0公里 = 共2.7公里（严重拥堵1.6公里）
            收费：当前规划路线无道路收费
            """.trimIndent(),
            label,
        )
        assertFalse(label.contains("¥/h"))
        assertFalse(label.contains("空7.0"))
    }
}
