package com.example.beacontracker

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

data class DeviceRow(val mac: String, val name: String, val rssi: Int)

class SessionsStore private constructor(context: Context) {

    data class SessionRecord(
        val id: Long = 0,
        val mac: String,
        val name: String,
        val entryTime: Long,
        val peakRssi: Int,
        val peakTime: Long,
        val exitTime: Long,
        val entryLat: Double?, val entryLon: Double?,
        val peakLat: Double?, val peakLon: Double?,
        val exitLat: Double?, val exitLon: Double?
    )

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, "sessions.json")
    private val gson = Gson()
    private val prefs = appContext.getSharedPreferences("beacons", Context.MODE_PRIVATE)

    // MAC-адреса, которые приложение само опознало как метки (iBeacon или имя TG_)
    private val _knownMacs = MutableStateFlow(
        prefs.getStringSet("macs", emptySet())?.toSet() ?: emptySet()
    )
    val knownMacs: StateFlow<Set<String>> = _knownMacs
    fun rememberMac(mac: String) {
        if (_knownMacs.value.contains(mac)) return
        val new = _knownMacs.value + mac
        _knownMacs.value = new
        prefs.edit().putStringSet("macs", new).apply()
    }

    private val _sessions = MutableStateFlow(load())
    val sessions: StateFlow<List<SessionRecord>> = _sessions

    // Текущие устройства в эфире (не сохраняются, только для UI)
    private val _devices = MutableStateFlow<Map<String, DeviceRow>>(emptyMap())
    val devices: StateFlow<Map<String, DeviceRow>> = _devices

    // Флаг: сервис сканирования сейчас работает
    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning
    fun setServiceRunning(v: Boolean) { _serviceRunning.value = v }

    // Диагностика запуска сервиса
    private val _serviceError = MutableStateFlow<String?>(null)
    val serviceError: StateFlow<String?> = _serviceError
    fun setServiceError(m: String?) { _serviceError.value = m }

    // Счётчик всех принятых рекламных пакетов (живость сканера без logcat)
    private val _packetCount = MutableStateFlow(0L)
    val packetCount: StateFlow<Long> = _packetCount
    private val _lastPacketAt = MutableStateFlow(0L)
    val lastPacketAt: StateFlow<Long> = _lastPacketAt
    fun bumpPacket() {
        _packetCount.value += 1
        _lastPacketAt.value = System.currentTimeMillis()
    }

    // Лента событий для диагностики (последние 6)
    private val _events = MutableStateFlow<List<String>>(emptyList())
    val events: StateFlow<List<String>> = _events
    fun logEvent(msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        _events.value = (_events.value + "$time $msg").takeLast(6)
    }

    @Synchronized
    fun add(r: SessionRecord) {
        val list = load().toMutableList()
        // id присваиваем здесь: трекер создаёт записи без id (0),
        // а в LazyColumn ключ по id — дубликаты уронят Compose
        val newId = (list.maxOfOrNull { it.id } ?: 0L) + 1
        list.add(0, r.copy(id = newId))
        file.writeText(gson.toJson(list))
        _sessions.value = list
    }

    fun load(): List<SessionRecord> {
        if (!file.exists()) return emptyList()
        val type = object : TypeToken<List<SessionRecord>>() {}.type
        return gson.fromJson(file.readText(), type) ?: emptyList()
    }

    fun updateDevice(mac: String, name: String, rssi: Int) {
        _devices.value = _devices.value + (mac to DeviceRow(mac, name, rssi))
    }

    /** Сброс: забыть устройства в эфире и опознанные MAC. */
    fun clearDevices() { _devices.value = emptyMap() }
    fun clearKnownMacs() {
        _knownMacs.value = emptySet()
        prefs.edit().remove("macs").apply()
    }

    companion object {
        @Volatile private var INSTANCE: SessionsStore? = null

        fun get(context: Context): SessionsStore =
            INSTANCE ?: synchronized(this) {
                SessionsStore(context).also { INSTANCE = it }
            }
    }
}
