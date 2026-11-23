import 'package:flutter_test/flutter_test.dart';
import 'package:iot_conn_bench/api/models.dart';
import 'package:iot_conn_bench/widgets/config_form.dart';

void main() {
  test('ExperimentConfig serialises with the native wire keys', () {
    final c = ExperimentConfig(protocols: [Protocol.ble, Protocol.mqttV5], payloadSizeBytes: 64, order: OrderStrategy.seededRandom);
    final m = c.toMap();
    expect(m['protocols'], ['BLE', 'MQTT_V5']);
    expect(m['operation'], 'WRITE_STATE');
    expect(m['order'], 'SEEDED_RANDOM');
    expect(m['selectionMode'], 'FIXED');
    expect(m['payloadSizeBytes'], 64);
    expect(c.validate(), isEmpty);
  });

  test('failover requires multipath declaration and two paths', () {
    final c = ExperimentConfig(protocols: [Protocol.ble], selectionMode: SelectionMode.naiveFailover);
    expect(c.validate().length, 2);
  });

  test('AdapterStatus readiness distinguishes configured, connected, verified', () {
    AdapterStatus s(bool cfg, String st, int v) => AdapterStatus.fromMap(Protocol.ble, {
          'state': st, 'configured': cfg, 'missing': <String>[], 'verifiedHardwareOps': v,
          'capabilities': {'executionMode': 'HARDWARE', 'knownLimitations': <String>[]},
        });
    expect(s(false, 'IDLE', 0).readiness, 'not configured');
    expect(s(true, 'IDLE', 0).readiness, 'configured');
    expect(s(true, 'CONNECTED', 0).readiness, startsWith('connected'));
    expect(s(true, 'CONNECTED', 5).readiness, startsWith('verified'));
  });

  test('progress event parsing', () {
    final p = RunProgressEvent.fromMap({
      'runId': 'r', 'completed': 3, 'total': 10, 'protocol': 'BLE', 'warmup': false,
      'last': {'sequence': 2, 'protocol': 'BLE', 'success': true, 'rttNs': 12500000, 'executionMode': 'HARDWARE'},
      'counts': {'BLE': {'attempts': 3, 'success': 2, 'failed': 1, 'timeouts': 1}},
    });
    expect(p.last!.rttMs, closeTo(12.5, 1e-9));
    expect(p.counts['BLE']!.timeouts, 1);
  });

  test('nested config paths', () {
    final m = <String, Object?>{'codec': {'a': 1}};
    writePath(m, 'codec.b', 'x');
    writePath(m, 'top', true);
    expect(readPath(m, 'codec.a'), 1);
    expect(readPath(m, 'codec.b'), 'x');
    expect(m['top'], true);
  });
}
