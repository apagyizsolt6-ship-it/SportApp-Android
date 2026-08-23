from fastapi import FastAPI
import requests
import os
import time
from datetime import datetime, timezone, timedelta

app = FastAPI()

STATPAL_KEY = os.getenv("STATPAL_KEY")
HIGHLIGHTLY_KEY = os.getenv("HIGHLIGHTLY_KEY")

STATPAL_CACHE_TTL = 20
HIGHLIGHTLY_CACHE_TTL = 90

_statpal_cache = {"data": None, "ts": 0.0}
_highlightly_cache = {"data": None, "ts": 0.0}

TOP_LEAGUES_ORDER = [
    "ANGLIA: Premier League",
    "SPANYOLORSZÁG: La Liga",
    "OLASZORSZÁG: Serie A",
    "NÉMETORSZÁG: Bundesliga",
    "FRANCIAORSZÁG: Ligue 1"
]

TRANSLATIONS = {
    "england": "Anglia", "spain": "Spanyolország", "italy": "Olaszország",
    "germany": "Németország", "france": "Franciaország", "hungary": "Magyarország",
    "brazil": "Brazília", "argentina": "Argentína", "netherlands": "Hollandia",
    "holland": "Hollandia", "portugal": "Portugália", "turkey": "Törökország",
    "belgium": "Belgium", "austria": "Ausztria", "poland": "Lengyelország",
    "croatia": "Horvátország", "serbia": "Szerbia", "romania": "Románia",
    "slovakia": "Szlovákia", "czech": "Csehország", "czech republic": "Csehország",
    "greece": "Görögország", "switzerland": "Svájc", "denmark": "Dánia",
    "sweden": "Svédország", "norway": "Norvégia", "scotland": "Skócia",
    "ukraine": "Ukrajna", "usa": "USA", "world": "Nemzetközi", "europe": "Európa",
    "saudi arabia": "Szaúd-Arábia", "saudiarabia": "Szaúd-Arábia", "egypt": "Egyiptom",
    "estonia": "Észtország", "lithuania": "Litvánia", "luxembourg": "Luxemburg",
    "malta": "Málta", "mexico": "Mexikó", "nicaragua": "Nicaragua",
    "tunisia": "Tunézia", "uruguay": "Uruguay", "fiji": "Fidzsi-szigetek",
    "dominican republic": "Dominikai Köztársaság", "equador": "Ecuador", "ecuador": "Ecuador",
    "el salvador": "El Salvador", "kyrgyzstan": "Kirgizisztán", "latvia": "Lettország",
    "russia": "Oroszország", "south africa": "Dél-Afrika", "uae": "Egyesült Arab Emírségek",
    "faroe islands": "Feröer-szigetek", "uzbekistan": "Üzbegisztán", "venezuela": "Venezuela"
}

def translate_text(text):
    if not text:
        return ""
    clean = str(text).replace("_", " ").strip()
    return TRANSLATIONS.get(clean.lower(), clean.title())

def format_league_title(raw_country, raw_league):
    country_hu = translate_text(raw_country).upper()
    league_clean = str(raw_league or "Egyéb Bajnokság").strip()

    if ":" in league_clean:
        parts = league_clean.split(":")
        league_clean = parts[-1].strip()

    if country_hu and country_hu not in league_clean.upper():
        return f"{country_hu}: {league_clean}"
    return league_clean

def adjust_time(time_str):
    if not time_str or ":" not in str(time_str):
        return time_str
    try:
        dt = datetime.strptime(str(time_str).strip(), "%H:%M")
        dt_adj = dt + timedelta(hours=2)
        return dt_adj.strftime("%H:%M")
    except:
        return time_str

def ensure_list(value):
    if value is None:
        return []
    if isinstance(value, list):
        return value
    if isinstance(value, dict):
        return [value]
    return []

def fetch_statpal_matches():
    now = time.time()
    if _statpal_cache["data"] is not None and (now - _statpal_cache["ts"]) < STATPAL_CACHE_TTL:
        return _statpal_cache["data"]

    headers = {"Accept": "application/json"}
    url = f"https://statpal.io/api/v2/soccer/matches/today?access_key={STATPAL_KEY}"
    response = requests.get(url, headers=headers, timeout=10)

    if response.status_code != 200:
        url = f"https://statpal.io/api/v2/soccer/matches/live?access_key={STATPAL_KEY}"
        response = requests.get(url, headers=headers, timeout=10)

    data = response.json()
    _statpal_cache["data"] = data
    _statpal_cache["ts"] = now
    return data

def fetch_highlightly_highlights():
    if not HIGHLIGHTLY_KEY:
        return []

    now = time.time()
    if _highlightly_cache["data"] is not None and (now - _highlightly_cache["ts"]) < HIGHLIGHTLY_CACHE_TTL:
        return _highlightly_cache["data"]

    today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    base_url = "https://soccer.highlightly.net/highlights"
    headers = {"x-rapidapi-key": HIGHLIGHTLY_KEY}

    all_highlights = []
    try:
        for offset in (0, 40):
            resp = requests.get(
                base_url,
                headers=headers,
                params={"date": today, "limit": 40, "offset": offset},
                timeout=6
            )
            if resp.status_code != 200:
                break
            payload = resp.json()
            page = payload.get("data") if isinstance(payload, dict) else None
            if not isinstance(page, list) or not page:
                break
            all_highlights.extend(page)
            if len(page) < 40:
                break
    except Exception:
        pass

    _highlightly_cache["data"] = all_highlights
    _highlightly_cache["ts"] = now
    return all_highlights

@app.get("/api/matches")
def get_matches():
    if not STATPAL_KEY:
        return [{
            "id": "0", "league_id": "0", "league": "Hiba",
            "home_team": "StatPal Kulcs Hiányzik", "away_team": "Render Environment-ben",
            "home_score": 0, "away_score": 0, "status": "error", "minute": 0
        }]

    try:
        data = fetch_statpal_matches()
        matches_list = []
        
        live_matches_data = data.get("live_matches") or data.get("matches") or {}
        if not isinstance(live_matches_data, dict):
            live_matches_data = {}
        leagues = ensure_list(live_matches_data.get("league"))

        highlights_data = fetch_highlightly_highlights()

        for league in leagues:
            if not isinstance(league, dict):
                continue
            
            league_id = str(league.get("id", ""))
            raw_country = league.get("country", "")
            raw_league = league.get("name", "Egyéb Bajnokság")
            full_league_title = format_league_title(raw_country, raw_league)

            matches = ensure_list(league.get("match"))
            for m in matches:
                if not isinstance(m, dict):
                    continue
                
                home_data = m.get("home") or {}
                away_data = m.get("away") or {}
                
                home_name = translate_text(home_data.get("name", "Hazai"))
                away_name = translate_text(away_data.get("name", "Vendég"))

                highlight_url = None
                if isinstance(highlights_data, list):
                    for hl in highlights_data:
                        match_obj = hl.get("match") or {}
                        hl_home = (match_obj.get("homeTeam") or {}).get("name", "").lower()
                        hl_away = (match_obj.get("awayTeam") or {}).get("name", "").lower()
                        title = str(hl.get("title", "")).lower()

                        if (home_name.lower() in title or away_name.lower() in title or 
                            (hl_home and home_name.lower() in hl_home) or (hl_away and away_name.lower() in hl_away)):
                            highlight_url = hl.get("embedUrl") or hl.get("url")
                            break

                try:
                    home_score = int(home_data.get("goals", 0))
                except:
                    home_score = 0

                try:
                    away_score = int(away_data.get("goals", 0))
                except:
                    away_score = 0

                minute_val = 0
                events_container = m.get("events") or {}
                if not isinstance(events_container, dict):
                    events_container = {}
                events = ensure_list(events_container.get("event"))
                if events:
                    try:
                        last_event = events[-1]
                        minute_val = int(last_event.get("minute", 0)) if isinstance(last_event, dict) else 0
                    except:
                        minute_val = 0

                raw_status = m.get("status", "live")
                adjusted_status = adjust_time(raw_status)

                matches_list.append({
                    "id": str(m.get("main_id", "")),
                    "league_id": league_id,
                    "league": full_league_title,
                    "home_team": home_name,
                    "away_team": away_name,
                    "home_score": home_score,
                    "away_score": away_score,
                    "status": adjusted_status,
                    "minute": minute_val,
                    "highlight_url": highlight_url,
                    "value_bet": True if m.get("inplay_odds_running") == "True" else False
                })

        if not matches_list:
            return [{
                "id": "0", "league_id": "0", "league": "Információ",
                "home_team": "Jelenleg nincs", "away_team": "aktív mérkőzés",
                "home_score": None, "away_score": None, "status": "info", "minute": 0
            }]

        def get_league_sort_key(item):
            league_title = item["league"]
            if league_title in TOP_LEAGUES_ORDER:
                return (0, TOP_LEAGUES_ORDER.index(league_title))
            return (1, league_title)

        matches_list.sort(key=get_league_sort_key)
        return matches_list

    except Exception as e:
        return [{
            "id": "err", "league_id": "0", "league": "Szerver hiba",
            "home_team": "API Hiba", "away_team": str(e)[:20],
            "home_score": None, "away_score": None, "status": "error", "minute": 0
        }]

@app.get("/api/standings/{league_id}")
def get_standings(league_id: str):
    if not STATPAL_KEY or not league_id:
        return []

    try:
        url = f"https://statpal.io/api/v2/soccer/leagues/{league_id}/standings?access_key={STATPAL_KEY}"
        res = requests.get(url, timeout=10)
        if res.status_code != 200:
            return []

        data = res.json()
        standings_data = data.get("standings", {})
        tournament = standings_data.get("tournament", {})
        
        if isinstance(tournament, list) and len(tournament) > 0:
            tournament = tournament[0]

        team_list = ensure_list(tournament.get("team"))
        standings = []

        for t in team_list:
            if not isinstance(t, dict):
                continue
            
            overall = t.get("overall", {})
            total = t.get("total", {})

            standings.append({
                "position": int(t.get("position", 0)),
                "team": translate_text(t.get("name", "Csapat")),
                "played": int(overall.get("games_played", 0)),
                "wins": int(overall.get("wins", 0)),
                "draws": int(overall.get("draws", 0)),
                "losses": int(overall.get("losses", 0)),
                "goalsScored": int(overall.get("goals_scored", 0)),
                "goalsAllowed": int(overall.get("goals_allowed", 0)),
                "goalDifference": str(total.get("goal_difference", "0")),
                "points": int(total.get("points", 0))
            })

        standings.sort(key=lambda x: x["position"])
        return standings
    except Exception:
        return []

@app.get("/api/status")
def get_status():
    now = time.time()
    def cache_info(cache, ttl):
        if cache["data"] is None:
            return {"cached": False}
        age = now - cache["ts"]
        return {
            "cached": True,
            "age_seconds": round(age, 1),
            "ttl_seconds": ttl,
            "still_valid": age < ttl
        }

    return {
        "statpal": cache_info(_statpal_cache, STATPAL_CACHE_TTL),
        "highlightly": cache_info(_highlightly_cache, HIGHLIGHTLY_CACHE_TTL),
        "highlightly_count": len(_highlightly_cache["data"] or []) if _highlightly_cache["data"] else 0
    }
