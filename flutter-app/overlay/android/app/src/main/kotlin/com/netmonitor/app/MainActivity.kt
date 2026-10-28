package com.netmonitor.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Pont Flutter ↔ service natif.
 *  - MethodChannel « netmonitor/control » : commandes (démarrer, arrêter, permission).
 *  - EventChannel  « netmonitor/state »   : flux d'état en direct (le StateFlow du service).
 */
class MainActivity : FlutterActivity() {

    private var collector: Job? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val messenger = flutterEngine.dartExecutor.binaryMessenger

        MethodChannel(messenger, "netmonitor/control").setMethodCallHandler { call, result ->
            when (call.method) {
                "autoStart" -> {
                    if (Prefs.isEnabled(this) && !NetworkStateHolder.state.value.running) {
                        NetworkMonitorService.start(this)
                    }
                    result.success(null)
                }
                "start" -> {
                    Prefs.setEnabled(this, true)
                    NetworkMonitorService.start(this)
                    result.success(null)
                }
                "stop" -> {
                    Prefs.setEnabled(this, false)
                    NetworkMonitorService.stop(this)
                    result.success(null)
                }
                "requestNotificationPermission" -> {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                    }
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }

        EventChannel(messenger, "netmonitor/state").setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                collector?.cancel()
                collector = CoroutineScope(Dispatchers.Main).launch {
                    NetworkStateHolder.state.collect { events.success(it.toMap()) }
                }
            }

            override fun onCancel(arguments: Any?) {
                collector?.cancel()
                collector = null
            }
        })
    }
}
