package com.starknetworksolutions.wifimonitor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: WifiMonitorViewModel by viewModels()

    // SAF document-creation launcher for CSV export
    private val createFileLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
            uri?.let { viewModel.exportCsv(this, it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WiFiMonitorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = AppColors.Background
                ) {
                    WifiMonitorScreen(
                        viewModel = viewModel,
                        onExportClick = {
                            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                                .format(System.currentTimeMillis())
                            createFileLauncher.launch("wifi_monitor_$ts.csv")
                        }
                    )
                }
            }
        }
    }
}

// ── Theme ──────────────────────────────────────────────────────────────────

object AppColors {
    val Background   = Color(0xFF0D0F14)
    val Surface      = Color(0xFF151820)
    val Surface2     = Color(0xFF1C1F2E)
    val Border       = Color(0x1F63DCB4)
    val Accent       = Color(0xFF3DFFC0)
    val AccentBlue   = Color(0xFF00B3FF)
    val Warn         = Color(0xFFFFB74D)
    val Danger       = Color(0xFFFF5252)
    val TextPrimary  = Color(0xFFE8F0FE)
    val TextMuted    = Color(0xFF7A8599)
    val TextLabel    = Color(0xFF4AEAAA)
}

@Composable
fun WiFiMonitorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = AppColors.Background,
            surface    = AppColors.Surface,
            primary    = AppColors.Accent
        ),
        content = content
    )
}

val Mono = FontFamily.Monospace

// ── Main Screen ────────────────────────────────────────────────────────────

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun WifiMonitorScreen(
    viewModel: WifiMonitorViewModel,
    onExportClick: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Determine required permissions by API level
    val requiredPerms = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }
    val permissionsState = rememberMultiplePermissionsState(requiredPerms)

    val context = androidx.compose.ui.platform.LocalContext.current

    // Check location services independently of permissions
    val locationManager = remember {
        context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
    }
    val locationServicesOn = remember(permissionsState.allPermissionsGranted) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            //@Suppress("DEPRECATION")
            locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }


    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            // Use Long duration for diagnostic messages so the user can read them
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Long)
            viewModel.clearError()
        }
    }
    LaunchedEffect(state.exportSuccess) {
        if (state.exportSuccess) {
            snackbarHostState.showSnackbar("CSV exported successfully", duration = SnackbarDuration.Short)
            viewModel.clearExportFlag()
        }
    }

    Scaffold(
        containerColor = AppColors.Background,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Top bar
            TopBar(isPolling = state.isPolling, pollCount = state.pollCount, wifiState = state.wifiState)

            // Permission banner
            if (!permissionsState.allPermissionsGranted) {
                PermissionBanner(
                    message = "Location & Nearby Devices permissions required to read SSID / BSSID.",
                    actionLabel = "Grant",
                    onAction = { permissionsState.launchMultiplePermissionRequest() }
                )
            }

            // Location services banner (separate from the permission grant — user
            // may have granted the permission but turned Location off in Settings)
            if (permissionsState.allPermissionsGranted && !locationServicesOn) {
                PermissionBanner(
                    message = "Location Services are off. Android requires Location to be enabled " +
                            "in Settings to reveal SSID and BSSID — open Settings → Location.",
                    actionLabel = "Settings",
                    onAction = {
                        context.startActivity(
                            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                        )
                    }
                )
            }

            // WiFi state banner — only shown after the first poll has completed
            // so we never flash a false "Disconnected" on startup.
            if (state.isPolling && state.pollCount > 0 && !state.wifiState.isConnected) {
                WifiStateBanner(state.wifiState)
            }

            // Scrollable content
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                item { NetworkIdentitySection(state.latest) }
                item { Divider() }
                item { PingSection(state) }
                item { Divider() }
                item { RadioSection(state.latest) }
                item { Divider() }
                item { PollLogSection(state.records) }
            }

            // Controls
            ControlsSection(
                state = state,
                onStart      = { viewModel.startPolling() },
                onStop       = { viewModel.stopPolling() },
                onInterval   = { viewModel.setInterval(it) },
                onContinuous = { viewModel.setContinuousMode(it) },
                onExport     = onExportClick,
                onClear      = { viewModel.clearData() }
            )
        }
    }
}

// ── Top bar ────────────────────────────────────────────────────────────────

@Composable
fun TopBar(isPolling: Boolean, pollCount: Int, wifiState: WifiConnectionState = WifiConnectionState.DISCONNECTED) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Surface)
            .border(
                width = 0.5.dp,
                color = AppColors.Border,
                shape = RoundedCornerShape(0.dp)
            )
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("WIFI MONITOR", fontFamily = Mono, fontSize = 13.sp,
            fontWeight = FontWeight.Bold, color = AppColors.Accent, letterSpacing = 1.5.sp)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Pulsing dot
                val alpha by rememberInfiniteTransition(label = "dot")
                    .animateFloat(
                        initialValue = 1f, targetValue = if (isPolling) 0.3f else 1f,
                        animationSpec = infiniteRepeatable(
                            animation = androidx.compose.animation.core.tween(700),
                            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                        ), label = "alpha"
                    )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (isPolling) AppColors.Accent.copy(alpha = alpha)
                            else AppColors.Danger
                        )
                )
                Text(
                    if (isPolling) "POLLING" else "IDLE",
                    fontFamily = Mono, fontSize = 11.sp, color = AppColors.TextMuted
                )
            }
            Surface(
                color = AppColors.Surface2,
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    "#$pollCount", fontFamily = Mono, fontSize = 11.sp,
                    color = AppColors.TextMuted,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }
}

// ── Network Identity ───────────────────────────────────────────────────────

@Composable
fun NetworkIdentitySection(rec: WifiPollRecord?) {
    Column(modifier = Modifier.padding(16.dp)) {
        SectionLabel("Network Identity")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val ssidDisplay = when {
                rec == null -> "—"
                !rec.wifiState.isConnected -> rec.wifiState.displayLabel
                else -> rec.ssid
            }
            val ssidColor = when {
                rec?.wifiState?.isConnected == true -> AppColors.Accent
                rec?.wifiState != null -> AppColors.Warn
                else -> AppColors.TextMuted
            }
            MetricCard("SSID", ssidDisplay, ssidColor, modifier = Modifier.weight(1f))
            MetricCard("BSSID",
                if (rec?.wifiState?.isConnected == true) rec.bssid else "—",
                AppColors.TextPrimary, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("IP Address", rec?.ipAddress ?: "—", modifier = Modifier.weight(1f))
            MetricCard("Subnet Prefix", rec?.subnetPrefix?.let { "/$it" } ?: "—", AppColors.AccentBlue, modifier = Modifier.weight(1f))
            MetricCard("Default Gateway", rec?.defaultGateway ?: "—", modifier = Modifier.weight(1f))
        }
    }
}

// ── Ping ───────────────────────────────────────────────────────────────────

@Composable
fun PingSection(state: MonitorUiState) {
    val rec = state.latest
    Column(modifier = Modifier.padding(16.dp)) {
        SectionLabel("Gateway Ping (RTT)")
        Surface(
            color = AppColors.Surface,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(0.5.dp, AppColors.Border, RoundedCornerShape(8.dp))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    val rttColor = when {
                        rec == null || !rec.pingSuccess -> AppColors.TextMuted
                        rec.pingRttMs < 100  -> AppColors.Accent
                        rec.pingRttMs < 500 -> AppColors.Warn
                        else               -> AppColors.Danger
                    }
                    Text(
                        text = if (rec?.pingSuccess == true) "%.1f".format(rec.pingRttMs) else "—",
                        fontFamily = Mono, fontSize = 28.sp, fontWeight = FontWeight.SemiBold,
                        color = rttColor
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val minColor = when {
                            state.rttMin == Double.MAX_VALUE -> AppColors.TextMuted
                            state.rttMin < 100  -> AppColors.Accent
                            state.rttMin < 500  -> AppColors.Warn
                            else                -> AppColors.Danger
                        }
                        val maxColor = when {
                            state.rttMax == Double.MIN_VALUE -> AppColors.TextMuted
                            state.rttMax < 100  -> AppColors.Accent
                            state.rttMax < 500  -> AppColors.Warn
                            else                -> AppColors.Danger
                        }
                        StatLabel("min", if (state.rttMin != Double.MAX_VALUE) "%.1f".format(state.rttMin) else "—", minColor)
                        StatLabel("avg", if (state.rttAvg > 0) "%.1f".format(state.rttAvg) else "—", AppColors.TextMuted)
                        StatLabel("max", if (state.rttMax != Double.MIN_VALUE) "%.1f".format(state.rttMax) else "—", maxColor)
                    }
                }
                Spacer(Modifier.height(8.dp))
                SparklineChart(history = state.rttHistory)
            }
        }
    }
}

@Composable
fun StatLabel(label: String, value: String, valueColor: Color) {
    Column(horizontalAlignment = Alignment.End) {
        Text(label, fontFamily = Mono, fontSize = 9.sp, color = AppColors.TextMuted)
        Text(value, fontFamily = Mono, fontSize = 11.sp, color = valueColor)
    }
}

@Composable
fun SparklineChart(history: List<Double>) {
    if (history.size < 2) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(AppColors.Surface2, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("Collecting data…", fontFamily = Mono, fontSize = 9.sp, color = AppColors.TextMuted)
        }
        return
    }

    val max = history.max() * 1.2
    val min = history.min() * 0.7
    val range = (max - min).coerceAtLeast(1.0)

    androidx.compose.foundation.Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
    ) {
        val pts = history.mapIndexed { i, v ->
            val x = (i.toFloat() / (history.size - 1)) * size.width
            val y = size.height - ((v - min) / range * size.height).toFloat()
            androidx.compose.ui.geometry.Offset(x, y)
        }

        // Fill
        val path = androidx.compose.ui.graphics.Path()
        path.moveTo(pts.first().x, pts.first().y)
        pts.drop(1).forEach { path.lineTo(it.x, it.y) }
        path.lineTo(pts.last().x, size.height)
        path.lineTo(0f, size.height)
        path.close()
        drawPath(path, color = AppColors.Accent.copy(alpha = 0.08f))

        // Line
        val linePath = androidx.compose.ui.graphics.Path()
        linePath.moveTo(pts.first().x, pts.first().y)
        pts.drop(1).forEach { linePath.lineTo(it.x, it.y) }
        drawPath(linePath, color = AppColors.Accent.copy(alpha = 0.7f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f))

        // Last dot
        drawCircle(color = AppColors.Accent, radius = 4f, center = pts.last())
    }
}

// ── Radio Properties ───────────────────────────────────────────────────────

@Composable
fun RadioSection(rec: WifiPollRecord?) {
    Column(modifier = Modifier.padding(16.dp)) {
        SectionLabel("Radio Properties")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("Band",    rec?.band    ?: "—", AppColors.AccentBlue, modifier = Modifier.weight(1f))
            MetricCard("Channel", rec?.channel?.toString() ?: "—", modifier = Modifier.weight(1f))
            MetricCard("SNR",
                rec?.snrDb?.let { "${it} dB" } ?: "—",
                rec?.snrDb?.let { if (it > 25) AppColors.Accent else if (it > 15) AppColors.Warn else AppColors.Danger }
                    ?: AppColors.TextPrimary,
                modifier = Modifier.weight(1f)
            )
            MetricCard("Quality",
                rec?.signalQualityPercent?.let { "$it%" } ?: "—",
                rec?.signalQualityPercent?.let { if (it > 70) AppColors.Accent else if (it > 40) AppColors.Warn else AppColors.Danger }
                    ?: AppColors.TextPrimary,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(8.dp))

        // Signal row with bars
        Surface(
            color = AppColors.Surface,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(0.5.dp, AppColors.Border, RoundedCornerShape(8.dp))
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column {
                    Text("RSSI", fontFamily = Mono, fontSize = 9.sp, color = AppColors.TextMuted)
                    val rssiColor = rec?.rssiDbm?.let {
                        if (it > -60) AppColors.Accent else if (it > -75) AppColors.Warn else AppColors.Danger
                    } ?: AppColors.TextPrimary
                    Text(
                        rec?.rssiDbm?.let { "$it dBm" } ?: "—",
                        fontFamily = Mono, fontSize = 18.sp, fontWeight = FontWeight.Medium,
                        color = rssiColor
                    )
                }

                // Signal bars
                SignalBars(quality = rec?.signalQualityPercent ?: 0, modifier = Modifier.weight(1f))

                Column(horizontalAlignment = Alignment.End) {
                    Text("Noise Floor", fontFamily = Mono, fontSize = 9.sp, color = AppColors.TextMuted)
                    Text(
                        rec?.noiseFloorDbm?.let { "$it dBm" } ?: "—",
                        fontFamily = Mono, fontSize = 12.sp, color = AppColors.TextMuted
                    )
                }
            }
        }
    }
}

@Composable
fun SignalBars(quality: Int, modifier: Modifier = Modifier) {
    val activeBars = when {
        quality > 80 -> 5
        quality > 60 -> 4
        quality > 40 -> 3
        quality > 20 -> 2
        quality > 0  -> 1
        else         -> 0
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.Center
    ) {
        val heights = listOf(4.dp, 7.dp, 11.dp, 15.dp, 20.dp)
        heights.forEachIndexed { i, h ->
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .height(h)
                    .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                    .background(if (i < activeBars) AppColors.Accent else AppColors.TextMuted.copy(alpha = 0.3f))
            )
            if (i < heights.size - 1) Spacer(Modifier.width(3.dp))
        }
    }
}

// ── Poll Log ───────────────────────────────────────────────────────────────

@Composable
fun PollLogSection(records: List<WifiPollRecord>) {
    val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionLabel("Poll Log")
            Text("${records.size} entries", fontFamily = Mono, fontSize = 10.sp, color = AppColors.TextMuted)
        }

        Surface(
            color = AppColors.Surface,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .border(0.5.dp, AppColors.Border, RoundedCornerShape(8.dp))
        ) {
            val listState = rememberLazyListState()

            LaunchedEffect(records.size) {
                if (records.isNotEmpty()) listState.animateScrollToItem(records.size - 1)
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.padding(8.dp),
                contentPadding = PaddingValues(vertical = 2.dp)
            ) {
                item { LogHeaderRow() }
                items(records, key = { it.timestamp.time }) { rec ->
                    LogDataRow(rec, timeFmt)
                }
            }
        }
    }
}

@Composable
fun LogHeaderRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        listOf("TIME" to 0.12f, "SSID" to 0.22f, "IP → GW" to 0.30f,
            "BAND/CH" to 0.18f, "RTT" to 0.10f, "QUAL" to 0.08f).forEach { (label, w) ->
            Text(label, fontFamily = Mono, fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                color = AppColors.TextLabel, modifier = Modifier.weight(w),
                maxLines = 1, overflow = TextOverflow.Clip)
        }
    }
}

@Composable
fun LogDataRow(rec: WifiPollRecord, timeFmt: SimpleDateFormat) {
    val rttColor = when {
        !rec.pingSuccess -> AppColors.Danger
        rec.pingRttMs < 100  -> AppColors.Accent
        rec.pingRttMs < 500 -> AppColors.Warn
        else               -> AppColors.Danger
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(timeFmt.format(rec.timestamp), fontFamily = Mono, fontSize = 9.sp, color = AppColors.TextMuted, modifier = Modifier.weight(0.12f), maxLines = 1)
        val logSsidText = if (rec.wifiState.isConnected) rec.ssid else rec.wifiState.displayLabel
        val logSsidColor = if (rec.wifiState.isConnected) AppColors.TextPrimary else AppColors.Warn
        Text(logSsidText, fontFamily = Mono, fontSize = 9.sp, color = logSsidColor, modifier = Modifier.weight(0.22f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${rec.ipAddress}→${rec.defaultGateway}", fontFamily = Mono, fontSize = 9.sp, color = AppColors.TextMuted, modifier = Modifier.weight(0.30f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${rec.band}/${rec.channel}", fontFamily = Mono, fontSize = 9.sp, color = AppColors.TextMuted, modifier = Modifier.weight(0.18f), maxLines = 1)
        Text(if (rec.pingSuccess) "%.1fms".format(rec.pingRttMs) else "T/O", fontFamily = Mono, fontSize = 9.sp, color = rttColor, modifier = Modifier.weight(0.10f), maxLines = 1)
        Text("${rec.signalQualityPercent}%", fontFamily = Mono, fontSize = 9.sp, color = AppColors.TextPrimary, modifier = Modifier.weight(0.08f), maxLines = 1)
    }
}

// ── Controls ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlsSection(
    state: MonitorUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onInterval: (Int) -> Unit,
    onContinuous: (Boolean) -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit
) {
    val intervalOptions = listOf(1 to "1s", 2 to "2s", 5 to "5s", 10 to "10s", 30 to "30s")
    var dropdownExpanded by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    onClear()
                }) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text("Cancel")
                }
            },
            title = { Text("Clear data?") },
            text = { Text("This will remove all collected WiFi data.") }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Surface)
            .border(0.5.dp, AppColors.Border, RoundedCornerShape(0.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ── Continuous mode toggle ─────────────────────────────────────────
        Surface(
            color = if (state.continuousMode)
                AppColors.Accent.copy(alpha = 0.07f) else AppColors.Surface2,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    0.5.dp,
                    if (state.continuousMode) AppColors.Accent.copy(alpha = 0.4f)
                    else AppColors.Border,
                    RoundedCornerShape(8.dp)
                )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Continuous mode",
                        fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        color = if (state.continuousMode) AppColors.Accent else AppColors.TextPrimary
                    )
                    Text(
                        if (state.continuousMode)
                            "Next poll starts immediately after each completes"
                        else
                            "Next poll waits for the interval below",
                        fontFamily = Mono, fontSize = 9.sp,
                        color = AppColors.TextMuted
                    )
                }
                Switch(
                    checked = state.continuousMode,
                    onCheckedChange = { onContinuous(it) },
                    enabled = !state.isPolling,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor   = Color(0xFF050E0A),
                        checkedTrackColor   = AppColors.Accent,
                        uncheckedThumbColor = AppColors.TextMuted,
                        uncheckedTrackColor = AppColors.Surface2,
                        disabledCheckedTrackColor   = AppColors.Accent.copy(alpha = 0.4f),
                        disabledUncheckedTrackColor = AppColors.Surface2
                    )
                )
            }
        }

        // ── Interval picker (greyed out in continuous mode) ────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Poll interval:",
                fontFamily = Mono, fontSize = 10.sp,
                color = if (state.continuousMode) AppColors.TextMuted.copy(alpha = 0.4f)
                else AppColors.TextMuted
            )
            ExposedDropdownMenuBox(
                expanded = dropdownExpanded && !state.continuousMode,
                onExpandedChange = { if (!state.continuousMode) dropdownExpanded = it }
            ) {
                Surface(
                    color = AppColors.Surface2,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .menuAnchor()
                        .border(0.5.dp, AppColors.Border, RoundedCornerShape(6.dp))
                        .then(
                            if (state.continuousMode)
                                Modifier.alpha(0.35f) else Modifier
                        )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            intervalOptions.first { it.first == state.intervalSeconds }.second,
                            fontFamily = Mono, fontSize = 11.sp,
                            color = if (state.continuousMode) AppColors.TextMuted.copy(alpha = 0.4f)
                            else AppColors.TextPrimary
                        )
                        ExposedDropdownMenuDefaults.TrailingIcon(
                            expanded = dropdownExpanded && !state.continuousMode
                        )
                    }
                }
                ExposedDropdownMenu(
                    expanded = dropdownExpanded && !state.continuousMode,
                    onDismissRequest = { dropdownExpanded = false },
                    modifier = Modifier.background(AppColors.Surface2)
                ) {
                    intervalOptions.forEach { (secs, label) ->
                        DropdownMenuItem(
                            text = {
                                Text(label, fontFamily = Mono, fontSize = 11.sp,
                                    color = AppColors.TextPrimary
                                )
                            },
                            onClick = { onInterval(secs); dropdownExpanded = false }
                        )
                    }
                }
            }
        }

        // Start / Stop row
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onStart,
                enabled = !state.isPolling,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.Accent,
                    contentColor   = Color(0xFF050E0A),
                    disabledContainerColor = AppColors.Accent.copy(alpha = 0.3f),
                    disabledContentColor   = Color(0xFF050E0A).copy(alpha = 0.3f)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("▶ START", fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            Button(
                onClick = onStop,
                enabled = state.isPolling,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.Danger.copy(alpha = 0.18f),
                    contentColor   = AppColors.Danger,
                    disabledContainerColor = AppColors.Danger.copy(alpha = 0.06f),
                    disabledContentColor   = AppColors.Danger.copy(alpha = 0.3f)
                ),
                border = androidx.compose.foundation.BorderStroke(0.5.dp, AppColors.Danger.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("■ STOP", fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }

        // Export
        Button(
            onClick = onExport,
            enabled = state.records.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = AppColors.AccentBlue.copy(alpha = 0.15f),
                contentColor   = AppColors.AccentBlue,
                disabledContainerColor = AppColors.AccentBlue.copy(alpha = 0.05f),
                disabledContentColor   = AppColors.AccentBlue.copy(alpha = 0.3f)
            ),
            border = androidx.compose.foundation.BorderStroke(0.5.dp, AppColors.AccentBlue.copy(alpha = 0.4f)),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("↓ EXPORT CSV  (${state.records.size} records)",
                fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }

        Button(
            onClick = { showConfirm = true },
            enabled = state.records.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = AppColors.Warn.copy(alpha = 0.15f),
                contentColor   = AppColors.Warn,
                disabledContainerColor = AppColors.Warn.copy(alpha = 0.05f),
                disabledContentColor   = AppColors.Warn.copy(alpha = 0.3f)
            ),
            border = BorderStroke(0.5.dp, AppColors.Warn.copy(alpha = 0.4f)),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                "⟲ CLEAR DATA",
                fontFamily = Mono,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }
    }
}

// ── Shared components ──────────────────────────────────────────────────────

@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(), fontFamily = Mono, fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold, color = AppColors.TextLabel,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(bottom = 10.dp)
    )
}

@Composable
fun MetricCard(
    label: String,
    value: String,
    valueColor: Color = AppColors.TextPrimary,
    modifier: Modifier = Modifier
) {
    Surface(
        color = AppColors.Surface,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.border(0.5.dp, AppColors.Border, RoundedCornerShape(8.dp))
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(label.uppercase(), fontFamily = Mono, fontSize = 9.sp,
                color = AppColors.TextMuted, letterSpacing = 1.sp)
            Spacer(Modifier.height(4.dp))
            Text(value, fontFamily = Mono, fontSize = 13.sp,
                fontWeight = FontWeight.Medium, color = valueColor,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun Divider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(AppColors.Border)
    )
}


// ── WiFi State Banner ──────────────────────────────────────────────────────

@Composable
fun WifiStateBanner(state: WifiConnectionState) {
    val (bgColor, borderColor, dotColor) = when (state) {
        WifiConnectionState.DISCONNECTED -> Triple(
            Color(0xFF1A0A0A), AppColors.Danger.copy(alpha = 0.4f), AppColors.Danger
        )
        WifiConnectionState.SCANNING,
        WifiConnectionState.RECONNECTING -> Triple(
            Color(0xFF1A140A), AppColors.Warn.copy(alpha = 0.4f), AppColors.Warn
        )
        else -> Triple(
            Color(0xFF0A1A14), AppColors.Accent.copy(alpha = 0.3f), AppColors.Accent
        )
    }
    val textColor = when (state) {
        WifiConnectionState.DISCONNECTED -> AppColors.Danger
        WifiConnectionState.SCANNING,
        WifiConnectionState.RECONNECTING -> AppColors.Warn
        else -> AppColors.Accent
    }

    // Pulsing dot to show activity
    val dotAlpha by rememberInfiniteTransition(label = "wifiBanner")
        .animateFloat(
            initialValue = 1f, targetValue = 0.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(700),
                repeatMode = RepeatMode.Reverse
            ), label = "dot"
        )

    Surface(
        color = bgColor,
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, borderColor, RoundedCornerShape(0.dp))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(dotColor.copy(alpha = dotAlpha))
            )
            Column {
                Text(
                    state.displayLabel.uppercase(),
                    fontFamily = Mono, fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold, color = textColor,
                    letterSpacing = 1.sp
                )
                Text(
                    when (state) {
                        WifiConnectionState.DISCONNECTED -> "No WiFi association — polling continues"
                        WifiConnectionState.SCANNING -> "Searching for networks"
                        WifiConnectionState.AUTHENTICATING -> "Completing WPA/802.1X handshake"
                        WifiConnectionState.ASSOCIATING -> "Completing 802.11 association"
                        WifiConnectionState.OBTAINING_IP -> "Waiting for DHCP lease"
                        WifiConnectionState.RECONNECTING -> "Re-establishing connection"
                        WifiConnectionState.CONNECTED -> ""
                    },
                    fontFamily = Mono, fontSize = 9.sp, color = AppColors.TextMuted
                )
            }
        }
    }
}

@Composable
fun PermissionBanner(message: String, actionLabel: String = "Grant", onAction: () -> Unit) {
    Surface(
        color = Color(0xFF2A1A0A),
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, AppColors.Warn.copy(alpha = 0.4f), RoundedCornerShape(0.dp))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Action required", fontFamily = Mono, fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold, color = AppColors.Warn
                )
                Text(message,
                    fontFamily = Mono, fontSize = 10.sp, color = AppColors.TextMuted
                )
            }
            TextButton(onClick = onAction) {
                Text(actionLabel, fontFamily = Mono, fontSize = 11.sp, color = AppColors.Warn)
            }
        }
    }
}
