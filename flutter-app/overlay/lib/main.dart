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
        theme: ThemeData(
          useMaterial3: true,
          brightness: Brightness.dark,
          scaffoldBackgroundColor: const Color(0xFF020604),
          colorScheme: const ColorScheme.dark(
            primary: Color(0xFF38FF91),
            secondary: Color(0xFF36E8FF),
            surface: Color(0xFF08100C),
          ),
          textTheme: ThemeData.dark().textTheme.apply(
                bodyColor: const Color(0xFFF2FFF6),
                displayColor: const Color(0xFFF2FFF6),
              ),
        ),
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
    'Excellent': Color(0xFF0B8F4D),
    'Moyen': Color(0xFFA87913),
    'Faible': Color(0xFFB95714),
    'Mauvais': Color(0xFFA02C38),
  };

  StreamSubscription<dynamic>? _sub;
  Map<dynamic, dynamic> _s = {};

  bool get _running => _s['running'] == true;

  double? _value(String key) {
    final value = _s[key];
    return value is num ? value.toDouble() : null;
  }

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
    final value = _value(key);
    return value == null ? '—' : '${value.toStringAsFixed(digits)} $unit';
  }

  bool? _isEligible(double minimumMbps) {
    if (!_running) return null;
    final downloadMbps = _value('download');
    if (downloadMbps == null || !downloadMbps.isFinite || downloadMbps <= 0) {
      return null;
    }
    return downloadMbps >= minimumMbps;
  }

  bool? _isGamingEligible({
    required double minimumMbps,
    required double maximumPingMs,
    required bool pingLimitIsInclusive,
  }) {
    if (!_running) return null;
    final downloadMbps = _value('download');
    final pingMs = _value('ping');
    final jitterMs = _value('jitter');
    final lossPercent = _value('loss');
    if (downloadMbps == null ||
        pingMs == null ||
        jitterMs == null ||
        lossPercent == null ||
        !downloadMbps.isFinite ||
        !pingMs.isFinite ||
        !jitterMs.isFinite ||
        !lossPercent.isFinite ||
        downloadMbps <= 0 ||
        pingMs < 0 ||
        jitterMs < 0 ||
        lossPercent < 0) {
      return null;
    }
    final pingEligible = pingLimitIsInclusive
        ? pingMs <= maximumPingMs
        : pingMs < maximumPingMs;
    return downloadMbps >= minimumMbps &&
        pingEligible &&
        jitterMs <= 20 &&
        lossPercent == 0;
  }

  String _estimate(int sizeMb) {
    if (!_running) return '—';
    final downloadMbps = _value('download');
    if (downloadMbps == null || !downloadMbps.isFinite || downloadMbps <= 0) {
      return '—';
    }
    final seconds = (sizeMb * 8 / downloadMbps).ceil();
    if (seconds < 60) return '~$seconds s';
    if (seconds < 3600) return '~${(seconds / 60).ceil()} min';
    final hours = seconds ~/ 3600;
    final minutes = ((seconds % 3600) / 60).ceil();
    return minutes == 0 ? '~$hours h' : '~$hours h $minutes min';
  }

  Widget _metricCard(String label, String value) => Container(
        decoration: BoxDecoration(
          color: const Color(0xA0101914),
          borderRadius: BorderRadius.circular(13),
          border: Border.all(color: const Color(0x2638FF91)),
        ),
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
        margin: const EdgeInsets.only(bottom: 4),
        child: Row(
          children: [
            Expanded(
              child: Text(
                label,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  fontSize: 15,
                  color: Color(0xFFF2FFF6),
                  fontWeight: FontWeight.w500,
                ),
              ),
            ),
            const SizedBox(width: 8),
            Flexible(
              child: Text(
                value,
                textAlign: TextAlign.end,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontSize: 17, fontWeight: FontWeight.bold),
              ),
            ),
          ],
        ),
      );

  Widget _qualityPage() {
    final quality = _running ? _s['quality'] as String? : null;
    final color = _colors[quality] ?? Colors.grey;
    return ListView(
      padding: const EdgeInsets.fromLTRB(4, 16, 4, 12),
      children: [
        const Center(
          child: Text(
            'QUALITÉ DU RÉSEAU',
            style: TextStyle(fontSize: 13, letterSpacing: 1.4, color: Color(0xFFD8E9DE)),
          ),
        ),
        const SizedBox(height: 12),
        Container(
          height: 140,
          width: double.infinity,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: color,
            borderRadius: BorderRadius.circular(28),
            border: Border.all(color: const Color(0x6638FF91)),
            boxShadow: [
              BoxShadow(color: color.withOpacity(0.24), blurRadius: 28, spreadRadius: 1),
            ],
          ),
          child: Text(
            quality ?? '—',
            style: const TextStyle(color: Colors.white, fontSize: 40, fontWeight: FontWeight.bold),
          ),
        ),
        const SizedBox(height: 20),
        _metricCard('Ping', _fmt('ping', 'ms')),
        _metricCard('Gigue', _fmt('jitter', 'ms')),
        _metricCard('Téléchargement', _fmt('download', 'Mbps', digits: 1)),
        _metricCard('Débit montant', _fmt('upload', 'Mbps', digits: 1)),
        _metricCard('Perte de paquets', _fmt('loss', '%')),
        _metricCard('Signal', (_s['signal'] as String?) ?? '—'),
        _metricCard('Connexion', (_s['transport'] as String?) ?? '—'),
      ],
    );
  }

  Widget _usageRow(IconData icon, String label, bool? eligible) {
    final color = eligible == null
        ? Colors.grey
        : eligible
            ? const Color(0xFF2E7D32)
            : const Color(0xFFC62828);
    final text = eligible == null ? '—' : eligible ? 'Oui' : 'Non';
    return Container(
      margin: const EdgeInsets.only(bottom: 5),
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
      decoration: BoxDecoration(
        color: const Color(0xA0101914),
        borderRadius: BorderRadius.circular(13),
        border: Border.all(color: const Color(0x2638FF91)),
      ),
      child: Row(
        children: [
          SizedBox(width: 40, child: Icon(icon, size: 24)),
          Expanded(child: Text(label, style: const TextStyle(fontSize: 14))),
          Semantics(
            label: label,
            value: eligible == null ? 'En attente d’une mesure' : eligible ? 'Disponible' : 'Non disponible',
            child: Container(
              width: 54,
              padding: const EdgeInsets.symmetric(vertical: 5),
              decoration: BoxDecoration(
                color: color,
                borderRadius: BorderRadius.circular(14),
                border: Border.all(color: color.withOpacity(0.9)),
              ),
              alignment: Alignment.center,
              child: Text(
                text,
                style: const TextStyle(color: Colors.white, fontWeight: FontWeight.bold),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _estimateRow(IconData icon, String label, int sizeMb) => Container(
        margin: const EdgeInsets.only(bottom: 5),
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
        decoration: BoxDecoration(
          color: const Color(0xA0101914),
          borderRadius: BorderRadius.circular(13),
          border: Border.all(color: const Color(0x2638FF91)),
        ),
        child: Row(
          children: [
            SizedBox(width: 40, child: Icon(icon, size: 22)),
            Expanded(child: Text(label, style: const TextStyle(fontSize: 14))),
            Text(_estimate(sizeMb), style: const TextStyle(fontSize: 14)),
          ],
        ),
      );

  Widget _usagesPage() {
    final showStabilityWarning = _running &&
        _s['quality'] == 'Mauvais' &&
        (_value('loss') ?? 0) > 0;
    return ListView(
      padding: const EdgeInsets.only(top: 8),
      children: [
        _usageRow(
          Icons.sports_esports_outlined,
          'Cloud gaming · 1080p 60 fps / 4K',
          _isGamingEligible(
            minimumMbps: 25,
            maximumPingMs: 30,
            pingLimitIsInclusive: false,
          ),
        ),
        _usageRow(
          Icons.sports_esports_outlined,
          'Cloud gaming · 720p',
          _isGamingEligible(
            minimumMbps: 15,
            maximumPingMs: 40,
            pingLimitIsInclusive: true,
          ),
        ),
        _usageRow(Icons.ondemand_video_outlined, 'Film UHD / 4K', _isEligible(25)),
        _usageRow(Icons.ondemand_video_outlined, 'Film HD / 1080p', _isEligible(5)),
        _usageRow(Icons.public, 'Web et musique', _isEligible(1)),
        if (showStabilityWarning)
          Container(
            margin: const EdgeInsets.only(top: 5, bottom: 8),
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            decoration: BoxDecoration(
              color: const Color(0x332B1B0A),
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: const Color(0x66FFB74D)),
            ),
            child: const Text(
              '⚠ Réseau instable : des coupures ou chargements peuvent survenir.',
              style: TextStyle(color: Color(0xFFFFB74D), fontSize: 13),
            ),
          ),
        const Padding(
          padding: EdgeInsets.only(top: 20, bottom: 8),
          child:           Text('Temps de téléchargement estimé',
              style: TextStyle(fontSize: 16, fontWeight: FontWeight.w500, color: Color(0xFFE6F1E9))),
        ),
        _estimateRow(Icons.music_note, 'Chanson · 5 Mo', 5),
        _estimateRow(Icons.movie_outlined, 'Film HD · 1 500 Mo', 1500),
        _estimateRow(Icons.movie_outlined, 'Film UHD · 7 000 Mo', 7000),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: 2,
      child: Scaffold(
        backgroundColor: const Color(0xFF020604),
        body: SafeArea(
          child: Stack(
            children: [
              Positioned.fill(
                child: Image.asset(
                  'assets/netmonitor_background.jpg',
                  fit: BoxFit.cover,
                ),
              ),
              Positioned.fill(
                child: ColoredBox(color: Colors.black.withOpacity(0.8)),
              ),
              Positioned.fill(
                child: Material(
                  color: Colors.transparent,
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(20, 12, 20, 38),
                    child: Column(
                      children: [
                        TabBar(
                          labelColor: const Color(0xFF56FF9F),
                          unselectedLabelColor: const Color(0xFFA5B5AA),
                          indicatorColor: const Color(0xFF38FF91),
                          indicatorWeight: 2,
                          dividerColor: const Color(0x2638FF91),
                          tabs: const [
                            Tab(text: 'Qualité réseau'),
                            Tab(text: 'Usages'),
                          ],
                        ),
                        Expanded(
                          child: TabBarView(
                            children: [
                              _qualityPage(),
                              _usagesPage(),
                            ],
                          ),
                        ),
                        SizedBox(
                          width: double.infinity,
                          height: 58,
                          child: OutlinedButton(
                            style: OutlinedButton.styleFrom(
                              foregroundColor: const Color(0xFF56FF9F),
                              backgroundColor: const Color(0xFF07150F),
                              side: const BorderSide(color: Color(0xFF38FF91), width: 1.5),
                              shape: const StadiumBorder(),
                              textStyle: const TextStyle(
                                fontSize: 13,
                                letterSpacing: 1,
                                fontWeight: FontWeight.bold,
                              ),
                            ),
                            onPressed: () => _control.invokeMethod(_running ? 'stop' : 'start'),
                            child: Text(_running ? 'ARRÊTER LA SURVEILLANCE' : 'LANCER LA SURVEILLANCE'),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
