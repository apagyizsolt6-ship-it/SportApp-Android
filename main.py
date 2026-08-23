from fastapi import FastAPI
import requests
import os

app = FastAPI()

STATPAL_KEY = os.getenv("STATPAL_KEY")
HIGHLIGHTLY_KEY = os.getenv("HIGHLIGHTLY_KEY")

# GIGANTIKUS FULLOS MAGYARÍTÓ SZÓTÁR
TRANSLATIONS = {
    # ORSZÁGOK & KONTINENSEK
    "england": "Anglia", "spain": "Spanyolország", "italy": "Olaszország",
    "germany": "Németország", "france": "Franciaország", "hungary": "Magyarország",
    "brazil": "Brazília", "argentina": "Argentína", "netherlands": "Hollandia",
    "portugal": "Portugália", "turkey": "Törökország", "belgium": "Belgium",
    "austria": "Ausztria", "poland": "Lengyelország", "croatia": "Horvátország",
    "serbia": "Szerbia", "romania": "Románia", "slovakia": "Szlovákia",
    "czech republic": "Csehország", "greece": "Görögország", "switzerland": "Svájc",
    "denmark": "Dánia", "sweden": "Svédország", "norway": "Norvégia",
    "scotland": "Skócia", "ukraine": "Ukrajna", "usa": "USA", "world": "Nemzetközi",
    "europe": "Európa", "south america": "Dél-Amerika", "asia": "Ázsia",
    "africa": "Afrika", "north america": "Észak-Amerika", "australia": "Ausztrália",
    "ireland": "Írország", "northern ireland": "Észak-Írország", "wales": "Wales",
    "finland": "Finnország", "iceland": "Izland", "slovenia": "Szlovénia",
    "bulgaria": "Bulgária", "cyprus": "Ciprus", "israel": "Izrael",
    "japan": "Japán", "south korea": "Dél-Korea", "china": "Kína",
    "saudi arabia": "Szaúd-Arábia", "egypt": "Egyiptom", "morocco": "Marokkó",
    "albania": "Albánia", "angola": "Angola", "belarus": "Fehéroroszország",
    "kazakhstan": "Kazahsztán", "kenya": "Kenyai", "kosovo": "Koszovó",
    "andorra": "Andorra", "georgia": "Grúzia", "armenia": "Örményország",
    "azerbaijan": "Azerbajdzsán", "moldova": "Moldova", "bosnia & herzegovina": "Bosznia-Hercegovina",

    # BAJNOKSÁGOK & KUPÁK
    "premier league": "Premier League", "la liga": "La Liga", "serie a": "Serie A",
    "bundesliga": "Bundesliga", "ligue 1": "Ligue 1", "nb i": "NB I", "nb ii": "NB II",
    "champions league": "Bajnokok Ligája", "europa league": "Európa-liga",
    "conference league": "Konferencia Liga", "world cup": "Világbajnokság",
    "euro": "Európa-bajnokság", "copa america": "Copa América",
    "dfb pokal": "Német Kupa", "copa del rey": "Spanyol Kupa",
    "coppa italia": "Olasz Kupa", "fa cup": "FA Kupa", "efl cup": "Angol Ligakupa",
    "moly kupa": "Magyar Kupa", "super cup": "Szuperkupa",
    "friendly": "Barátságos Mérkőzés", "friendlies": "Barátságos Mérkőzések",

    # CSAPATOK
    "bayern munich": "Bayern München", "red star belgrade": "Crvena Zvezda",
    "sporting cp": "Sporting Lisszabon", "inter": "Inter Milánó",
    "ac milan": "AC Milan", "as roma": "AS Roma", "real madrid": "Real Madrid",
    "barcelona": "FC Barcelona", "atletico madrid": "Atlético Madrid",
    "manchester united": "Manchester United", "manchester city": "Manchester City",
    "liverpool": "Liverpool", "chelsea": "Chelsea", "arsenal": "Arsenal",
    "tottenham": "Tottenham Hotspur", "juventus": "Juventus",
    "paris saint germain": "PSG", "psg": "PSG", "ferencvaros": "Ferencváros",
    "uipest": "Újpest FC", "fehervar": "Fehérvár FC", "debrecen": "Debreceni VSC",
    "paks": "Paksi FC", "puskas akademia": "Puskás Akadémia", "gyor": "ETO FC Győr",
    "zalaegerszeg": "Zalaegerszegi TE", "kisvarda": "Kisvárda", "diósgyőr": "DVTK"
}

def translate_text(text):
    if not text:
        return ""
    clean = str(text).strip()
    return TRANSLATIONS.get(clean.lower(), clean)

def ensure_list(value):
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
            "id": "0", "league": "Hiba",
            "home_team": "StatPal Kulcs Hiányzik", "away_team": "Render Environment-ben",
            "home_score": 0, "away_score": 0, "status": "error", "minute": 0
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
            
            # Ország és Bajnokság magyarítása & egyedi összefűzése
            raw_country = league.get("country", "")
            raw_league = league.get("name", "Egyéb Bajnokság")
            
            hu_country = translate_text(raw_country).upper()
            hu_league = translate_text(raw_league)

            # EGYEDI KULCS: megakadályozza a szétcsúszást (pl. OLASZORSZÁG: Serie A)
            if hu_country and hu_country not in hu_league.upper():
                full_league_title = f"{hu_country}: {hu_league}"
            else:
                full_league_title = hu_league

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
                "id": "0", "league": "Információ",
                "home_team": "Jelenleg nincs", "away_team": "aktív mérkőzés",
                "home_score": None, "away_score": None, "status": "info", "minute": 0
            }]

        return matches_list

    except Exception as e:
        return [{
            "id": "err", "league": "Szerver hiba",
            "home_team": "API Hiba", "away_team": str(e)[:20],
            "home_score": None, "away_score": None, "status": "error", "minute": 0
        }]
