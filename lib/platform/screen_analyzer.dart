import 'dart:async';

import 'package:flutter/services.dart';

class AnalyzerOrder {
  const AnalyzerOrder({
    this.isFullyVisible = false,
    this.price,
    this.pickupDistanceKm,
    this.tripDistanceKm,
    this.pickupMinutes,
    this.pickupName,
    this.destinationName,
    this.pickupNameRaw,
    this.destinationNameRaw,
    this.routeStatus,
    this.pickupRouteMinutes,
    this.pickupRouteDistanceKm,
    this.tripRouteMinutes,
    this.tripRouteDistanceKm,
    this.pickupRouteCongestionDistanceKm,
    this.tripRouteCongestionDistanceKm,
    this.pickupRouteSevereCongestionDistanceKm,
    this.tripRouteSevereCongestionDistanceKm,
    this.pickupRouteTrafficLights,
    this.tripRouteTrafficLights,
    this.pickupRouteTollsYuan,
    this.tripRouteTollsYuan,
    this.pickupRouteTollDistanceKm,
    this.tripRouteTollDistanceKm,
    this.pickupRouteTollRoad,
    this.tripRouteTollRoad,
    this.waitingMinutes,
    this.pickupMatchName,
    this.destinationMatchName,
    this.estimatedTotalMinutes,
    this.estimatedHourlyIncome,
  });

  final bool isFullyVisible;
  final double? price;
  final double? pickupDistanceKm;
  final double? tripDistanceKm;
  final int? pickupMinutes;
  final String? pickupName;
  final String? destinationName;
  final String? pickupNameRaw;
  final String? destinationNameRaw;
  final String? routeStatus;
  final int? pickupRouteMinutes;
  final double? pickupRouteDistanceKm;
  final int? tripRouteMinutes;
  final double? tripRouteDistanceKm;
  final double? pickupRouteCongestionDistanceKm;
  final double? tripRouteCongestionDistanceKm;
  final double? pickupRouteSevereCongestionDistanceKm;
  final double? tripRouteSevereCongestionDistanceKm;
  final int? pickupRouteTrafficLights;
  final int? tripRouteTrafficLights;
  final double? pickupRouteTollsYuan;
  final double? tripRouteTollsYuan;
  final double? pickupRouteTollDistanceKm;
  final double? tripRouteTollDistanceKm;
  final String? pickupRouteTollRoad;
  final String? tripRouteTollRoad;
  final int? waitingMinutes;
  final String? pickupMatchName;
  final String? destinationMatchName;
  final int? estimatedTotalMinutes;
  final double? estimatedHourlyIncome;

  factory AnalyzerOrder.fromMap(Map<Object?, Object?> map) {
    double? decimal(String key) => (map[key] as num?)?.toDouble();
    int? integer(String key) => (map[key] as num?)?.toInt();

    return AnalyzerOrder(
      isFullyVisible: map['isFullyVisible'] as bool? ?? false,
      price: decimal('price'),
      pickupDistanceKm: decimal('pickupDistanceKm'),
      tripDistanceKm: decimal('tripDistanceKm'),
      pickupMinutes: integer('pickupMinutes'),
      pickupName: map['pickupName'] as String?,
      destinationName: map['destinationName'] as String?,
      pickupNameRaw: map['pickupNameRaw'] as String?,
      destinationNameRaw: map['destinationNameRaw'] as String?,
      routeStatus: map['routeStatus'] as String?,
      pickupRouteMinutes: integer('pickupRouteMinutes'),
      pickupRouteDistanceKm: decimal('pickupRouteDistanceKm'),
      tripRouteMinutes: integer('tripRouteMinutes'),
      tripRouteDistanceKm: decimal('tripRouteDistanceKm'),
      pickupRouteCongestionDistanceKm:
          decimal('pickupRouteCongestionDistanceKm'),
      tripRouteCongestionDistanceKm: decimal('tripRouteCongestionDistanceKm'),
      pickupRouteSevereCongestionDistanceKm:
          decimal('pickupRouteSevereCongestionDistanceKm'),
      tripRouteSevereCongestionDistanceKm:
          decimal('tripRouteSevereCongestionDistanceKm'),
      pickupRouteTrafficLights: integer('pickupRouteTrafficLights'),
      tripRouteTrafficLights: integer('tripRouteTrafficLights'),
      pickupRouteTollsYuan: decimal('pickupRouteTollsYuan'),
      tripRouteTollsYuan: decimal('tripRouteTollsYuan'),
      pickupRouteTollDistanceKm: decimal('pickupRouteTollDistanceKm'),
      tripRouteTollDistanceKm: decimal('tripRouteTollDistanceKm'),
      pickupRouteTollRoad: map['pickupRouteTollRoad'] as String?,
      tripRouteTollRoad: map['tripRouteTollRoad'] as String?,
      waitingMinutes: integer('waitingMinutes'),
      pickupMatchName: map['pickupMatchName'] as String?,
      destinationMatchName: map['destinationMatchName'] as String?,
      estimatedTotalMinutes: integer('estimatedTotalMinutes'),
      estimatedHourlyIncome: decimal('estimatedHourlyIncome'),
    );
  }
}

class AnalyzerSnapshot {
  const AnalyzerSnapshot({
    required this.status,
    this.price,
    this.pickupDistanceKm,
    this.tripDistanceKm,
    this.pickupMinutes,
    this.estimatedTotalMinutes,
    this.estimatedHourlyIncome,
    this.message,
    this.rawText,
    this.orders = const [],
  });

  final String status;
  final double? price;
  final double? pickupDistanceKm;
  final double? tripDistanceKm;
  final int? pickupMinutes;
  final int? estimatedTotalMinutes;
  final double? estimatedHourlyIncome;
  final String? message;
  final String? rawText;
  final List<AnalyzerOrder> orders;

  bool get hasOrder => price != null || orders.isNotEmpty;

  factory AnalyzerSnapshot.fromMap(Map<Object?, Object?> map) {
    double? decimal(String key) => (map[key] as num?)?.toDouble();
    int? integer(String key) => (map[key] as num?)?.toInt();

    return AnalyzerSnapshot(
      status: map['status'] as String? ?? 'idle',
      price: decimal('price'),
      pickupDistanceKm: decimal('pickupDistanceKm'),
      tripDistanceKm: decimal('tripDistanceKm'),
      pickupMinutes: integer('pickupMinutes'),
      estimatedTotalMinutes: integer('estimatedTotalMinutes'),
      estimatedHourlyIncome: decimal('estimatedHourlyIncome'),
      message: map['message'] as String?,
      rawText: map['rawText'] as String?,
      orders: (map['orders'] as List<Object?>? ?? const [])
          .whereType<Map<Object?, Object?>>()
          .map(AnalyzerOrder.fromMap)
          .toList(growable: false),
    );
  }
}

class ScreenAnalyzerPlatform {
  const ScreenAnalyzerPlatform();

  static const _methods = MethodChannel('cheat_cat/screen_analyzer');
  static const _events = EventChannel('cheat_cat/screen_analyzer_events');

  Stream<AnalyzerSnapshot> get events => _events
      .receiveBroadcastStream()
      .map((event) => AnalyzerSnapshot.fromMap(event as Map<Object?, Object?>));

  Future<Map<String, Object?>> capabilities() async {
    final result =
        await _methods.invokeMapMethod<String, Object?>('capabilities');
    return result ?? const {};
  }

  Future<void> requestOverlayPermission() =>
      _methods.invokeMethod<void>('requestOverlayPermission');

  Future<void> requestLocationPermission() =>
      _methods.invokeMethod<void>('requestLocationPermission');

  Future<void> start() => _methods.invokeMethod<void>('startCapture');

  Future<void> stop() => _methods.invokeMethod<void>('stopCapture');

  Future<void> calibrateScanRegion() =>
      _methods.invokeMethod<void>('calibrateScanRegion');
}
