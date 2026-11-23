import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:iot_conn_bench/api/models.dart';
import 'package:iot_conn_bench/api/research_api.dart';
import 'package:iot_conn_bench/main.dart';

/// UI test double: returns canned status. It never produces measurement records.
class FakeApi implements ResearchApi {
  final _events = StreamController<Map<String, Object?>>.broadcast();
  ExperimentConfig? lastRun;

  @override
  Stream<Map<String, Object?>> get events => _events.stream;

  Map<String, Object?> _adapter(bool configured) => {
        'state': 'IDLE', 'configured': configured, 'missing': configured ? <String>[] : ['ble.serviceUuid'],
        'verifiedHardwareOps': 0,
        'capabilities': {'executionMode': 'HARDWARE', 'knownLimitations': <String>[]},
      };

  @override
  Future<AppStatus> getStatus() async => AppStatus.fromMap({
        'metadata': {'phone.manufacturer': 'Acme', 'phone.model': 'P1', 'android.release': '15', 'android.sdkInt': '35'},
        'blePermissionsGranted': false,
        'matter': {'available': false, 'reason': 'test'},
        'running': false,
        'adapters': {'BLE': _adapter(false), 'MQTT_V5': _adapter(true), 'MATTER': _adapter(false)},
      });

  @override
  Future<Map<String, Object?>> getConfig(Protocol p) async => {'config': <String, Object?>{}, 'missing': ['x']};
  @override
  Future<List<SessionView>> listSessions() async => [];
  @override
  Future<void> startRun(ExperimentConfig config) async => lastRun = config;
  @override
  Future<void> setKeepScreenOn(bool on) async {}
  @override
  dynamic noSuchMethod(Invocation invocation) => Future<Object?>.value(null);
}

void main() {
  testWidgets('dashboard shows the configured/not-configured distinction', (tester) async {
    await tester.pumpWidget(BenchApp(api: FakeApi()));
    await tester.pumpAndSettle();
    expect(find.text('Dashboard'), findsWidgets);
    expect(find.textContaining('Readiness: not configured'), findsNWidgets(2));
    expect(find.textContaining('Readiness: configured'), findsOneWidget);
    expect(find.textContaining('Missing contract values'), findsWidgets);
  });

  testWidgets('benchmark tab validates and submits an experiment-level command', (tester) async {
    final api = FakeApi();
    await tester.pumpWidget(BenchApp(api: api));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Benchmark').last);
    await tester.pumpAndSettle();
    final start = find.text('Start run');
    await tester.ensureVisible(start);
    await tester.tap(start);
    await tester.pumpAndSettle();
    expect(api.lastRun, isNotNull);
    expect(api.lastRun!.protocols, [Protocol.ble]);
    expect(api.lastRun!.measuredCount, 100);
  });
}
