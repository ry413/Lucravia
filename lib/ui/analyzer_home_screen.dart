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
  String _versionName = '';
  AppUpdateInfo? _updateInfo;
  bool _updateChecked = false;
  bool _checkingUpdate = false;
  bool _installingUpdate = false;

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
        _versionName = capabilities['versionName'] as String? ?? '';
        _busy = false;
      });
      if (!_updateChecked) {
        _updateChecked = true;
        await _checkForUpdate(silent: true);
      }
    } on PlatformException catch (error) {
      _showError(error.message ?? error.code);
    }
  }

  Future<void> _start() async {
    if (_updateInfo?.required == true) {
      _showError('请先安装必要更新，再开始订单分析');
      return;
    }
    if (!_vlmServerConfigured) {
      _showError('当前版本未连接分析服务，请联系提供者更新应用');
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

  Future<void> _checkForUpdate({bool silent = false}) async {
    if (_checkingUpdate) return;
    setState(() => _checkingUpdate = true);
    try {
      final update = await _platform.checkForUpdate();
      if (!mounted) return;
      setState(() => _updateInfo = update);
      if (!silent && update == null) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('当前已是最新版本')),
        );
      }
    } on PlatformException catch (error) {
      if (!silent) _showError(error.message ?? error.code);
    } finally {
      if (mounted) setState(() => _checkingUpdate = false);
    }
  }

  Future<void> _installUpdate() async {
    if (_installingUpdate) return;
    setState(() => _installingUpdate = true);
    try {
      final result = await _platform.installUpdate();
      if (!mounted) return;
      if (result?['permissionRequested'] == true) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('请允许安装应用，然后返回再次点击更新')),
        );
      }
    } on PlatformException catch (error) {
      _showError(error.message ?? error.code);
    } finally {
      if (mounted) setState(() => _installingUpdate = false);
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
            if (_updateInfo != null) ...[
              const SizedBox(height: 12),
              _UpdateCard(
                update: _updateInfo!,
                installing: _installingUpdate,
                onInstall: _installUpdate,
              ),
            ],
            if (_snapshot.orders.isNotEmpty) ...[
              const SizedBox(height: 12),
              _OrdersCard(orders: _snapshot.orders),
            ],
            const SizedBox(height: 20),
            if (!_vlmServerConfigured) ...[
              const _ServiceWarning(),
              const SizedBox(height: 12),
            ],
            _PermissionStep(
              number: '1',
              title: '开启悬浮提示',
              description: '将订单评价直接标在对应卡片上。',
              complete: _overlayGranted,
              actionLabel: '去授权',
              onPressed: _overlayGranted
                  ? null
                  : () => _platform.requestOverlayPermission(),
            ),
            const SizedBox(height: 12),
            _PermissionStep(
              number: '2',
              title: '开启位置服务',
              description: '用当前位置估算真实接驾路线和耗时。',
              complete: _locationGranted,
              actionLabel: '去授权',
              onPressed: _locationGranted
                  ? null
                  : () => _platform.requestLocationPermission(),
            ),
            const SizedBox(height: 12),
            _PermissionStep(
              number: '3',
              title: '选择订单区域',
              description: _scanRegionConfigured
                  ? '已选择屏幕纵向 ${(_scanTopRatio * 100).round()}%–${(_scanBottomRatio * 100).round()}% 的区域。'
                  : '在订单页拖动上下边界，只分析有订单的区域。',
              complete: _scanRegionConfigured,
              actionLabel: _scanRegionConfigured ? '重新选择' : '去选择',
              showActionWhenComplete: true,
              onPressed: _overlayGranted && !_running && !_busy
                  ? _calibrateScanRegion
                  : null,
            ),
            const SizedBox(height: 12),
            _PermissionStep(
              number: '4',
              title: '开始订单分析',
              description: '确认系统的屏幕共享提示后，切换到司机端即可使用。',
              complete: _running,
              actionLabel: _running
                  ? '分析中'
                  : (_updateInfo?.required == true ? '请先更新' : '开始'),
              onPressed: _overlayGranted &&
                      _locationGranted &&
                      !_running &&
                      !_busy &&
                      _updateInfo?.required != true
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
                      label: const Text('停止订单分析'),
                    )
                  : FilledButton.icon(
                      key: const Key('start-analysis'),
                      onPressed: _busy ||
                              !_vlmServerConfigured ||
                              _updateInfo?.required == true
                          ? null
                          : _start,
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
                            ? '当前版本暂不可用'
                            : (_updateInfo?.required == true
                                ? '请先安装必要更新'
                                : (!_overlayGranted
                                    ? '先开启悬浮提示'
                                    : (!_locationGranted
                                        ? '先开启位置服务'
                                        : '开始订单分析'))),
                        style: const TextStyle(
                          fontSize: 17,
                          fontWeight: FontWeight.w800,
                        ),
                      ),
                    ),
            ),
            const SizedBox(height: 20),
            _VersionCard(
              versionName: _versionName,
              checking: _checkingUpdate,
              onCheck: () => _checkForUpdate(),
            ),
            const SizedBox(height: 12),
            const _PrivacyCard(),
          ],
        ),
      ),
    );
  }
}

class _UpdateCard extends StatelessWidget {
  const _UpdateCard({
    required this.update,
    required this.installing,
    required this.onInstall,
  });

  final AppUpdateInfo update;
  final bool installing;
  final VoidCallback onInstall;

  @override
  Widget build(BuildContext context) {
    final megabytes = update.apkSizeBytes / 1024 / 1024;
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: const Color(0xFFE9F8F2),
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: const Color(0xFFBCEBD9)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Icon(Icons.system_update_rounded,
              color: Color(0xFF168A68), size: 24),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '${update.required ? '需要更新' : '发现新版本'} ${update.versionName}',
                  style: const TextStyle(
                    color: _ink,
                    fontSize: 16,
                    fontWeight: FontWeight.w900,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  update.releaseNotes.isEmpty
                      ? '安装包 ${megabytes.toStringAsFixed(1)} MB'
                      : '${update.releaseNotes}\n安装包 ${megabytes.toStringAsFixed(1)} MB',
                  style:
                      const TextStyle(color: _muted, fontSize: 12, height: 1.4),
                ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          FilledButton(
            onPressed: installing ? null : onInstall,
            style: FilledButton.styleFrom(
                backgroundColor: const Color(0xFF168A68)),
            child: installing
                ? const SizedBox.square(
                    dimension: 16,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Text('立即更新'),
          ),
        ],
      ),
    );
  }
}

class _VersionCard extends StatelessWidget {
  const _VersionCard({
    required this.versionName,
    required this.checking,
    required this.onCheck,
  });

  final String versionName;
  final bool checking;
  final VoidCallback onCheck;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(
          child: Text(
            versionName.isEmpty ? '跑单助手' : '跑单助手 $versionName',
            style: const TextStyle(color: _muted, fontSize: 12),
          ),
        ),
        TextButton.icon(
          onPressed: checking ? null : onCheck,
          icon: checking
              ? const SizedBox.square(
                  dimension: 14,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : const Icon(Icons.refresh_rounded, size: 18),
          label: const Text('检查更新'),
        ),
      ],
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
                '看清时间成本，再决定抢哪单',
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
      child: const Icon(Icons.alt_route_rounded, color: Colors.white, size: 28),
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
                running ? '正在留意新订单' : '订单分析尚未开始',
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
                    label: '订单金额',
                    value: '¥${snapshot.price!.toStringAsFixed(2)}'),
                _HeroMetric(
                  label: '行程里程',
                  value: snapshot.tripDistanceKm == null
                      ? '--'
                      : '${snapshot.tripDistanceKm!.toStringAsFixed(1)} km',
                ),
                _HeroMetric(
                  label: '预计毛时薪',
                  value: snapshot.estimatedHourlyIncome == null
                      ? '--'
                      : '¥${snapshot.estimatedHourlyIncome!.toStringAsFixed(0)}/h',
                ),
              ],
            )
          else
            Text(
              running
                  ? (snapshot.message ?? '请切换到司机端订单页')
                  : '开始后切换到司机端，画面停稳后会自动分析。',
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
            '本次发现 ${orders.length} 个完整订单',
            style: const TextStyle(
              color: _ink,
              fontSize: 16,
              fontWeight: FontWeight.w900,
            ),
          ),
          const SizedBox(height: 4),
          const Text(
            '按实时路线估算的毛时薪从高到低排列',
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
                '¥$price  ·  接驾 $pickup km/$minutes 分  ·  行程 $trip km',
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
                    ? '路线估算：接驾 $pickupRoute 分/$pickupRouteKm km + 等客 $waiting 分 + 行程 $tripRoute 分/$tripRouteKm km'
                    : order.routeStatus == 'route_mismatch'
                        ? '地图地点匹配异常，已停止路线评价'
                        : '地图暂时无法计算路线',
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
                  '地图匹配：$amapRoute',
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
          order.routeStatus == 'ok'
              ? '¥$hourly/小时'
              : order.routeStatus == 'route_mismatch'
                  ? '匹配异常'
                  : '待算路',
          style: const TextStyle(
            color: _orange,
            fontWeight: FontWeight.w900,
          ),
        ),
      ],
    );
  }
}

class _ServiceWarning extends StatelessWidget {
  const _ServiceWarning();

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: const Color(0xFFFFECEE),
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: const Color(0xFFFFC8CE)),
      ),
      child: const Row(
        children: [
          Icon(Icons.cloud_off_rounded, color: _coral, size: 22),
          SizedBox(width: 11),
          Expanded(
            child: Text(
              '当前版本未连接分析服务，请联系提供者更新应用。',
              style: TextStyle(color: _ink, fontSize: 12, height: 1.5),
            ),
          ),
        ],
      ),
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
              '只在订单画面停稳后进行分析，不会保存截图，也不会替你点击或抢单。位置信息仅用于估算接驾路线。',
              style: TextStyle(
                  color: Color(0xFF775126), fontSize: 12, height: 1.5),
            ),
          ),
        ],
      ),
    );
  }
}
