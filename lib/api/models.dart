// Typed models for the Flutter <-> Kotlin research API.
//
// Keys mirror android/.../core/mapping/Mappers.kt. Flutter never measures anything: all
// timestamps come from the native Kotlin core (SystemClock.elapsedRealtimeNanos).

enum Protocol { ble, mqttV5, matter }

extension ProtocolX on Protocol {
  String get wire => switch (this) {
        Protocol.ble => 'BLE',
        Protocol.mqttV5 => 'MQTT_V5',
        Protocol.matter => 'MATTER',
      };

  String get label => switch (this) {
        Protocol.ble => 'BLE / GATT',
        Protocol.mqttV5 => 'MQTT v5 (Wi-Fi)',
        Protocol.matter => 'Matter (Wi-Fi)',
      };

  static Protocol fromWire(String s) =>
      Protocol.values.firstWhere((p) => p.wire == s, orElse: () => throw ArgumentError('protocol $s'));
}

enum OperationKind { readState, writeState }

extension OperationKindX on OperationKind {
  String get wire => this == OperationKind.readState ? 'READ_STATE' : 'WRITE_STATE';
}

enum OrderStrategy { fixed, roundRobin, seededRandom }

extension OrderStrategyX on OrderStrategy {
  String get wire => switch (this) {
        OrderStrategy.fixed => 'FIXED',
        OrderStrategy.roundRobin => 'ROUND_ROBIN',
        OrderStrategy.seededRandom => 'SEEDED_RANDOM',
      };
}

enum SelectionMode { fixed, naiveFailover }

extension SelectionModeX on SelectionMode {
  String get wire => this == SelectionMode.fixed ? 'FIXED' : 'NAIVE_FAILOVER';
}

Map<String, Object?> _m(Object? o) => (o as Map?)?.cast<String, Object?>() ?? const {};
List<Object?> _l(Object? o) => (o as List?)?.cast<Object?>() ?? const [];
int _i(Object? o, [int d = 0]) => (o as num?)?.toInt() ?? d;
double? _d(Object? o) => (o as num?)?.toDouble();

class AdapterStatus {
  AdapterStatus({
    required this.protocol,
    required this.state,
    required this.configured,
    required this.missing,
    required this.initError,
    required this.executionMode,
    required this.limitations,
    required this.verifiedHardwareOps,
    required this.device,
  });

  final Protocol protocol;
  final String state;
  final bool configured;
  final List<String> missing;
  final String? initError;
  final String executionMode;
  final List<String> limitations;
  final int verifiedHardwareOps;
  final Map<String, Object?>? device;

  bool get connected => state == 'CONNECTED';

  /// Configured -> connected -> experimentally verified (spec §8.1).
  String get readiness {
    if (!configured) return 'not configured';
    if (!connected) return 'configured';
    if (verifiedHardwareOps == 0) return 'connected (not yet verified)';
    return 'verified ($verifiedHardwareOps hardware ops)';
  }

  factory AdapterStatus.fromMap(Protocol p, Map<String, Object?> m) {
    final caps = _m(m['capabilities']);
    return AdapterStatus(
      protocol: p,
      state: m['state'] as String? ?? 'IDLE',
      configured: m['configured'] as bool? ?? false,
      missing: _l(m['missing']).map((e) => '$e').toList(),
      initError: m['initError'] as String?,
      executionMode: caps['executionMode'] as String? ?? 'HARDWARE',
      limitations: _l(caps['knownLimitations']).map((e) => '$e').toList(),
      verifiedHardwareOps: _i(m['verifiedHardwareOps']),
      device: m['device'] == null ? null : _m(m['device']),
    );
  }
}

class AppStatus {
  AppStatus({
    required this.metadata,
    required this.blePermissionsGranted,
    required this.hasMqttPassword,
    required this.hasWifiPassword,
    required this.matter,
    required this.running,
    required this.sessionId,
    required this.adapters,
  });

  final Map<String, String> metadata;
  final bool blePermissionsGranted;
  final bool hasMqttPassword;
  final bool hasWifiPassword;
  final Map<String, Object?> matter;
  final bool running;
  final String? sessionId;
  final Map<Protocol, AdapterStatus> adapters;

  factory AppStatus.fromMap(Map<String, Object?> m) {
    final a = _m(m['adapters']);
    return AppStatus(
      metadata: _m(m['metadata']).map((k, v) => MapEntry(k, '$v')),
      blePermissionsGranted: m['blePermissionsGranted'] as bool? ?? false,
      hasMqttPassword: m['hasMqttPassword'] as bool? ?? false,
      hasWifiPassword: m['hasWifiPassword'] as bool? ?? false,
      matter: _m(m['matter']),
      running: m['running'] as bool? ?? false,
      sessionId: m['sessionId'] as String?,
      adapters: {
        for (final p in Protocol.values)
          if (a[p.wire] != null) p: AdapterStatus.fromMap(p, _m(a[p.wire])),
      },
    );
  }
}

class ExperimentConfig {
  ExperimentConfig({
    this.name = '',
    required this.protocols,
    this.operation = OperationKind.writeState,
    this.writeValues = const [1, 0],
    this.payloadSizeBytes,
    this.warmupCount = 10,
    this.measuredCount = 100,
    this.timeoutMs = 5000,
    this.interOperationDelayMs = 200,
    this.order = OrderStrategy.fixed,
    this.randomSeed = 42,
    this.conditionLabels = const ['baseline'],
    this.operatorNotes = '',
    this.selectionMode = SelectionMode.fixed,
    this.multipathDeclared = false,
    this.reconnectBetweenOps = false,
  });

  final String name;
  final List<Protocol> protocols;
  final OperationKind operation;
  final List<int> writeValues;
  final int? payloadSizeBytes;
  final int warmupCount;
  final int measuredCount;
  final int timeoutMs;
  final int interOperationDelayMs;
  final OrderStrategy order;
  final int randomSeed;
  final List<String> conditionLabels;
  final String operatorNotes;
  final SelectionMode selectionMode;
  final bool multipathDeclared;
  final bool reconnectBetweenOps;

  /// Mirrors the Kotlin ExperimentConfig.validate() so the UI can explain problems early.
  /// The native side re-validates and is authoritative.
  List<String> validate() => [
        if (protocols.isEmpty) 'Select at least one protocol',
        if (measuredCount <= 0) 'Measured iterations must be > 0',
        if (warmupCount < 0) 'Warm-up must be >= 0',
        if (timeoutMs <= 0) 'Timeout must be > 0',
        if (interOperationDelayMs < 0) 'Delay must be >= 0',
        if (operation == OperationKind.writeState && writeValues.isEmpty) 'Write values required',
        if (payloadSizeBytes != null && payloadSizeBytes! <= 0) 'Payload size must be > 0',
        if (selectionMode != SelectionMode.fixed && !multipathDeclared)
          'Failover requires declaring that all paths reach the same logical device',
        if (selectionMode != SelectionMode.fixed && protocols.length < 2) 'Failover requires >= 2 protocols',
      ];

  Map<String, Object?> toMap() => {
        'name': name,
        'protocols': protocols.map((p) => p.wire).toList(),
        'operation': operation.wire,
        'writeValues': writeValues,
        'payloadSizeBytes': payloadSizeBytes,
        'warmupCount': warmupCount,
        'measuredCount': measuredCount,
        'timeoutMs': timeoutMs,
        'interOperationDelayMs': interOperationDelayMs,
        'order': order.wire,
        'randomSeed': randomSeed,
        'conditionLabels': conditionLabels,
        'operatorNotes': operatorNotes,
        'selectionMode': selectionMode.wire,
        'multipathDeclared': multipathDeclared,
        'reconnectBetweenOps': reconnectBetweenOps,
      };
}

class ProtocolCounts {
  ProtocolCounts(this.attempts, this.success, this.failed, this.timeouts);
  final int attempts, success, failed, timeouts;
  factory ProtocolCounts.fromMap(Map<String, Object?> m) =>
      ProtocolCounts(_i(m['attempts']), _i(m['success']), _i(m['failed']), _i(m['timeouts']));
}

class OperationRow {
  OperationRow(this.raw);
  final Map<String, Object?> raw;
  int get sequence => _i(raw['sequence']);
  String get protocol => raw['protocol'] as String? ?? '';
  bool get success => raw['success'] as bool? ?? false;
  bool get warmup => raw['warmup'] as bool? ?? false;
  String get errorCategory => raw['errorCategory'] as String? ?? '';
  String? get errorDetail => raw['errorDetail'] as String?;
  String get executionMode => raw['executionMode'] as String? ?? '';
  double? get rttMs => raw['rttNs'] == null ? null : (raw['rttNs'] as num) / 1e6;
}

class RunProgressEvent {
  RunProgressEvent({required this.runId, required this.completed, required this.total, this.protocol, required this.warmup, this.last, required this.counts});
  final String runId;
  final int completed, total;
  final String? protocol;
  final bool warmup;
  final OperationRow? last;
  final Map<String, ProtocolCounts> counts;

  factory RunProgressEvent.fromMap(Map<String, Object?> m) => RunProgressEvent(
        runId: m['runId'] as String? ?? '',
        completed: _i(m['completed']),
        total: _i(m['total']),
        protocol: m['protocol'] as String?,
        warmup: m['warmup'] as bool? ?? false,
        last: m['last'] == null ? null : OperationRow(_m(m['last'])),
        counts: _m(m['counts']).map((k, v) => MapEntry(k, ProtocolCounts.fromMap(_m(v)))),
      );
}

class LifecycleEventView {
  LifecycleEventView(this.raw);
  final Map<String, Object?> raw;
  String get kind => raw['kind'] as String? ?? '';
  String? get protocol => raw['protocol'] as String?;
  String get detail => raw['detail'] as String? ?? '';
  int get wallMs => _i(raw['wallMs']);
  double? get durationMs => raw['durationNs'] == null ? null : (raw['durationNs'] as num) / 1e6;
}

class LatencyView {
  LatencyView(this.raw);
  final Map<String, Object?> raw;
  int get n => _i(raw['n']);
  double? get min => _d(raw['min']);
  double? get mean => _d(raw['mean']);
  double? get p50 => _d(raw['p50']);
  double? get p90 => _d(raw['p90']);
  double? get p95 => _d(raw['p95']);
  double? get p99 => _d(raw['p99']);
  double? get max => _d(raw['max']);
}

class ProtocolSummaryView {
  ProtocolSummaryView(this.raw);
  final Map<String, Object?> raw;
  String get protocol => raw['protocol'] as String? ?? '';
  int get attempts => _i(raw['attempts']);
  int get successes => _i(raw['successes']);
  int get failures => _i(raw['failures']);
  int get timeouts => _i(raw['timeouts']);
  double get successRate => _d(raw['success_rate']) ?? 0;
  LatencyView get latency => LatencyView(_m(raw['latency_ms']));
  double? get throughputWindow => _d(raw['throughput_window_ops_per_s']);
  double? get throughputBusy => _d(raw['throughput_busy_ops_per_s']);
  int get reconnectCount => _i(raw['reconnect_count']);
  double? get meanReconnectMs => _d(raw['mean_reconnect_ms']);
  int get pathSwitchCount => _i(raw['path_switch_count']);
  Map<String, Object?> get failuresByCategory => _m(raw['failures_by_category']);
}

class RunView {
  RunView(this.raw);
  final Map<String, Object?> raw;
  String get runId => raw['runId'] as String? ?? '';
  String get sessionId => raw['sessionId'] as String? ?? '';
  String get status => raw['status'] as String? ?? '';
  Map<String, Object?> get config => _m(raw['config']);
  int get startedWallMs => _i(raw['startedWallMs']);
  List<String> get protocols => _l(config['protocols']).map((e) => '$e').toList();
  List<String> get labels => _l(config['conditionLabels']).map((e) => '$e').toList();
}

class SessionView {
  SessionView(this.raw);
  final Map<String, Object?> raw;
  String get sessionId => raw['sessionId'] as String? ?? '';
  int get startedWallMs => _i(raw['startedWallMs']);
  int get runCount => _i(raw['runCount']);
}
