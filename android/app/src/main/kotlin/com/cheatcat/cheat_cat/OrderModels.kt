package com.cheatcat.cheat_cat

data class NormalizedOrderBox(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    init {
        require(left in 0.0..1000.0)
        require(top in 0.0..1000.0)
        require(right in 0.0..1000.0)
        require(bottom in 0.0..1000.0)
        require(right > left)
        require(bottom > top)
    }

    fun toList(): List<Double> = listOf(left, top, right, bottom)
}

data class ParsedOrder(
    val screenRank: Int,
    val boundingBox: NormalizedOrderBox?,
    val isFullyVisible: Boolean,
    val price: Double?,
    val pickupDistanceKm: Double?,
    val tripDistanceKm: Double?,
    val pickupMinutes: Int?,
    val pickupNameRaw: String? = null,
    val destinationNameRaw: String? = null,
    val pickupName: String? = null,
    val destinationName: String? = null,
    val routeStatus: String? = null,
    val pickupRouteMinutes: Int? = null,
    val pickupRouteDistanceKm: Double? = null,
    val tripRouteMinutes: Int? = null,
    val tripRouteDistanceKm: Double? = null,
    val pickupRouteCongestionDistanceKm: Double? = null,
    val tripRouteCongestionDistanceKm: Double? = null,
    val pickupRouteSevereCongestionDistanceKm: Double? = null,
    val tripRouteSevereCongestionDistanceKm: Double? = null,
    val pickupRouteTrafficLights: Int? = null,
    val tripRouteTrafficLights: Int? = null,
    val pickupRouteTollsYuan: Double? = null,
    val tripRouteTollsYuan: Double? = null,
    val pickupRouteTollDistanceKm: Double? = null,
    val tripRouteTollDistanceKm: Double? = null,
    val pickupRouteTollRoad: String? = null,
    val tripRouteTollRoad: String? = null,
    val waitingMinutes: Int? = null,
    val pickupMatchName: String? = null,
    val destinationMatchName: String? = null,
    val estimatedTotalMinutes: Int?,
    val estimatedHourlyIncome: Double?,
    val rawText: String,
) {
    val isComplete: Boolean
        get() = isFullyVisible &&
            price != null &&
            pickupDistanceKm != null &&
            tripDistanceKm != null &&
            pickupMinutes != null &&
            pickupNameRaw != null &&
            destinationNameRaw != null

    fun toMap(): Map<String, Any> = buildMap {
        put("screenRank", screenRank)
        put("isFullyVisible", isFullyVisible)
        boundingBox?.let { put("bbox1000", it.toList()) }
        price?.let { put("price", it) }
        pickupDistanceKm?.let { put("pickupDistanceKm", it) }
        tripDistanceKm?.let { put("tripDistanceKm", it) }
        pickupMinutes?.let { put("pickupMinutes", it) }
        pickupNameRaw?.let { put("pickupNameRaw", it) }
        destinationNameRaw?.let { put("destinationNameRaw", it) }
        pickupName?.let { put("pickupName", it) }
        destinationName?.let { put("destinationName", it) }
        routeStatus?.let { put("routeStatus", it) }
        pickupRouteMinutes?.let { put("pickupRouteMinutes", it) }
        pickupRouteDistanceKm?.let { put("pickupRouteDistanceKm", it) }
        tripRouteMinutes?.let { put("tripRouteMinutes", it) }
        tripRouteDistanceKm?.let { put("tripRouteDistanceKm", it) }
        pickupRouteCongestionDistanceKm?.let { put("pickupRouteCongestionDistanceKm", it) }
        tripRouteCongestionDistanceKm?.let { put("tripRouteCongestionDistanceKm", it) }
        pickupRouteSevereCongestionDistanceKm?.let {
            put("pickupRouteSevereCongestionDistanceKm", it)
        }
        tripRouteSevereCongestionDistanceKm?.let {
            put("tripRouteSevereCongestionDistanceKm", it)
        }
        pickupRouteTrafficLights?.let { put("pickupRouteTrafficLights", it) }
        tripRouteTrafficLights?.let { put("tripRouteTrafficLights", it) }
        pickupRouteTollsYuan?.let { put("pickupRouteTollsYuan", it) }
        tripRouteTollsYuan?.let { put("tripRouteTollsYuan", it) }
        pickupRouteTollDistanceKm?.let { put("pickupRouteTollDistanceKm", it) }
        tripRouteTollDistanceKm?.let { put("tripRouteTollDistanceKm", it) }
        pickupRouteTollRoad?.let { put("pickupRouteTollRoad", it) }
        tripRouteTollRoad?.let { put("tripRouteTollRoad", it) }
        waitingMinutes?.let { put("waitingMinutes", it) }
        pickupMatchName?.let { put("pickupMatchName", it) }
        destinationMatchName?.let { put("destinationMatchName", it) }
        estimatedTotalMinutes?.let { put("estimatedTotalMinutes", it) }
        estimatedHourlyIncome?.let { put("estimatedHourlyIncome", it) }
    }

    fun toEvent(message: String = "已分析当前订单画面"): Map<String, Any> = buildMap {
        put("status", "scanning")
        put("rawText", rawText)
        price?.let { put("price", it) }
        pickupDistanceKm?.let { put("pickupDistanceKm", it) }
        tripDistanceKm?.let { put("tripDistanceKm", it) }
        pickupMinutes?.let { put("pickupMinutes", it) }
        pickupName?.let { put("pickupName", it) }
        destinationName?.let { put("destinationName", it) }
        estimatedTotalMinutes?.let { put("estimatedTotalMinutes", it) }
        estimatedHourlyIncome?.let { put("estimatedHourlyIncome", it) }
        put("message", message)
    }

    companion object {
        fun fromExtracted(
            screenRank: Int,
            boundingBox: NormalizedOrderBox?,
            isFullyVisible: Boolean,
            price: Double?,
            pickupDistanceKm: Double?,
            tripDistanceKm: Double?,
            pickupMinutes: Int?,
            pickupNameRaw: String?,
            destinationNameRaw: String?,
            pickupNameNormalized: String?,
            destinationNameNormalized: String?,
            routeStatus: String?,
            pickupRouteMinutes: Int?,
            pickupRouteDistanceKm: Double?,
            tripRouteMinutes: Int?,
            tripRouteDistanceKm: Double?,
            pickupRouteCongestionDistanceKm: Double?,
            tripRouteCongestionDistanceKm: Double?,
            pickupRouteSevereCongestionDistanceKm: Double?,
            tripRouteSevereCongestionDistanceKm: Double?,
            pickupRouteTrafficLights: Int?,
            tripRouteTrafficLights: Int?,
            pickupRouteTollsYuan: Double?,
            tripRouteTollsYuan: Double?,
            pickupRouteTollDistanceKm: Double?,
            tripRouteTollDistanceKm: Double?,
            pickupRouteTollRoad: String?,
            tripRouteTollRoad: String?,
            waitingMinutes: Int?,
            pickupMatchName: String?,
            destinationMatchName: String?,
            totalOccupiedMinutes: Int?,
            effectiveHourlyIncome: Double?,
            rawText: String,
        ): ParsedOrder {
            return ParsedOrder(
                screenRank = screenRank,
                boundingBox = boundingBox,
                isFullyVisible = isFullyVisible,
                price = price,
                pickupDistanceKm = pickupDistanceKm,
                tripDistanceKm = tripDistanceKm,
                pickupMinutes = pickupMinutes,
                pickupNameRaw = pickupNameRaw,
                destinationNameRaw = destinationNameRaw,
                pickupName = pickupNameNormalized ?: pickupNameRaw,
                destinationName = destinationNameNormalized ?: destinationNameRaw,
                routeStatus = routeStatus,
                pickupRouteMinutes = pickupRouteMinutes,
                pickupRouteDistanceKm = pickupRouteDistanceKm,
                tripRouteMinutes = tripRouteMinutes,
                tripRouteDistanceKm = tripRouteDistanceKm,
                pickupRouteCongestionDistanceKm = pickupRouteCongestionDistanceKm,
                tripRouteCongestionDistanceKm = tripRouteCongestionDistanceKm,
                pickupRouteSevereCongestionDistanceKm =
                    pickupRouteSevereCongestionDistanceKm,
                tripRouteSevereCongestionDistanceKm = tripRouteSevereCongestionDistanceKm,
                pickupRouteTrafficLights = pickupRouteTrafficLights,
                tripRouteTrafficLights = tripRouteTrafficLights,
                pickupRouteTollsYuan = pickupRouteTollsYuan,
                tripRouteTollsYuan = tripRouteTollsYuan,
                pickupRouteTollDistanceKm = pickupRouteTollDistanceKm,
                tripRouteTollDistanceKm = tripRouteTollDistanceKm,
                pickupRouteTollRoad = pickupRouteTollRoad,
                tripRouteTollRoad = tripRouteTollRoad,
                waitingMinutes = waitingMinutes,
                pickupMatchName = pickupMatchName,
                destinationMatchName = destinationMatchName,
                estimatedTotalMinutes = totalOccupiedMinutes,
                estimatedHourlyIncome = effectiveHourlyIncome,
                rawText = rawText,
            )
        }
    }
}

data class VlmScreenAnalysis(
    val orders: List<ParsedOrder>,
    val rawJson: String,
) {
    val completeOrders: List<ParsedOrder> get() = orders.filter { it.isComplete }

    val rankedCompleteOrders: List<ParsedOrder>
        get() = completeOrders.sortedByDescending { it.estimatedHourlyIncome ?: 0.0 }

    val rankedRoutableOrders: List<ParsedOrder>
        get() = completeOrders
            .filter { it.routeStatus == "ok" && it.estimatedHourlyIncome != null }
            .sortedByDescending { it.estimatedHourlyIncome }

    val bestOrder: ParsedOrder?
        get() = rankedRoutableOrders.firstOrNull()

    fun toEvent(message: String): Map<String, Any> {
        val best = bestOrder
        return buildMap {
            put("status", "scanning")
            put("message", message)
            put("rawText", rawJson)
            put("orders", rankedCompleteOrders.map { it.toMap() })
            if (best != null) putAll(best.toMap())
        }
    }
}
