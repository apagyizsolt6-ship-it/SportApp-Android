package com.sportapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sportapp.api.RetrofitInstance
import com.sportapp.api.StandingTeam
import com.sportapp.models.FormResponse
import com.sportapp.models.H2hResponse
import com.sportapp.models.HighlightVideo
import com.sportapp.models.LineupsResponse
import com.sportapp.models.MatchEvent
import com.sportapp.models.MatchResponse
import com.sportapp.models.StatItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Meccs részlet állapot – kikerült a MatchDetailDialog-ból,
 * hogy kevesebb recompose / state bug legyen.
 */
data class MatchDetailUiState(
    val detail: MatchResponse? = null,
    val events: List<MatchEvent> = emptyList(),
    val stats: List<StatItem> = emptyList(),
    val lineups: LineupsResponse? = null,
    val videos: List<HighlightVideo> = emptyList(),
    val standings: List<StandingTeam> = emptyList(),
    val h2h: H2hResponse? = null,
    val form: FormResponse? = null,
    val oddsHome: Double? = null,
    val oddsDraw: Double? = null,
    val oddsAway: Double? = null,
    val oddsSource: String? = null,
    val oddsMarkets: List<Map<String, Any?>> = emptyList(),
    val aiText: String? = null,
    val showAi: Boolean = false,
    val selectedTab: Int = 0,
    val loadingTab: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMsg: String? = null,
    val lastRefreshedAt: Long? = null
)

class MatchDetailViewModel : ViewModel() {

    private val _state = MutableStateFlow(MatchDetailUiState())
    val state: StateFlow<MatchDetailUiState> = _state.asStateFlow()

    private var matchId: String = ""
    private var seed: MatchResponse? = null
    private var pollJob: Job? = null
    private var statsPollJob: Job? = null

    fun start(match: MatchResponse) {
        if (matchId == match.id && _state.value.detail != null) return
        matchId = match.id
        seed = match
        _state.value = MatchDetailUiState(
            detail = match,
            events = match.events.orEmpty(),
            oddsHome = match.oddsHome,
            oddsDraw = match.oddsDraw,
            oddsAway = match.oddsAway
        )
        pollJob?.cancel()
        statsPollJob?.cancel()
        pollJob = viewModelScope.launch { pollLoop() }
        viewModelScope.launch { loadOdds() }
        viewModelScope.launch { loadH2hAndForm() }
    }

    fun selectTab(index: Int) {
        _state.update { it.copy(selectedTab = index, errorMsg = null) }
        loadTab(index)
        // Élő stats poll csak stats tabon
        statsPollJob?.cancel()
        if (index == 2) {
            statsPollJob = viewModelScope.launch { statsPollLoop() }
        }
    }

    fun refreshNow() {
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true) }
            try {
                val d = RetrofitInstance.api.getMatchDetail(matchId)
                _state.update {
                    it.copy(
                        detail = d,
                        events = if (!d.events.isNullOrEmpty()) d.events.orEmpty() else it.events,
                        lastRefreshedAt = System.currentTimeMillis()
                    )
                }
                try {
                    val h = RetrofitInstance.api.getMatchH2h(matchId)
                    _state.update { it.copy(h2h = h) }
                } catch (_: Exception) {
                }
            } catch (_: Exception) {
            } finally {
                _state.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun loadAi() {
        viewModelScope.launch {
            _state.update { it.copy(showAi = true, aiText = null) }
            try {
                val r = RetrofitInstance.api.getAiAnalysis(matchId)
                _state.update { it.copy(aiText = extractAiText(r)) }
            } catch (_: Exception) {
                _state.update { it.copy(aiText = "AI nem elérhető.") }
            }
        }
    }

    fun dismissAi() {
        _state.update { it.copy(showAi = false) }
    }

    fun clearError() {
        _state.update { it.copy(errorMsg = null) }
    }

    private suspend fun pollLoop() {
        while (true) {
            try {
                _state.update { it.copy(isRefreshing = true) }
                val d = RetrofitInstance.api.getMatchDetail(matchId)
                _state.update { s ->
                    s.copy(
                        detail = d,
                        events = if (!d.events.isNullOrEmpty()) d.events.orEmpty()
                        else if (s.events.isEmpty()) seed?.events.orEmpty()
                        else s.events,
                        lastRefreshedAt = System.currentTimeMillis()
                    )
                }
            } catch (_: Exception) {
            } finally {
                _state.update { it.copy(isRefreshing = false) }
            }
            val st = (_state.value.detail?.status ?: "").uppercase()
            if (st == "FT" || st == "INFO" || st == "ERROR") break
            delay(30_000L)
        }
    }

    private suspend fun statsPollLoop() {
        while (true) {
            val d = _state.value.detail ?: break
            val live = d.status != "FT" && d.status != "NS" &&
                d.status != "info" && d.status != "error"
            val hlId = (d.highlightMatchId ?: seed?.highlightMatchId)?.trim().orEmpty()
            if (!live || hlId.isBlank() || _state.value.selectedTab != 2) break
            try {
                val r = RetrofitInstance.api.getMatchStatistics(hlId)
                if (!r.items.isNullOrEmpty()) {
                    _state.update { it.copy(stats = r.items.orEmpty(), errorMsg = null) }
                }
            } catch (_: Exception) {
            }
            if ((_state.value.detail?.status ?: "") == "FT") break
            delay(30_000L)
        }
    }

    private suspend fun loadOdds() {
        try {
            val o = RetrofitInstance.api.getMatchOdds(matchId)
            fun num(key: String): Double? {
                val v = o[key] ?: return null
                return when (v) {
                    is Number -> v.toDouble()
                    is String -> v.toDoubleOrNull()
                    else -> null
                }
            }
            val rawMarkets = o["markets"]
            val markets: List<Map<String, Any?>> =
                if (rawMarkets is List<*>) {
                    rawMarkets.mapNotNull { item ->
                        if (item is Map<*, *>) {
                            item.entries.associate { (k, v) -> k.toString() to v }
                        } else null
                    }
                } else emptyList()
            _state.update {
                it.copy(
                    oddsHome = num("odds_home") ?: it.oddsHome,
                    oddsDraw = num("odds_draw") ?: it.oddsDraw,
                    oddsAway = num("odds_away") ?: it.oddsAway,
                    oddsSource = o["source"]?.toString(),
                    oddsMarkets = markets
                )
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun loadH2hAndForm() {
        if (_state.value.h2h == null) {
            try {
                _state.update { it.copy(h2h = RetrofitInstance.api.getMatchH2h(matchId)) }
            } catch (_: Exception) {
            }
        }
        if (_state.value.form == null) {
            try {
                _state.update { it.copy(form = RetrofitInstance.api.getMatchForm(matchId)) }
            } catch (_: Exception) {
            }
        }
    }

    private fun loadTab(index: Int) {
        viewModelScope.launch {
            _state.update { it.copy(loadingTab = true, errorMsg = null) }
            val m = _state.value.detail ?: seed ?: return@launch
            val hlId = (m.highlightMatchId ?: seed?.highlightMatchId)?.trim().orEmpty()
            try {
                when (index) {
                    2 -> {
                        if (_state.value.stats.isEmpty()) {
                            val r = try {
                                if (hlId.isNotBlank()) {
                                    RetrofitInstance.api.getMatchStatistics(hlId)
                                } else {
                                    RetrofitInstance.api.getMatchStatisticsByMatchId(m.id)
                                }
                            } catch (_: Exception) {
                                RetrofitInstance.api.getMatchStatisticsByMatchId(m.id)
                            }
                            val items = r.items.orEmpty()
                            _state.update {
                                it.copy(
                                    stats = items,
                                    errorMsg = if (items.isEmpty())
                                        "Ehhez a meccshez még nincs statisztika (alsóbb ligáknál gyakran hiányzik)."
                                    else null
                                )
                            }
                        }
                    }
                    3 -> {
                        if (_state.value.lineups == null || _state.value.lineups?.available != true) {
                            val lu = try {
                                if (hlId.isNotBlank()) {
                                    RetrofitInstance.api.getMatchLineups(hlId)
                                } else {
                                    RetrofitInstance.api.getMatchLineupsByMatchId(m.id)
                                }
                            } catch (_: Exception) {
                                RetrofitInstance.api.getMatchLineupsByMatchId(m.id)
                            }
                            _state.update {
                                it.copy(
                                    lineups = lu,
                                    errorMsg = if (lu.available != true)
                                        "Az összeállítás ehhez a meccshez nem érhető el."
                                    else null
                                )
                            }
                        }
                    }
                    4 -> {
                        if (hlId.isNotBlank() && _state.value.videos.isEmpty()) {
                            val vids = RetrofitInstance.api.getMatchHighlights(hlId)
                                .filter { !it.embedUrl.isNullOrBlank() || !it.url.isNullOrBlank() }
                                .sortedWith(
                                    compareByDescending<HighlightVideo> {
                                        it.category.equals("goal-clip", ignoreCase = true)
                                    }.thenBy { it.title.orEmpty() }
                                )
                            _state.update {
                                it.copy(
                                    videos = vids,
                                    errorMsg = if (vids.isEmpty()) "Nincs elérhető videó." else null
                                )
                            }
                        } else if (hlId.isBlank()) {
                            _state.update { it.copy(errorMsg = "Nincs Highlightly videó ehhez a meccshez.") }
                        }
                    }
                    5 -> {
                        val lid = m.leagueId.orEmpty().trim()
                        if (lid.isNotBlank() && _state.value.standings.isEmpty()) {
                            val st = RetrofitInstance.api.getStandings(lid)
                            _state.update {
                                it.copy(
                                    standings = st,
                                    errorMsg = if (st.isEmpty()) "Tabella nem elérhető." else null
                                )
                            }
                        } else if (lid.isBlank()) {
                            _state.update { it.copy(errorMsg = "Nincs liga azonosító.") }
                        }
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(errorMsg = "Betöltési hiba: ${e.message?.take(40) ?: "ismeretlen"}")
                }
            } finally {
                _state.update { it.copy(loadingTab = false) }
            }
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        statsPollJob?.cancel()
        super.onCleared()
    }
}
