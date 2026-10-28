import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() => runApp(const NetMonitorApp());

class NetMonitorApp extends StatelessWidget {
  const NetMonitorApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'NetMonitor',
        debugShowCheckedModeBanner: false,
        theme: ThemeData(useMaterial3: true, colorSchemeSeed: const Color(0xFF1565C0)),
        home: const StatusPage(),
      );
}

class StatusPage extends StatefulWidget {
  const StatusPage({super.key});

  @override
  State<StatusPage> createState() => _StatusPageState();
}

class _StatusPageState extends State<StatusPage> with WidgetsBindingObserver {
  // Le service de surveillance tourne en natif (Kotlin) : Dart ne fait qu'afficher.
  static const _control = MethodChannel('netmonitor/control');
  static const _events = EventChannel('netmonitor/state');

  static const _colors = {
    'Excellent': Color(0xFF2E7D32),
    'Moyen': Color(0xFFF9A825),
    'Faible': Color(0xFFEF6C00),
    'Mauvais': Color(0xFFC62828),
  };

  StreamSubscription<dynamic>? _sub;
  Map<dynamic, dynamic> _s = {};

  bool get _running => _s['running'] == true;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _listen();
    _control.invokeMethod('requestNotificationPermission');
    _control.invokeMethod('autoStart');
  }

  // On n'écoute le flux que lorsque l'écran est visible : aucun travail Dart en arrière-plan.
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _listen();
    } else if (state == AppLifecycleState.paused) {
      _sub?.cancel();
      _sub = null;
    }
  }

  void _listen() {
    _sub ??= _events.receiveBroadcastStream().listen((data) {
      if (mounted) setState(() => _s = data as Map<dynamic, dynamic>);
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _sub?.cancel();
    super.dispose();
  }

  String _fmt(String key, String unit, {int digits = 0}) {
    final v = _s[key];
    return v is num ? '${v.toStringAsFixed(digits)} $unit' : '—';
  }

  @override
  Widget build(BuildContext context) {
    final quality = _running ? _s['quality'] as String? : null;
    final color = _colors[quality] ?? Colors.grey;

    Widget row(String label, String value) => Padding(
          padding: const EdgeInsets.symmetric(vertical: 8),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(label, style: const TextStyle(fontSize: 16)),
              Text(value, style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
            ],
          ),
        );

    return Scaffold(
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            children: [
              const SizedBox(height: 32),
              const Text('Qualité du réseau', style: TextStyle(fontSize: 16)),
              const SizedBox(height: 12),
              Container(
                height: 140,
                width: double.infinity,
                alignment: Alignment.center,
                decoration: BoxDecoration(color: color, borderRadius: BorderRadius.circular(28)),
                child: Text(
                  quality ?? '—',
                  style: const TextStyle(color: Colors.white, fontSize: 40, fontWeight: FontWeight.bold),
                ),
              ),
              const SizedBox(height: 32),
              row('Ping', _fmt('ping', 'ms')),
              row('Gigue', _fmt('jitter', 'ms')),
              row('Débit', _fmt('download', 'Mbps', digits: 1)),
              row('Perte de paquets', _fmt('loss', '%')),
              row('Signal', (_s['signal'] as String?) ?? '—'),
              row('Connexion', (_s['transport'] as String?) ?? '—'),
              const Spacer(),
              SizedBox(
                width: double.infinity,
                child: FilledButton(
                  onPressed: () => _control.invokeMethod(_running ? 'stop' : 'start'),
                  child: Text(_running ? 'Arrêter la surveillance' : 'Démarrer la surveillance'),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
