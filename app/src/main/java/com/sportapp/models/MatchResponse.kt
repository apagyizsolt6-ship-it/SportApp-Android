package com.sportapp.models

import com.google.gson.annotations.SerializedName

data class MatchResponse(
    @SerializedName("id") val matchId: String,
    @SerializedName("league_id") val leagueId: String = "",
    @SerializedName("league") val league: String?,
    @SerializedName("country") val country: String? = null,
    @SerializedName("country_code") val countryCode: String? = null,
    @SerializedName("league_logo_url") val leagueLogoUrl: String? = null,
    @SerializedName("home_team") val homeTeam: String,
    @SerializedName("away_team") val awayTeam: String,
    @SerializedName("home_logo_url") val homeLogoUrl: String? = null,
    @SerializedName("away_logo_url") val awayLogoUrl: String? = null,
    @SerializedName("home_score") val homeScore: Int?,
    @SerializedName("away_score") val awayScore: Int?,
    @SerializedName("status") val status: String,
    @SerializedName("minute") val minute: Int?,
    @SerializedName("highlight_url") val highlightUrl: String?,
    @SerializedName("odds_home") val oddsHome: Double?,
    @SerializedName("value_bet") val isValueBet: Boolean?
) {
    // Visszafelé kompatibilis alias: a meglévő UI-kód továbbra is használhatja az id-t.
    val id: String
        get() = matchId
}
