import 'package:flutter/material.dart';

import '../api/research_api.dart';

/// Provides the ResearchApi to the widget tree.
class ApiScope extends InheritedWidget {
  const ApiScope({super.key, required this.api, required super.child});
  final ResearchApi api;

  static ResearchApi of(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<ApiScope>()!.api;

  @override
  bool updateShouldNotify(ApiScope oldWidget) => oldWidget.api != api;
}

void showSnack(BuildContext context, String msg) {
  ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg), duration: const Duration(seconds: 4)));
}

/// Runs an async action, reporting errors (including native error categories) in a snackbar.
Future<T?> guarded<T>(BuildContext context, Future<T> Function() action, {String? ok}) async {
  try {
    final r = await action();
    if (ok != null && context.mounted) showSnack(context, ok);
    return r;
  } catch (e) {
    if (context.mounted) showSnack(context, describeError(e));
    return null;
  }
}

class SectionTitle extends StatelessWidget {
  const SectionTitle(this.text, {super.key});
  final String text;
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.fromLTRB(4, 16, 4, 6),
        child: Text(text, style: Theme.of(context).textTheme.titleMedium),
      );
}

class Badge2 extends StatelessWidget {
  const Badge2(this.text, {super.key, this.color});
  final String text;
  final Color? color;
  @override
  Widget build(BuildContext context) {
    final c = color ?? Theme.of(context).colorScheme.secondaryContainer;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(color: c, borderRadius: BorderRadius.circular(10)),
      child: Text(text, style: const TextStyle(fontSize: 12)),
    );
  }
}

/// Execution-mode badge: every result view shows whether data came from hardware or a test double.
class ModeBadge extends StatelessWidget {
  const ModeBadge(this.mode, {super.key});
  final String mode;
  @override
  Widget build(BuildContext context) =>
      Badge2(mode, color: mode == 'HARDWARE' ? Colors.green.shade100 : Colors.orange.shade200);
}

Color stateColor(String s) => switch (s) {
      'CONNECTED' => Colors.green.shade100,
      'ERROR' => Colors.red.shade100,
      'CONNECTING' || 'RECONNECTING' || 'DISCOVERING' => Colors.amber.shade100,
      _ => Colors.grey.shade200,
    };

String fmtMs(double? v) => v == null ? '–' : v.toStringAsFixed(2);
