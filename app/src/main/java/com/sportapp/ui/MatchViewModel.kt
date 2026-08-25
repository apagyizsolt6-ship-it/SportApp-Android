package com.sportapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sportapp.api.RetrofitInstance
import com.sportapp.models.MatchResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MatchViewModel : ViewModel() {

    private val _matches = MutableStateFlow<List<MatchResponse>>(emptyList())
    val matches: StateFlow<List<MatchResponse>> = _matches

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    // Gemini AI állapotok
    private val _aiAnalysis = MutableStateFlow<String?>(null)
    val aiAnalysis: StateFlow<String?> = _aiAnalysis.asStateFlow()

    private val _isLoadingAi = MutableStateFlow(false)
    val isLoadingAi: StateFlow<Boolean> = _isLoadingAi.asStateFlow()

    // A backend saját cache-e frissül, ezért 20 mp-es időköz elegendő.
    private val REFRESH_INTERVAL_MS = 20_000L

    init {
        startAutoRefresh()
    }

    private fun startAutoRefresh() {
        viewModelScope.launch {
            while (isActive) {
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

    // Gemini AI elemzés lekérése backend-ről
    fun fetchAiAnalysis(matchId: String) {
        viewModelScope.launch {
            _isLoadingAi.value = true
            _aiAnalysis.value = null
            try {
                val response = RetrofitInstance.api.getAiAnalysis(matchId)
                _aiAnalysis.value = response.analysis
            } catch (e: Exception) {
                _aiAnalysis.value = "Az AI elemzés elérése sikertelen volt."
            } finally {
                _isLoadingAi.value = false
            }
        }
    }

    fun clearAiAnalysis() {
        _aiAnalysis.value = null
    }
}
