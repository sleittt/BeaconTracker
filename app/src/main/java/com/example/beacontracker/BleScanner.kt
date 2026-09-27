package com.example.beacontracker

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Обёртка над BluetoothLeScanner.
 * Аппаратный фильтр по company ID отбирает пакеты в прошивке чипа —
 * это обходит баги хост-пути, где на части устройств терялись пакеты
 * от устройств со static random address. Значение меняется на лету
 * через IBEACON_MANUFACTURER_ID / пресеты ScanFilterPreset.
 */
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

    /** Фильтр собирается при каждом старте — менять можно на лету. */
    private fun buildScanFilters(): List<ScanFilter>? =
        IBEACON_MANUFACTURER_ID?.let { mfr ->
            listOf(
                ScanFilter.Builder()
                    .setManufacturerData(
                        mfr,
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
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                    .setReportDelay(0L)
                    .build()
                scanner.startScan(buildScanFilters(), settings, callback)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (hasScanPermission()) scanner?.stopScan(callback)
    }

    companion object {
        /** Apple company ID (iBeacon). null = видеть все BLE-устройства. */
        @Volatile
        var IBEACON_MANUFACTURER_ID: Int? = 0x004C

        fun filterIBeaconOnly() { IBEACON_MANUFACTURER_ID = 0x004C }
        fun filterAllDevices() { IBEACON_MANUFACTURER_ID = null }
    }
}
