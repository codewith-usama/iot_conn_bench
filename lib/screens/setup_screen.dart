import 'package:flutter/material.dart';

import '../api/models.dart';
import '../api/research_api.dart';
import '../widgets/common.dart';
import '../widgets/config_form.dart';

const _codecFields = [
  FieldSpec('codec.writeTemplate', 'Write command template', required: true,
      hint: 'Exact bytes the firmware expects. Placeholders: {op_id} {op} {value} {pad}. Must contain {op_id}.'),
  FieldSpec('codec.readTemplate', 'Read command template', required: true, hint: 'Must contain {op_id}.'),
  FieldSpec('codec.ackOpIdField', 'Ack JSON field echoing op id', required: true),
  FieldSpec('codec.ackStatusField', 'Ack status field (optional)'),
  FieldSpec('codec.ackOkValue', 'Ack status value meaning success'),
  FieldSpec('codec.ackValueField', 'Ack field carrying the resulting state (optional)'),
];

const bleFields = [
  FieldSpec('serviceUuid', 'GATT service UUID', required: true, hint: '128-bit UUID from the ESP32 firmware'),
  FieldSpec('commandCharUuid', 'Command characteristic UUID', required: true),
  FieldSpec('responseCharUuid', 'Ack/response characteristic UUID (notify/indicate)', required: true),
  FieldSpec('stateCharUuid', 'State characteristic UUID (GATT_READ mode only)'),
  FieldSpec('readMode', 'Read mode', type: FieldType.choice, choices: ['COMMAND', 'GATT_READ']),
  FieldSpec('writeWithResponse', 'Write with response', type: FieldType.boolean),
  FieldSpec('requestedMtu', 'Requested ATT MTU', type: FieldType.integer),
  FieldSpec('deviceNameFilter', 'Advertised name filter (optional)'),
  ..._codecFields,
];

const mqttFields = [
  FieldSpec('host', 'Broker host / IP', required: true),
  FieldSpec('port', 'Broker port', type: FieldType.integer),
  FieldSpec('useTls', 'TLS', type: FieldType.boolean),
  FieldSpec('allowInsecureLab', 'Allow plain TCP (explicit local-lab opt-in)', type: FieldType.boolean),
  FieldSpec('clientId', 'Client ID', required: true),
  FieldSpec('username', 'Username (optional; password goes to secure storage)'),
  FieldSpec('keepAliveSeconds', 'Keep-alive (s)', type: FieldType.integer),
  FieldSpec('cleanStart', 'Clean start', type: FieldType.boolean),
  FieldSpec('sessionExpirySeconds', 'Session expiry (s)', type: FieldType.integer),
  FieldSpec('commandTopic', 'Command topic', required: true),
  FieldSpec('ackTopic', 'Device acknowledgement topic', required: true),
  FieldSpec('stateTopic', 'State topic (optional)'),
  FieldSpec('qos', 'QoS (0/1/2)', type: FieldType.integer),
  FieldSpec('useMqtt5RequestResponse', 'Send MQTT 5 Response Topic + Correlation Data', type: FieldType.boolean),
  ..._codecFields,
];

const matterFields = [
  FieldSpec('nodeId', 'Node ID (set by commissioning)', type: FieldType.integer),
  FieldSpec('endpointId', 'Endpoint ID', type: FieldType.integer, required: true),
  FieldSpec('clusterId', 'Cluster ID (decimal)', type: FieldType.integer, required: true, hint: 'e.g. OnOff = 6'),
  FieldSpec('attributeId', 'Attribute ID (decimal)', type: FieldType.integer, required: true, hint: 'e.g. OnOff.OnOff = 0'),
  FieldSpec('valueType', 'Attribute data type', type: FieldType.choice, choices: ['BOOLEAN', 'UINT8', 'UINT16', 'UINT32', 'INT16', 'INT32']),
  FieldSpec('writeMode', 'Write mapping', type: FieldType.choice, choices: ['WRITE_ATTRIBUTE', 'INVOKE_ONOFF']),
  FieldSpec('interactionTimeoutMs', 'Interaction timeout (ms)', type: FieldType.integer),
];

/// Spec §8.2: BLE scan/select, contract configuration, broker/topics, Matter commissioning,
/// connection controls and permission diagnostics.
class SetupScreen extends StatelessWidget {
  const SetupScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: 3,
      child: Column(children: [
        const TabBar(tabs: [Tab(text: 'BLE'), Tab(text: 'MQTT v5'), Tab(text: 'Matter')]),
        Expanded(
          child: TabBarView(children: [
            _ProtocolSetup(protocol: Protocol.ble, fields: bleFields, extra: (c, reload) => _BleExtras(reload: reload)),
            _ProtocolSetup(protocol: Protocol.mqttV5, fields: mqttFields, extra: (c, reload) => const _SecretField(SecretSlots.mqttPassword, 'MQTT password')),
            _ProtocolSetup(protocol: Protocol.matter, fields: matterFields, extra: (c, reload) => _MatterCommissioning(reload: reload)),
          ]),
        ),
      ]),
    );
  }
}

class _ProtocolSetup extends StatefulWidget {
  const _ProtocolSetup({required this.protocol, required this.fields, required this.extra});
  final Protocol protocol;
  final List<FieldSpec> fields;
  final Widget Function(BuildContext, VoidCallback reload) extra;
  @override
  State<_ProtocolSetup> createState() => _ProtocolSetupState();
}

class _ProtocolSetupState extends State<_ProtocolSetup> with AutomaticKeepAliveClientMixin {
  Map<String, Object?>? _view;
  AdapterStatus? _adapter;

  @override
  bool get wantKeepAlive => true;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_view == null) _load();
  }

  Future<void> _load() async {
    final api = ApiScope.of(context);
    final v = await guarded(context, () => api.getConfig(widget.protocol));
    final st = await guarded(context, api.getStatus);
    if (mounted) setState(() { _view = v; _adapter = st?.adapters[widget.protocol]; });
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    final api = ApiScope.of(context);
    final v = _view;
    if (v == null) return const Center(child: CircularProgressIndicator());
    final cfg = (v['config'] as Map?)?.cast<String, Object?>() ?? {};
    final missing = ((v['missing'] as List?) ?? const []).map((e) => '$e').toList();
    final a = _adapter;
    return ListView(padding: const EdgeInsets.all(12), children: [
      if (a != null)
        Card(
          child: ListTile(
            title: Text('${widget.protocol.label}: ${a.state}'),
            subtitle: Text([
              'Readiness: ${a.readiness}',
              if (v['initError'] != null) '${v['initError']}',
              ...a.limitations.map((l) => '• $l'),
            ].join('\n')),
            trailing: Wrap(spacing: 4, children: [
              IconButton(
                tooltip: 'Connect',
                icon: const Icon(Icons.link),
                onPressed: () async {
                  final r = await guarded(context, () => api.connect(widget.protocol));
                  if (r != null && context.mounted) {
                    final phases = (r['phasesNs'] as Map?)?.map((k, v) => MapEntry(k, '${((v as num) / 1e6).toStringAsFixed(1)} ms'));
                    showSnack(context, 'Connected. Setup phases: $phases');
                  }
                  _load();
                },
              ),
              IconButton(
                tooltip: 'Disconnect',
                icon: const Icon(Icons.link_off),
                onPressed: () => guarded(context, () => api.disconnect(widget.protocol)).then((_) => _load()),
              ),
            ]),
          ),
        ),
      widget.extra(context, _load),
      const SectionTitle('Device contract configuration'),
      ConfigForm(
        fields: widget.fields,
        initial: cfg,
        missing: missing,
        onSave: (m) async {
          final r = await guarded(context, () => api.saveConfig(widget.protocol, m), ok: 'Saved');
          if (r != null && mounted) setState(() => _view = r);
          _load();
        },
      ),
    ]);
  }
}

class _BleExtras extends StatefulWidget {
  const _BleExtras({required this.reload});
  final VoidCallback reload;
  @override
  State<_BleExtras> createState() => _BleExtrasState();
}

class _BleExtrasState extends State<_BleExtras> {
  List<Map<String, Object?>> _found = [];
  bool _scanning = false;

  @override
  Widget build(BuildContext context) {
    final api = ApiScope.of(context);
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Wrap(spacing: 8, children: [
        OutlinedButton(
          onPressed: () async {
            final ok = await guarded(context, api.requestBlePermissions);
            if (context.mounted) showSnack(context, ok == true ? 'Bluetooth permissions granted' : 'Permissions denied — BLE unavailable');
            widget.reload();
          },
          child: const Text('Request BLE permissions'),
        ),
        FilledButton.tonal(
          onPressed: _scanning
              ? null
              : () async {
                  setState(() => _scanning = true);
                  final r = await guarded(context, () => api.discover(Protocol.ble));
                  if (mounted) setState(() { _scanning = false; _found = r ?? []; });
                },
          child: Text(_scanning ? 'Scanning…' : 'Scan (service UUID filter)'),
        ),
      ]),
      for (final d in _found)
        ListTile(
          dense: true,
          title: Text('${d['displayName']}'),
          subtitle: Text('${d['address']} · RSSI ${d['rssiDbm']} dBm'),
          trailing: TextButton(
            onPressed: () => guarded(context, () => api.selectDevice(Protocol.ble, d), ok: 'Selected').then((_) => widget.reload()),
            child: const Text('Select'),
          ),
        ),
    ]);
  }
}

class _SecretField extends StatefulWidget {
  const _SecretField(this.slot, this.label);
  final String slot;
  final String label;
  @override
  State<_SecretField> createState() => _SecretFieldState();
}

class _SecretFieldState extends State<_SecretField> {
  final _c = TextEditingController();
  @override
  void dispose() { _c.dispose(); super.dispose(); }

  @override
  Widget build(BuildContext context) {
    final api = ApiScope.of(context);
    return Row(children: [
      Expanded(
        child: TextField(
          controller: _c,
          obscureText: true,
          decoration: InputDecoration(labelText: '${widget.label} (stored encrypted, never exported)', isDense: true),
        ),
      ),
      TextButton(
        onPressed: () async {
          await guarded(context, () => api.setSecret(widget.slot, _c.text.isEmpty ? null : _c.text), ok: 'Stored securely');
          _c.clear();
        },
        child: const Text('Store'),
      ),
    ]);
  }
}

class _MatterCommissioning extends StatefulWidget {
  const _MatterCommissioning({required this.reload});
  final VoidCallback reload;
  @override
  State<_MatterCommissioning> createState() => _MatterCommissioningState();
}

class _MatterCommissioningState extends State<_MatterCommissioning> {
  String _mode = 'BLE_WIFI';
  bool _busy = false;
  final _node = TextEditingController(text: '1');
  final _disc = TextEditingController();
  final _pin = TextEditingController();
  final _ip = TextEditingController();
  final _port = TextEditingController(text: '5540');
  final _ssid = TextEditingController();

  @override
  void dispose() {
    for (final c in [_node, _disc, _pin, _ip, _port, _ssid]) {
      c.dispose();
    }
    super.dispose();
  }

  Widget _tf(TextEditingController c, String label, {bool number = false, bool obscure = false}) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 3),
        child: TextField(
          controller: c,
          obscureText: obscure,
          keyboardType: number ? TextInputType.number : TextInputType.text,
          decoration: InputDecoration(labelText: label, isDense: true, border: const OutlineInputBorder()),
        ),
      );

  @override
  Widget build(BuildContext context) {
    final api = ApiScope.of(context);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(10),
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Text('Commission with this phone (explicit action)', style: Theme.of(context).textTheme.titleSmall),
          const Text('Commissioning time is recorded separately and never mixed into interaction RTT. '
              'The setup passcode is used once and not stored.'),
          SegmentedButton<String>(
            segments: const [
              ButtonSegment(value: 'BLE_WIFI', label: Text('BLE → Wi-Fi')),
              ButtonSegment(value: 'ON_NETWORK', label: Text('On-network (IP)')),
            ],
            selected: {_mode},
            onSelectionChanged: (s) => setState(() => _mode = s.first),
          ),
          _tf(_node, 'Node ID to assign', number: true),
          _tf(_disc, 'Discriminator (from device QR/manual code)', number: true),
          _tf(_pin, 'Setup passcode', number: true, obscure: true),
          if (_mode == 'ON_NETWORK') ...[
            _tf(_ip, 'Device IP address'),
            _tf(_port, 'Port', number: true),
          ] else ...[
            _tf(_ssid, 'Wi-Fi SSID to provision'),
            const _SecretField(SecretSlots.wifiPassword, 'Wi-Fi password'),
          ],
          const SizedBox(height: 6),
          FilledButton(
            onPressed: _busy
                ? null
                : () async {
                    setState(() => _busy = true);
                    final r = await guarded(context, () => api.commissionMatter({
                          'mode': _mode,
                          'nodeId': int.tryParse(_node.text) ?? 0,
                          'discriminator': int.tryParse(_disc.text) ?? -1,
                          'setupPinCode': int.tryParse(_pin.text) ?? 0,
                          'ipAddress': _ip.text.trim(),
                          'port': int.tryParse(_port.text) ?? 5540,
                          'ssid': _ssid.text,
                        }));
                    _pin.clear();
                    if (mounted) setState(() => _busy = false);
                    if (r != null && context.mounted) {
                      showSnack(context, r['success'] == true ? 'Commissioned node ${r['nodeId']}' : 'Commissioning failed: ${r['detail']}');
                    }
                    widget.reload();
                  },
            child: Text(_busy ? 'Commissioning…' : 'Commission'),
          ),
        ]),
      ),
    );
  }
}
