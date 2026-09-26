package com.example.beacontracker

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

class MainActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var store: SessionsStore

    private val devices = mutableStateMapOf<String, DeviceRow>()
    private val sessions = mutableStateListOf<SessionsStore.SessionRecord>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName

        store = SessionsStore.get(this)

        setContent {
            MaterialTheme {
                BeaconApp()
            }
        }
    }


    @Composable
    private fun BeaconApp() {
        var granted by remember { mutableStateOf(corePermissionsGranted()) }
        var mapView by remember { mutableStateOf<MapView?>(null) }
        var myLocation by remember { mutableStateOf<MyLocationNewOverlay?>(null) }
        val addedOverlays = remember { mutableListOf<Overlay>() }

        // Диагностика: перечитываем состояния системы раз в 2 сек
        var diagTick by remember { mutableStateOf(0) }
        var serviceRunning by remember { mutableStateOf(false) }
        var startAttempts by remember { mutableStateOf(0) }
        var serviceError by remember { mutableStateOf<String?>(null) }
        var events by remember { mutableStateOf<List<String>>(emptyList()) }
        var packetCount by remember { mutableStateOf(0L) }
        var lastPacketAt by remember { mutableStateOf(0L) }
        LaunchedEffect(Unit) {
            store.serviceRunning.collect { serviceRunning = it }
            store.startAttempts.collect { startAttempts = it }
            store.serviceError.collect { serviceError = it }
            store.events.collect { events = it }
            store.packetCount.collect { packetCount = it }
            store.lastPacketAt.collect { lastPacketAt = it }
        }
        LaunchedEffect(diagTick) {
            delay(2000)
            diagTick++
        }

        val permLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            granted = corePermissionsGranted()
        }
        LaunchedEffect(Unit) {
            if (!granted) permLauncher.launch(allPermissionsToRequest())
        }

        LaunchedEffect(Unit) {
            store.devices.collect { map ->
                devices.clear()
                devices.putAll(map)
            }
        }

        LaunchedEffect(Unit) {
            store.sessions.collect { list ->
                sessions.clear()
                sessions.addAll(list)
            }
        }

        // Маркеры сессий на карте
        LaunchedEffect(sessions.toList()) {
            val mv = mapView ?: return@LaunchedEffect
            addedOverlays.forEach { mv.overlays.remove(it) }
            addedOverlays.clear()
            sessions.forEach { r ->
                val pts = mutableListOf<GeoPoint>()
                fun mark(lat: Double?, lon: Double?, title: String) {
                    if (lat == null || lon == null) return
                    val m = Marker(mv)
                    m.position = GeoPoint(lat, lon)
                    m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    m.title = title
                    mv.overlays.add(m)
                    addedOverlays.add(m)
                    pts.add(GeoPoint(lat, lon))
                }
                mark(r.entryLat, r.entryLon, "Вход: ${TimeFmt.short(r.entryTime)}")
                mark(r.peakLat, r.peakLon, "Пик: ${r.peakRssi} dBm, ${TimeFmt.short(r.peakTime)}")
                mark(r.exitLat, r.exitLon, "Выход: ${TimeFmt.short(r.exitTime)}")
                if (pts.size >= 2) {
                    val line = Polyline(mv)
                    line.setPoints(pts)
                    line.outlinePaint.color = 0xFF2196F3.toInt()
                    line.outlinePaint.strokeWidth = 6f
                    mv.overlays.add(line)
                    addedOverlays.add(line)
                }
            }
            mv.invalidate()
        }

        // Жизненный цикл MapView
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> mapView?.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapView?.onPause()
                    else -> {}
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(8.dp)) {

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = ::startScanning, modifier = Modifier.weight(1f)) {
                        Text("Старт")
                    }
                    Button(onClick = ::stopScanning, modifier = Modifier.weight(1f)) {
                        Text("Стоп")
                    }
                    Button(onClick = ::exportCsv, modifier = Modifier.weight(1f)) {
                        Text("Экспорт")
                    }
                }

                Text(
                    text = buildString {
                        appendLine("сборка: fgs-diag-10")
                        appendLine("Манифест: ${if (isServiceDeclared()) "сервис объявлен" else "СЕРВИС НЕ ОБЪЯВЛЕН!"}")
                        appendLine("Bluetooth: ${if (isBluetoothOn()) "вкл" else "ВЫКЛ"}")
                        appendLine("GPS: ${if (isLocationEnabled()) "вкл" else "ВЫКЛ"}")
                        val missing = missingCoreNames()
                        appendLine("Разрешения: ${if (missing.isEmpty()) "все выданы" else "НЕТ: $missing"}")
                        appendLine("Сервис: ${if (serviceRunning) "работает" else "НЕ запущен"}")
                        appendLine("Попыток старта: $startAttempts")
                        serviceError?.let { appendLine("ОШИБКА: $it") }
                        val idleSec = if (lastPacketAt == 0L) "-"
                        else (System.currentTimeMillis() - lastPacketAt) / 1000
                        appendLine("Пакетов всего: $packetCount (пауза: ${idleSec}с)")
                        append("Устройств в эфире: ${devices.size}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 2.dp)
                )

                // Лента событий
                if (events.isNotEmpty()) {
                    Text(
                        text = events.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }

                Box(
                    Modifier
                        .weight(3f)
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .clipToBounds()
                ) {
                    AndroidView(
                        factory = { ctx ->
                            MapView(ctx).apply {
                                val tileSource = XYTileSource(
                                    "OSM.DE",
                                    0, 19, 256, ".png",
                                    arrayOf("https://tile.openstreetmap.de/")
                                )
                                setTileSource(tileSource)
                                setMultiTouchControls(true)
                                controller.setZoom(16.0)
                                val o = MyLocationNewOverlay(GpsMyLocationProvider(ctx), this)
                                myLocation = o
                                overlays.add(o)
                                mapView = this
                            }
                        },
                        update = {
                            if (granted) {
                                myLocation?.enableMyLocation()
                                myLocation?.enableFollowLocation()
                                // Центрируем на первом же фиксе координат
                                myLocation?.runOnFirstFix {
                                    runOnUiThread {
                                        myLocation?.myLocation?.let { loc ->
                                            mapView?.controller?.animateTo(
                                                GeoPoint(loc.latitude, loc.longitude)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    )
                }

                Text("Устройства рядом:", style = MaterialTheme.typography.labelLarge)
                LazyColumn(Modifier.weight(2f).fillMaxWidth()) {
                    items(
                        items = devices.values.sortedByDescending { it.rssi },
                        key = { it.mac }
                    ) { d ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(
                                d.name,
                                Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(d.mac, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.width(8.dp))
                            Text("${d.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                }

                Text(
                    "Сессии (вход / пик / выход):",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 4.dp)
                )
                LazyColumn(Modifier.weight(2f).fillMaxWidth()) {
                    items(items = sessions, key = { it.id }) { r ->
                        SessionRow(r)
                    }
                }
            }
        }
    }

    @Composable
    private fun SessionRow(r: SessionsStore.SessionRecord) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text("${r.name} (${r.mac})", style = MaterialTheme.typography.titleSmall)
            Text("Вход: ${TimeFmt.full(r.entryTime)}", style = MaterialTheme.typography.bodySmall)
            Text(
                "Пик: ${r.peakRssi} dBm в ${TimeFmt.full(r.peakTime)}",
                style = MaterialTheme.typography.bodySmall
            )
            Text("Выход: ${TimeFmt.full(r.exitTime)}", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
        }
    }


    /** Обязательные разрешения для сканирования и карты. БЕЗ нотификаций. */
    private fun corePermissions(): List<String> {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 31) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        }
        return perms
    }

    private fun corePermissionsGranted() =
        corePermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun allPermissionsToRequest(): Array<String> {
        val perms = corePermissions().toMutableList()
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        return perms.toTypedArray()
    }

    private fun missingCoreNames(): String =
        corePermissions()
            .filter {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_DENIED
            }
            .map {
                when (it) {
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION -> "Геолокация"
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT -> "Bluetooth"
                    else -> it
                }
            }
            .distinct()
            .joinToString(", ")

    private fun isBluetoothOn(): Boolean =
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled == true

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    /** Проверка в рантайме: сервис объявлен в манифесте или нет. */
    private fun isServiceDeclared(): Boolean =
        try {
            packageManager.getServiceInfo(ComponentName(this, ScanService::class.java), 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    private fun startScanning() {
        store.logEvent("click Старт")
        if (!isServiceDeclared()) {
            store.setServiceError("ScanService НЕ объявлен в AndroidManifest!")
            store.logEvent("ERROR: сервиса нет в манифесте")
            return
        }
        if (!corePermissionsGranted()) {
            toast("Не выдано: ${missingCoreNames()}. Проверь настройки приложения.")
            // Открываем настройки приложения, если система больше не показывает диалог
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", packageName, null)
                )
            )
            return
        }
        if (!isBluetoothOn()) {
            toast("Включи Bluetooth")
            return
        }
        if (!isLocationEnabled()) {
            toast("Включи геолокацию (GPS) в шторке")
            return
        }
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, ScanService::class.java).setAction(ScanService.ACTION_START)
            )
            store.logEvent("startForegroundService() вызван")
        } catch (e: Exception) {
            store.setServiceError("startForegroundService: ${e.javaClass.simpleName}: ${e.message}")
            store.logEvent("ERROR вызова: ${e.javaClass.simpleName}: ${e.message}")
        }
        toast("Сканирование запущено")
    }

    private fun stopScanning() {
        startService(Intent(this, ScanService::class.java).setAction(ScanService.ACTION_STOP))
        toast("Остановлено")
    }

    private fun exportCsv() {
        scope.launch {
            val uri = withContext(Dispatchers.IO) {
                CsvExporter.export(this@MainActivity, store.load())
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(contentResolver, "beacon_sessions", uri)
            }
            startActivity(Intent.createChooser(intent, "Экспорт CSV"))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
