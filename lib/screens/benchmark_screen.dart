import 'dart:async';

import 'package:flutter/material.dart';

import '../api/models.dart';
import '../widgets/common.dart';

/// Spec §8.3 (configuration) and §8.4 (live benchmark).
class BenchmarkScreen extends StatefulWidget {
  const BenchmarkScreen({super.key});
  @override
  State<BenchmarkScreen> createState() => _BenchmarkScreenState();
}

class _BenchmarkScreenState extends State<BenchmarkScreen> {
  final _selected = <Protocol>{Protocol.ble};
  OperationKind _op = OperationKind.writeState;
  OrderStrategy _order = OrderStrategy.fixed;
  SelectionMode _selection = SelectionMode.fixed;
  bool _multipath = false;
  bool _reconnect = false;
  final _name = TextEditingController(text: 'pilot');
  final _payload = TextEditingController();
  final _values = TextEditingController(text: '1,0');
  final _warmup = TextEditingController(text: '10');
  final _measured = TextEditingController(text: '100');
  final _timeout = TextEditingController(text: '5000');
  final _delay = TextEditingController(text: '200');
  final _seed = TextEditingController(text: '42');
  final _labels = TextEditingController(text: 'baseline');
  final _notes = TextEditingController();

  bool _running = false;
  RunProgressEvent? _progress;
  final _log = <String>[];
  final _states = <String, String>{};
  StreamSubscription<Map<String, Object?>>? _sub;

  static const conditionPresets = [
    'baseline', 'wifi_contention', 'weak_signal', 'ble_degradation', 'wifi_disconnection',
    'ble_disconnection', 'broker_unavailable', 'device_reboot',
  ];

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _sub ??= ApiScope.of(context).events.listen(_onEvent);
  }

  @override
  void dispose() {
    _sub?.cancel();
    for (final c in [_name, _payload, _values, _warmup, _measured, _timeout, _delay, _seed, _labels, _notes]) {
      c.dispose();
    }
    super.dispose();
  }

  void _onEvent(Map<String, Object?> e) {
    if (!mounted) return;
    setState(() {
      switch (e['type']) {
        case 'progress':
          _progress = RunProgressEvent.fromMap(e);
        case 'state':
          _states['${e['protocol']}'] = '${e['state']}';
          _addLog('${e['protocol']} → ${e['state']}');
        case 'lifecycle':
          final ev = LifecycleEventView((e['event'] as Map).cast<String, Object?>());
          _addLog('${ev.protocol ?? '-'} ${ev.kind} ${ev.detail}${ev.durationMs != null ? ' (${fmtMs(ev.durationMs)} ms)' : ''}');
        case 'runStarted':
          _running = true;
        case 'runFinished':
          _running = false;
          _addLog(e['error'] != null ? 'RUN ERROR ${e['error']}' : 'RUN FINISHED ${(e['run'] as Map?)?['status']}');
          ApiScope.of(context).setKeepScreenOn(false);
      }
    });
  }

  void _addLog(String s) {
    final t = DateTime.now().toIso8601String().substring(11, 23);
    _log.insert(0, '$t  $s');
    if (_log.length > 300) _log.removeLast();
  }

  ExperimentConfig _config() => ExperimentConfig(
        name: _name.text.trim(),
        protocols: Protocol.values.where(_selected.contains).toList(),
        operation: _op,
        writeValues: _values.text.split(',').map((s) => int.tryParse(s.trim())).whereType<int>().toList(),
        payloadSizeBytes: int.tryParse(_payload.text.trim()),
        warmupCount: int.tryParse(_warmup.text) ?? -1,
        measuredCount: int.tryParse(_measured.text) ?? 0,
        timeoutMs: int.tryParse(_timeout.text) ?? 0,
        interOperationDelayMs: int.tryParse(_delay.text) ?? -1,
        order: _order,
        randomSeed: int.tryParse(_seed.text) ?? 42,
        conditionLabels: _labels.text.split(',').map((s) => s.trim()).where((s) => s.isNotEmpty).toList(),
        operatorNotes: _notes.text,
        selectionMode: _selection,
        multipathDeclared: _multipath,
        reconnectBetweenOps: _reconnect,
      );

  Future<void> _start() async {
    final cfg = _config();
    final problems = cfg.validate();
    if (problems.isNotEmpty) {
      showSnack(context, problems.join('\n'));
      return;
    }
    final api = ApiScope.of(context);
    await api.setKeepScreenOn(true);
    if (!mounted) return;
    final ok = await guarded(context, () => api.startRun(cfg).then((_) => true));
    if (ok == true && mounted) setState(() { _running = true; _progress = null; });
    if (ok != true) await api.setKeepScreenOn(false);
  }

  Widget _num(TextEditingController c, String label) => SizedBox(
        width: 150,
        child: TextField(controller: c, keyboardType: TextInputType.number, decoration: InputDecoration(labelText: label, isDense: true)),
      );

  @override
  Widget build(BuildContext context) {
    final api = ApiScope.of(context);
    final p = _progress;
    return ListView(padding: const EdgeInsets.all(12), children: [
      const SectionTitle('Protocols'),
      Wrap(spacing: 8, children: [
        for (final pr in Protocol.values)
          FilterChip(
            label: Text('${pr.label}${_states[pr.wire] != null ? ' · ${_states[pr.wire]}' : ''}'),
            selected: _selected.contains(pr),
            onSelected: _running ? null : (v) => setState(() => v ? _selected.add(pr) : _selected.remove(pr)),
          ),
      ]),
      const SectionTitle('Workload'),
      SegmentedButton<OperationKind>(
        segments: const [
          ButtonSegment(value: OperationKind.writeState, label: Text('Write state')),
          ButtonSegment(value: OperationKind.readState, label: Text('Read state')),
        ],
        selected: {_op},
        onSelectionChanged: (s) => setState(() => _op = s.first),
      ),
      Wrap(spacing: 12, runSpacing: 4, children: [
        SizedBox(width: 150, child: TextField(controller: _name, decoration: const InputDecoration(labelText: 'Run name', isDense: true))),
        SizedBox(width: 150, child: TextField(controller: _values, decoration: const InputDecoration(labelText: 'Write values (cycled)', isDense: true))),
        _num(_payload, 'Payload bytes (BLE/MQTT)'),
        _num(_warmup, 'Warm-up ops'),
        _num(_measured, 'Measured ops'),
        _num(_timeout, 'Timeout (ms)'),
        _num(_delay, 'Inter-op delay (ms)'),
        _num(_seed, 'Random seed'),
      ]),
      const SizedBox(height: 8),
      DropdownButtonFormField<OrderStrategy>(
        initialValue: _order,
        decoration: const InputDecoration(labelText: 'Protocol order'),
        items: const [
          DropdownMenuItem(value: OrderStrategy.fixed, child: Text('Fixed (blocked)')),
          DropdownMenuItem(value: OrderStrategy.roundRobin, child: Text('Round-robin')),
          DropdownMenuItem(value: OrderStrategy.seededRandom, child: Text('Seeded random per round')),
        ],
        onChanged: (v) => setState(() => _order = v!),
      ),
      DropdownButtonFormField<SelectionMode>(
        initialValue: _selection,
        decoration: const InputDecoration(
          labelText: 'Path selection',
          helperText: 'Model-based selection is disabled until a trained policy is supplied; Oracle is offline-only.',
        ),
        items: const [
          DropdownMenuItem(value: SelectionMode.fixed, child: Text('Fixed protocol(s)')),
          DropdownMenuItem(value: SelectionMode.naiveFailover, child: Text('Naive failover (multi-path)')),
        ],
        onChanged: (v) => setState(() => _selection = v!),
      ),
      if (_selection != SelectionMode.fixed)
        CheckboxListTile(
          value: _multipath,
          onChanged: (v) => setState(() => _multipath = v ?? false),
          title: const Text('I confirm all selected paths reach the same logical device (multipath firmware)'),
        ),
      SwitchListTile(
        value: _reconnect,
        onChanged: (v) => setState(() => _reconnect = v),
        title: const Text('Explicit reconnect between operations'),
        subtitle: const Text('Each attempt is recorded; nothing reconnects silently.'),
      ),
      TextField(controller: _labels, decoration: const InputDecoration(labelText: 'Condition labels (comma separated)')),
      Wrap(spacing: 6, children: [
        for (final c in conditionPresets)
          ActionChip(label: Text(c), onPressed: () => setState(() => _labels.text = c)),
      ]),
      TextField(controller: _notes, decoration: const InputDecoration(labelText: 'Operator notes (how the condition was created/verified)'), maxLines: 2),
      const SizedBox(height: 12),
      Row(children: [
        Expanded(
          child: FilledButton.icon(
            icon: const Icon(Icons.play_arrow),
            onPressed: _running ? null : _start,
            label: const Text('Start run'),
          ),
        ),
        const SizedBox(width: 8),
        OutlinedButton.icon(
          icon: const Icon(Icons.stop),
          onPressed: _running ? () => guarded(context, api.cancelRun) : null,
          label: const Text('Stop'),
        ),
      ]),
      const SectionTitle('Live'),
      if (p == null) const Text('No run in progress. Keep the app in the foreground during runs.'),
      if (p != null) ...[
        LinearProgressIndicator(value: p.total == 0 ? null : p.completed / p.total),
        Text('${p.completed}/${p.total} · current ${p.protocol ?? '-'}${p.warmup ? ' (warm-up)' : ''}'),
        if (p.last != null)
          Text(
            'Last: ${p.last!.protocol} #${p.last!.sequence} '
            '${p.last!.success ? 'OK ${fmtMs(p.last!.rttMs)} ms' : '${p.last!.errorCategory} ${p.last!.errorDetail ?? ''}'}',
          ),
        for (final e in p.counts.entries)
          Text('${e.key}: attempts ${e.value.attempts} · ok ${e.value.success} · failed ${e.value.failed} · timeouts ${e.value.timeouts}'),
      ],
      const SectionTitle('Event log'),
      SizedBox(
        height: 260,
        child: ListView(children: [
          for (final l in _log) Text(l, style: const TextStyle(fontFamily: 'monospace', fontSize: 11)),
        ]),
      ),
    ]);
  }
}
