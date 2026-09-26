package com.example.beacontracker

import android.bluetooth.le.ScanResult

/**
 * Точка расширения фильтрации меток.
 *
 * Как добавить новый фильтр:
 *   1. Напиши класс, реализующий BeaconFilter (или используй готовые ниже).
 *   2. Добавь его экземпляр в BeaconFilterConfig.filters.
 * Всё. Сканер, трекер, UI менять не нужно.
 *
 * В matches() доступен полный ScanResult: device, rssi,
 * scanRecord (UUID сервисов, manufacturer-данные, txPower) — хватит
 * на любые параметры, какие придумает заказчик.
 */
fun interface BeaconFilter {
    fun matches(result: ScanResult): Boolean
}

/** Сюда добавлять фильтры. Пустой список = видны все BLE-устройства. */
object BeaconFilterConfig {
    /**
     * Провайдер MAC меток, опознанных автоматически. Заполняется
     * ScanService при старте сервиса — сюда подставляется хранилище.
     */
    var knownMacsProvider: () -> Set<String> = { emptySet() }

    /**
     * Фильтр по умолчанию: автоматически распознаёт метки.
     * Пропускает устройство, если:
     *  1) это iBeacon-пакет (парсится UUID/Major/Minor), или
     *  2) имя начинается с TG_, или
     *  3) MAC уже видели раньше как метку (запоминается само).
     * Метки должны быть в режиме iBeacon или Смешанный (настраивается
     * один раз в «Эскорт Конфигураторе»).
     * Чтобы игнорировать чужие далёкие метки, добавь MinRssiFilter(-80).
     */
    val filters: List<BeaconFilter> = emptyList()
//        listOf(
//        SmartBeaconFilter { knownMacsProvider() }
//    )

    // Другие варианты:
    //   listOf(NamePrefixFilter("TG_"))               — только по имени (метки без имени не пройдут)
    //   listOf(MacFilter("AA:BB:CC:DD:EE:FF"))        — жёстко одна метка
    //   listOf(IBeaconUuidFilter("A386C1DD-EEFF-A9E0-93F3-A3B501004060"))
    //   listOf(SmartBeaconFilter { knownMacsProvider() }, MinRssiFilter(-80))
}

/**
 * Автоматическое распознавание меток: iBeacon-пакет ИЛИ имя TG_
 * ИЛИ MAC из списка уже опознанных (наполняется сам при первом
 * совпадении по 1 или 2).
 */
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

/** По префиксу имени. ВАЖНО: многие метки не вещают имя в пакете —
 *  с этим фильтром список может быть пустым. Надёжнее MacFilter. */
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

/** По MAC-адресу конкретной метки. */
class MacFilter(private val mac: String) : BeaconFilter {
    override fun matches(result: ScanResult): Boolean =
        result.device.address.equals(mac, ignoreCase = true)
}

/** Только устройства ближе порога (по RSSI). */
class MinRssiFilter(private val minRssi: Int) : BeaconFilter {
    override fun matches(result: ScanResult): Boolean = result.rssi >= minRssi
}

/** По UUID iBeacon-рассылки (нужен, если метки в режиме iBeacon). */
class IBeaconUuidFilter(private val uuid: String) : BeaconFilter {
    override fun matches(result: ScanResult): Boolean =
        IBeaconParser.parse(result)?.uuid.equals(uuid, ignoreCase = true)
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
        // Формат: 02 15 | 16 байт UUID | 2 байта Major | 2 байта Minor | 1 байт TxPower
        if (data.size < 23 || data[0] != 0x02.toByte() || data[1] != 0x15.toByte()) return null

        val uuid = StringBuilder()
        for (i in 0 until 16) {
            uuid.append(String.format("%02X", data[2 + i]))
            if (i == 3 || i == 5 || i == 7 || i == 9) uuid.append('-')
        }
        val major = ((data[18].toInt() and 0xFF) shl 8) or (data[19].toInt() and 0xFF)
        val minor = ((data[20].toInt() and 0xFF) shl 8) or (data[21].toInt() and 0xFF)
        val txPower = data[22].toInt() // знаковый
        return IBeaconData(uuid.toString(), major, minor, txPower)
    }
}
