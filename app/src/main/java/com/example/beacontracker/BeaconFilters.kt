package com.example.beacontracker

import android.bluetooth.le.ScanResult

/**
 * Программная фильтрация пакетов (работает поверх уже принятых).
 * Добавить свой фильтр = написать класс с BeaconFilter и добавить
 * экземпляр в пресет или в BeaconFilterConfig.filters.
 */
fun interface BeaconFilter {
    fun matches(result: ScanResult): Boolean
}

/** Живой список программных фильтров. Меняется пресетами (ScanFilterPreset). */
object BeaconFilterConfig {
    var knownMacsProvider: () -> Set<String> = { emptySet() }
    var filters: List<BeaconFilter> = emptyList()
}

/**
 * Готовые наборы фильтрации, переключаются из выпадающего меню.
 * Каждый пресет задаёт и аппаратный фильтр чипа
 * (BleScanner.IBEACON_MANUFACTURER_ID), и программные фильтры.
 * Новое значение вступает в силу при следующем старте скана
 * (сервис перезапускает скан каждые 30 с, либо Стоп/Старт).
 */
enum class ScanFilterPreset(val label: String) {
    ALL("Все BLE-устройства"),
    IBEACON("Только iBeacon"),
    ESCORT("Метки Эскорт (TG_)"),
    NEARBY("iBeacon ближе -80 dBm");

    fun apply() {
        BleScanner.IBEACON_MANUFACTURER_ID = when (this) {
            ALL -> null
            else -> 0x004C
        }
        BeaconFilterConfig.filters = when (this) {
            ALL, IBEACON -> emptyList()
            ESCORT -> listOf(SmartBeaconFilter { BeaconFilterConfig.knownMacsProvider() })
            NEARBY -> listOf(MinRssiFilter(-80))
        }
    }
}

/** Авто-распознавание: iBeacon-пакет ИЛИ имя TG_ ИЛИ MAC уже опознанной метки. */
class SmartBeaconFilter(private val knownMacs: () -> Set<String>) : BeaconFilter {
    override fun matches(result: ScanResult): Boolean {
        if (IBeaconParser.parse(result) != null) return true
        val name = try {
            result.device.name
        } catch (e: SecurityException) {
            null
        }
        if (name?.startsWith("TG_") == true) return true
        return knownMacs().contains(result.device.address)
    }
}

class NamePrefixFilter(private val prefix: String) : BeaconFilter {
    override fun matches(result: ScanResult): Boolean {
        val name = try {
            result.device.name
        } catch (e: SecurityException) {
            null
        }
        return name?.startsWith(prefix) == true
    }
}

class MacFilter(private val mac: String) : BeaconFilter {
    override fun matches(result: ScanResult): Boolean =
        result.device.address.equals(mac, ignoreCase = true)
}

class MinRssiFilter(private val minRssi: Int) : BeaconFilter {
    override fun matches(result: ScanResult): Boolean = result.rssi >= minRssi
}

class IBeaconUuidFilter(private val uuid: String) : BeaconFilter {
    override fun matches(result: ScanResult): Boolean =
        IBeaconParser.parse(result)?.uuid.equals(uuid, ignoreCase = true)
}

/** По суффиксу UUID (у вендора меток суффикс общий). Пример: "-eeff-a9e0-93f3-a3b50100406". */
class IBeaconUuidSuffixFilter(private val suffix: String) : BeaconFilter {
    override fun matches(result: ScanResult): Boolean =
        IBeaconParser.parse(result)?.uuid?.endsWith(suffix, ignoreCase = true) == true
}

data class IBeaconData(
    val uuid: String,
    val major: Int,
    val minor: Int,
    val txPower: Int
)

/** Разбор iBeacon из manufacturer-данных Apple (0x004C). */
object IBeaconParser {
    fun parse(result: ScanResult): IBeaconData? {
        val data = result.scanRecord?.getManufacturerSpecificData(0x004C) ?: return null
        if (data.size < 23 || data[0] != 0x02.toByte() || data[1] != 0x15.toByte()) return null

        val uuid = StringBuilder()
        for (i in 0 until 16) {
            uuid.append(String.format("%02X", data[2 + i]))
            if (i == 3 || i == 5 || i == 7 || i == 9) uuid.append('-')
        }
        val major = ((data[18].toInt() and 0xFF) shl 8) or (data[19].toInt() and 0xFF)
        val minor = ((data[20].toInt() and 0xFF) shl 8) or (data[21].toInt() and 0xFF)
        val txPower = data[22].toInt()
        return IBeaconData(uuid.toString(), major, minor, txPower)
    }
}
