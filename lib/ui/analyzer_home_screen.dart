import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../platform/screen_analyzer.dart';

const _ink = Color(0xFF17213A);
const _coral = Color(0xFFFF5364);
const _orange = Color(0xFFFF7A19);
const _muted = Color(0xFF737989);

class AnalyzerHomeScreen extends StatefulWidget {
  const AnalyzerHomeScreen({super.key});

  @override
  State<AnalyzerHomeScreen> createState() => _AnalyzerHomeScreenState();
}

class _AnalyzerHomeScreenState extends State<AnalyzerHomeScreen>
    with WidgetsBindingObserver {
  final _platform = const ScreenAnalyzerPlatform();
  StreamSubscription<AnalyzerSnapshot>? _subscription;
  AnalyzerSnapshot _snapshot = const AnalyzerSnapshot(status: 'idle');
  bool _overlayGranted = false;
  bool _locationGranted = false;
  bool _running = false;
  bool _busy = true;
  bool _vlmServerConfigured = false;
  bool _scanRegionConfigured = false;
  double _scanTopRatio = 0;
  double _scanBottomRatio = 1;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _subscription = _platform.events.listen(_onEvent);
    _loadCapabilities();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _subscription?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _loadCapabilities();
  }

  void _onEvent(AnalyzerSnapshot snapshot) {
    if (!mounted) return;
    setState(() {
      _snapshot = snapshot;
      _running = snapshot.status != 'idle' && snapshot.status != 'stopped';
      _busy = false;
    });
  }

  Future<void> _loadCapabilities() async {
    try {
      final capabilities = await _platform.capabilities();
      if (!mounted) return;
      setState(() {
        _overlayGranted = capabilities['overlayGranted'] as bool? ?? false;
        _locationGranted = capabilities['locationGranted'] as bool? ?? false;
        _running = capabilities['running'] as bool? ?? false;
        _vlmServerConfigured =
            capabilities['vlmServerConfigured'] as bool? ?? false;
        _scanRegionConfigured =
            capabilities['scanRegionConfigured'] as bool? ?? false;
        _scanTopRatio = (capabilities['scanTopRatio'] as num?)?.toDouble() ?? 0;
        _scanBottomRatio =
            (capabilities['scanBottomRatio'] as num?)?.toDouble() ?? 1;
        _busy = false;
      });
    } on PlatformException catch (error) {
      _showError(error.message ?? error.code);
    }
  }

  Future<void> _start() async {
    if (!_vlmServerConfigured) {
      _showError('请在工程根目录 .env 配置 VLM_SERVER_URL 后重新构建');
      return;
    }
    if (!_overlayGranted) {
      await _platform.requestOverlayPermission();
      return;
    }
    if (!_locationGranted) {
      await _platform.requestLocationPermission();
      return;
    }
    setState(() => _busy = true);
    try {
      await _platform.start();
    } on PlatformException catch (error) {
      if (mounted) setState(() => _busy = false);
      _showError(error.message ?? error.code);
    }
  }

  Future<void> _stop() async {
    setState(() => _busy = true);
    try {
      await _platform.stop();
    } finally {
      if (mounted) {
        setState(() {
          _busy = false;
          _running = false;
        });
      }
    }
  }

  Future<void> _calibrateScanRegion() async {
    if (!_overlayGranted) {
      await _platform.requestOverlayPermission();
      return;
    }
    setState(() => _busy = true);
    try {
      await _platform.calibrateScanRegion();
    } on PlatformException catch (error) {
      _showError(error.message ?? error.code);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _showError(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message)),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 20, 20, 28),
          children: [
            const _BrandHeader(),
            const SizedBox(height: 26),
            _StatusHero(running: _running, snapshot: _snapshot),
            if (_snapshot.orders.isNotEmpty) ...[
              const SizedBox(height: 12),
              _OrdersCard(orders: _snapshot.orders),
            ],
            const SizedBox(height: 20),
            _PermissionStep(
              number: '1',
              title: '局域网 VLM 服务',
              description: _vlmServerConfigured
                  ? '已从工程根目录 .env 编译服务地址。'
                  : '未配置 VLM_SERVER_URL；写入 .env 后需要重新构建 APK。',
              complete: _vlmServerConfigured,
              actionLabel: '检查 .env',
              onPressed: null,
            ),
            const SizedBox(height: 12),
            _PermissionStep(
              number: '2',
              title: '允许悬浮结果窗',
              description: '在对应订单卡片上显示排行和实际有效时薪。',
              complete: _overlayGranted,
              actionLabel: '去授权',
              onPressed: _overlayGranted
                  ? null
                  : () => _platform.requestOverlayPermission(),
            ),
            const SizedBox(height: 12),
            _PermissionStep(
              number: '3',
              title: '允许获取当前位置',
              description: '用于计算当前位置到上车点的实时接驾路线；分析期间持续更新。',
              complete: _locationGranted,
              actionLabel: '去授权',
              onPressed: _locationGranted
                  ? null
                  : () => _platform.requestLocationPermission(),
            ),
            const SizedBox(height: 12),
            _PermissionStep(
              number: '4',
              title: '校准识图区域',
              description: _scanRegionConfigured
                  ? '当前识别屏幕纵向 ${(_scanTopRatio * 100).round()}%–${(_scanBottomRatio * 100).round()}%；在目标页面点校准悬浮按钮可调整。'
                  : '点击后先打开目标页面，再点校准悬浮按钮拖动上下边界。',
              complete: _scanRegionConfigured,
              actionLabel: _scanRegionConfigured ? '重新校准' : '去校准',
              showActionWhenComplete: true,
              onPressed: _overlayGranted && !_running && !_busy
                  ? _calibrateScanRegion
                  : null,
            ),
            const SizedBox(height: 12),
            _PermissionStep(
              number: '5',
              title: '开始屏幕分析',
              description: 'Android 会弹出录屏授权；每次会话都需要确认。',
              complete: _running,
              actionLabel: _running ? '分析中' : '开始',
              onPressed:
                  _overlayGranted && _locationGranted && !_running && !_busy
                      ? _start
                      : null,
            ),
            const SizedBox(height: 20),
            SizedBox(
              height: 56,
              child: _running
                  ? OutlinedButton.icon(
                      onPressed: _busy ? null : _stop,
                      icon: const Icon(Icons.stop_circle_outlined),
                      label: const Text('停止屏幕分析'),
                    )
                  : FilledButton.icon(
                      key: const Key('start-analysis'),
                      onPressed: _busy || !_vlmServerConfigured ? null : _start,
                      style: FilledButton.styleFrom(
                        backgroundColor: _ink,
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(16),
                        ),
                      ),
                      icon: _busy
                          ? const SizedBox.square(
                              dimension: 18,
                              child: CircularProgressIndicator(
                                color: Colors.white,
                                strokeWidth: 2,
                              ),
                            )
                          : const Icon(Icons.play_arrow_rounded),
                      label: Text(
                        !_vlmServerConfigured
                            ? '先配置 .env 并重新构建'
                            : (!_overlayGranted
                                ? '先授权悬浮窗'
                                : (!_locationGranted ? '先授权定位' : '开始屏幕分析')),
                        style: const TextStyle(
                          fontSize: 17,
                          fontWeight: FontWeight.w800,
                        ),
                      ),
                    ),
            ),
            const SizedBox(height: 20),
            const _PrivacyCard(),
          ],
        ),
      ),
    );
  }
}

class _BrandHeader extends StatelessWidget {
  const _BrandHeader();

  @override
  Widget build(BuildContext context) {
    return const Row(
      children: [
        _Logo(),
        SizedBox(width: 13),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '跑单助手',
                style: TextStyle(
                  color: _ink,
                  fontSize: 23,
                  fontWeight: FontWeight.w900,
                ),
              ),
              Text(
                '局域网 VLM 订单价值分析',
                style: TextStyle(color: _muted, fontSize: 13),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _Logo extends StatelessWidget {
  const _Logo();

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 50,
      height: 50,
      decoration: const BoxDecoration(
        gradient: LinearGradient(colors: [_coral, _orange]),
        borderRadius: BorderRadius.all(Radius.circular(16)),
      ),
      child:
          const Icon(Icons.center_focus_strong, color: Colors.white, size: 28),
    );
  }
}

class _StatusHero extends StatelessWidget {
  const _StatusHero({required this.running, required this.snapshot});

  final bool running;
  final AnalyzerSnapshot snapshot;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        gradient: const LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [Color(0xFF1E2A49), Color(0xFF101729)],
        ),
        borderRadius: BorderRadius.circular(24),
        boxShadow: const [
          BoxShadow(
              color: Color(0x3317213A), blurRadius: 20, offset: Offset(0, 8)),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                width: 10,
                height: 10,
                decoration: BoxDecoration(
                  color: running
                      ? const Color(0xFF35D69B)
                      : const Color(0xFF7E879E),
                  shape: BoxShape.circle,
                  boxShadow: running
                      ? const [
                          BoxShadow(color: Color(0x8835D69B), blurRadius: 8)
                        ]
                      : null,
                ),
              ),
              const SizedBox(width: 9),
              Text(
                running ? '正在识别屏幕' : '分析器未启动',
                style: const TextStyle(
                    color: Colors.white, fontWeight: FontWeight.w800),
              ),
            ],
          ),
          const SizedBox(height: 18),
          if (snapshot.hasOrder)
            Row(
              children: [
                _HeroMetric(
                    label: '识别价格',
                    value: '¥${snapshot.price!.toStringAsFixed(2)}'),
                _HeroMetric(
                  label: '行程里程',
                  value: snapshot.tripDistanceKm == null
                      ? '--'
                      : '${snapshot.tripDistanceKm!.toStringAsFixed(1)} km',
                ),
                _HeroMetric(
                  label: '高德有效时薪',
                  value: snapshot.estimatedHourlyIncome == null
                      ? '--'
                      : '¥${snapshot.estimatedHourlyIncome!.toStringAsFixed(0)}/h',
                ),
              ],
            )
          else
            Text(
              running
                  ? (snapshot.message ?? '请切换到网约车司机端订单页面')
                  : '启动后切换到司机端，识别结果将通过悬浮窗显示。',
              style: const TextStyle(color: Color(0xFFB8C0D4), height: 1.5),
            ),
        ],
      ),
    );
  }
}

class _HeroMetric extends StatelessWidget {
  const _HeroMetric({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Expanded(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(value,
              style: const TextStyle(
                  color: Colors.white,
                  fontSize: 18,
                  fontWeight: FontWeight.w900)),
          const SizedBox(height: 3),
          Text(label,
              style: const TextStyle(color: Color(0xFF99A3BC), fontSize: 11)),
        ],
      ),
    );
  }
}

class _OrdersCard extends StatelessWidget {
  const _OrdersCard({required this.orders});

  final List<AnalyzerOrder> orders;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(18),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            '本屏完整订单 · ${orders.length} 张',
            style: const TextStyle(
              color: _ink,
              fontSize: 16,
              fontWeight: FontWeight.w900,
            ),
          ),
          const SizedBox(height: 4),
          const Text(
            '按高德实时路线的有效时薪从高到低排列',
            style: TextStyle(color: _muted, fontSize: 11),
          ),
          const SizedBox(height: 10),
          for (var index = 0; index < orders.length; index++) ...[
            if (index > 0) const Divider(height: 20),
            _OrderRow(rank: index + 1, order: orders[index]),
          ],
        ],
      ),
    );
  }
}

class _OrderRow extends StatelessWidget {
  const _OrderRow({required this.rank, required this.order});

  final int rank;
  final AnalyzerOrder order;

  @override
  Widget build(BuildContext context) {
    final price = order.price?.toStringAsFixed(2) ?? '--';
    final pickup = order.pickupDistanceKm?.toStringAsFixed(1) ?? '--';
    final trip = order.tripDistanceKm?.toStringAsFixed(1) ?? '--';
    final minutes = order.pickupMinutes?.toString() ?? '--';
    final hourly = order.estimatedHourlyIncome?.toStringAsFixed(0) ?? '--';
    final pickupRoute = order.pickupRouteMinutes?.toString() ?? '--';
    final waiting = order.waitingMinutes?.toString() ?? '--';
    final tripRoute = order.tripRouteMinutes?.toString() ?? '--';
    final pickupRouteKm =
        order.pickupRouteDistanceKm?.toStringAsFixed(1) ?? '--';
    final tripRouteKm = order.tripRouteDistanceKm?.toStringAsFixed(1) ?? '--';
    final pickupCongestion = order.pickupRouteCongestionDistanceKm;
    final tripCongestion = order.tripRouteCongestionDistanceKm;
    final pickupSevere = order.pickupRouteSevereCongestionDistanceKm;
    final tripSevere = order.tripRouteSevereCongestionDistanceKm;
    final pickupLights = order.pickupRouteTrafficLights;
    final tripLights = order.tripRouteTrafficLights;
    final pickupTolls = order.pickupRouteTollsYuan;
    final tripTolls = order.tripRouteTollsYuan;
    final pickupTollKm = order.pickupRouteTollDistanceKm;
    final tripTollKm = order.tripRouteTollDistanceKm;
    final trafficDetails = <String>[
      if (pickupCongestion != null && tripCongestion != null)
        '拥堵：接驾 ${pickupCongestion.toStringAsFixed(1)} km + 行程 ${tripCongestion.toStringAsFixed(1)} km'
            '（严重 ${((pickupSevere ?? 0) + (tripSevere ?? 0)).toStringAsFixed(1)} km）',
      if (pickupLights != null && tripLights != null)
        '红绿灯：接驾 $pickupLights + 行程 $tripLights = ${pickupLights + tripLights} 个',
    ];
    final totalTolls = pickupTolls != null && tripTolls != null
        ? pickupTolls + tripTolls
        : null;
    final totalTollKm = (pickupTollKm ?? 0) + (tripTollKm ?? 0);
    final tollRoads = [order.pickupRouteTollRoad, order.tripRouteTollRoad]
        .whereType<String>()
        .where((value) => value.isNotEmpty)
        .toSet()
        .join('、');
    final route = [order.pickupName, order.destinationName]
        .whereType<String>()
        .where((value) => value.isNotEmpty)
        .join(' → ');
    final amapRoute = [order.pickupMatchName, order.destinationMatchName]
        .whereType<String>()
        .where((value) => value.isNotEmpty)
        .join(' → ');
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          width: 28,
          height: 28,
          alignment: Alignment.center,
          decoration: const BoxDecoration(
            color: Color(0xFFFFECEE),
            shape: BoxShape.circle,
          ),
          child: Text(
            '$rank',
            style: const TextStyle(color: _coral, fontWeight: FontWeight.w900),
          ),
        ),
        const SizedBox(width: 10),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '¥$price  ·  接$pickup km/$minutes 分  ·  行$trip km',
                style:
                    const TextStyle(color: _ink, fontWeight: FontWeight.w800),
              ),
              if (route.isNotEmpty) ...[
                const SizedBox(height: 3),
                Text(
                  route,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(color: _muted, fontSize: 11),
                ),
              ],
              const SizedBox(height: 3),
              Text(
                order.routeStatus == 'ok'
                    ? '高德：接驾 $pickupRoute 分/$pickupRouteKm km + 等客 $waiting 分 + 行程 $tripRoute 分/$tripRouteKm km'
                    : '高德算路暂不可用',
                style: const TextStyle(color: _muted, fontSize: 11),
              ),
              if (order.routeStatus == 'ok' && trafficDetails.isNotEmpty) ...[
                const SizedBox(height: 2),
                for (final detail in trafficDetails)
                  Text(
                    detail,
                    style: const TextStyle(color: _muted, fontSize: 10),
                  ),
              ],
              if (order.routeStatus == 'ok' && totalTolls != null) ...[
                const SizedBox(height: 2),
                Text(
                  totalTolls > 0
                      ? '收费：¥${totalTolls.toStringAsFixed(1)} / ${totalTollKm.toStringAsFixed(1)} km'
                          '${tollRoads.isEmpty ? '' : ' · $tollRoads'}'
                      : '收费：无收费道路',
                  style: const TextStyle(color: _muted, fontSize: 10),
                ),
              ],
              if (order.routeStatus == 'ok' && amapRoute.isNotEmpty) ...[
                const SizedBox(height: 2),
                Text(
                  '高德匹配：$amapRoute',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(color: _muted, fontSize: 10),
                ),
              ],
            ],
          ),
        ),
        const SizedBox(width: 8),
        Text(
          order.routeStatus == 'ok' ? '¥$hourly/h' : '待算路',
          style: const TextStyle(
            color: _orange,
            fontWeight: FontWeight.w900,
          ),
        ),
      ],
    );
  }
}

class _PermissionStep extends StatelessWidget {
  const _PermissionStep({
    required this.number,
    required this.title,
    required this.description,
    required this.complete,
    required this.actionLabel,
    required this.onPressed,
    this.showActionWhenComplete = false,
  });

  final String number;
  final String title;
  final String description;
  final bool complete;
  final String actionLabel;
  final VoidCallback? onPressed;
  final bool showActionWhenComplete;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
          color: Colors.white, borderRadius: BorderRadius.circular(18)),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 34,
            height: 34,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color:
                  complete ? const Color(0xFFE4F8F0) : const Color(0xFFFFECEE),
              shape: BoxShape.circle,
            ),
            child: complete
                ? const Icon(Icons.check, color: Color(0xFF15966D), size: 19)
                : Text(number,
                    style: const TextStyle(
                        color: _coral, fontWeight: FontWeight.w900)),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title,
                    style: const TextStyle(
                        color: _ink,
                        fontSize: 16,
                        fontWeight: FontWeight.w800)),
                const SizedBox(height: 4),
                Text(description,
                    style: const TextStyle(
                        color: _muted, fontSize: 12, height: 1.4)),
              ],
            ),
          ),
          if (!complete || showActionWhenComplete)
            TextButton(onPressed: onPressed, child: Text(actionLabel)),
        ],
      ),
    );
  }
}

class _PrivacyCard extends StatelessWidget {
  const _PrivacyCard();

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: const Color(0xFFFFF7E9),
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: const Color(0xFFFFE2AE)),
      ),
      child: const Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(Icons.shield_outlined, color: _orange, size: 22),
          SizedBox(width: 11),
          Expanded(
            child: Text(
              '稳定的新订单画面只上传到同一局域网的 VLM 服务，由服务端调用 DashScope；API Key 不进入 APK。工具不会控制司机端或自动接单。',
              style: TextStyle(
                  color: Color(0xFF775126), fontSize: 12, height: 1.5),
            ),
          ),
        ],
      ),
    );
  }
}
