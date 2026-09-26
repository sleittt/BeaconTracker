package com.example.beacontracker

import android.annotation.SuppressLint
import android.util.Log
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

class BleScanner(
    context: Context,
    private val onResult: (ScanResult) -> Unit,
    private val onError: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val adapter: android.bluetooth.BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val scanner = adapter?.bluetoothLeScanner

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            onResult(result)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("BeaconDbg", "Scan failed: $errorCode")
            onError("Ошибка сканирования: код $errorCode")
        }
    }

    /**
     * Аппаратный фильтр: только iBeacon (manufacturer Apple 0x004C,
     * первые два байта данных 0x02 0x15). Отбор идёт в прошивке чипа —
     * обходит баги хост-пути, где терялись пакеты с random-адресов.
     * null в IBEACON_MANUFACTURER_ID = снова видеть все устройства.
     */
    private val scanFilters: List<ScanFilter>? =
        IBEACON_MANUFACTURER_ID?.let { apple ->
            listOf(
                ScanFilter.Builder()
                    .setManufacturerData(
                        apple,
                        byteArrayOf(0x02, 0x15),
                        byteArrayOf(0xFF.toByte(), 0xFF.toByte())
                    )
                    .build()
            )
        }

    private fun hasScanPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
                ContextCompat.checkSelfPermission(
                    appContext, android.Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED

    fun isBluetoothOn(): Boolean = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun start() {
        when {
            !hasScanPermission() -> onError("Нет разрешения BLUETOOTH_SCAN")
            scanner == null -> onError("BLE не поддерживается на устройстве")
            else -> {
                // Настройки как у nRF Connect: агрессивный матчинг, все пакеты,
                // без батчинга. На части прошивок (MediaTek!) софтверный скан
                // с дефолтными настройками пропускает устройства.
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                    .setReportDelay(0L)
                    .build()
                Log.d("BeaconDbg", "scanner.start() called")
                scanner.startScan(scanFilters, settings, callback)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (hasScanPermission()) scanner?.stopScan(callback)
    }

    companion object {
        /** Apple company ID (iBeacon). null = видеть все BLE-устройства. */
        val IBEACON_MANUFACTURER_ID: Int? = 0x004C
    }
}
