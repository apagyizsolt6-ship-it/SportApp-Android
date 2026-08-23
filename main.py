from fastapi import FastAPI
import requests
import os

app = FastAPI()

STATPAL_KEY = os.getenv("STATPAL_KEY")
HIGHLIGHTLY_KEY = os.getenv("HIGHLIGHTLY_KEY")

@app.get("/api/matches")
def get_matches():
    if not STATPAL_KEY:
        return [{
            "id": "0", 
            "home_team": "StatPal Kulcs Hiányzik", 
            "away_team": "Render Environment-ben", 
            "home_score": 0, 
            "away_score": 0, 
            "status": "error", 
            "minute": 0
        }]

    try:
        # 1. StatPal ÉLŐ Meccsek lekérése
        url = f"https://statpal.io/api/v2/soccer/matches/live?access_key={STATPAL_KEY}"
        headers = {"Accept": "application/json"}
        response = requests.get(url, headers=headers, timeout=10)
        data = response.json()

        matches_list = []
        live_matches_data = data.get("live_matches", {})
        leagues = live_matches_data.get("league", [])

        # 2. Highlightly API videók lekérése a megadott struktúra alapján
        highlights_data = []
        if HIGHLIGHTLY_KEY:
            try:
                hl_url = f"https://api.highlightly.net/v1/highlights?api_key={HIGHLIGHTLY_KEY}"
                hl_res = requests.get(hl_url, timeout=5)
                if hl_res.status_code == 200:
                    hl_json = hl_res.json()
                    # A megadott JSON alapján a "data" tömbben vannak a videók
                    highlights_data = hl_json.get("data", [])
            except:
                pass

        # 3. Összepárosítás és adatok kinyerése
        for league in leagues:
            matches = league.get("match", [])
            for m in matches:
                home_data = m.get("home", {})
                away_data = m.get("away", {})
                home_name = home_data.get("name", "Hazai")
                away_name = away_data.get("name", "Vendég")

                # Videó URL keresése a "data" elemeiből
                highlight_url = None
                if isinstance(highlights_data, list):
                    for hl in highlights_data:
                        match_obj = hl.get("match", {})
                        hl_home = match_obj.get("homeTeam", {}).get("name", "").lower()
                        hl_away = match_obj.get("awayTeam", {}).get("name", "").lower()
                        title = str(hl.get("title", "")).lower()

                        # Csapatnév egyezés ellenőrzése
                        if (home_name.lower() in title or away_name.lower() in title or 
                            home_name.lower() in hl_home or away_name.lower() in hl_away):
                            highlight_url = hl.get("embedUrl") or hl.get("url")
                            break

                # Gólok feldolgozása
                try:
                    home_score = int(home_data.get("goals", 0))
                except:
                    home_score = 0

                try:
                    away_score = int(away_data.get("goals", 0))
                except:
                    away_score = 0

                # Perc kiolvasása
                minute_val = 0
                events = m.get("events", {}).get("event", [])
                if events and isinstance(events, list):
                    try:
                        minute_val = int(events[-1].get("minute", 0))
                    except:
                        minute_val = 0

                matches_list.append({
                    "id": str(m.get("main_id", "")),
                    "home_team": home_name,
                    "away_team": away_name,
                    "home_score": home_score,
                    "away_score": away_score,
                    "status": m.get("status", "live"),
                    "minute": minute_val,
                    "highlight_url": highlight_url,
                    "value_bet": True if m.get("inplay_odds_running") == "True" else False
                })

        return matches_list if matches_list else [{
            "id": "0", 
            "home_team": "Jelenleg nincs", 
            "away_team": "élő meccs", 
            "home_score": None, 
            "away_score": None, 
            "status": "info", 
            "minute": 0
        }]

    except Exception as e:
        return [{
            "id": "err", 
            "home_team": "API Hiba", 
            "away_team": str(e)[:25], 
            "home_score": None, 
            "away_score": None, 
            "status": "error", 
            "minute": 0
        }]
