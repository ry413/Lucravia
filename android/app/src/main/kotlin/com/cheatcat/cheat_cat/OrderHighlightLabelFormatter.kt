package com.cheatcat.cheat_cat

import java.util.Locale
import kotlin.math.roundToInt

/** Produces driver-facing labels without terse developer abbreviations. */
object OrderHighlightLabelFormatter {
    fun format(order: ParsedOrder, rankIndex: Int): String {
        val rank = when (rankIndex) {
            0 -> "推荐订单"
            1 -> "备选订单"
            else -> "第${rankIndex + 1}名订单"
        }
        val lines = mutableListOf<String>()
        val hourly = order.estimatedHourlyIncome
        lines += if (hourly != null) {
            "$rank · 预计毛时薪 ${hourly.roundToInt()}元/小时"
        } else {
            "$rank · 高德路线暂不可用"
        }

        val pickupMinutes = order.pickupRouteMinutes
        val waitingMinutes = order.waitingMinutes
        val tripMinutes = order.tripRouteMinutes
        if (pickupMinutes != null && waitingMinutes != null && tripMinutes != null) {
            lines += "时间：接驾${pickupMinutes}分钟 + 等客${waitingMinutes}分钟 + " +
                "载客${tripMinutes}分钟"
        }

        val pickupKm = order.pickupRouteDistanceKm
        val tripKm = order.tripRouteDistanceKm
        if (pickupKm != null && tripKm != null) {
            lines += "里程：接驾${decimal(pickupKm)}公里 + 载客${decimal(tripKm)}公里 = " +
                "总行驶${decimal(pickupKm + tripKm)}公里"
        }

        val pickupLights = order.pickupRouteTrafficLights
        val tripLights = order.tripRouteTrafficLights
        if (pickupLights != null && tripLights != null) {
            lines += "红绿灯：接驾${pickupLights}个 + 载客${tripLights}个 = " +
                "共${pickupLights + tripLights}个"
        }

        val pickupCongestion = order.pickupRouteCongestionDistanceKm
        val tripCongestion = order.tripRouteCongestionDistanceKm
        if (pickupCongestion != null && tripCongestion != null) {
            val total = pickupCongestion + tripCongestion
            val severe = (order.pickupRouteSevereCongestionDistanceKm ?: 0.0) +
                (order.tripRouteSevereCongestionDistanceKm ?: 0.0)
            lines += if (total > 0.0) {
                "拥堵：接驾${decimal(pickupCongestion)}公里 + " +
                    "载客${decimal(tripCongestion)}公里 = 共${decimal(total)}公里" +
                    if (severe > 0.0) "（严重拥堵${decimal(severe)}公里）" else ""
            } else {
                "拥堵：当前规划路线未发现拥堵路段"
            }
        }

        val pickupTolls = order.pickupRouteTollsYuan
        val tripTolls = order.tripRouteTollsYuan
        if (pickupTolls != null && tripTolls != null) {
            val totalTolls = pickupTolls + tripTolls
            val tollKm = (order.pickupRouteTollDistanceKm ?: 0.0) +
                (order.tripRouteTollDistanceKm ?: 0.0)
            lines += if (totalTolls > 0.0) {
                "收费：预计${money(totalTolls)}元，收费路段${decimal(tollKm)}公里"
            } else {
                "收费：当前规划路线无道路收费"
            }
        }
        return lines.joinToString("\n")
    }

    private fun decimal(value: Double): String =
        String.format(Locale.US, "%.1f", value)

    private fun money(value: Double): String =
        if (value % 1.0 == 0.0) value.roundToInt().toString() else decimal(value)
}
