package com.example.wifimonitor

import java.util.Date

/**
 * Holds a single poll snapshot of all WiFi metrics.
 */
enum class WifiConnectionState {
    DISCONNECTED,
    SCANNING,
    ASSOCIATING,
    CONNECTED
}
data class WifiPollRecord(

    val timestamp: Date,

    // Network identity
    val ssid: String,
    val bssid: String,

    // IP layer
    val ipAddress: String,
    val subnetPrefix: Int,          // e.g. 24
    val defaultGateway: String,

    // Radio
    val band: String,               // e.g. "5 GHz"
    val channel: Int,
    val rssiDbm: Int,               // e.g. -65
    val noiseFloorDbm: Int,         // e.g. -95 (estimated)
    val snrDb: Int,                 // rssi - noiseFloor
    val signalQualityPercent: Int,  // 0–100

    // WiFi Connection State
    val connectionState: WifiConnectionState

    // Ping
    val pingRttMs: Double,          // -1.0 if unreachable
    val pingSuccess: Boolean
) {
    /** CSV header row */
    companion object {
        const val CSV_HEADER =
            "Timestamp,SSID,BSSID,IP Address,Subnet Prefix,Default Gateway," +
            "Band,Channel,RSSI (dBm),Noise Floor (dBm),SNR (dB),Signal Quality (%)," +
            "Ping RTT (ms),Ping Success"
    }

    /** Serialise this record to a single CSV row. */
    fun toCsvRow(): String {
        val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .format(timestamp)
        val rtt = if (pingSuccess) "%.2f".format(pingRttMs) else "TIMEOUT"
        return listOf(
            ts, csvEscape(ssid), csvEscape(bssid),
            ipAddress, "/$subnetPrefix", defaultGateway,
            band, channel,
            rssiDbm, noiseFloorDbm, snrDb, signalQualityPercent,
            rtt, pingSuccess
        ).joinToString(",")
    }

    private fun csvEscape(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n'))
            "\"${s.replace("\"", "\"\"")}\"" else s
}
