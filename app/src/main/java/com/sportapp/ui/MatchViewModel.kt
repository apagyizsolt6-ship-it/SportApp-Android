package com.sportapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sportapp.api.RetrofitInstance
import com.sportapp.models.MatchResponse
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Locale

class MatchViewModel : ViewModel() {

    private val _matches = MutableStateFlow<List<MatchResponse>>(emptyList())
    val matches: StateFlow<List<MatchResponse>> = _matches

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError

    private val _aiAnalysis = MutableStateFlow<String?>(null)
    val aiAnalysis: StateFlow<String?> = _aiAnalysis

    private val _isLoadingAi = MutableStateFlow(false)
    val isLoadingAi: StateFlow<Boolean> = _isLoadingAi

    /** Élő meccsnél sűrűbb poll, egyébként kímélőbb. */
    private val REFRESH_INTERVAL_MS = 15_000L
    private val REFRESH_IDLE_MS = 35_000L

    /** 0 = ma; -1 tegnap; +1 holnap … */
    private var dayOffset: Int = 0
    private var autoJob: Job? = null

    init {
        startAutoRefresh()
    }

    fun setDayOffset(offset: Int) {
        if (dayOffset == offset) {
            // Ugyanaz a nap – ha üres a lista, próbáljuk újra
            if (_matches.value.isEmpty()) fetchMatches(showLoading = true)
            return
        }
        dayOffset = offset
        fetchMatches(showLoading = true)
        autoJob?.cancel()
        if (offset == 0) startAutoRefresh()
    }

    private fun startAutoRefresh() {
        autoJob?.cancel()
        autoJob = viewModelScope.launch {
            while (true) {
                fetchMatches(showLoading = false)
                val hasLive = _matches.value.any { m ->
                    val s = (m.status ?: "").trim().uppercase().replace(".", "")
                    s in setOf("1H", "2H", "HT", "LIVE", "ET", "INPLAY") ||
                        ((m.minute ?: 0) > 0 && s !in setOf("FT", "AET", "PEN", "NS", "TBD", "PST", "CANC"))
                }
                delay(if (hasLive) REFRESH_INTERVAL_MS else REFRESH_IDLE_MS)
            }
        }
    }

    private fun dateForOffset(offset: Int): String {
        return try {
            LocalDate.now().plusDays(offset.toLong()).toString()
        } catch (_: Exception) {
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, offset)
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
        }
    }

    fun fetchMatches(showLoading: Boolean = true) {
        viewModelScope.launch {
            if (showLoading && _matches.value.isEmpty()) _isLoading.value = true
            try {
                val offset = dayOffset
                val list = if (offset == 0) {
                    // Ma: először a gazdag StatPal lista (/api/matches)
                    val main = try {
                        RetrofitInstance.api.getMatches()
                    } catch (_: Exception) {
                        emptyList()
                    }
                    if (main.isNotEmpty()) {
                        main
                    } else {
                        // Fallback: by-date
                        try {
                            RetrofitInstance.api.getMatchesByDate(dateForOffset(0))
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                } else {
                    RetrofitInstance.api.getMatchesByDate(dateForOffset(offset))
                }
                _matches.value = list
                _loadError.value = null
            } catch (e: Exception) {
                e.printStackTrace()
                if (_matches.value.isEmpty()) {
                    _loadError.value = e.message ?: "Hálózati hiba"
                }
            } finally {
                if (showLoading) _isLoading.value = false
            }
        }
    }

    fun fetchAiAnalysis(matchId: String) {
        viewModelScope.launch {
            _isLoadingAi.value = true
            _aiAnalysis.value = null
            try {
                val r = RetrofitInstance.api.getAiAnalysis(matchId)
                // AiAnalysisResponse.analysis – elsődleges
                val direct = try {
                    r.analysis.takeIf { it.isNotBlank() }
                } catch (_: Exception) {
                    null
                }
                val text = direct ?: try {
                    val clazz = r.javaClass
                    listOf("analysis", "summary", "text", "message", "content")
                        .mapNotNull { name ->
                            try {
                                val f = clazz.declaredFields.find { it.name == name }
                                    ?: clazz.fields.find { it.name == name }
                                f?.isAccessible = true
                                f?.get(r)?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                            } catch (_: Exception) {
                                null
                            }
                        }
                        .firstOrNull()
                } catch (_: Exception) {
                    null
                }
                _aiAnalysis.value = text ?: "AI válasz üres."
            } catch (e: Exception) {
                e.printStackTrace()
                _aiAnalysis.value = "AI elemzés jelenleg nem elérhető. (${e.message ?: "hiba"})"
            } finally {
                _isLoadingAi.value = false
            }
        }
    }

    fun retry() {
        fetchMatches(showLoading = true)
    }

    fun clearAiAnalysis() {
        _aiAnalysis.value = null
    }
}
