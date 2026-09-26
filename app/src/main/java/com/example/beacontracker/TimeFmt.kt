package com.example.beacontracker

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimeFmt {
    private val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
    private val fmtShort = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun full(t: Long): String = fmt.format(Date(t))
    fun short(t: Long): String = fmtShort.format(Date(t))
}
