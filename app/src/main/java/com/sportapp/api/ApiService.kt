package com.sportapp.api

import com.sportapp.models.MatchResponse
import retrofit2.http.GET
import retrofit2.http.Path

data class StandingTeam(
    val position: Int,
    val team: String,
    val played: Int,
    val wins: Int,
    val draws: Int,
    val losses: Int,
    val goalsScored: Int,
    val goalsAllowed: Int,
    val goalDifference: String,
    val points: Int
)

interface ApiService {
    @GET("api/matches")
    suspend fun getMatches(): List<MatchResponse>

    @GET("api/standings/{league_id}")
    suspend fun getStandings(@Path("league_id") leagueId: String): List<StandingTeam>
}
