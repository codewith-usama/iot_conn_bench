import 'package:flutter/material.dart';

import 'api/research_api.dart';
import 'screens/benchmark_screen.dart';
import 'screens/dashboard_screen.dart';
import 'screens/results_screen.dart';
import 'screens/setup_screen.dart';
import 'widgets/common.dart';

void main() {
  runApp(BenchApp(api: ChannelResearchApi()));
}

class BenchApp extends StatelessWidget {
  const BenchApp({super.key, required this.api});
  final ResearchApi api;

  @override
  Widget build(BuildContext context) {
    return ApiScope(
      api: api,
      child: MaterialApp(
        title: 'IoT Conn Bench',
        theme: ThemeData(colorSchemeSeed: Colors.indigo, useMaterial3: true),
        home: const HomeShell(),
      ),
    );
  }
}

class HomeShell extends StatefulWidget {
  const HomeShell({super.key});
  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> {
  int _index = 0;
  static const _titles = ['Dashboard', 'Device & protocol setup', 'Benchmark', 'Results'];

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(_titles[_index])),
      // IndexedStack keeps the live benchmark subscribed while other tabs are shown.
      body: IndexedStack(index: _index, children: const [
        DashboardScreen(),
        SetupScreen(),
        BenchmarkScreen(),
        ResultsScreen(),
      ]),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _index,
        onDestinationSelected: (i) => setState(() => _index = i),
        destinations: const [
          NavigationDestination(icon: Icon(Icons.dashboard), label: 'Dashboard'),
          NavigationDestination(icon: Icon(Icons.settings_input_antenna), label: 'Setup'),
          NavigationDestination(icon: Icon(Icons.speed), label: 'Benchmark'),
          NavigationDestination(icon: Icon(Icons.table_chart), label: 'Results'),
        ],
      ),
    );
  }
}
