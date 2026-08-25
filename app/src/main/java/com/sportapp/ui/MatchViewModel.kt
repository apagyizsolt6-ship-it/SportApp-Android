package com.sportapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sportapp.api.RetrofitInstance
import com.sportapp.models.MatchResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MatchViewModel : ViewModel() {

    private val _matches = MutableStateFlow<List<MatchResponse>>(emptyList())
    val matches: StateFlow<List<MatchResponse>> = _matches

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    // A backend saját cache-e frissül, ezért 20 mp-es időköz elegendő.
    private val REFRESH_INTERVAL_MS = 20_000L

    init {
        startAutoRefresh()
    }

    private fun startAutoRefresh() {
        viewModelScope.launch {
            while (isActive) {
                // FONTOS: a korábbi verzió fetchMatches() belül új coroutine-t
                // indított, ezért ha a hálózat lassú volt, a 20 mp-es ciklusok
                // egymásra torlódtak. Ez lefagyást és lassulást okozhatott.
                loadMatches(showLoading = _matches.value.isEmpty())
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    fun fetchMatches(showLoading: Boolean = true) {
        viewModelScope.launch {
            loadMatches(showLoading)
        }
    }

    private suspend fun loadMatches(showLoading: Boolean) {
        if (showLoading) {
            _isLoading.value = true
        }

        try {
            val result = RetrofitInstance.api.getMatches()

            // Üres vagy hibajellegű válasz esetén nem töröljük le a
            // már megjelenített meccseket egy rövid hálózati hibával.
            if (result.isNotEmpty()) {
                _matches.value = result
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            if (showLoading) {
                _isLoading.value = false
            }
        }
    }
}
