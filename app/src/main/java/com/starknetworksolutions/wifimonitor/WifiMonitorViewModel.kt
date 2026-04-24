package com.starknetworksolutions.wifimonitor

import android.app.Application
import android.content.Context
import android.os.PowerManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.io.OutputStreamWriter

/**
 * When [continuousMode] is true the polling loop fires the next poll
 * immediately after the previous one completes, with no deliberate delay.
 * When false it waits [intervalSeconds] between polls.
 */
data class MonitorUiState(
    val isPolling: Boolean = false,
    val pollCount: Int = 0,
    val latest: WifiPollRecord? = null,
    val records: List<WifiPollRecord> = emptyList(),

    /** Current WiFi connection state (mirrors the latest poll record's state). */
    val wifiState: WifiConnectionState = WifiConnectionState.DISCONNECTED,

    // Running stats for RTT (connected polls only)
    val rttMin: Double = Double.MAX_VALUE,
    val rttMax: Double = Double.MIN_VALUE,
    val rttAvg: Double = 0.0,

    // Sparkline history (last 60 successful pings)
    val rttHistory: List<Double> = emptyList(),

    val intervalSeconds: Int = 1,
    val continuousMode: Boolean = false,
    val errorMessage: String? = null,
    val exportSuccess: Boolean = false
)

class WifiMonitorViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        /** Polling stops automatically once this many records have been collected. */
        const val MAX_RECORDS = 10_000
    }

    private val collector = WifiDataCollector(application)
    private var pollJob: Job? = null

    // CPU wake lock — keeps the processor running while polling even after
    // the screen is turned off by the user pressing the sleep button.
    // The screen wake lock in MainActivity only prevents *automatic* timeout;
    // this ensures polls continue when the user manually sleeps the device.
    private val cpuWakeLock: PowerManager.WakeLock by lazy {
        val pm = application.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "WiFiMonitor:PollWakeLock"
        ).apply { setReferenceCounted(false) }
    }

    private val _uiState = MutableStateFlow(MonitorUiState())
    val uiState: StateFlow<MonitorUiState> = _uiState.asStateFlow()

    init {
        collector.start()
    }

    // ── Polling control ────────────────────────────────────────────────────

    fun checkPrerequisites(): String? = collector.checkPrerequisites()

    fun startPolling() {
        if (pollJob?.isActive == true) return
         // Acquire CPU wake lock so polls continue even when the screen is off
        if (!cpuWakeLock.isHeld) cpuWakeLock.acquire(/* timeout: */ 24 * 60 * 60 * 1000L) // 24 h safety timeout
        _uiState.update { it.copy(isPolling = true, errorMessage = null) }

        pollJob = viewModelScope.launch {
            // Yield once so any pending NetworkCallback.onCapabilitiesChanged
            // that is already queued can deliver before we check state.
            // Then re-sync synchronously in case the callback still hasn't fired.
            yield()
            collector.syncInitialState()

            while (true) {
                                // Stop automatically when the record cap is reached
                if (_uiState.value.records.size >= MAX_RECORDS) {
                    stopPolling()
                    _uiState.update {
                        it.copy(errorMessage = "Polling stopped: reached the $MAX_RECORDS record limit.")
                    }
                    return@launch
                }

                when (val result = collector.poll()) {
                    is PollResult.Success -> {
                        val record = result.record
                        _uiState.update { state ->
                            val newRecords = state.records + record

                            // Only update RTT stats for connected polls with a
                            // successful ping — disconnected records have no RTT.
                            val successPings = if (record.wifiState.isConnected && record.pingSuccess)
                                newRecords.filter { it.wifiState.isConnected && it.pingSuccess }
                                    .map { it.pingRttMs }
                            else null

                            val newHistory = if (record.wifiState.isConnected && record.pingSuccess)
                                (state.rttHistory + record.pingRttMs).takeLast(60)
                            else state.rttHistory

                            val newMin = if (successPings != null) minOf(state.rttMin, record.pingRttMs) else state.rttMin
                            val newMax = if (successPings != null) maxOf(state.rttMax, record.pingRttMs) else state.rttMax
                            val newAvg = if (!successPings.isNullOrEmpty()) successPings.average() else state.rttAvg

                            state.copy(
                                pollCount    = state.pollCount + 1,
                                latest       = record,
                                records      = newRecords,
                                wifiState    = record.wifiState,
                                rttMin       = newMin,
                                rttMax       = newMax,
                                rttAvg       = newAvg,
                                rttHistory   = newHistory,
                                errorMessage = null
                            )
                        }
                    }
                    is PollResult.Error -> {
                        // Only hard errors (permission denied, unexpected exception)
                        // reach here now — disconnection is a PollResult.Success.
                        _uiState.update { it.copy(errorMessage = result.message) }
                    }
                }

                val state = _uiState.value
                if (!state.continuousMode) {
                    delay(state.intervalSeconds * 1000L)
                }
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        if (cpuWakeLock.isHeld) cpuWakeLock.release()
        _uiState.update { it.copy(isPolling = false) }
    }

    fun setInterval(seconds: Int) {
        _uiState.update { it.copy(intervalSeconds = seconds) }
    }

    fun setContinuousMode(enabled: Boolean) {
        _uiState.update { it.copy(continuousMode = enabled) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearExportFlag() {
        _uiState.update { it.copy(exportSuccess = false) }
    }

    fun clearData() {
        _uiState.update {
            it.copy(
                records    = emptyList(),
                latest     = null,
                pollCount  = 0,
                rttHistory = emptyList(),
                rttMin     = Double.MAX_VALUE,
                rttMax     = Double.MIN_VALUE,
                rttAvg     = 0.0,
                errorMessage  = null,
                exportSuccess = false
            )
        }
    }

    // ── CSV export ─────────────────────────────────────────────────────────

    fun exportCsv(context: Context, uri: Uri) {
        val records = _uiState.value.records
        if (records.isEmpty()) return
        viewModelScope.launch {
            try {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    OutputStreamWriter(os, Charsets.UTF_8).use { w ->
                        w.write(WifiPollRecord.CSV_HEADER + "\n")
                        records.forEach { w.write(it.toCsvRow() + "\n") }
                    }
                }
                _uiState.update { it.copy(exportSuccess = true) }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Export failed: ${e.message}") }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
        collector.stop()
    }
}
