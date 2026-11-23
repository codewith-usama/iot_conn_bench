import 'dart:async';

import 'package:flutter/services.dart';

import 'models.dart';

/// Experiment-level API to the native Kotlin research core.
///
/// This interface carries configuration, control, progress and results only. Individual measured
/// operations never cross the bridge; the Kotlin BenchmarkRunner executes and times them.
abstract class ResearchApi {
  Stream<Map<String, Object?>> get events;

  Future<AppStatus> getStatus();
  Future<Map<String, Object?>> getConfig(Protocol p);
  Future<Map<String, Object?>> saveConfig(Protocol p, Map<String, Object?> config);
  Future<void> setSecret(String name, String? value);
  Future<bool> requestBlePermissions();
  Future<List<Map<String, Object?>>> discover(Protocol p, {int timeoutMs = 8000});
  Future<void> selectDevice(Protocol p, Map<String, Object?> device);
  Future<Map<String, Object?>> connect(Protocol p);
  Future<void> disconnect(Protocol p);
  Future<Map<String, Object?>> commissionMatter(Map<String, Object?> args);
  Future<String> startSession(Map<String, String> metadata);
  Future<void> endSession();
  Future<void> startRun(ExperimentConfig config);
  Future<void> cancelRun();
  Future<List<SessionView>> listSessions();
  Future<List<RunView>> listRuns({String? sessionId});
  Future<Map<String, Object?>> runDetails(String runId);
  Future<List<OperationRow>> records(String runId, {int offset = 0, int limit = 200});
  Future<List<LifecycleEventView>> sessionEvents(String sessionId, {int limit = 500});
  Future<Map<String, Object?>> compareRuns(String a, String b);
  Future<Map<String, Object?>> exportSession(String sessionId, {bool pickFolder = true});
  Future<void> setKeepScreenOn(bool on);
}

/// Secret slots understood by the native SecretStore (Android Keystore AES-GCM).
class SecretSlots {
  static const mqttPassword = 'mqtt.password';
  static const wifiPassword = 'wifi.commissioning.password';
}

class ChannelResearchApi implements ResearchApi {
  ChannelResearchApi({MethodChannel? methods, EventChannel? events})
      : _m = methods ?? const MethodChannel('iotconnbench/api'),
        _e = events ?? const EventChannel('iotconnbench/events');

  final MethodChannel _m;
  final EventChannel _e;
  Stream<Map<String, Object?>>? _events;

  @override
  Stream<Map<String, Object?>> get events =>
      _events ??= _e.receiveBroadcastStream().map((e) => (e as Map).cast<String, Object?>()).asBroadcastStream();

  Future<T> _call<T>(String method, [Map<String, Object?>? args]) async {
    final r = await _m.invokeMethod<Object?>(method, args);
    return r as T;
  }

  Map<String, Object?> _map(Object? o) => (o as Map?)?.cast<String, Object?>() ?? {};
  List<Map<String, Object?>> _listOfMaps(Object? o) =>
      ((o as List?) ?? const []).map((e) => (e as Map).cast<String, Object?>()).toList();

  @override
  Future<AppStatus> getStatus() async => AppStatus.fromMap(_map(await _call<Object?>('getStatus')));

  @override
  Future<Map<String, Object?>> getConfig(Protocol p) async => _map(await _call<Object?>('getConfig', {'protocol': p.wire}));

  @override
  Future<Map<String, Object?>> saveConfig(Protocol p, Map<String, Object?> config) async =>
      _map(await _call<Object?>('saveConfig', {'protocol': p.wire, 'config': config}));

  @override
  Future<void> setSecret(String name, String? value) => _call<Object?>('setSecret', {'name': name, 'value': value});

  @override
  Future<bool> requestBlePermissions() async => (await _call<Object?>('requestBlePermissions')) as bool? ?? false;

  @override
  Future<List<Map<String, Object?>>> discover(Protocol p, {int timeoutMs = 8000}) async =>
      _listOfMaps(await _call<Object?>('discover', {'protocol': p.wire, 'timeoutMs': timeoutMs}));

  @override
  Future<void> selectDevice(Protocol p, Map<String, Object?> device) =>
      _call<Object?>('selectDevice', {'protocol': p.wire, 'device': device});

  @override
  Future<Map<String, Object?>> connect(Protocol p) async => _map(await _call<Object?>('connect', {'protocol': p.wire}));

  @override
  Future<void> disconnect(Protocol p) => _call<Object?>('disconnect', {'protocol': p.wire});

  @override
  Future<Map<String, Object?>> commissionMatter(Map<String, Object?> args) async =>
      _map(await _call<Object?>('commissionMatter', args));

  @override
  Future<String> startSession(Map<String, String> metadata) async =>
      (await _call<Object?>('startSession', {'metadata': metadata})) as String;

  @override
  Future<void> endSession() => _call<Object?>('endSession');

  @override
  Future<void> startRun(ExperimentConfig config) => _call<Object?>('startRun', {'config': config.toMap()});

  @override
  Future<void> cancelRun() => _call<Object?>('cancelRun');

  @override
  Future<List<SessionView>> listSessions() async =>
      _listOfMaps(await _call<Object?>('listSessions')).map(SessionView.new).toList();

  @override
  Future<List<RunView>> listRuns({String? sessionId}) async =>
      _listOfMaps(await _call<Object?>('listRuns', {'sessionId': sessionId})).map(RunView.new).toList();

  @override
  Future<Map<String, Object?>> runDetails(String runId) async => _map(await _call<Object?>('runDetails', {'runId': runId}));

  @override
  Future<List<OperationRow>> records(String runId, {int offset = 0, int limit = 200}) async =>
      _listOfMaps(await _call<Object?>('records', {'runId': runId, 'offset': offset, 'limit': limit})).map(OperationRow.new).toList();

  @override
  Future<List<LifecycleEventView>> sessionEvents(String sessionId, {int limit = 500}) async =>
      _listOfMaps(await _call<Object?>('events', {'sessionId': sessionId, 'limit': limit})).map(LifecycleEventView.new).toList();

  @override
  Future<Map<String, Object?>> compareRuns(String a, String b) async => _map(await _call<Object?>('compareRuns', {'a': a, 'b': b}));

  @override
  Future<Map<String, Object?>> exportSession(String sessionId, {bool pickFolder = true}) async =>
      _map(await _call<Object?>('exportSession', {'sessionId': sessionId, 'pickFolder': pickFolder}));

  @override
  Future<void> setKeepScreenOn(bool on) => _call<Object?>('setKeepScreenOn', {'on': on});
}

/// Human-readable message for a native PlatformException (category code + message).
String describeError(Object e) {
  if (e is PlatformException) return '${e.code}: ${e.message ?? ''}';
  return e.toString();
}
