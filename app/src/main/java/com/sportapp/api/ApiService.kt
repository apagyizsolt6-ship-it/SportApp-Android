package com.sportapp.api

import com.sportapp.models.MatchResponse
import retrofit2.http.GET

interface ApiService {
    @GET("api/matches")
    suspend fun getMatches(): List<MatchResponse>
}
