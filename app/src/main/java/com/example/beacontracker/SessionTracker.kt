package com.example.beacontracker

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Логика зон по каждой BLE-метке (MAC-адрес):
 *  - первое появление = вход в зону (entryTime)
 *  - максимальный RSSI с моментом времени = пик
 *  - устройство не видно exitTimeoutMs = выход из зоны (exitTime)
 * На выходе формируется SessionRecord — одна строка на сессию.
 */
class SessionTracker(
    private val scope: CoroutineScope,
    private val exitTimeoutMs: Long = 7000L,
    private val locationProvider: () -> LatLon?,
    private val onSessionClosed: (SessionsStore.SessionRecord) -> Unit
) {
    data class LatLon(val lat: Double, val lon: Double)

    private class State(
        val mac: String,
        val name: String,
        val entryTime: Long
    ) {
        var peakRssi: Int = Int.MIN_VALUE
        var peakTime: Long = 0L
        var entryLat: Double? = null
        var entryLon: Double? = null
        var peakLat: Double? = null
        var peakLon: Double? = null
        var exitJob: Job? = null
    }

    private val active = mutableMapOf<String, State>()

    fun onResult(mac: String, name: String, rssi: Int) {
        val now = System.currentTimeMillis()
        var st = active[mac]
        if (st == null) {
            st = State(mac, name, now)
            locationProvider()?.let { st.entryLat = it.lat; st.entryLon = it.lon }
            active[mac] = st
        }

        st.exitJob?.cancel()

        if (rssi > st.peakRssi) {
            st.peakRssi = rssi
            st.peakTime = now
            locationProvider()?.let { st.peakLat = it.lat; st.peakLon = it.lon }
        }

        st.exitJob = scope.launch {
            delay(exitTimeoutMs)
            active.remove(mac)
            val loc = locationProvider()
            onSessionClosed(
                SessionsStore.SessionRecord(
                    mac = st.mac,
                    name = st.name,
                    entryTime = st.entryTime,
                    peakRssi = st.peakRssi,
                    peakTime = st.peakTime,
                    exitTime = System.currentTimeMillis(),
                    entryLat = st.entryLat, entryLon = st.entryLon,
                    peakLat = st.peakLat, peakLon = st.peakLon,
                    exitLat = loc?.lat, exitLon = loc?.lon
                )
            )
        }
    }

    /** Забыть все активные сессии БЕЗ создания записей (кнопка «Сброс»). */
    fun reset() {
        active.values.forEach { it.exitJob?.cancel() }
        active.clear()
    }

    /** Принудительно закрыть все активные сессии (при остановке сканирования). */
    fun closeAll() {
        val now = System.currentTimeMillis()
        val loc = locationProvider()
        active.values.toList().forEach { st ->
            st.exitJob?.cancel()
            onSessionClosed(
                SessionsStore.SessionRecord(
                    mac = st.mac,
                    name = st.name,
                    entryTime = st.entryTime,
                    peakRssi = st.peakRssi,
                    peakTime = st.peakTime,
                    exitTime = now,
                    entryLat = st.entryLat, entryLon = st.entryLon,
                    peakLat = st.peakLat, peakLon = st.peakLon,
                    exitLat = loc?.lat, exitLon = loc?.lon
                )
            )
        }
        active.clear()
    }
}
