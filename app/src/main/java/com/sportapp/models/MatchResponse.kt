package com.sportapp.models

import com.google.gson.annotations.SerializedName

data class HighlightVideo(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("embedUrl") val embedUrl: String? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("category") val category: String? = null,
    @SerializedName("source") val source: String? = null,
    @SerializedName("imgUrl") val imgUrl: String? = null
)

data class MatchEvent(
    @SerializedName("type") val type: String? = null,
    @SerializedName("team") val team: String? = null,
    @SerializedName("team_name") val teamName: String? = null,
    @SerializedName("minute") val minute: Int? = null,
    @SerializedName("minute_display") val minuteDisplay: String? = null,
    @SerializedName("player") val player: String? = null,
    @SerializedName("assist") val assist: String? = null,
    @SerializedName("result") val result: String? = null,
    @SerializedName("substituted") val substituted: String? = null
)

data class MatchResponse(
    @SerializedName("id") val matchId: String = "",
    @SerializedName("league_id") val leagueId: String = "",
    @SerializedName("league") val league: String?,
    @SerializedName("country") val country: String? = null,
    @SerializedName("country_code") val countryCode: String? = null,
    @SerializedName("league_logo_url") val leagueLogoUrl: String? = null,
    @SerializedName("home_team") val homeTeam: String = "",
    @SerializedName("away_team") val awayTeam: String = "",
    @SerializedName("home_logo_url") val homeLogoUrl: String? = null,
    @SerializedName("away_logo_url") val awayLogoUrl: String? = null,
    @SerializedName("home_score") val homeScore: Int?,
    @SerializedName("away_score") val awayScore: Int?,
    @SerializedName("status") val status: String = "",
    @SerializedName("minute") val minute: Int?,
    @SerializedName("highlight_url") val highlightUrl: String?,
    @SerializedName("highlight_match_id") val highlightMatchId: String? = null,
    @SerializedName("odds_home") val oddsHome: Double? = null,
    @SerializedName("odds_draw") val oddsDraw: Double? = null,
    @SerializedName("odds_away") val oddsAway: Double? = null,
    @SerializedName("value_bet") val isValueBet: Boolean? = null,
    @SerializedName("venue") val venue: String? = null,
    @SerializedName("referee") val referee: String? = null,
    @SerializedName("events") val events: List<MatchEvent>? = null,
    @SerializedName("kickoff_date") val kickoffDate: String? = null,
    @SerializedName("kickoff_time") val kickoffTime: String? = null,
    @SerializedName("hl_home_team_id") val hlHomeTeamId: Any? = null,
    @SerializedName("hl_away_team_id") val hlAwayTeamId: Any? = null,
    @SerializedName("predictions") val predictions: Any? = null,
    @SerializedName("forecast") val forecast: Any? = null
) {
    val id: String
        get() = matchId

    /** Null-safe megjelenítendő nevek (Gson null-t is adhat non-null mezőre). */
    val homeTeamSafe: String get() = homeTeam ?: ""
    val awayTeamSafe: String get() = awayTeam ?: ""
    val statusSafe: String get() = status ?: ""
}

data class LineupPlayer(
    @SerializedName("name") val name: String? = null,
    @SerializedName("number") val number: Any? = null,
    @SerializedName("position") val position: String? = null,
    @SerializedName("is_bench") val isBench: Boolean? = false,
    @SerializedName("player_id") val playerId: String? = null
)

data class LineupSide(
    @SerializedName("team_name") val teamName: String? = null,
    @SerializedName("formation") val formation: String? = null,
    @SerializedName("players") val players: List<LineupPlayer>? = null
)

data class LineupsResponse(
    @SerializedName("home") val home: LineupSide? = null,
    @SerializedName("away") val away: LineupSide? = null,
    @SerializedName("available") val available: Boolean? = false
)

data class StatItem(
    @SerializedName("name") val name: String? = null,
    @SerializedName("home") val home: Any? = null,
    @SerializedName("away") val away: Any? = null
)

data class StatisticsResponse(
    @SerializedName("items") val items: List<StatItem>? = null,
    @SerializedName("available") val available: Boolean? = false
)


data class H2hItem(
    @SerializedName("date") val date: String? = null,
    @SerializedName("home_team") val homeTeam: String? = null,
    @SerializedName("away_team") val awayTeam: String? = null,
    @SerializedName("home_score") val homeScore: Any? = null,
    @SerializedName("away_score") val awayScore: Any? = null,
    @SerializedName("competition") val competition: String? = null
)

data class H2hResponse(
    @SerializedName("items") val items: List<H2hItem>? = null,
    @SerializedName("available") val available: Boolean? = false,
    @SerializedName("message") val message: String? = null,
    @SerializedName("home_team") val homeTeam: String? = null,
    @SerializedName("away_team") val awayTeam: String? = null
)


data class FormResponse(
    @SerializedName("home") val home: List<String>? = null,
    @SerializedName("away") val away: List<String>? = null,
    @SerializedName("home_team") val homeTeam: String? = null,
    @SerializedName("away_team") val awayTeam: String? = null,
    @SerializedName("available") val available: Boolean? = false
)
