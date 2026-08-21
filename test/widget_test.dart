import 'package:cheat_cat/app.dart';
import 'package:cheat_cat/platform/screen_analyzer.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const methods = MethodChannel('cheat_cat/screen_analyzer');
  const events = MethodChannel('cheat_cat/screen_analyzer_events');

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methods, (call) async {
      if (call.method == 'capabilities') {
        return <String, Object?>{
          'overlayGranted': true,
          'locationGranted': true,
          'running': false,
          'vlmServerConfigured': true,
          'scanRegionConfigured': true,
          'scanTopRatio': 0.18,
          'scanBottomRatio': 0.92,
        };
      }
      return null;
    });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(events, (_) async => null);
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methods, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(events, null);
  });

  testWidgets('展示局域网 VLM 分析控制台', (tester) async {
    await tester.pumpWidget(const CheatCatApp());
    await tester.pump();

    expect(find.text('局域网 VLM 订单价值分析'), findsOneWidget);
    expect(find.text('局域网 VLM 服务'), findsOneWidget);
    expect(find.textContaining('已从工程根目录 .env 编译服务地址和请求签名密钥'), findsOneWidget);
    expect(find.text('允许悬浮结果窗'), findsOneWidget);
    expect(find.text('校准识图区域'), findsOneWidget);
    expect(find.textContaining('18%–92%'), findsOneWidget);
    expect(find.text('开始屏幕分析'), findsWidgets);
    await tester.drag(find.byType(ListView), const Offset(0, -700));
    await tester.pump();
    expect(find.textContaining('不会控制司机端'), findsOneWidget);
  });

  test('解析服务端返回的多订单列表', () {
    final snapshot = AnalyzerSnapshot.fromMap({
      'status': 'scanning',
      'price': 30.98,
      'orders': [
        {
          'isFullyVisible': true,
          'price': 30.98,
          'pickupDistanceKm': 1.5,
          'pickupMinutes': 3,
          'tripDistanceKm': 8.1,
          'estimatedHourlyIncome': 84.0,
          'routeStatus': 'ok',
          'pickupRouteMinutes': 4,
          'tripRouteMinutes': 15,
          'pickupRouteCongestionDistanceKm': 0.4,
          'tripRouteCongestionDistanceKm': 0.8,
          'pickupRouteTrafficLights': 3,
          'tripRouteTrafficLights': 12,
          'tripRouteTollsYuan': 6.0,
          'tripRouteTollRoad': '南坪快速',
          'waitingMinutes': 3,
          'pickupName': '科技园',
          'pickupNameRaw': '科技技园',
        },
        {
          'price': 28.71,
          'pickupDistanceKm': 0.7,
          'pickupMinutes': 1,
          'tripDistanceKm': 8.6,
          'estimatedHourlyIncome': 82.0,
        },
      ],
    });

    expect(snapshot.orders, hasLength(2));
    expect(snapshot.orders.first.price, 30.98);
    expect(snapshot.orders.first.isFullyVisible, isTrue);
    expect(snapshot.orders.first.pickupName, '科技园');
    expect(snapshot.orders.first.pickupNameRaw, '科技技园');
    expect(snapshot.orders.first.routeStatus, 'ok');
    expect(snapshot.orders.first.pickupRouteMinutes, 4);
    expect(snapshot.orders.first.pickupRouteCongestionDistanceKm, 0.4);
    expect(snapshot.orders.first.tripRouteTrafficLights, 12);
    expect(snapshot.orders.first.tripRouteTollsYuan, 6.0);
    expect(snapshot.orders.first.tripRouteTollRoad, '南坪快速');
    expect(snapshot.orders.last.tripDistanceKm, 8.6);
  });
}
