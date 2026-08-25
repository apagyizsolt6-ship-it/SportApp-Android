package com.sportapp.api

import com.sportapp.models.MatchResponse
import com.sportapp.models.HighlightVideo
import com.sportapp.models.AiAnalysisResponse
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

    @GET("api/highlights/watch/{highlight_match_id}")
    suspend fun getMatchHighlights(
        @Path("highlight_match_id") highlightMatchId: String
    ): List<HighlightVideo>

    @GET("api/ai-analysis/{match_id}")
    suspend fun getAiAnalysis(
        @Path("match_id") matchId: String
    ): AiAnalysisResponse
}
