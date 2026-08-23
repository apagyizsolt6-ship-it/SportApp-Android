package com.sportapp.models

import com.google.gson.annotations.SerializedName

data class MatchResponse(
    @SerializedName("id") val matchId: String,
    @SerializedName("league") val league: String?,
    @SerializedName("home_team") val homeTeam: String,
    @SerializedName("away_team") val awayTeam: String,
    @SerializedName("home_score") val homeScore: Int?,
    @SerializedName("away_score") val awayScore: Int?,
    @SerializedName("status") val status: String,
    @SerializedName("minute") val minute: Int?,
    @SerializedName("highlight_url") val highlightUrl: String?,
    @SerializedName("odds_home") val oddsHome: Double?,
    @SerializedName("value_bet") val isValueBet: Boolean?
)
