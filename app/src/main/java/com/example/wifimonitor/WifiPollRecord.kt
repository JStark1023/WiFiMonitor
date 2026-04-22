package com.example.wifimonitor

import java.util.Date

/**
 * The connection state captured at the moment of each poll.
 *
 * When [CONNECTED] all metric fields carry real values.
 * All other states represent a disconnected or transitioning condition;
 * metric fields will contain sentinel/zero values in those records.
 */
enum class WifiConnectionState {
    /** Fully associated, authenticated, and IP-configured. */
    CONNECTED,
    /** No WiFi network — link is down. */
    DISCONNECTED,
    /** Scanning for networks. */
    SCANNING,
    /** Completing 802.11 authentication (Open/WPA handshake). */
    AUTHENTICATING,
    /** Completing 802.11 association with the AP. */
    ASSOCIATING,
    /** DHCP or link-local address negotiation in progress. */
    OBTAINING_IP,
    /** Connection lost; attempting to reconnect. */
    RECONNECTING;

    val displayLabel: String get() = when (this) {
        CONNECTED      -> "Connected"
        DISCONNECTED   -> "Disconnected"
        SCANNING       -> "Scanning…"
        AUTHENTICATING -> "Authenticating…"
        ASSOCIATING    -> "Associating…"
        OBTAINING_IP   -> "Obtaining IP…"
        RECONNECTING   -> "Reconnecting…"
    }

    val isConnected get() = this == CONNECTED
}

/**
 * Holds a single poll snapshot of all WiFi metrics.
 *
 * When [wifiState] is not [WifiConnectionState.CONNECTED], the radio and
 * IP metric fields contain sentinel values (empty strings, -1, 0, etc.)
 * and should not be displayed as real measurements.
 */
data class WifiPollRecord(
    val timestamp: Date,

    /** Connection state at the moment this record was captured. */
    val wifiState: WifiConnectionState,

    // Network identity — empty strings when not connected
    val ssid: String,
    val bssid: String,

    // IP layer — empty / zero when not connected
    val ipAddress: String,
    val subnetPrefix: Int,
    val defaultGateway: String,

    // Radio — zero / "Unknown" when not connected
    val band: String,
    val channel: Int,
    val rssiDbm: Int,
    val noiseFloorDbm: Int,
    val snrDb: Int,
    val signalQualityPercent: Int,

    // Ping — always false / -1 when not connected
    val pingRttMs: Double,
    val pingSuccess: Boolean
) {
    companion object {
        const val CSV_HEADER =
            "Timestamp,WiFi State,SSID,BSSID,IP Address,Subnet Prefix,Default Gateway," +
                    "Band,Channel,RSSI (dBm),Noise Floor (dBm),SNR (dB),Signal Quality (%)," +
                    "Ping RTT (ms),Ping Success"

        /** Convenience factory for a disconnected/transitioning poll record. */
        fun disconnected(state: WifiConnectionState, timestamp: Date = Date()) = WifiPollRecord(
            timestamp            = timestamp,
            wifiState            = state,
            ssid                 = "",
            bssid                = "",
            ipAddress            = "",
            subnetPrefix         = 0,
            defaultGateway       = "",
            band                 = "",
            channel              = 0,
            rssiDbm              = 0,
            noiseFloorDbm        = 0,
            snrDb                = 0,
            signalQualityPercent = 0,
            pingRttMs            = -1.0,
            pingSuccess          = false
        )
    }

    fun toCsvRow(): String {
        val ts  = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(timestamp)
        val rtt = if (pingSuccess) "%.2f".format(pingRttMs) else "TIMEOUT"
        return listOf(
            ts, wifiState.name, csvEscape(ssid), csvEscape(bssid),
            ipAddress, if (subnetPrefix > 0) "/$subnetPrefix" else "",
            defaultGateway, band, channel,
            rssiDbm, noiseFloorDbm, snrDb, signalQualityPercent,
            rtt, pingSuccess
        ).joinToString(",")
    }

    private fun csvEscape(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n'))
            "\"${s.replace("\"", "\"\"")}\"" else s
}
