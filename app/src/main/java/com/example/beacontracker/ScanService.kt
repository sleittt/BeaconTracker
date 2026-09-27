package com.example.beacontracker

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service: сканирует BLE и трекает геолокацию постоянно,
 * в том числе с выключенным экраном. Результаты пишет в SessionsStore
 * (синглтон), MainActivity подписан на те же потоки.
 *
 * Фильтрация меток — в BeaconFilters.kt (BeaconFilterConfig.filters).
 */
class ScanService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var store: SessionsStore
    private lateinit var scanner: BleScanner
    private lateinit var tracker: SessionTracker
    private lateinit var fused: FusedLocationProviderClient
    private var lastLocation: Location? = null
    private var scanning = false
    private var restartJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        store = SessionsStore.get(this)
        store.logEvent("svc onCreate")
        // умный фильтр получает доступ к списку опознанных MAC
        BeaconFilterConfig.knownMacsProvider = { store.knownMacs.value }
        fused = LocationServices.getFusedLocationProviderClient(this)
        scanner = BleScanner(this, ::onScanResult) {}
        tracker = SessionTracker(
            scope = serviceScope,
            exitTimeoutMs = 7000L,
            locationProvider = {
                lastLocation?.let { l -> SessionTracker.LatLon(l.latitude, l.longitude) }
            },
            onSessionClosed = { store.add(it) }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        store.logEvent("svc onStartCommand action=${intent?.action}")
        try {
            store.setServiceError(null)
            when (intent?.action) {
                ACTION_STOP -> {
                    stopScan()
                    stopSelf()
                }
                ACTION_RESET -> {
                    tracker.reset()
                    store.clearDevices()
                    store.clearKnownMacs()
                    store.logEvent("сброс: устройства забыты")
                    if (!scanning) stopSelf()
                }
                else -> startAsForeground()
            }
        } catch (e: Exception) {
            Log.e("BeaconDbg", "service crashed in onStartCommand", e)
            store.setServiceError("${e.javaClass.simpleName}: ${e.message}")
            store.logEvent("svc ERROR: ${e.javaClass.simpleName}: ${e.message}")
            stopSelf()
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        if (scanning) return
        scanning = true

        createChannel()
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Beacon Tracker")
            .setContentText("Сканирование меток и отслеживание зон активно")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIF_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: SecurityException) {
            store.logEvent("svc SecurityException: ${e.message}")
            Log.e("BeaconDbg", "service: startForeground SecurityException", e)
            store.setServiceError("startForeground: ${e.message}")
            stopSelf()
            return
        }

        Log.d("BeaconDbg", "service: startForeground ok, scan starting")
        store.logEvent("svc startForeground OK")
        // Перезапуск скана раз в 30 сек: на части прошивок фильтр-дедупликатор
        // "залипает" и перестаёт отдавать новые устройства — рестарт пробивает
        restartJob = serviceScope.launch {
            while (true) {
                kotlinx.coroutines.delay(30_000)
                if (scanning) {
                    scanner.stop()
                    scanner.start()
                    store.logEvent("scanner restart (30s)")
                }
            }
        }
        store.setServiceRunning(true)
        startLocationUpdates()
        scanner.start()
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build()
        fused.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
    }

    private fun onScanResult(result: ScanResult) {
        store.bumpPacket()
        Log.d("BeaconDbg", "seen: ${result.device.address} rssi=${result.rssi}")
        // Применяем все фильтры из конфигурации (пусто = все устройства)
        if (!BeaconFilterConfig.filters.all { it.matches(result) }) return

        val name = try {
            result.device.name
        } catch (e: SecurityException) {
            null
        } ?: "неизвестно"
        store.updateDevice(result.device.address, name, result.rssi)
        tracker.onResult(result.device.address, name, result.rssi)

        // Учимся: если это iBeacon-пакет или имя TG_ — запоминаем MAC,
        // чтобы метка узнавалась даже когда перестанет вещать имя
        if (IBeaconParser.parse(result) != null || name.startsWith("TG_")) {
            store.rememberMac(result.device.address)
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            // Берём только точные фиксы: первые координаты часто по вышкам/Wi-Fi
            // с точностью ±100-500 м и скачут; GPS нужно 30-60 сек на холодный старт
            if (loc.hasAccuracy() && loc.accuracy <= 25f) {
                lastLocation = loc
            }
        }
    }

    private fun stopScan() {
        if (!scanning) return
        Log.d("BeaconDbg", "service: stop scan")
        store.setServiceRunning(false)
        scanning = false
        restartJob?.cancel()
        scanner.stop()
        fused.removeLocationUpdates(locationCallback)
        tracker.closeAll()
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
    }

    override fun onDestroy() {
        stopScan()
        store.setServiceRunning(false)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Сканирование", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    companion object {
        const val ACTION_START = "com.example.beacontracker.START"
        const val ACTION_STOP = "com.example.beacontracker.STOP"
        const val ACTION_RESET = "com.example.beacontracker.RESET"
        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "scan_channel"
    }
}
