package com.sportapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sportapp.api.RetrofitInstance
import com.sportapp.models.MatchResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MatchViewModel : ViewModel() {

    private val _matches = MutableStateFlow<List<MatchResponse>>(emptyList())
    val matches: StateFlow<List<MatchResponse>> = _matches

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    init {
        fetchMatches()
    }

    fun fetchMatches() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _matches.value = RetrofitInstance.api.getMatches()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = False
            }
        }
    }
}
