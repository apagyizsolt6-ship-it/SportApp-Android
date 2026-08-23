from fastapi import FastAPI
import requests
import os
import re
import time
from datetime import datetime, timezone

app = FastAPI()

STATPAL_KEY = os.getenv("STATPAL_KEY")
HIGHLIGHTLY_KEY = os.getenv("HIGHLIGHTLY_KEY")

# ------------------------------------------------------------------
# CACHE
# StatPal Starter csomag: 50.000 hívás/nap. Az élő végpontok a StatPal
#   szerverén is csak 15 mp-enként frissülnek, ezért nincs értelme ennél
#   gyakrabban lekérdezni. Cache nélkül MINDEN kliens kérés = 1 StatPal
#   hívás -> egy megosztott, folyamat-szintű cache-szel a StatPal-t
#   ténylegesen csak STATPAL_CACHE_TTL másodpercenként hívjuk meg,
#   függetlenül attól hányan nyitják meg az appot ugyanabban az ablakban.
#   (86400 / 20 = 4320 hívás/nap a StatPal felé, az 50.000-es keret ~9%-a)
#
# Highlightly Ultra csomag: 25.000 hívás/nap. A /highlights végpont a
#   dokumentáció szerint percenként frissül -> percnél gyakrabban kérni
#   felesleges.
#   (2 hívás/frissítés * 86400/90 = ~1920 hívás/nap, a 25.000-es keret ~8%-a)
# ------------------------------------------------------------------
STATPAL_CACHE_TTL = 20       # másodperc
HIGHLIGHTLY_CACHE_TTL = 90   # másodperc

_statpal_cache = {"data": None, "ts": 0.0}
_highlightly_cache = {"data": None, "ts": 0.0}

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
    "russia": "Oroszország", "southafrica": "Dél-Afrika", "south africa": "Dél-Afrika",
    "united_arab_emirates": "Egyesült Arab Emírségek", "uae": "Egyesült Arab Emírségek",
    "faroe_islands": "Feröer-szigetek", "faroe islands": "Feröer-szigetek",
    "uzbekistan": "Üzbegisztán", "venezuela": "Venezuela", "tanzania": "Tanzánia",

    # BAJNOKSÁGOK & KUPÁK
    "premier league": "Premier League", "la liga": "La Liga", "serie a": "Serie A",
    "bundesliga": "Bundesliga", "ligue 1": "Ligue 1", "nb i": "NB I", "nb ii": "NB II",
    "champions league": "Bajnokok Ligája", "europa league": "Európa-liga",
    "conference league": "Konferencia Liga", "world cup": "Világbajnokság",
    "euro": "Európa-bajnokság", "copa america": "Copa América",
    "dfb pokal": "Német Kupa", "copa del rey": "Spanyol Kupa",
    "coppa italia": "Olasz Kupa", "fa cup": "FA Kupa", "efl cup": "Angol Ligakupa",
    "moly kupa": "Magyar Kupa", "super cup": "Szuperkupa",
    "friendly": "Barátságos Mérkőzés", "friendlies": "Barátságos Mérkőzések"
}

def translate_text(text):
    if not text:
        return ""
    clean = str(text).replace("_", " ").strip()
    return TRANSLATIONS.get(clean.lower(), clean.title())

def format_league_title(raw_country, raw_league):
    country_hu = translate_text(raw_country).upper()
    league_clean = str(raw_league or "Egyéb Bajnokság").strip()

    # Megszünteti a duplázott országneveket a ligacímből
    if ":" in league_clean:
        parts = league_clean.split(":")
        league_clean = parts[-1].strip()

    if country_hu and country_hu not in league_clean.upper():
        return f"{country_hu}: {league_clean}"
    return league_clean

def ensure_list(value):
    if value is None:
        return []
    if isinstance(value, list):
        return value
    if isinstance(value, dict):
        return [value]
    return []


def fetch_statpal_matches():
    """StatPal meccsadatok lekérése, folyamat-szintű cache-elve (STATPAL_CACHE_TTL)."""
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
    """
    Highlightly videók lekérése - JAVÍTVA:
    - RÉGI (hibás) hívás: https://api.highlightly.net/v1/highlights?api_key=...
      -> ez a domain/verzió soha nem is létezett, ezért highlights_data mindig
         üres listaként tért vissza, a 🎥 gomb sosem jelent meg az appban.
    - HELYES Highlightly (nem RapidAPI-n keresztüli) végpont a foci API-hoz:
      https://soccer.highlightly.net/highlights
      header: x-rapidapi-key: <kulcs>  (ez a header neve a Highlightly saját
      kulcsainál is, nem csak RapidAPI-nál)
      + kötelező legalább egy elsődleges query paraméter (pl. date=YYYY-MM-DD)
    Ultra csomaggal (25.000 hívás/nap) bőven belefér, hogy naponta lekérjük a
    mai nap videóit, akár 2 oldalban (limit=40 a max/oldal), hogy minél több
    meccshez legyen videónk.
    """
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
            "id": "0", "league": "Hiba",
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


@app.get("/api/status")
def get_status():
    """Gyors ellenőrzés Render-en: mikor volt az utolsó valódi StatPal/Highlightly
    hívás, és mennyi ideig érvényes még a cache-elt adat."""
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
