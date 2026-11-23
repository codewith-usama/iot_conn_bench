import 'dart:async';

import 'package:flutter/material.dart';

import '../api/models.dart';
import '../widgets/common.dart';

/// Spec §8.1: readiness, radio state, broker/Matter readiness, sessions, and a clear distinction
/// between configured, connected, and experimentally verified paths.
class DashboardScreen extends StatefulWidget {
  const DashboardScreen({super.key});
  @override
  State<DashboardScreen> createState() => _DashboardScreenState();
}

class _DashboardScreenState extends State<DashboardScreen> {
  AppStatus? _status;
  List<SessionView> _sessions = [];
  StreamSubscription<Map<String, Object?>>? _sub;
  final _meta = <String, TextEditingController>{
    for (final k in _metaKeys.keys) k: TextEditingController(),
  };

  static const _metaKeys = {
    'device.boardModel': 'ESP32 board model / revision (from chip marking)',
    'device.firmwareVersion': 'ESP32 firmware version',
    'device.firmwareHash': 'Firmware commit hash',
    'network.routerModel': 'Router make / model',
    'network.ssidLabel': 'SSID label (no password)',
    'broker.version': 'MQTT broker + version (e.g. mosquitto 2.0.x)',
    'broker.location': 'Broker host location (e.g. MacBook on LAN)',
    'operator.notes': 'Operator notes',
  };

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _sub ??= ApiScope.of(context).events.listen((e) {
      if (e['type'] == 'state' || e['type'] == 'runFinished') _refresh();
    });
    _refresh();
  }

  @override
  void dispose() {
    _sub?.cancel();
    for (final c in _meta.values) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _refresh() async {
    final api = ApiScope.of(context);
    final s = await guarded(context, api.getStatus);
    final sessions = await guarded(context, api.listSessions);
    if (!mounted) return;
    setState(() {
      _status = s ?? _status;
      _sessions = sessions ?? _sessions;
    });
  }

  @override
  Widget build(BuildContext context) {
    final s = _status;
    final api = ApiScope.of(context);
    return RefreshIndicator(
      onRefresh: _refresh,
      child: ListView(padding: const EdgeInsets.all(12), children: [
        if (s == null) const LinearProgressIndicator(),
        if (s != null) ...[
          Card(
            child: ListTile(
              title: Text('${s.metadata['phone.manufacturer']} ${s.metadata['phone.model']}'),
              subtitle: Text(
                'Android ${s.metadata['android.release']} (API ${s.metadata['android.sdkInt']}) · '
                'app ${s.metadata['app.versionName']} rev ${s.metadata['app.sourceRevision']}\n'
                'Bluetooth: ${s.metadata['bluetooth.enabled'] == 'true' ? 'on' : 'OFF'} · '
                'BLE permissions: ${s.blePermissionsGranted ? 'granted' : 'missing'}\n'
                'Wi-Fi: ${s.metadata['network.wifiConnected'] == 'true' ? '${s.metadata['network.band']} ch ${s.metadata['network.channel']} RSSI ${s.metadata['network.rssiDbm']} dBm' : 'not connected'}',
              ),
              isThreeLine: true,
            ),
          ),
          const SectionTitle('Protocol paths'),
          for (final a in s.adapters.values) _AdapterCard(a),
          Card(
            child: ListTile(
              leading: const Icon(Icons.hub),
              title: const Text('Matter controller'),
              subtitle: Text(s.matter.entries.map((e) => '${e.key}: ${e.value}').join('\n')),
            ),
          ),
          const SectionTitle('Experiment session'),
          if (s.sessionId != null)
            Card(
              child: ListTile(
                title: Text('Active session ${s.sessionId!.substring(0, 8)}'),
                subtitle: const Text('Phone/app/network metadata captured at session start.'),
                trailing: TextButton(
                  onPressed: s.running ? null : () => guarded(context, api.endSession).then((_) => _refresh()),
                  child: const Text('End'),
                ),
              ),
            )
          else ...[
            for (final e in _metaKeys.entries)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 3),
                child: TextField(
                  controller: _meta[e.key],
                  decoration: InputDecoration(labelText: e.value, isDense: true, border: const OutlineInputBorder()),
                ),
              ),
            FilledButton(
              onPressed: () async {
                final md = {
                  for (final e in _meta.entries)
                    if (e.value.text.trim().isNotEmpty) e.key: e.value.text.trim(),
                };
                await guarded(context, () => api.startSession(md), ok: 'Session started');
                _refresh();
              },
              child: const Text('Start session'),
            ),
          ],
          const SectionTitle('Recent sessions'),
          if (_sessions.isEmpty) const Text('No sessions recorded yet.'),
          for (final ss in _sessions.take(10))
            ListTile(
              dense: true,
              title: Text(ss.sessionId.substring(0, 8)),
              subtitle: Text('${DateTime.fromMillisecondsSinceEpoch(ss.startedWallMs)} · ${ss.runCount} run(s)'),
            ),
        ],
      ]),
    );
  }
}

class _AdapterCard extends StatelessWidget {
  const _AdapterCard(this.a);
  final AdapterStatus a;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(10),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Expanded(child: Text(a.protocol.label, style: Theme.of(context).textTheme.titleSmall)),
            ModeBadge(a.executionMode),
            const SizedBox(width: 6),
            Badge2(a.state, color: stateColor(a.state)),
          ]),
          const SizedBox(height: 4),
          Text('Readiness: ${a.readiness}'),
          if (a.device != null) Text('Device: ${a.device!['displayName']} (${a.device!['address'] ?? ''})'),
          if (a.missing.isNotEmpty) Text('Missing contract values: ${a.missing.join(', ')}', style: const TextStyle(color: Colors.brown)),
          if (a.initError != null && a.missing.isEmpty) Text(a.initError!, style: const TextStyle(color: Colors.red)),
        ]),
      ),
    );
  }
}
