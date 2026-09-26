package com.example.beacontracker

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CsvExporter {

    fun export(context: Context, sessions: List<SessionsStore.SessionRecord>): Uri {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()
        sb.appendLine(
            "mac;name;entry_time;peak_rssi_dbm;peak_time;exit_time;" +
                    "entry_lat;entry_lon;peak_lat;peak_lon;exit_lat;exit_lon"
        )
        sessions.forEach { r ->
            sb.appendLine(
                listOf(
                    r.mac, r.name,
                    fmt.format(Date(r.entryTime)),
                    r.peakRssi,
                    fmt.format(Date(r.peakTime)),
                    fmt.format(Date(r.exitTime)),
                    r.entryLat ?: "", r.entryLon ?: "",
                    r.peakLat ?: "", r.peakLon ?: "",
                    r.exitLat ?: "", r.exitLon ?: ""
                ).joinToString(";")
            )
        }
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "beacon_sessions.csv")
        file.writeText(sb.toString())
        return FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
    }
}
