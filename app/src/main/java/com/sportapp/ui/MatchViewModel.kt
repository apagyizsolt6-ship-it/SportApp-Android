package com.sportapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sportapp.api.RetrofitInstance
import com.sportapp.models.MatchResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MatchViewModel : ViewModel() {

    private val _matches = MutableStateFlow<List<MatchResponse>>(emptyList())
    val matches: StateFlow<List<MatchResponse>> = _matches

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    // A Render-es backend a StatPal élő adatait 20 mp-enként frissíti a
    // saját cache-éből (lásd main.py STATPAL_CACHE_TTL), ezért ennél
    // gyakoribb lekérdezés csak felesleges hálózati/akkumulátor terhelés
    // lenne - 20 mp-enként viszont mindig friss adatot kapunk automatikusan,
    // anélkül hogy a felhasználónak újra kéne indítania az appot.
    private val REFRESH_INTERVAL_MS = 20_000L

    init {
        startAutoRefresh()
    }

    private fun startAutoRefresh() {
        viewModelScope.launch {
            while (true) {
                // Csak az első betöltésnél mutatunk teljes képernyős spinnert,
                // a háttérbeli frissítéseknél nem villantjuk fel újra.
                fetchMatches(showLoading = _matches.value.isEmpty())
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    fun fetchMatches(showLoading: Boolean = true) {
        viewModelScope.launch {
            if (showLoading) _isLoading.value = true
            try {
                _matches.value = RetrofitInstance.api.getMatches()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                if (showLoading) _isLoading.value = false
            }
        }
    }
}
