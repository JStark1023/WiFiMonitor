package com.example.wifimonitor

import android.app.Application
import android.content.Context
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

    // Running stats for RTT
    val rttMin: Double = Double.MAX_VALUE,
    val rttMax: Double = Double.MIN_VALUE,
    val rttAvg: Double = 0.0,

    // Sparkline history (last 60 successful pings)
    val rttHistory: List<Double> = emptyList(),

    val intervalSeconds: Int = 5,
    val continuousMode: Boolean = false,
    val errorMessage: String? = null,
    val exportSuccess: Boolean = false
)

class WifiMonitorViewModel(application: Application) : AndroidViewModel(application) {

    private val collector = WifiDataCollector(application)
    private var pollJob: Job? = null

    private val _uiState = MutableStateFlow(MonitorUiState())
    val uiState: StateFlow<MonitorUiState> = _uiState.asStateFlow()

    init {
        // Register the NetworkCallback immediately so it's ready before the
        // first poll.  On API 31+ this is the only path that yields an
        // unredacted WifiInfo; on older APIs it's a no-op register that
        // just caches the network reference.
        collector.start()
    }

    // ── Polling control ────────────────────────────────────────────────────

    fun checkPrerequisites(): String? = collector.checkPrerequisites()

    fun startPolling() {
        if (pollJob?.isActive == true) return
        _uiState.update { it.copy(isPolling = true, errorMessage = null) }

        pollJob = viewModelScope.launch {
            while (true) {
                when (val result = collector.poll()) {
                    is PollResult.Success -> {
                        val record = result.record
                        _uiState.update { state ->
                            val newRecords   = state.records + record
                            val successPings = newRecords.filter { it.pingSuccess }.map { it.pingRttMs }
                            val newHistory   = (state.rttHistory +
                                    if (record.pingSuccess) listOf(record.pingRttMs) else emptyList()
                                    ).takeLast(60)
                            val newMin = if (record.pingSuccess) minOf(state.rttMin, record.pingRttMs) else state.rttMin
                            val newMax = if (record.pingSuccess) maxOf(state.rttMax, record.pingRttMs) else state.rttMax
                            val newAvg = if (successPings.isNotEmpty()) successPings.average() else state.rttAvg
                            state.copy(
                                pollCount    = state.pollCount + 1,
                                latest       = record,
                                records      = newRecords,
                                rttMin       = newMin,
                                rttMax       = newMax,
                                rttAvg       = newAvg,
                                rttHistory   = newHistory,
                                errorMessage = null
                            )
                        }
                    }
                    is PollResult.Error -> {
                        _uiState.update { it.copy(errorMessage = result.message) }
                        // Only auto-stop on confirmed SSID redaction
                        if (result.message.contains("SSID", ignoreCase = true) &&
                            result.message.contains("redacted", ignoreCase = true)
                        ) {
                            stopPolling()
                            return@launch
                        }
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
        collector.stop()   // unregister NetworkCallback
    }
}
