package com.example.wifimonitor

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.SupplicantState
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Date

/**
 * Collects WiFi metrics and pings the default gateway.
 *
 * ## Disconnection handling
 *
 * Rather than returning [PollResult.Error] when WiFi is down, [poll] always
 * returns [PollResult.Success] with a [WifiPollRecord] whose [WifiPollRecord.wifiState]
 * reflects the current association step.  Callers can inspect [WifiConnectionState]
 * to distinguish connected records from transitional ones.
 *
 * The association step is tracked by listening to two system broadcasts:
 *  - [WifiManager.NETWORK_STATE_CHANGED_ACTION] for high-level link state
 *  - [WifiManager.SUPPLICANT_STATE_CHANGED_ACTION] for fine-grained 802.11
 *    supplicant states (SCANNING → AUTHENTICATING → ASSOCIATING → OBTAINING_IP)
 *
 * ## Android 12+ redaction
 *
 * On API 31+ [ConnectivityManager.getNetworkCapabilities] always redacts
 * SSID/BSSID unless the [WifiInfo] is obtained inside a
 * [ConnectivityManager.NetworkCallback] registered with
 * [ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO].
 */
class WifiDataCollector(private val context: Context) {

    private val tag = "WifiDataCollector"

    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val locationManager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // ── Connection-state tracking ──────────────────────────────────────────

    /**
     * Current WiFi connection state, updated by broadcast receiver.
     * Starts as DISCONNECTED; the NetworkCallback will set it to CONNECTED
     * once the first valid WifiInfo arrives.
     */
    @Volatile var currentConnectionState: WifiConnectionState = WifiConnectionState.DISCONNECTED
        private set

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiManager.SUPPLICANT_STATE_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val supState = intent.getParcelableExtra<SupplicantState>(
                        WifiManager.EXTRA_NEW_STATE
                    )
                    val newState = supplicantStateToConnectionState(supState)
                    // Only apply non-connected supplicant states here;
                    // CONNECTED is driven by the NetworkCallback receiving a valid WifiInfo.
                    if (newState != WifiConnectionState.CONNECTED) {
                        currentConnectionState = newState
                        Log.d(tag, "SupplicantState $supState → connectionState=$newState")
                    }
                }
                WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val netInfo = intent.getParcelableExtra<android.net.NetworkInfo>(
                        WifiManager.EXTRA_NETWORK_INFO
                    )
                    if (netInfo != null && !netInfo.isConnected) {
                        // Link went down — downgrade immediately so the next poll
                        // records the right state even before the supplicant fires.
                        if (currentConnectionState == WifiConnectionState.CONNECTED) {
                            currentConnectionState = WifiConnectionState.DISCONNECTED
                            Log.d(tag, "NETWORK_STATE_CHANGED: link lost → DISCONNECTED")
                        }
                    }
                }
            }
        }
    }
    private var receiverRegistered = false

    // ── NetworkCallback cache (API 31+) ────────────────────────────────────

    @Volatile private var cachedWifiInfo: WifiInfo? = null
    @Volatile private var cachedNetwork: Network? = null
    @Volatile private var callbackRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            FLAG_INCLUDE_LOCATION_INFO else 0
    ) {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val info = caps.transportInfo as? WifiInfo
            Log.d(tag, "NetworkCallback.onCapabilitiesChanged  SSID=${info?.ssid}  BSSID=${info?.bssid}")
            cachedWifiInfo = info
            cachedNetwork  = network
            // If we have a real SSID the link is up — mark as connected
            if (info != null) {
                val ssid = info.ssid?.removeSurrounding("\"") ?: ""
                if (ssid.isNotBlank() && !ssid.equals("<unknown ssid>", ignoreCase = true)) {
                    currentConnectionState = WifiConnectionState.CONNECTED
                }
            }
        }

        override fun onLost(network: Network) {
            if (network == cachedNetwork) {
                Log.d(tag, "NetworkCallback.onLost — clearing cached WifiInfo")
                cachedWifiInfo = null
                cachedNetwork  = null
                currentConnectionState = WifiConnectionState.DISCONNECTED
            }
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    fun start() {
        // NetworkCallback
        if (!callbackRegistered) {
            try {
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build()
                connectivityManager.registerNetworkCallback(request, networkCallback)
                callbackRegistered = true
                Log.d(tag, "NetworkCallback registered")
            } catch (e: Exception) {
                Log.e(tag, "Failed to register NetworkCallback: ${e.message}")
            }
        }

        // Broadcast receiver for supplicant / network-state events
        if (!receiverRegistered) {
            try {
                val filter = IntentFilter().apply {
                    @Suppress("DEPRECATION")
                    addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION)
                    @Suppress("DEPRECATION")
                    addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                }
                context.registerReceiver(stateReceiver, filter)
                receiverRegistered = true
                Log.d(tag, "State BroadcastReceiver registered")
            } catch (e: Exception) {
                Log.e(tag, "Failed to register BroadcastReceiver: ${e.message}")
            }
        }
    }

    fun stop() {
        if (callbackRegistered) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback)
                callbackRegistered = false
                cachedWifiInfo = null
                cachedNetwork  = null
                Log.d(tag, "NetworkCallback unregistered")
            } catch (e: Exception) {
                Log.e(tag, "Failed to unregister NetworkCallback: ${e.message}")
            }
        }
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(stateReceiver)
                receiverRegistered = false
                Log.d(tag, "State BroadcastReceiver unregistered")
            } catch (e: Exception) {
                Log.e(tag, "Failed to unregister BroadcastReceiver: ${e.message}")
            }
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────

    fun checkPrerequisites(): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return "ACCESS_FINE_LOCATION not granted."

        val locationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            locationManager.isLocationEnabled
        else {
            @Suppress("DEPRECATION")
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
        if (!locationEnabled) return "Location Services are disabled."

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES)
            != PackageManager.PERMISSION_GRANTED
        ) return "NEARBY_WIFI_DEVICES not granted (required on Android 13+)."

        return null
    }

    /**
     * Perform one poll.  Always returns [PollResult.Success]; when WiFi is
     * down the record's [WifiPollRecord.wifiState] will be a non-CONNECTED
     * value and metric fields will contain sentinel zeros.
     *
     * [PollResult.Error] is only returned for hard failures such as a missing
     * permission or an unexpected exception.
     */
    suspend fun poll(): PollResult = withContext(Dispatchers.IO) {
        Log.d(tag, "=== poll()  state=$currentConnectionState  API=${Build.VERSION.SDK_INT} ===")

        val connState = currentConnectionState

        // ── Disconnected / transitioning ──────────────────────────────────
        if (!connState.isConnected) {
            Log.d(tag, "  WiFi not connected — returning disconnected record (state=$connState)")
            return@withContext PollResult.Success(
                WifiPollRecord.disconnected(connState)
            )
        }

        // ── Connected — gather full metrics ───────────────────────────────
        Log.d(tag, "  prereq: ${checkPrerequisites() ?: "OK"}")

        val wifiInfo: WifiInfo?
        val linkProps: LinkProperties?

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wifiInfo  = cachedWifiInfo
            linkProps = cachedNetwork?.let { connectivityManager.getLinkProperties(it) }
            Log.d(tag, "  source: NetworkCallback cache")
        } else {
            @Suppress("DEPRECATION")
            wifiInfo  = wifiManager.connectionInfo
            linkProps = getWifiLinkPropertiesLegacy()
            Log.d(tag, "  source: WifiManager.connectionInfo (legacy)")
        }

        Log.d(tag, "  rawSSID:    \"${wifiInfo?.ssid}\"")
        Log.d(tag, "  BSSID:      ${wifiInfo?.bssid}")
        Log.d(tag, "  Frequency:  ${wifiInfo?.frequency} MHz")
        Log.d(tag, "  RSSI:       ${wifiInfo?.rssi} dBm")

        // Guard: if wifiInfo is null even though we think we're connected,
        // the link probably dropped between the state check and here.
        if (wifiInfo == null) {
            currentConnectionState = WifiConnectionState.DISCONNECTED
            Log.w(tag, "  WifiInfo null despite CONNECTED state — returning DISCONNECTED record")
            return@withContext PollResult.Success(
                WifiPollRecord.disconnected(WifiConnectionState.DISCONNECTED)
            )
        }

        try {
            val rawSsid = wifiInfo.ssid ?: ""
            val ssid    = rawSsid.removeSurrounding("\"")
            val bssid   = wifiInfo.bssid ?: "00:00:00:00:00:00"

            // If we still get a redacted SSID after all this, return a
            // disconnected record rather than an error — it just means the
            // system hasn't surfaced the real value yet (e.g. mid-roam).
            if (ssid.equals("<unknown ssid>", ignoreCase = true) || ssid.isBlank()) {
                Log.w(tag, "  SSID redacted mid-poll — treating as RECONNECTING")
                return@withContext PollResult.Success(
                    WifiPollRecord.disconnected(WifiConnectionState.RECONNECTING)
                )
            }

            if (bssid == "02:00:00:00:00:00") {
                Log.w(tag, "  BSSID placeholder — SSID=$ssid is real, continuing")
            }

            // ── IP / Subnet / Gateway ──────────────────────────────────────
            val ipAddress: String
            val subnetPrefix: Int
            val defaultGateway: String

            if (linkProps != null) {
                val linkAddr = linkProps.linkAddresses.firstOrNull { la ->
                    !la.address.isLoopbackAddress &&
                            la.address.hostAddress?.contains('.') == true
                }
                ipAddress    = linkAddr?.address?.hostAddress ?: formatLegacyIp(wifiInfo.ipAddress)
                subnetPrefix = linkAddr?.prefixLength ?: 24
                val routeGw  = linkProps.routes
                    .filter { it.isDefaultRoute && it.gateway != null }
                    .mapNotNull { it.gateway?.hostAddress }
                    .filterNot { it.isBlank() || it == "0.0.0.0" || it == "::" }
                    .firstOrNull()
                defaultGateway = routeGw ?: inferGateway(ipAddress, subnetPrefix)
            } else {
                ipAddress      = formatLegacyIp(wifiInfo.ipAddress)
                subnetPrefix   = 24
                defaultGateway = inferGateway(ipAddress, subnetPrefix)
            }

            // ── Band & Channel ─────────────────────────────────────────────
            val frequencyMhz = wifiInfo.frequency
            val band = when {
                frequencyMhz in 2400..2500 -> "2.4 GHz"
                frequencyMhz in 5150..5850 -> "5 GHz"
                frequencyMhz >= 5925       -> "6 GHz"
                else                       -> "Unknown"
            }
            val channel = frequencyToChannel(frequencyMhz)

            // ── Signal / SNR / Quality ─────────────────────────────────────
            val rssi       = wifiInfo.rssi
            val noiseFloor = estimatedNoiseFloor(band)
            val snr        = rssi - noiseFloor
            val quality    = rssiToQuality(rssi)

            Log.d(tag, "Poll OK — SSID=$ssid  BSSID=$bssid  " +
                    "IP=$ipAddress/$subnetPrefix  GW=$defaultGateway  " +
                    "Band=$band  CH=$channel  RSSI=${rssi}dBm")

            val (pingSuccess, pingRtt) = pingHost(defaultGateway)

            PollResult.Success(
                WifiPollRecord(
                    timestamp            = Date(),
                    wifiState            = WifiConnectionState.CONNECTED,
                    ssid                 = ssid,
                    bssid                = bssid,
                    ipAddress            = ipAddress,
                    subnetPrefix         = subnetPrefix,
                    defaultGateway       = defaultGateway,
                    band                 = band,
                    channel              = channel,
                    rssiDbm              = rssi,
                    noiseFloorDbm        = noiseFloor,
                    snrDb                = snr,
                    signalQualityPercent = quality,
                    pingRttMs            = pingRtt,
                    pingSuccess          = pingSuccess
                )
            )
        } catch (e: SecurityException) {
            PollResult.Error("SecurityException: ${e.message}").also { Log.e(tag, it.message) }
        } catch (e: Exception) {
            PollResult.Error("Poll failed: ${e.message}").also { Log.e(tag, it.message, e) }
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────

    /**
     * Maps Android's fine-grained [SupplicantState] to our
     * [WifiConnectionState] enum.
     */
    private fun supplicantStateToConnectionState(state: SupplicantState?): WifiConnectionState =
        when (state) {
            SupplicantState.COMPLETED             -> WifiConnectionState.CONNECTED
            SupplicantState.AUTHENTICATING        -> WifiConnectionState.AUTHENTICATING
            SupplicantState.ASSOCIATING,
            SupplicantState.ASSOCIATED            -> WifiConnectionState.ASSOCIATING
            SupplicantState.FOUR_WAY_HANDSHAKE,
            SupplicantState.GROUP_HANDSHAKE       -> WifiConnectionState.AUTHENTICATING
            //SupplicantState.OBTAINING_IPADDR      -> WifiConnectionState.OBTAINING_IP
            SupplicantState.SCANNING              -> WifiConnectionState.SCANNING
            SupplicantState.DISCONNECTED,
            SupplicantState.DORMANT,
            SupplicantState.INACTIVE,
            SupplicantState.INTERFACE_DISABLED,
            SupplicantState.UNINITIALIZED,
            SupplicantState.INVALID,
            null                                  -> WifiConnectionState.DISCONNECTED
        }

    private fun getWifiLinkPropertiesLegacy(): LinkProperties? =
        connectivityManager.allNetworks.firstOrNull { net ->
            connectivityManager.getNetworkCapabilities(net)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }?.let { connectivityManager.getLinkProperties(it) }

    private fun pingHost(host: String, timeoutMs: Int = 3000): Pair<Boolean, Double> {
        if (host.isBlank() || host == "0.0.0.0") return Pair(false, -1.0)
        return try {
            val addr  = InetAddress.getByName(host)
            val start = System.nanoTime()
            val ok    = addr.isReachable(timeoutMs)
            val rtt   = (System.nanoTime() - start) / 1_000_000.0
            if (ok) Pair(true, rtt) else tcpPing(host, timeoutMs)
        } catch (e: Exception) {
            Log.w(tag, "ICMP probe failed ($host): ${e.message}")
            tcpPing(host, timeoutMs)
        }
    }

    private fun tcpPing(host: String, timeoutMs: Int): Pair<Boolean, Double> {
        for (port in listOf(80, 443, 53)) {
            try {
                Socket().use { s ->
                    val start = System.nanoTime()
                    s.connect(InetSocketAddress(host, port), timeoutMs)
                    return Pair(true, (System.nanoTime() - start) / 1_000_000.0)
                }
            } catch (_: Exception) {}
        }
        return Pair(false, -1.0)
    }

    private fun formatLegacyIp(ip: Int): String = "%d.%d.%d.%d".format(
        ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff
    )

    private fun inferGateway(ip: String, prefix: Int): String = try {
        val p = ip.split(".").map { it.toInt() }
        if (p.size == 4) "${p[0]}.${p[1]}.${p[2]}.1" else "0.0.0.0"
    } catch (_: Exception) { "0.0.0.0" }

    private fun frequencyToChannel(freq: Int): Int = when {
        freq == 2484       -> 14
        freq in 2412..2484 -> (freq - 2407) / 5
        freq in 5160..5885 -> (freq - 5000) / 5
        freq in 5955..7115 -> (freq - 5950) / 5
        else               -> 0
    }

    private fun estimatedNoiseFloor(band: String): Int = when (band) {
        "2.4 GHz" -> -92
        "5 GHz"   -> -95
        "6 GHz"   -> -96
        else      -> -93
    }

    private fun rssiToQuality(rssi: Int): Int = when {
        rssi >= -50  -> 100
        rssi <= -100 -> 0
        else         -> (2 * (rssi + 100)).coerceIn(0, 100)
    }
}

sealed class PollResult {
    data class Success(val record: WifiPollRecord) : PollResult()
    data class Error(val message: String) : PollResult()
}
