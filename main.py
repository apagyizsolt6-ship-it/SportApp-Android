from fastapi import FastAPI
import requests
import os

app = FastAPI()

STATPAL_KEY = os.getenv("STATPAL_KEY")
HIGHLIGHTLY_KEY = os.getenv("HIGHLIGHTLY_KEY")


def ensure_list(value):
    """A StatPal API (XML->JSON konverzió miatt) egy elem esetén nem listát,
    hanem egyetlen dict-et ad vissza. Ez a helper mindig listává alakítja."""
    if value is None:
        return []
    if isinstance(value, list):
        return value
    if isinstance(value, dict):
        return [value]
    return []

@app.get("/api/matches")
def get_matches():
    if not STATPAL_KEY:
        return [{
            "id": "0", 
            "league": "Hiba",
            "home_team": "StatPal Kulcs Hiányzik", 
            "away_team": "Render Environment-ben", 
            "home_score": 0, 
            "away_score": 0, 
            "status": "error", 
            "minute": 0
        }]

    try:
        url = f"https://statpal.io/api/v2/soccer/matches/today?access_key={STATPAL_KEY}"
        headers = {"Accept": "application/json"}
        response = requests.get(url, headers=headers, timeout=10)
        
        if response.status_code != 200:
            url = f"https://statpal.io/api/v2/soccer/matches/live?access_key={STATPAL_KEY}"
            response = requests.get(url, headers=headers, timeout=10)

        data = response.json()
        matches_list = []
        
        live_matches_data = data.get("live_matches") or data.get("matches") or {}
        if not isinstance(live_matches_data, dict):
            live_matches_data = {}
        leagues = ensure_list(live_matches_data.get("league"))

        highlights_data = []
        if HIGHLIGHTLY_KEY:
            try:
                hl_url = f"https://api.highlightly.net/v1/highlights?api_key={HIGHLIGHTLY_KEY}"
                hl_res = requests.get(hl_url, timeout=5)
                if hl_res.status_code == 200:
                    hl_json = hl_res.json()
                    highlights_data = hl_json.get("data") or []
            except:
                pass

        for league in leagues:
            if not isinstance(league, dict):
                continue
            
            league_name = league.get("name", "Egyéb Bajnokság")
            country_name = league.get("country", "")
            full_league_title = f"{country_name.upper()} - {league_name}" if country_name else league_name

            matches = ensure_list(league.get("match"))
            for m in matches:
                if not isinstance(m, dict):
                    continue
                home_data = m.get("home") or {}
                away_data = m.get("away") or {}
                if not isinstance(home_data, dict):
                    home_data = {}
                if not isinstance(away_data, dict):
                    away_data = {}
                home_name = home_data.get("name", "Hazai")
                away_name = away_data.get("name", "Vendég")

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

                matches_list.append({
                    "id": str(m.get("main_id", "")),
                    "league": full_league_title,
                    "home_team": home_name,
                    "away_team": away_name,
                    "home_score": home_score,
                    "away_score": away_score,
                    "status": m.get("status", "live"),
                    "minute": minute_val,
                    "highlight_url": highlight_url,
                    "value_bet": True if m.get("inplay_odds_running") == "True" else False
                })

        if not matches_list:
            return [{
                "id": "0", 
                "league": "Információ",
                "home_team": "Mára nincs több", 
                "away_team": "kiírt mérkőzés", 
                "home_score": None, 
                "away_score": None, 
                "status": "info", 
                "minute": 0
            }]

        return matches_list

    except Exception as e:
        return [{
            "id": "err", 
            "league": "Szerver hiba",
            "home_team": "API Hiba", 
            "away_team": str(e)[:20], 
            "home_score": None, 
            "away_score": None, 
            "status": "error", 
            "minute": 0
        }]
