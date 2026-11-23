import 'package:flutter/material.dart';

enum FieldType { text, integer, boolean, choice }

/// Describes one device-contract field. [key] may be nested with '.', e.g. 'codec.writeTemplate'.
class FieldSpec {
  const FieldSpec(this.key, this.label, {this.type = FieldType.text, this.hint, this.choices = const [], this.required = false});
  final String key;
  final String label;
  final FieldType type;
  final String? hint;
  final List<String> choices;
  final bool required;
}

Object? readPath(Map<String, Object?> m, String path) {
  Object? cur = m;
  for (final part in path.split('.')) {
    if (cur is! Map) return null;
    cur = cur[part];
  }
  return cur;
}

/// Writes [value] at a dotted [path], copying intermediate maps (platform-channel maps may be
/// unmodifiable).
void writePath(Map<String, Object?> m, String path, Object? value) {
  final parts = path.split('.');
  var cur = m;
  for (final p in parts.take(parts.length - 1)) {
    final next = cur[p];
    final child = next is Map ? <String, Object?>{...next.cast<String, Object?>()} : <String, Object?>{};
    cur[p] = child;
    cur = child;
  }
  cur[parts.last] = value;
}

/// Generic editable form over a config map. Empty required fields are shown as PLACEHOLDER so the
/// operator can see exactly which firmware-contract values are still missing.
class ConfigForm extends StatefulWidget {
  const ConfigForm({super.key, required this.fields, required this.initial, required this.onSave, this.missing = const []});
  final List<FieldSpec> fields;
  final Map<String, Object?> initial;
  final List<String> missing;
  final Future<void> Function(Map<String, Object?>) onSave;

  @override
  State<ConfigForm> createState() => _ConfigFormState();
}

class _ConfigFormState extends State<ConfigForm> {
  late Map<String, Object?> _values;
  final _controllers = <String, TextEditingController>{};

  @override
  void initState() {
    super.initState();
    _reset();
  }

  @override
  void didUpdateWidget(ConfigForm old) {
    super.didUpdateWidget(old);
    if (old.initial != widget.initial) _reset();
  }

  void _reset() {
    _values = _deepCopy(widget.initial);
    for (final f in widget.fields) {
      if (f.type == FieldType.text || f.type == FieldType.integer) {
        final v = readPath(_values, f.key);
        (_controllers[f.key] ??= TextEditingController()).text = v == null ? '' : '$v';
      }
    }
  }

  Map<String, Object?> _deepCopy(Map<String, Object?> m) => {
        for (final e in m.entries) e.key: e.value is Map ? _deepCopy((e.value as Map).cast<String, Object?>()) : e.value,
      };

  @override
  void dispose() {
    for (final c in _controllers.values) {
      c.dispose();
    }
    super.dispose();
  }

  Map<String, Object?> _collect() {
    final out = _deepCopy(_values);
    for (final f in widget.fields) {
      if (f.type == FieldType.text) writePath(out, f.key, _controllers[f.key]!.text);
      if (f.type == FieldType.integer) writePath(out, f.key, int.tryParse(_controllers[f.key]!.text.trim()));
    }
    return out;
  }

  @override
  Widget build(BuildContext context) {
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      if (widget.missing.isNotEmpty)
        Card(
          color: Colors.amber.shade50,
          child: Padding(
            padding: const EdgeInsets.all(8),
            child: Text('PLACEHOLDER — required from the firmware contract:\n${widget.missing.join('\n')}'),
          ),
        ),
      for (final f in widget.fields) _field(f),
      const SizedBox(height: 8),
      FilledButton.icon(
        icon: const Icon(Icons.save),
        label: const Text('Save configuration'),
        onPressed: () => widget.onSave(_collect()),
      ),
    ]);
  }

  Widget _field(FieldSpec f) {
    switch (f.type) {
      case FieldType.boolean:
        return SwitchListTile(
          title: Text(f.label),
          subtitle: f.hint == null ? null : Text(f.hint!),
          value: readPath(_values, f.key) as bool? ?? false,
          onChanged: (v) => setState(() => writePath(_values, f.key, v)),
        );
      case FieldType.choice:
        final cur = readPath(_values, f.key) as String?;
        return Padding(
          padding: const EdgeInsets.symmetric(vertical: 4),
          child: DropdownButtonFormField<String>(
            initialValue: f.choices.contains(cur) ? cur : f.choices.first,
            decoration: InputDecoration(labelText: f.label, helperText: f.hint),
            items: [for (final c in f.choices) DropdownMenuItem(value: c, child: Text(c))],
            onChanged: (v) => setState(() => writePath(_values, f.key, v)),
          ),
        );
      case FieldType.text:
      case FieldType.integer:
        final empty = (_controllers[f.key]?.text ?? '').isEmpty;
        return Padding(
          padding: const EdgeInsets.symmetric(vertical: 4),
          child: TextField(
            key: ValueKey('field-${f.key}'),
            controller: _controllers[f.key],
            keyboardType: f.type == FieldType.integer ? TextInputType.number : TextInputType.text,
            decoration: InputDecoration(
              labelText: f.label + (f.required ? ' *' : ''),
              helperText: f.hint,
              helperMaxLines: 3,
              hintText: f.required && empty ? 'PLACEHOLDER (from firmware contract)' : null,
              border: const OutlineInputBorder(),
              isDense: true,
            ),
            onChanged: (_) => setState(() {}),
          ),
        );
    }
  }
}
