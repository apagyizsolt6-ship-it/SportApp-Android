package com.sportapp.api

import com.sportapp.models.MatchResponse
import com.sportapp.models.HighlightVideo
import com.sportapp.models.LineupsResponse
import com.sportapp.models.StatisticsResponse
import com.sportapp.models.AiAnalysisResponse
import com.sportapp.models.H2hResponse
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

    @GET("api/matches/{match_id}")
    suspend fun getMatchDetail(@Path("match_id") matchId: String): MatchResponse

    @GET("api/standings/{league_id}")
    suspend fun getStandings(@Path("league_id") leagueId: String): List<StandingTeam>

    @GET("api/highlights/match/{highlight_match_id}")
    suspend fun getMatchHighlights(
        @Path("highlight_match_id") highlightMatchId: String
    ): List<HighlightVideo>

    @GET("api/matches/highlightly/{highlight_match_id}/lineups")
    suspend fun getMatchLineups(
        @Path("highlight_match_id") highlightMatchId: String
    ): LineupsResponse

    @GET("api/matches/highlightly/{highlight_match_id}/statistics")
    suspend fun getMatchStatistics(
        @Path("highlight_match_id") highlightMatchId: String
    ): StatisticsResponse

    // AI elemzés – a GitHub-on lévő MatchViewModel hivatkozik rá.
    // Ha a backend még nem ad valódi AI-t, üres/placeholder válasz jön.
    @GET("api/ai-analysis/{match_id}")
    suspend fun getAiAnalysis(
        @Path("match_id") matchId: String
    ): AiAnalysisResponse

    @GET("api/matches/{match_id}/h2h")
    suspend fun getMatchH2h(
        @Path("match_id") matchId: String
    ): H2hResponse
}
