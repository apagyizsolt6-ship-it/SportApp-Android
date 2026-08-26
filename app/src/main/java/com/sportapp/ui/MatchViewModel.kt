package com.sportapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sportapp.api.RetrofitInstance
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MatchViewModel : ViewModel() {

    private val _matches = MutableStateFlow<List<com.sportapp.models.MatchResponse>>(emptyList())
    val matches: StateFlow<List<com.sportapp.models.MatchResponse>> = _matches

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _aiAnalysis = MutableStateFlow<String?>(null)
    val aiAnalysis: StateFlow<String?> = _aiAnalysis.asStateFlow()

    private val _isLoadingAi = MutableStateFlow(false)
    val isLoadingAi: StateFlow<Boolean> = _isLoadingAi.asStateFlow()

    // Kicsit ritkább frissítés – kevesebb terhelés, snappibb UI
    private val REFRESH_INTERVAL_MS = 30_000L

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

    /**
     * Gemini AI elemzés – 1 automatikus újrapróbálással.
     */
    fun fetchAiAnalysis(matchId: String) {
        viewModelScope.launch {
            _isLoadingAi.value = true
            _aiAnalysis.value = null

            var lastError: String? = null
            repeat(2) { attempt ->
                try {
                    val response = RetrofitInstance.api.getAiAnalysis(matchId)
                    val text = response.analysis?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        _aiAnalysis.value = text
                        _isLoadingAi.value = false
                        return@launch
                    }
                    lastError = "Üres AI válasz érkezett."
                } catch (e: Exception) {
                    lastError = e.message ?: "hálózati hiba"
                    e.printStackTrace()
                    // Rövid várás újrapróbálás előtt
                    if (attempt == 0) delay(1200)
                }
            }

            _aiAnalysis.value =
                "Az AI elemzés elérése sikertelen volt.\n\n" +
                    "Lehetséges ok: lassú szerver, hálózat vagy Gemini terhelés.\n" +
                    "Próbáld újra néhány másodperc múlva." +
                    (if (!lastError.isNullOrBlank()) "\n($lastError)" else "")
            _isLoadingAi.value = false
        }
    }

    fun clearAiAnalysis() {
        _aiAnalysis.value = null
    }
}
