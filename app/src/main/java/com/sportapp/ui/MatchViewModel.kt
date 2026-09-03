package com.sportapp.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sportapp.api.RetrofitInstance
import com.sportapp.models.MatchResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Calendar
import java.util.Locale

class MatchViewModel(app: Application) : AndroidViewModel(app) {

    private val _matches = MutableStateFlow<List<MatchResponse>>(emptyList())
    val matches: StateFlow<List<MatchResponse>> = _matches

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError

    private val _fromCache = MutableStateFlow(false)
    val fromCache: StateFlow<Boolean> = _fromCache

    private val _aiAnalysis = MutableStateFlow<String?>(null)
    val aiAnalysis: StateFlow<String?> = _aiAnalysis

    private val _isLoadingAi = MutableStateFlow(false)
    val isLoadingAi: StateFlow<Boolean> = _isLoadingAi

    private val REFRESH_INTERVAL_MS = 18_000L
    private val REFRESH_IDLE_MS = 40_000L

    private var dayOffset: Int = 0
    private var autoJob: Job? = null
    private var fetchJob: Job? = null

    init {
        val cached = MatchCache.load(getApplication(), dateForOffset(0))
        if (cached.isNotEmpty()) {
            _matches.value = cached
            _fromCache.value = true
            _isLoading.value = false
        }
        startAutoRefresh()
    }

    fun setDayOffset(offset: Int) {
        if (dayOffset == offset) {
            if (_matches.value.isEmpty()) fetchMatches(showLoading = true)
            return
        }
        dayOffset = offset
        val cached = MatchCache.load(getApplication(), dateForOffset(offset))
        if (cached.isNotEmpty()) {
            _matches.value = cached
            _fromCache.value = true
            _loadError.value = null
            _isLoading.value = false
            fetchMatches(showLoading = false)
        } else {
            _matches.value = emptyList()
            _fromCache.value = false
            fetchMatches(showLoading = true)
        }
        autoJob?.cancel()
        if (offset == 0) startAutoRefresh()
    }

    private fun startAutoRefresh() {
        autoJob?.cancel()
        autoJob = viewModelScope.launch {
            fetchMatches(showLoading = _matches.value.isEmpty())
            while (true) {
                val hasLive = _matches.value.any { m ->
                    val s = (m.status ?: "").trim().uppercase().replace(".", "")
                    s in setOf("1H", "2H", "HT", "LIVE", "ET", "INPLAY") ||
                        ((m.minute ?: 0) > 0 && s !in setOf("FT", "AET", "PEN", "NS", "TBD", "PST", "CANC"))
                }
                delay(if (hasLive) REFRESH_INTERVAL_MS else REFRESH_IDLE_MS)
                fetchMatches(showLoading = false)
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
        if (fetchJob?.isActive == true) return
        fetchJob = viewModelScope.launch {
            val hadData = _matches.value.isNotEmpty()
            if (showLoading && !hadData) _isLoading.value = true
            if (hadData) _isRefreshing.value = true
            try {
                val offset = dayOffset
                val dateIso = dateForOffset(offset)
                val list = withContext(Dispatchers.IO) {
                    if (offset == 0) {
                        val main = try {
                            RetrofitInstance.api.getMatches()
                        } catch (_: Exception) {
                            emptyList()
                        }
                        if (main.isNotEmpty()) main
                        else try {
                            RetrofitInstance.api.getMatchesByDate(dateIso)
                        } catch (_: Exception) {
                            emptyList()
                        }
                    } else {
                        RetrofitInstance.api.getMatchesByDate(dateIso)
                    }
                }
                if (list.isNotEmpty()) {
                    _matches.value = list
                    _loadError.value = null
                    _fromCache.value = false
                    withContext(Dispatchers.IO) {
                        MatchCache.save(getApplication(), dateIso, list)
                    }
                } else if (_matches.value.isEmpty()) {
                    val cached = MatchCache.load(getApplication(), dateIso)
                    if (cached.isNotEmpty()) {
                        _matches.value = cached
                        _fromCache.value = true
                        _loadError.value = "Offline cache – nincs friss adat"
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (_matches.value.isEmpty()) {
                    val cached = MatchCache.load(getApplication(), dateForOffset(dayOffset))
                    if (cached.isNotEmpty()) {
                        _matches.value = cached
                        _fromCache.value = true
                        _loadError.value = "Offline mód – cache betöltve"
                    } else {
                        _loadError.value = e.message ?: "Hálózati hiba"
                    }
                }
            } finally {
                _isLoading.value = false
                _isRefreshing.value = false
            }
        }
    }

    fun fetchAiAnalysis(matchId: String) {
        viewModelScope.launch {
            _isLoadingAi.value = true
            _aiAnalysis.value = null
            try {
                val r = withContext(Dispatchers.IO) {
                    RetrofitInstance.api.getAiAnalysis(matchId)
                }
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
        fetchMatches(showLoading = _matches.value.isEmpty())
    }

    fun clearAiAnalysis() {
        _aiAnalysis.value = null
    }
}
