package com.sportapp.models

data class AiAnalysisResponse(
    val analysis: String = "",
    val summary: String? = null,
    val text: String? = null,
    val available: Boolean? = null
)
