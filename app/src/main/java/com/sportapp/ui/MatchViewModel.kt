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

class MatchViewModel : ViewModel() {

    private val _matches = MutableStateFlow<List<MatchResponse>>(emptyList())
    val matches: StateFlow<List<MatchResponse>> = _matches

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _aiAnalysis = MutableStateFlow<String?>(null)
    val aiAnalysis: StateFlow<String?> = _aiAnalysis

    private val _isLoadingAi = MutableStateFlow(false)
    val isLoadingAi: StateFlow<Boolean> = _isLoadingAi

    private val REFRESH_INTERVAL_MS = 20_000L

    /** 0 = ma; -1 tegnap; +1 holnap … */
    private var dayOffset: Int = 0
    private var autoJob: Job? = null

    init {
        startAutoRefresh()
    }

    fun setDayOffset(offset: Int) {
        if (dayOffset == offset) return
        dayOffset = offset
        fetchMatches(showLoading = true)
        autoJob?.cancel()
        if (offset == 0) startAutoRefresh()
    }

    private fun startAutoRefresh() {
        autoJob?.cancel()
        autoJob = viewModelScope.launch {
            while (true) {
                fetchMatches(showLoading = _matches.value.isEmpty())
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    fun fetchMatches(showLoading: Boolean = true) {
        viewModelScope.launch {
            if (showLoading) _isLoading.value = true
            try {
                _matches.value = if (dayOffset == 0) {
                    RetrofitInstance.api.getMatches()
                } else {
                    val date = try {
                        LocalDate.now().plusDays(dayOffset.toLong()).toString()
                    } catch (_: Exception) {
                        val cal = java.util.Calendar.getInstance()
                        cal.add(java.util.Calendar.DAY_OF_YEAR, dayOffset)
                        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                            .format(cal.time)
                    }
                    RetrofitInstance.api.getMatchesByDate(date)
                }
            } catch (e: Exception) {
                e.printStackTrace()
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
                // Mezőnevek repónként eltérhetnek – reflection nélkül toString fallback
                val text = try {
                    val clazz = r.javaClass
                    listOf("summary", "analysis", "text", "message", "content")
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
                _aiAnalysis.value = text ?: "AI válasz érkezett."
            } catch (e: Exception) {
                _aiAnalysis.value = "AI elemzés jelenleg nem elérhető."
            } finally {
                _isLoadingAi.value = false
            }
        }
    }

    fun clearAiAnalysis() {
        _aiAnalysis.value = null
    }
}
