package com.example.wifimonitor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TransportInfo
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
 * ## The Android 12+ redaction problem
 *
 * On API 31+, calling [ConnectivityManager.getNetworkCapabilities] directly
 * (outside a callback) returns a [WifiInfo] whose SSID and BSSID are always
 * redacted to `<unknown ssid>` / `02:00:00:00:00:00`, regardless of granted
 * permissions.  The only way to receive an **unredacted** [WifiInfo] on API 31+
 * is to read `transportInfo` inside a [ConnectivityManager.NetworkCallback]
 * — Android populates the field differently in that code path.
 *
 * This class therefore registers a persistent [NetworkCallback] on [start] and
 * caches the last good [WifiInfo] it receives.  [poll] reads from that cache
 * rather than calling [getNetworkCapabilities] directly.
 */
class WifiDataCollector(private val context: Context) {

    private val tag = "WifiDataCollector"

    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val locationManager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // ── NetworkCallback cache (API 31+) ────────────────────────────────────

    @Volatile private var cachedWifiInfo: WifiInfo? = null
    @Volatile private var cachedNetwork: Network? = null
    @Volatile private var callbackRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback(
        // FLAG_INCLUDE_LOCATION_INFO is the key flag — without it the
        // WifiInfo inside the callback is still redacted on API 31+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            FLAG_INCLUDE_LOCATION_INFO else 0
    ) {
        override fun onCapabilitiesChanged(
            network: Network,
            caps: NetworkCapabilities
        ) {
            val info = caps.transportInfo as? WifiInfo
            Log.d(tag, "NetworkCallback.onCapabilitiesChanged  SSID=${info?.ssid}  BSSID=${info?.bssid}")
            cachedWifiInfo = info
            cachedNetwork  = network
        }

        override fun onLost(network: Network) {
            if (network == cachedNetwork) {
                cachedWifiInfo = null
                cachedNetwork  = null
            }
        }
    }

    /** Register the callback.  Call once when the ViewModel starts polling. */
    fun start() {
        if (callbackRegistered) return
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

    /** Unregister the callback.  Call when the ViewModel is cleared. */
    fun stop() {
        if (!callbackRegistered) return
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

    suspend fun poll(): PollResult = withContext(Dispatchers.IO) {
        Log.d(tag, "=== poll()  API=${Build.VERSION.SDK_INT}  callbackRegistered=$callbackRegistered ===")
        Log.d(tag, "  prereq: ${checkPrerequisites() ?: "OK"}")

        val wifiInfo: WifiInfo?
        val linkProps: LinkProperties?

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // API 31+: use the callback-cached WifiInfo which carries
            // FLAG_INCLUDE_LOCATION_INFO and is therefore unredacted.
            wifiInfo  = cachedWifiInfo
            linkProps = cachedNetwork?.let { connectivityManager.getLinkProperties(it) }
            Log.d(tag, "  source: NetworkCallback cache")
        } else {
            // API 26–30: deprecated direct call is correct and unredacted.
            @Suppress("DEPRECATION")
            wifiInfo  = wifiManager.connectionInfo
            linkProps = getWifiLinkPropertiesLegacy()
            Log.d(tag, "  source: WifiManager.connectionInfo (legacy)")
        }

        Log.d(tag, "  rawSSID:   \"${wifiInfo?.ssid}\"")
        Log.d(tag, "  BSSID:     ${wifiInfo?.bssid}")
        Log.d(tag, "  Frequency: ${wifiInfo?.frequency} MHz")
        Log.d(tag, "  RSSI:      ${wifiInfo?.rssi} dBm")
        Log.d(tag, "  LinkProps: ${if (linkProps != null) "ok" else "null"}")
        Log.d(tag, "  FineLoc:   ${ContextCompat.checkSelfPermission(context,
            Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED}")
        Log.d(tag, "  LocOn:     ${locationManager.isLocationEnabled}")

        if (wifiInfo == null) {
            val msg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !callbackRegistered)
                "NetworkCallback not registered — call start() before polling."
            else
                "WifiInfo is null — not connected to WiFi."
            Log.w(tag, msg)
            return@withContext PollResult.Error(msg)
        }

        try {
            val rawSsid = wifiInfo.ssid ?: ""
            val ssid    = rawSsid.removeSurrounding("\"")
            val bssid   = wifiInfo.bssid ?: "00:00:00:00:00:00"

            if (ssid.equals("<unknown ssid>", ignoreCase = true) || ssid.isBlank()) {
                val msg = "SSID still redacted even via NetworkCallback. " +
                        "raw=\"${wifiInfo.ssid}\"  API=${Build.VERSION.SDK_INT}  " +
                        "FineLoc=${ContextCompat.checkSelfPermission(context,
                            Manifest.permission.ACCESS_FINE_LOCATION) ==
                                PackageManager.PERMISSION_GRANTED}  " +
                        "LocOn=${locationManager.isLocationEnabled}. " +
                        "Try toggling WiFi off and back on, or check that " +
                        "Location permission is set to 'While using the app'."
                Log.w(tag, msg)
                return@withContext PollResult.Error(msg)
            }

            if (bssid == "02:00:00:00:00:00") {
                Log.w(tag, "BSSID is MAC privacy placeholder — SSID=$ssid is real, continuing.")
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
                val gw = linkProps.dhcpServerAddress
                    ?: linkProps.routes.firstOrNull { it.isDefaultRoute && it.gateway != null }?.gateway
                defaultGateway = gw?.hostAddress ?: inferGateway(ipAddress, subnetPrefix)
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

    private fun getWifiLinkPropertiesLegacy(): LinkProperties? {
        return connectivityManager.allNetworks.firstOrNull { net ->
            connectivityManager.getNetworkCapabilities(net)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }?.let { connectivityManager.getLinkProperties(it) }
    }

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


/**
 * Collects a single [WifiPollRecord] snapshot from Android's WiFi and
 * connectivity APIs, then pings the default gateway.
 *
 * ## Why SSID / BSSID can come back redacted
 *
 * Android deliberately returns `<unknown ssid>` and `02:00:00:00:00:00` when
 * any of the following conditions are not met:
 *
 *   1. `ACCESS_FINE_LOCATION` is granted **and** Location Services is enabled
 *      in system Settings (required on all API levels ≥ 26).
 *   2. On API 31+ (Android 12), `WifiInfo` must be obtained via
 *      `NetworkCapabilities.getTransportInfo()`, not the deprecated
 *      `WifiManager.getConnectionInfo()`.
 *   3. On API 33+ (Android 13), `NEARBY_WIFI_DEVICES` must also be granted
 *      — and the manifest declaration must NOT carry the
 *      `usesPermissionFlags="neverForLocation"` flag, because that flag
 *      explicitly breaks the location linkage needed for SSID/BSSID access.
 *
 * [checkPrerequisites] exposes a human-readable diagnosis so the UI can guide
 * the user before starting a poll.
 */
class WifiDataCollector(private val context: Context) {

    private val tag = "WifiDataCollector"

    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val locationManager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Returns a non-null human-readable string describing the first unmet
     * prerequisite, or null if everything looks good.
     */
    fun checkPrerequisites(): String? {
        // 1. ACCESS_FINE_LOCATION permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return "Fine Location permission not granted. " +
                    "Go to Settings → Apps → WiFi Monitor → Permissions and enable Location."
        }

        // 2. Location Services must be switched on in system settings
        val locationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
        if (!locationEnabled) {
            return "Location Services are disabled. " +
                    "Open Settings → Location and turn Location on. " +
                    "Android requires this to reveal SSID and BSSID."
        }

        // 3. Android 13+: NEARBY_WIFI_DEVICES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.NEARBY_WIFI_DEVICES
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return "Nearby Devices permission not granted (required on Android 13+). " +
                        "Go to Settings → Apps → WiFi Monitor → Permissions and enable Nearby devices."
            }
        }

        return null // all good
    }

    /**
     * Perform one full poll.
     *
     * We always attempt the poll regardless of the prerequisite check result —
     * some OEM builds (Samsung, Xiaomi, etc.) report location state differently
     * from AOSP, so a failed prereq check can be a false positive.  Instead we
     * inspect the actual values Android returns and detect redaction sentinels
     * after the fact, logging every raw field so the issue is visible in logcat
     * (filter tag "WifiDataCollector").
     */
    suspend fun poll(): PollResult = withContext(Dispatchers.IO) {
        // Log prereq state for diagnostics but never abort here
        val prereqDiag = checkPrerequisites()
        Log.d(tag, "=== WiFi Poll start  API=${Build.VERSION.SDK_INT} ===")
        Log.d(tag, "  prereqCheck: ${prereqDiag ?: "OK"}")

        try {
            val wifiInfo: WifiInfo? = getWifiInfo()
            val linkProps: LinkProperties? = getWifiLinkProperties()

            val fineLoc = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val locOn = locationManager.isLocationEnabled

            Log.d(tag, "  WifiInfo:   ${if (wifiInfo != null) "obtained" else "NULL"}")
            Log.d(tag, "  rawSSID:    \"${wifiInfo?.ssid}\"")
            Log.d(tag, "  BSSID:      ${wifiInfo?.bssid}")
            Log.d(tag, "  Frequency:  ${wifiInfo?.frequency} MHz")
            Log.d(tag, "  RSSI:       ${wifiInfo?.rssi} dBm")
            Log.d(tag, "  LinkProps:  ${if (linkProps != null) "obtained" else "NULL"}")
            Log.d(tag, "  FineLoc:    $fineLoc")
            Log.d(tag, "  LocEnabled: $locOn")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val nearby = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.NEARBY_WIFI_DEVICES
                ) == PackageManager.PERMISSION_GRANTED
                Log.d(tag, "  NearbyWifi: $nearby")
            }

            if (wifiInfo == null) {
                val msg = "WifiInfo is null — not connected to WiFi, or ConnectivityManager " +
                        "could not find an active WiFi network."
                Log.w(tag, msg)
                return@withContext PollResult.Error(msg)
            }

            // ── SSID / BSSID ───────────────────────────────────────────────
            val rawSsid = wifiInfo.ssid ?: ""
            val ssid    = rawSsid.removeSurrounding("\"")
            val bssid   = wifiInfo.bssid ?: "00:00:00:00:00:00"

            // Detect redaction sentinels and report exactly what Android returned
            if (ssid.equals("<unknown ssid>", ignoreCase = true) || ssid.isBlank()) {
                val msg = "SSID redacted — raw=\"${wifiInfo.ssid}\"  " +
                        "API=${Build.VERSION.SDK_INT}  " +
                        "ACCESS_FINE_LOCATION=$fineLoc  " +
                        "LocationEnabled=$locOn. " +
                        "If both are true, force-stop the app and relaunch, or check " +
                        "Settings → Location → App permissions → WiFi Monitor is set to " +
                        "\"While using the app\" or \"Always\"."
                Log.w(tag, msg)
                return@withContext PollResult.Error(msg)
            }

            if (bssid == "02:00:00:00:00:00") {
                // SSID resolved but BSSID is still the MAC privacy placeholder.
                // Log a warning but continue — the rest of the data is valid.
                Log.w(tag, "BSSID is the MAC privacy placeholder (02:00:00:00:00:00). " +
                        "SSID=$ssid is real. Recording poll with redacted BSSID.")
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
                ipAddress     = linkAddr?.address?.hostAddress ?: formatLegacyIp(wifiInfo.ipAddress)
                subnetPrefix  = linkAddr?.prefixLength ?: 24

                val gw = linkProps.dhcpServerAddress
                    ?: linkProps.routes
                        .firstOrNull { it.isDefaultRoute && it.gateway != null }
                        ?.gateway
                defaultGateway = gw?.hostAddress ?: inferGateway(ipAddress, subnetPrefix)
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

            Log.d(tag, "Poll OK – SSID=$ssid  BSSID=$bssid  " +
                    "IP=$ipAddress/$subnetPrefix  GW=$defaultGateway  " +
                    "Band=$band  CH=$channel  RSSI=${rssi}dBm  SNR=${snr}dB")

            // ── Ping ───────────────────────────────────────────────────────
            val (pingSuccess, pingRtt) = pingHost(defaultGateway)

            PollResult.Success(
                WifiPollRecord(
                    timestamp            = Date(),
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
            val msg = "Permission denied: ${e.message}"
            Log.e(tag, msg)
            PollResult.Error(msg)
        } catch (e: Exception) {
            val msg = "Poll failed: ${e.message}"
            Log.e(tag, msg, e)
            PollResult.Error(msg)
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────

    /**
     * Returns WifiInfo using the correct path for the running API level.
     *
     * On API 31+ the only way to get an un-redacted WifiInfo is via
     * [NetworkCapabilities.getTransportInfo].  The deprecated
     * [WifiManager.getConnectionInfo] path always returns redacted values
     * on API 31+ regardless of permissions.
     */
    private fun getWifiInfo(): WifiInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // API 31+: must use NetworkCapabilities path
            val network = getWifiNetwork() ?: run {
                Log.w(tag, "No active WiFi network found via ConnectivityManager")
                return null
            }
            val caps = connectivityManager.getNetworkCapabilities(network)
            (caps?.transportInfo as? WifiInfo).also {
                if (it == null) Log.w(tag, "transportInfo is null or not WifiInfo")
            }
        } else {
            // API 26-30: deprecated path is still the correct one
            @Suppress("DEPRECATION")
            wifiManager.connectionInfo
        }
    }

    /** Returns the active WiFi [Network], preferring [ConnectivityManager.activeNetwork]. */
    private fun getWifiNetwork(): Network? {
        return connectivityManager.activeNetwork?.let { net ->
            val caps = connectivityManager.getNetworkCapabilities(net)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) net else null
        } ?: connectivityManager.allNetworks.firstOrNull { net ->
            connectivityManager.getNetworkCapabilities(net)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    private fun getWifiLinkProperties(): LinkProperties? {
        val network = getWifiNetwork() ?: return null
        return connectivityManager.getLinkProperties(network)
    }

    /**
     * Ping via [InetAddress.isReachable] (attempts ICMP on same-subnet hosts)
     * with a TCP SYN fallback for hosts that block ICMP.
     */
    private fun pingHost(host: String, timeoutMs: Int = 3000): Pair<Boolean, Double> {
        if (host.isBlank() || host == "0.0.0.0") return Pair(false, -1.0)
        return try {
            val addr  = InetAddress.getByName(host)
            val start = System.nanoTime()
            val ok    = addr.isReachable(timeoutMs)
            val rtt   = (System.nanoTime() - start) / 1_000_000.0
            if (ok) Pair(true, rtt) else tcpPing(host, timeoutMs)
        } catch (e: Exception) {
            Log.w(tag, "ICMP probe failed ($host): ${e.message}, trying TCP")
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

    /** Android stores IPv4 as a little-endian int; convert to dotted-decimal. */
    private fun formatLegacyIp(ip: Int): String = "%d.%d.%d.%d".format(
        ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff
    )

    private fun inferGateway(ip: String, prefix: Int): String {
        return try {
            val p = ip.split(".").map { it.toInt() }
            if (p.size == 4) "${p[0]}.${p[1]}.${p[2]}.1" else "0.0.0.0"
        } catch (_: Exception) { "0.0.0.0" }
    }

    private fun frequencyToChannel(freq: Int): Int = when {
        freq == 2484          -> 14
        freq in 2412..2484    -> (freq - 2407) / 5
        freq in 5160..5885    -> (freq - 5000) / 5
        freq in 5955..7115    -> (freq - 5950) / 5   // 6 GHz
        else                  -> 0
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

/** Sealed result type so callers can distinguish success from a diagnosed error. */
sealed class PollResult {
    data class Success(val record: WifiPollRecord) : PollResult()
    data class Error(val message: String) : PollResult()
}
