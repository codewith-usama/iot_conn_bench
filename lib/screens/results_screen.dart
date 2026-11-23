import 'package:flutter/material.dart';

import '../api/models.dart';
import '../widgets/common.dart';

/// Spec §8.5: per-protocol summaries, percentiles, success rate, recovery metrics, raw records,
/// comparison with compatibility warnings, and CSV/JSON export.
class ResultsScreen extends StatefulWidget {
  const ResultsScreen({super.key});
  @override
  State<ResultsScreen> createState() => _ResultsScreenState();
}

class _ResultsScreenState extends State<ResultsScreen> {
  List<SessionView> _sessions = [];
  String? _session;
  List<RunView> _runs = [];
  final _compare = <String>{};

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _load();
  }

  Future<void> _load() async {
    final api = ApiScope.of(context);
    final s = await guarded(context, api.listSessions) ?? [];
    final sid = _session ?? (s.isNotEmpty ? s.first.sessionId : null);
    final runs = sid == null ? <RunView>[] : (await guarded(context, () => api.listRuns(sessionId: sid)) ?? []);
    if (mounted) setState(() { _sessions = s; _session = sid; _runs = runs; });
  }

  @override
  Widget build(BuildContext context) {
    final api = ApiScope.of(context);
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView(padding: const EdgeInsets.all(12), children: [
        if (_sessions.isEmpty) const Text('No sessions yet.'),
        if (_sessions.isNotEmpty)
          DropdownButtonFormField<String>(
            initialValue: _session,
            decoration: const InputDecoration(labelText: 'Session'),
            items: [
              for (final s in _sessions)
                DropdownMenuItem(
                  value: s.sessionId,
                  child: Text('${s.sessionId.substring(0, 8)} · ${DateTime.fromMillisecondsSinceEpoch(s.startedWallMs)} · ${s.runCount} runs'),
                ),
            ],
            onChanged: (v) { setState(() { _session = v; _compare.clear(); }); _load(); },
          ),
        if (_session != null)
          Wrap(spacing: 8, children: [
            FilledButton.tonalIcon(
              icon: const Icon(Icons.folder_open),
              label: const Text('Export CSV + JSON (choose folder)'),
              onPressed: () async {
                final r = await guarded(context, () => api.exportSession(_session!));
                if (r != null && context.mounted) showSnack(context, 'Exported ${r['files']} (schema ${r['schemaVersion']}). Copy also in ${r['appStorageDir']}');
              },
            ),
            OutlinedButton(
              onPressed: () async {
                final r = await guarded(context, () => api.exportSession(_session!, pickFolder: false));
                if (r != null && context.mounted) showSnack(context, 'Written to ${r['appStorageDir']}');
              },
              child: const Text('Export to app storage only'),
            ),
            if (_compare.length == 2)
              FilledButton(
                onPressed: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => _CompareView(_compare.toList()))),
                child: const Text('Compare selected'),
              ),
          ]),
        const SectionTitle('Runs (select two to compare)'),
        for (final r in _runs)
          Card(
            child: ListTile(
              leading: Checkbox(
                value: _compare.contains(r.runId),
                onChanged: (v) => setState(() {
                  if (v == true && _compare.length < 2) { _compare.add(r.runId); } else { _compare.remove(r.runId); }
                }),
              ),
              title: Text('${r.config['name'] ?? ''} ${r.protocols.join('+')} · ${r.config['operation']}'),
              subtitle: Text('${r.status} · labels ${r.labels.join(',')} · n=${r.config['measuredCount']} · ${r.runId.substring(0, 8)}'),
              onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => RunDetailView(runId: r.runId))),
            ),
          ),
      ]),
    );
  }
}

class SummaryTable extends StatelessWidget {
  const SummaryTable(this.summaries, {super.key});
  final List<ProtocolSummaryView> summaries;

  @override
  Widget build(BuildContext context) {
    DataCell c(String s) => DataCell(Text(s));
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: DataTable(
        columnSpacing: 14,
        columns: const [
          DataColumn(label: Text('Protocol')), DataColumn(label: Text('n')), DataColumn(label: Text('ok')),
          DataColumn(label: Text('fail')), DataColumn(label: Text('t/o')), DataColumn(label: Text('success')),
          DataColumn(label: Text('min')), DataColumn(label: Text('mean')), DataColumn(label: Text('p50')),
          DataColumn(label: Text('p90')), DataColumn(label: Text('p95')), DataColumn(label: Text('p99')),
          DataColumn(label: Text('max')), DataColumn(label: Text('ops/s busy')), DataColumn(label: Text('reconn.')),
        ],
        rows: [
          for (final s in summaries)
            DataRow(cells: [
              c(s.protocol), c('${s.attempts}'), c('${s.successes}'), c('${s.failures}'), c('${s.timeouts}'),
              c('${(s.successRate * 100).toStringAsFixed(1)}%'),
              c(fmtMs(s.latency.min)), c(fmtMs(s.latency.mean)), c(fmtMs(s.latency.p50)), c(fmtMs(s.latency.p90)),
              c(fmtMs(s.latency.p95)), c(fmtMs(s.latency.p99)), c(fmtMs(s.latency.max)),
              c(s.throughputBusy?.toStringAsFixed(2) ?? '–'),
              c('${s.reconnectCount}${s.meanReconnectMs != null ? ' (${fmtMs(s.meanReconnectMs)} ms)' : ''}'),
            ]),
        ],
      ),
    );
  }
}

List<ProtocolSummaryView> _summaries(Map<String, Object?> details) =>
    ((details['summary'] as List?) ?? const []).map((e) => ProtocolSummaryView((e as Map).cast<String, Object?>())).toList();

class RunDetailView extends StatefulWidget {
  const RunDetailView({super.key, required this.runId});
  final String runId;
  @override
  State<RunDetailView> createState() => _RunDetailViewState();
}

class _RunDetailViewState extends State<RunDetailView> {
  Map<String, Object?>? _details;
  List<OperationRow> _rows = [];

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_details == null) _load();
  }

  Future<void> _load() async {
    final api = ApiScope.of(context);
    final d = await guarded(context, () => api.runDetails(widget.runId));
    final rows = await guarded(context, () => api.records(widget.runId, limit: 1000)) ?? [];
    if (mounted) setState(() { _details = d; _rows = rows; });
  }

  @override
  Widget build(BuildContext context) {
    final d = _details;
    final modes = _rows.map((r) => r.executionMode).toSet();
    return Scaffold(
      appBar: AppBar(title: Text('Run ${widget.runId.substring(0, 8)}')),
      body: d == null
          ? const Center(child: CircularProgressIndicator())
          : ListView(padding: const EdgeInsets.all(12), children: [
              Wrap(spacing: 6, children: [for (final m in modes) ModeBadge(m)]),
              Text('Latency in ms (successful measured ops only; warm-up excluded). ${d['percentileMethod']}'),
              SummaryTable(_summaries(d)),
              Text('Events: ${d['eventCounts']}'),
              const SectionTitle('Raw operation records'),
              for (final r in _rows)
                Text(
                  '#${r.sequence} ${r.protocol}${r.warmup ? ' [warm-up]' : ''} '
                  '${r.success ? '${fmtMs(r.rttMs)} ms' : '${r.errorCategory} ${r.errorDetail ?? ''}'}',
                  style: TextStyle(fontFamily: 'monospace', fontSize: 11, color: r.success ? null : Colors.red.shade700),
                ),
            ]),
    );
  }
}

class _CompareView extends StatelessWidget {
  const _CompareView(this.ids);
  final List<String> ids;

  @override
  Widget build(BuildContext context) {
    final api = ApiScope.of(context);
    return Scaffold(
      appBar: AppBar(title: const Text('Compare runs')),
      body: FutureBuilder<Map<String, Object?>>(
        future: api.compareRuns(ids[0], ids[1]),
        builder: (context, snap) {
          if (snap.hasError) return Center(child: Text('${snap.error}'));
          if (!snap.hasData) return const Center(child: CircularProgressIndicator());
          final m = snap.data!;
          final warnings = ((m['warnings'] as List?) ?? const []).map((e) => '$e').toList();
          return ListView(padding: const EdgeInsets.all(12), children: [
            if (warnings.isNotEmpty)
              Card(
                color: Colors.orange.shade50,
                child: Padding(
                  padding: const EdgeInsets.all(8),
                  child: Text('NOT DIRECTLY COMPARABLE:\n${warnings.map((w) => '• $w').join('\n')}'),
                ),
              )
            else
              const Card(child: Padding(padding: EdgeInsets.all(8), child: Text('Workload, firmware, phone, settings and labels match.'))),
            const SectionTitle('Run A'),
            SummaryTable(_summaries((m['a'] as Map).cast<String, Object?>())),
            const SectionTitle('Run B'),
            SummaryTable(_summaries((m['b'] as Map).cast<String, Object?>())),
          ]);
        },
      ),
    );
  }
}
