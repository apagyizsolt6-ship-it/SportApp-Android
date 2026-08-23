from fastapi import FastAPI
import requests
import os

app = FastAPI()

STATPAL_KEY = os.getenv("STATPAL_KEY")
HIGHLIGHTLY_KEY = os.getenv("HIGHLIGHTLY_KEY")

@app.get("/api/matches")
def get_matches():
    # Ha nincs megadva kulcs, hiba elkerülése
    if not STATPAL_KEY:
        return [{"id": "0", "home_team": "API kulcs hiányzik", "away_team": "Render Environment-ben", "home_score": 0, "away_score": 0, "status": "error", "minute": 0}]

    try:
        # 1. ÉLŐ MECCSEK LEKÉRÉSE A STATPAL API-RÓL
        url = f"https://api.statpal.io/v1/matches/live?api_key={STATPAL_KEY}"
        response = requests.get(url, timeout=10)
        data = response.json()

        matches_list = []
        
        # 2. ADATOK FELDOLGOZÁSA
        raw_matches = data.get("data", []) if isinstance(data, dict) else data

        for item in raw_matches[:10]: # Első 10 élő meccs
            # HighLightly API hívás a videóért (ha van)
            highlight_url = None
            if HIGHLIGHTLY_KEY:
                try:
                    hl_res = requests.get(f"https://api.highlightly.net/v1/highlights?match_id={item.get('id')}&api_key={HIGHLIGHTLY_KEY}", timeout=3)
                    if hl_res.status_code == 200:
                        hl_data = hl_res.json()
                        highlight_url = hl_data.get("video_url")
                except:
                    pass

            # Odds/Value bet logika
            home_odds = item.get("odds_home", 2.0)
            is_value = True if home_odds and float(home_odds) > 2.5 else False

            matches_list.append({
                "id": str(item.get("id", "")),
                "home_team": item.get("home_team", "Hazai"),
                "away_team": item.get("away_team", "Vendég"),
                "home_score": item.get("home_score"),
                "away_score": item.get("away_score"),
                "status": item.get("status", "live"),
                "minute": item.get("minute", 0),
                "highlight_url": highlight_url,
                "odds_home": home_odds,
                "value_bet": is_value
            })

        return matches_list if matches_list else [{"id": "0", "home_team": "Nincs élő meccs", "away_team": "jelenleg", "home_score": None, "away_score": None, "status": "info", "minute": 0}]

    except Exception as e:
        return [{"id": "err", "home_team": "API Hiba", "away_team": str(e)[:20], "home_score": None, "away_score": None, "status": "error", "minute": 0}]
