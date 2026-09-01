package com.sportapp.api

import com.sportapp.models.MatchResponse
import com.sportapp.models.HighlightVideo
import com.sportapp.models.LineupsResponse
import com.sportapp.models.StatisticsResponse
import com.sportapp.models.AiAnalysisResponse
import com.sportapp.models.H2hResponse
import com.sportapp.models.FormResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

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

data class PlayerSummaryResponse(
    val available: Boolean? = false,
    @com.google.gson.annotations.SerializedName("player_id") val playerId: String? = null,
    val name: String? = null,
    val photo: String? = null,
    val team: String? = null,
    val position: String? = null,
    val season: String? = null,
    val stats: Map<String, @JvmSuppressWildcards Any?>? = null,
    val message: String? = null
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

    /** HL id nélkül is: backend feloldja a Highlightly id-t. */
    @GET("api/matches/{match_id}/lineups")
    suspend fun getMatchLineupsByMatchId(
        @Path("match_id") matchId: String
    ): LineupsResponse

    @GET("api/matches/{match_id}/statistics")
    suspend fun getMatchStatisticsByMatchId(
        @Path("match_id") matchId: String
    ): StatisticsResponse

    @GET("api/matches/{match_id}/odds")
    suspend fun getMatchOdds(
        @Path("match_id") matchId: String
    ): Map<String, Any?>

    // AI elemzés – a GitHub-on lévő MatchViewModel hivatkozik rá.
    // Ha a backend még nem ad valódi AI-t, üres/placeholder válasz jön.
    @GET("api/tips/daily")
    suspend fun getDailyTips(
        @Query("date") date: String? = null,
        @Query("offset") offset: Int = 0,
        @Query("refresh") refresh: Int = 0
    ): Map<String, @JvmSuppressWildcards Any?>

    @GET("api/tips/results")
    suspend fun getTipsResults(
        @Query("date") date: String? = null
    ): Map<String, @JvmSuppressWildcards Any?>

    @GET("api/ai-analysis/{match_id}")
    suspend fun getAiAnalysis(
        @Path("match_id") matchId: String
    ): AiAnalysisResponse

    @GET("api/matches/{match_id}/h2h")
    suspend fun getMatchH2h(
        @Path("match_id") matchId: String
    ): H2hResponse

    @GET("api/players/{player_id}/summary")
    suspend fun getPlayerSummary(@Path("player_id") playerId: String): PlayerSummaryResponse

    @GET("api/matches/{match_id}/form")
    suspend fun getMatchForm(
        @Path("match_id") matchId: String
    ): FormResponse

    @GET("api/matches/by-date/{date}")
    suspend fun getMatchesByDate(
        @Path("date") date: String
    ): List<MatchResponse>

    @POST("api/fcm/register")
    suspend fun registerFcmToken(@Body body: Map<String, String>): Map<String, Any>

    @POST("api/fcm/subscribe")
    suspend fun fcmSubscribe(@Body body: Map<String, String>): Map<String, Any>

    @POST("api/fcm/unsubscribe")
    suspend fun fcmUnsubscribe(@Body body: Map<String, String>): Map<String, Any>

    @POST("api/fcm/test")
    suspend fun fcmTest(@Body body: Map<String, String>): Map<String, Any>
}

