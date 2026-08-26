from fastapi import FastAPI
from fastapi.responses import Response
import requests
import re
import os
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone, timedelta
try:
    from google import genai as google_genai
except ImportError:
    google_genai = None

app = FastAPI()

STATPAL_KEY = os.getenv("STATPAL_KEY")
HIGHLIGHTLY_KEY = os.getenv("HIGHLIGHTLY_KEY")
GEMINI_KEY = os.getenv("GEMINI_KEY")

# Új Google Gen AI SDK kliens (google-genai csomag)
_gemini_client = None
if GEMINI_KEY and google_genai is not None:
    try:
        _gemini_client = google_genai.Client(api_key=GEMINI_KEY)
    except Exception as e:
        print(f"[AI INIT ERROR] {e}")
        _gemini_client = None

STATPAL_CACHE_TTL = 20
HIGHLIGHTLY_CACHE_TTL = 300  # 5 perc – ne blokkolja a meccslistát gyakran
TEAM_IMAGE_CACHE_TTL = 21600
AI_CACHE_TTL = 43200

IMAGE_PROXY_BASE_URL = os.getenv(
    "IMAGE_PROXY_BASE_URL",
    "https://sportapp-android.onrender.com"
)

_statpal_cache = {"data": None, "ts": 0.0}
_highlightly_cache = {"data": None, "ts": 0.0}
_team_image_cache = {}
_highlightly_match_cache = {}
_ai_analysis_cache = {}

TOP_LEAGUES_ORDER = [
    "ANGLIA: Premier League",
    "SPANYOLORSZÁG: La Liga",
    "OLASZORSZÁG: Serie A",
    "NÉMETORSZÁG: Bundesliga",
    "FRANCIAORSZÁG: Ligue 1"
]

TRANSLATIONS = {
    "england": "Anglia",
    "spain": "Spanyolország",
    "italy": "Olaszország",
    "germany": "Németország",
    "france": "Franciaország",
    "hungary": "Magyarország",
    "brazil": "Brazília",
    "argentina": "Argentína",
    "netherlands": "Hollandia",
    "holland": "Hollandia",
    "portugal": "Portugália",
    "turkey": "Törökország",
    "belgium": "Belgium",
    "austria": "Ausztria",
    "poland": "Lengyelország",
    "croatia": "Horvátország",
    "serbia": "Szerbia",
    "romania": "Románia",
    "slovakia": "Szlovákia",
    "czech": "Csehország",
    "czech republic": "Csehország",
    "greece": "Görögország",
    "switzerland": "Svájc",
    "denmark": "Dánia",
    "sweden": "Svédország",
    "norway": "Norvégia",
    "scotland": "Skócia",
    "ukraine": "Ukrajna",
    "usa": "USA",
    "world": "Nemzetközi",
    "europe": "Európa",
    "saudi arabia": "Szaúd-Arábia",
    "saudiarabia": "Szaúd-Arábia",
    "egypt": "Egyiptom",
    "estonia": "Észtország",
    "lithuania": "Litvánia",
    "luxembourg": "Luxemburg",
    "malta": "Málta",
    "mexico": "Mexikó",
    "nicaragua": "Nicaragua",
    "tunisia": "Tunézia",
    "uruguay": "Uruguay",
    "fiji": "Fidzsi-szigetek",
    "dominican republic": "Dominikai Köztársaság",
    "equador": "Ecuador",
    "ecuador": "Ecuador",
    "el salvador": "El Salvador",
    "kyrgyzstan": "Kirgizisztán",
    "latvia": "Lettország",
    "russia": "Oroszország",
    "south africa": "Dél-Afrika",
    "uae": "Egyesült Arab Emírségek",
    "faroe islands": "Feröer-szigetek",
    "uzbekistan": "Üzbegisztán",
    "venezuela": "Venezuela",
    "canada": "Kanada",
    "chile": "Chile",
    "china": "Kína",
    "colombia": "Kolumbia",
    "costa rica": "Costa Rica",
    "kosovo": "Koszovó",
    "iceland": "Izland",
    "india": "India",
    "iran": "Irán",
    "israel": "Izrael",
    "japan": "Japán",
    "kazakhstan": "Kazahsztán",
    "kenya": "Kenya",
    "south korea": "Dél-Korea",
    "korea republic": "Dél-Korea",
    "republic of korea": "Dél-Korea",
    "south korea republic": "Dél-Korea",
    "bosnia and herzegovina": "Bosznia-Hercegovina",
    "bosnia & herzegovina": "Bosznia-Hercegovina",
    "united arab emirates": "Egyesült Arab Emírségek",
    "slovenia": "Szlovénia",
    "finland": "Finnország",
    "gibraltar": "Gibraltár",
    "guatemala": "Guatemala",
    "brazilia": "Brazília",
    "bolivia": "Bolívia",
    "peru": "Peru",
    "georgia": "Grúzia",
    "australia": "Ausztrália",
    "new zealand": "Új-Zéland",
    "qatar": "Katar",
    "jordan": "Jordánia",
    "kuwait": "Kuvait",
    "lebanon": "Libanon",
    "cyprus": "Ciprus",
    "czechia": "Csehország",
    "south sudan": "Dél-Szudán",
    "nigeria": "Nigéria",
    "ghana": "Ghána",
    "morocco": "Marokkó",
    "algeria": "Algéria",
    "angola": "Angola",
    "zambia": "Zambia",
    "zimbabwe": "Zimbabwe",
    "mauritius": "Mauritius",
    "armenia": "Örményország",
    "belarus": "Fehéroroszország",
    "north macedonia": "Észak-Macedónia",
    "macedonia": "Észak-Macedónia",
    "bulgaria": "Bulgária",
    "honduras": "Honduras",
    "malaysia": "Malajzia",
    "panama": "Panama",
    "paraguay": "Paraguay",
    "singapore": "Szingapúr",
    "sri lanka": "Srí Lanka",
    "tanzania": "Tanzánia",
    "taiwan": "Tajvan",
    "vietnam": "Vietnam",
    "thailand": "Thaiföld",
    "philippines": "Fülöp-szigetek",
    "indonesia": "Indonézia",
    "uganda": "Uganda",
    "senegal": "Szenegál",
    "cameroon": "Kamerun",
    "ivory coast": "Elefántcsontpart",
    "cote d'ivoire": "Elefántcsontpart",
    "mali": "Mali",
    "burkina faso": "Burkina Faso",
    "togo": "Togo",
    "benin": "Benin",
    "ethiopia": "Etiópia",
    "mozambique": "Mozambik",
    "botswana": "Botswana",
    "namibia": "Namíbia",
    "jamaica": "Jamaica",
    "trinidad and tobago": "Trinidad és Tobago",
    "new caledonia": "Új-Kaledónia",
    "hong kong": "Hongkong",
    "china pr": "Kína",
    "korea": "Dél-Korea",
    "palestine": "Palesztina",
    "iraq": "Irak",
    "oman": "Omán",
    "bahrain": "Bahrein",
    "syria": "Szíria",
    "albania": "Albánia",
    "montenegro": "Montenegró",
    "moldova": "Moldova"
}

def translate_text(text):
    if not text:
        return ""
    clean = str(text).replace("_", " ").strip()
    key = " ".join(clean.lower().split())
    aliases = {
        "bosnia-herzegovina": "bosnia and herzegovina",
        "bosnia & herzegovina": "bosnia and herzegovina",
        "korea republic": "south korea",
        "republic of korea": "south korea",
        "korea, republic of": "south korea",
        "uae": "united arab emirates",
        "czechia": "czechia",
        "czech republic": "czech republic",
        "saudiarabia": "saudi arabia",
    }
    key = aliases.get(key, key)
    return TRANSLATIONS.get(key, clean.title())

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

def _find_image_url(value):
    if isinstance(value, dict):
        preferred_keys = (
            "logo_url", "logo", "image_url", "image", "crest_url",
            "crest", "team_logo_url", "team_logo", "icon_url", "icon",
            "badge_url", "badge"
        )
        for key in preferred_keys:
            candidate = value.get(key)
            if isinstance(candidate, str) and candidate.strip().startswith(("http://", "https://")):
                return candidate.strip()
        for nested_key in ("team", "club", "data", "info"):
            found = _find_image_url(value.get(nested_key))
            if found:
                return found
    elif isinstance(value, list):
        for item in value:
            found = _find_image_url(item)
            if found:
                return found
    return None

def _country_code(raw_country):
    if not raw_country:
        return ""
    key = " ".join(str(raw_country).replace("_", " ").strip().lower().split())
    aliases = {
        "england": "gb", "scotland": "gb", "wales": "gb", "northern ireland": "gb",
        "spain": "es", "italy": "it", "germany": "de", "france": "fr", "hungary": "hu",
        "argentina": "ar", "armenia": "am", "australia": "au", "austria": "at",
        "belarus": "by", "belgium": "be", "bolivia": "bo", "bosnia and herzegovina": "ba",
        "bosnia & herzegovina": "ba", "brazil": "br", "brazilia": "br", "bulgaria": "bg",
        "canada": "ca", "chile": "cl", "china": "cn", "china pr": "cn", "colombia": "co",
        "costa rica": "cr", "croatia": "hr", "cyprus": "cy", "czechia": "cz",
        "czech republic": "cz", "denmark": "dk", "dominican republic": "do",
        "ecuador": "ec", "equador": "ec", "egypt": "eg", "el salvador": "sv",
        "estonia": "ee", "ethiopia": "et", "faroe islands": "fo", "fiji": "fj",
        "finland": "fi", "georgia": "ge", "ghana": "gh", "gibraltar": "gi",
        "greece": "gr", "guatemala": "gt", "honduras": "hn", "hong kong": "hk",
        "iceland": "is", "india": "in", "indonesia": "id", "iran": "ir", "iraq": "iq",
        "ireland": "ie", "israel": "il", "ivory coast": "ci", "cote d'ivoire": "ci",
        "jamaica": "jm", "japan": "jp", "jordan": "jo", "kazakhstan": "kz", "kenya": "ke",
        "korea": "kr", "korea republic": "kr", "kosovo": "xk", "kyrgyzstan": "kg",
        "kuwait": "kw", "latvia": "lv", "lebanon": "lb", "lithuania": "lt",
        "luxembourg": "lu", "malaysia": "my", "malta": "mt", "mauritius": "mu",
        "mexico": "mx", "moldova": "md", "montenegro": "me", "morocco": "ma",
        "mozambique": "mz", "netherlands": "nl", "new caledonia": "nc", "new zealand": "nz",
        "nicaragua": "ni", "nigeria": "ng", "north macedonia": "mk", "macedonia": "mk",
        "norway": "no", "oman": "om", "panama": "pa", "paraguay": "py", "peru": "pe",
        "philippines": "ph", "poland": "pl", "portugal": "pt", "qatar": "qa",
        "romania": "ro", "russia": "ru", "saudi arabia": "sa", "saudiarabia": "sa",
        "senegal": "sn", "serbia": "rs", "singapore": "sg", "slovakia": "sk",
        "slovenia": "si", "south africa": "za", "south korea": "kr", "south sudan": "ss",
        "sri lanka": "lk", "sweden": "se", "switzerland": "ch", "taiwan": "tw",
        "tanzania": "tz", "thailand": "th", "togo": "tg", "trinidad and tobago": "tt",
        "tunisia": "tn", "turkey": "tr", "uganda": "ug", "ukraine": "ua",
        "united arab emirates": "ae", "uae": "ae", "uruguay": "uy", "usa": "us",
        "uzbekistan": "uz", "venezuela": "ve", "vietnam": "vn", "world": "", "europe": "",
        "zambia": "zm", "zimbabwe": "zw"
    }
    return aliases.get(key, "")

def _hungarian_sort_key(value):
    text = str(value or "").strip().casefold()
    multigraphs = ("dzs", "cs", "dz", "gy", "ly", "ny", "sz", "ty", "zs")
    alphabet = {
        "a": 1, "á": 2, "b": 3, "c": 4, "cs": 5, "d": 6, "dz": 7, "dzs": 8,
        "e": 9, "é": 10, "f": 11, "g": 12, "gy": 13, "h": 14, "i": 15, "í": 16,
        "j": 17, "k": 18, "l": 19, "ly": 20, "m": 21, "n": 22, "ny": 23,
        "o": 24, "ó": 25, "ö": 26, "ő": 27, "p": 28, "q": 29, "r": 30,
        "s": 31, "sz": 32, "t": 33, "ty": 34, "u": 35, "ú": 36, "ü": 37, "ű": 38,
        "v": 39, "w": 40, "x": 41, "y": 42, "z": 43, "zs": 44,
    }
    tokens = []
    i = 0
    while i < len(text):
        matched = None
        for token in multigraphs:
            if text.startswith(token, i):
                matched = token
                break
        if matched is not None:
            tokens.append(alphabet[matched])
            i += len(matched)
            continue
        char = text[i]
        tokens.append(alphabet.get(char, 100 + ord(char)))
        i += 1
    return tuple(tokens)

def _get_team_id(team_data):
    if not isinstance(team_data, dict):
        return ""
    for key in ("id", "team_id", "teamId", "main_id"):
        value = team_data.get(key)
        if value is not None and str(value).strip():
            return str(value).strip()
    for nested_key in ("team", "data"):
        nested = team_data.get(nested_key)
        if isinstance(nested, dict):
            value = _get_team_id(nested)
            if value:
                return value
    return ""

def _team_image_url(team_id):
    if not team_id:
        return None
    return f"{IMAGE_PROXY_BASE_URL.rstrip('/')}/api/team-image/{team_id}"

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

_highlightly_refreshing = {"busy": False}


def _highlightly_fetch_pages_fast():
    """Max 2 gyors kérés párhuzamosan – ne fogja le a /api/matches-et."""
    if not HIGHLIGHTLY_KEY:
        return []
    base_url = "https://soccer.highlightly.net/highlights"
    headers = {"x-rapidapi-key": HIGHLIGHTLY_KEY}
    today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    yesterday = (datetime.now(timezone.utc) - timedelta(days=1)).strftime("%Y-%m-%d")

    def one(day, offset=0):
        try:
            resp = requests.get(
                base_url,
                headers=headers,
                params={"date": day, "limit": 40, "offset": offset},
                timeout=2.5,
            )
            if resp.status_code != 200:
                return []
            payload = resp.json()
            page = payload.get("data") if isinstance(payload, dict) else None
            return page if isinstance(page, list) else []
        except Exception:
            return []

    all_highlights = []
    with ThreadPoolExecutor(max_workers=2) as ex:
        futures = [ex.submit(one, today, 0), ex.submit(one, yesterday, 0)]
        for fut in futures:
            try:
                all_highlights.extend(fut.result())
            except Exception:
                pass

    seen = set()
    unique = []
    for hl in all_highlights:
        if not isinstance(hl, dict):
            continue
        hid = hl.get("id") or id(hl)
        if hid in seen:
            continue
        seen.add(hid)
        unique.append(hl)
    return unique


def _refresh_highlightly_background():
    if _highlightly_refreshing["busy"]:
        return
    _highlightly_refreshing["busy"] = True
    try:
        data = _highlightly_fetch_pages_fast()
        # Üres választ ne írjuk felül a jó cache-re
        if data or _highlightly_cache["data"] is None:
            _highlightly_cache["data"] = data
            _highlightly_cache["ts"] = time.time()
    finally:
        _highlightly_refreshing["busy"] = False


def fetch_highlightly_highlights():
    """
    Gyors útvonal a meccslistához:
    - ha van cache (akár lejárt), azonnal visszaadja
    - háttérben frissít, nem blokkol 10-30 másodpercet
    """
    if not HIGHLIGHTLY_KEY:
        return []
    now = time.time()
    cached = _highlightly_cache.get("data")
    ts = float(_highlightly_cache.get("ts") or 0)

    # Friss cache
    if cached is not None and (now - ts) < HIGHLIGHTLY_CACHE_TTL:
        return cached

    # Van régi cache → azonnal vissza, háttérfrissítés
    if cached is not None:
        if not _highlightly_refreshing["busy"]:
            ThreadPoolExecutor(max_workers=1).submit(_refresh_highlightly_background)
        return cached

    # Első indítás: egy rövid próbálkozás, max ~2.5s
    data = _highlightly_fetch_pages_fast()
    _highlightly_cache["data"] = data
    _highlightly_cache["ts"] = now
    return data

def fetch_highlightly_match_highlights(highlight_match_id: str):
    if not HIGHLIGHTLY_KEY or not highlight_match_id:
        return []
    requested_id = str(highlight_match_id).strip()
    if not requested_id:
        return []
    cache_key = requested_id
    now = time.time()
    cached = _highlightly_match_cache.get(cache_key)
    if cached and (now - cached["ts"]) < HIGHLIGHTLY_CACHE_TTL:
        return cached["data"]

    all_highlights = fetch_highlightly_highlights()
    matched_highlights = []
    for highlight in all_highlights:
        if not isinstance(highlight, dict):
            continue
        match_obj = highlight.get("match") or {}
        if not isinstance(match_obj, dict):
            continue
        raw_match_id = match_obj.get("id")
        if raw_match_id is None:
            continue
        if str(raw_match_id).strip() != requested_id:
            continue
        embed_url = highlight.get("embedUrl")
        normal_url = highlight.get("url")
        if (isinstance(embed_url, str) and embed_url.strip()) or (isinstance(normal_url, str) and normal_url.strip()):
            matched_highlights.append(highlight)

    _highlightly_match_cache[cache_key] = {"data": matched_highlights, "ts": now}
    return matched_highlights

def _highlightly_video_payload(highlight: dict):
    return {
        "id": str(highlight.get("id", "")),
        "title": highlight.get("title"),
        "description": highlight.get("description"),
        "embedUrl": highlight.get("embedUrl"),
        "url": highlight.get("url"),
        "category": highlight.get("category"),
        "source": highlight.get("source"),
        "imgUrl": highlight.get("imgUrl")
    }

@app.get("/api/ai-analysis/{match_id}")
def get_ai_analysis(match_id: str):
    """
    Prémium AI elemzés – közvetlen Gemini REST API (stabilabb Renderen, mint az SDK).
    """
    if not GEMINI_KEY:
        return {"analysis": "Az AI elemző modul jelenleg nem érhető el (Hiányzó API kulcs)."}

    try:
        matches = get_matches()
    except Exception as e:
        print(f"[AI] get_matches failed: {e}")
        return {"analysis": "A mérkőzéslista ideiglenesen nem érhető el. Próbáld újra."}

    target_match = next((m for m in matches if str(m.get("id")) == str(match_id)), None)
    if not target_match:
        return {"analysis": "A mérkőzés adatai nem találhatók az AI elemzéshez."}

    raw_status = str(target_match.get("status") or "").strip()
    minute = target_match.get("minute") or 0
    try:
        minute = int(minute)
    except (TypeError, ValueError):
        minute = 0

    home_score = target_match.get("home_score")
    away_score = target_match.get("away_score")
    score_txt = f"{home_score if home_score is not None else 0}-{away_score if away_score is not None else 0}"
    home = target_match.get("home_team") or "Hazai"
    away = target_match.get("away_team") or "Vendég"
    league = target_match.get("league") or "Ismeretlen bajnokság"
    country = target_match.get("country") or ""

    status_upper = raw_status.upper()
    looks_like_kickoff_time = bool(
        len(raw_status) <= 5 and ":" in raw_status
        and status_upper not in {"HT", "FT", "1H", "2H", "NS", "LIVE"}
    )

    if status_upper in {"FT", "AET", "PEN", "FINISHED"}:
        phase = "finished"
        match_phase = "A mérkőzés VÉGET ért."
        score_line = f"Végeredmény: {score_txt}"
        task = (
            f"Utólagos értékelés, végeredmény: {score_txt}. "
            "Mi döntött, melyik csapat érdemelte, rövid szakmai konklúzió. "
            "NE írd, hogy a meccs még tart."
        )
    elif status_upper == "HT":
        phase = "halftime"
        match_phase = "A mérkőzés FÉLIDŐBEN van."
        score_line = f"Állás félidőben: {score_txt}"
        task = (
            f"Félidős értékelés, állás: {score_txt}. "
            "Első játékrész tanulságai, várható második félidő."
        )
    elif status_upper in {"1H", "2H", "LIVE", "ET", "P"} or (minute > 0 and not looks_like_kickoff_time):
        phase = "live"
        match_phase = f"A mérkőzés ÉLŐBEN zajlik, kb. a {minute}. percben."
        score_line = f"Jelenlegi állás: {score_txt} ({minute}. perc)"
        task = (
            f"Élő elemzés a {minute}. perc környékén, állás: {score_txt}. "
            "Ki dominál, mi változhat. NE írd, hogy a meccs nem kezdődött el."
        )
    else:
        phase = "preview"
        kickoff = raw_status if looks_like_kickoff_time else (raw_status or "ismeretlen")
        match_phase = f"A mérkőzés MÉG NEM kezdődött el. Tervezett kezdés: {kickoff}."
        score_line = "Állás: még nincs (kezdés előtt)"
        task = (
            "ELŐZETES elemzés – a meccs még NEM kezdődött el. "
            "Tilos perc/félidő/élő állás említése. Várható stílus, esélyek, kulcsmomentum."
        )

    cache_key = f"{match_id}|{phase}|{raw_status}|{minute}|{score_txt}"
    now = time.time()
    cached = _ai_analysis_cache.get(cache_key)
    if cached and (now - cached["ts"]) < AI_CACHE_TTL:
        return {"analysis": cached["data"]}

    prompt = f"""Te a SportApp vezető labdarúgó-szakértője vagy. Magyarul írj, élvezetesen, szakmailag hitelesen.

MÉRKŐZÉS:
- Bajnokság: {league}
- Ország: {country}
- {home} vs {away}
- Állapot: {match_phase}
- {score_line}

FELADAT: {task}

FORMÁTUM:
1) Rövid felvezetés (1 mondat)
2) Játékkép / kulcsmozzanatok (2-3 mondat)
3) Esélyek vagy konklúzió (1-2 mondat)
4) Ütős záró mondat

Szabályok: 5-8 mondat, konkrét csapatnevek, ne markdown címsorok, ne sablonos frázisok.
"""

    # Közvetlen REST – nem függ a google-genai SDK verziójától
    models = [
        "gemini-2.5-flash-lite",
        "gemini-2.5-flash",
        "gemini-2.0-flash",
    ]
    last_error = None

    for model_name in models:
        try:
            url = (
                "https://generativelanguage.googleapis.com/v1beta/models/"
                f"{model_name}:generateContent?key={GEMINI_KEY}"
            )
            payload = {
                "contents": [{"parts": [{"text": prompt}]}],
                "generationConfig": {
                    "temperature": 0.8,
                    "maxOutputTokens": 512,
                },
            }
            resp = requests.post(url, json=payload, timeout=20)
            if resp.status_code != 200:
                last_error = f"{model_name}: HTTP {resp.status_code} {resp.text[:200]}"
                print(f"[AI ERROR] {last_error}")
                # rossz modell / 404 → következő
                if resp.status_code in (404, 400):
                    continue
                if resp.status_code in (401, 403):
                    return {"analysis": "Az AI API kulcs érvénytelen vagy nincs jogosultsága."}
                if resp.status_code == 429:
                    return {"analysis": "Az AI kvóta ideiglenesen elfogyott. Próbáld újra pár perc múlva."}
                continue

            data = resp.json()
            # candidates[0].content.parts[0].text
            text_out = ""
            for cand in data.get("candidates") or []:
                content = cand.get("content") or {}
                for part in content.get("parts") or []:
                    if isinstance(part.get("text"), str):
                        text_out += part["text"]
            text_out = text_out.strip()
            if not text_out:
                last_error = f"{model_name}: empty response body={str(data)[:200]}"
                continue

            _ai_analysis_cache[cache_key] = {"data": text_out, "ts": now}
            print(f"[AI OK] match={match_id} model={model_name} phase={phase}")
            return {"analysis": text_out}
        except Exception as e:
            last_error = f"{model_name}: {e}"
            print(f"[AI ERROR] {last_error}")
            continue

    print(f"[AI FAIL] match={match_id} last={last_error}")
    return {
        "analysis": "Az AI elemzés most nem sikerült. Próbáld újra – a Gemini szolgáltatás átmenetileg lassú vagy terhelt lehet."
    }


@app.get("/api/highlights/match/{highlight_match_id}")
def get_match_highlights(highlight_match_id: str):
    if not HIGHLIGHTLY_KEY or not highlight_match_id:
        return []
    highlights = fetch_highlightly_match_highlights(highlight_match_id)
    result = []
    for highlight in highlights:
        if not isinstance(highlight, dict):
            continue
        item = _highlightly_video_payload(highlight)
        if item["id"] and (item["embedUrl"] or item["url"]):
            result.append(item)
    result.sort(key=lambda item: (not str(item.get("category") or "").lower() == "goal-clip", str(item.get("title") or "").lower()))
    return result

@app.get("/api/team-image/{team_id}")
def get_team_image(team_id: str):
    if not STATPAL_KEY or not team_id or not str(team_id).isdigit():
        return Response(status_code=404)
    cache_key = str(team_id)
    now = time.time()
    cached = _team_image_cache.get(cache_key)
    if cached and (now - cached["ts"]) < TEAM_IMAGE_CACHE_TTL:
        return Response(content=cached["content"], media_type="image/png", headers={"Cache-Control": "public, max-age=21600"})
    try:
        url = "https://statpal.io/api/v2/soccer/images"
        response = requests.get(url, params={"type": "team", "id": team_id, "access_key": STATPAL_KEY}, headers={"Accept": "image/png, application/json"}, timeout=10, allow_redirects=True)
        if response.status_code != 200 or not response.content:
            return Response(status_code=response.status_code or 404)
        content_type = response.headers.get("content-type", "image/png").split(";")[0].strip()
        if content_type != "image/png":
            content_type = "image/png"
        _team_image_cache[cache_key] = {"content": response.content, "ts": now}
        return Response(content=response.content, media_type=content_type, headers={"Cache-Control": "public, max-age=21600"})
    except Exception:
        return Response(status_code=404)

def _normalize_team_name(name: str) -> str:
    """Csapatnév normalizálása párosításhoz."""
    if not name:
        return ""
    s = str(name).lower()
    # ékezetek egyszerűsítése
    for a, b in (
        ("á", "a"), ("é", "e"), ("í", "i"), ("ó", "o"), ("ö", "o"), ("ő", "o"),
        ("ú", "u"), ("ü", "u"), ("ű", "u"), ("ç", "c"), ("ñ", "n"),
    ):
        s = s.replace(a, b)
    # gyakori toldalékok / zaj
    for token in (
        " fc", " cf", " sc", " afc", " united", " city", " club",
        " reserve", " reserves", " u21", " u23", " u19", " ii", " 2",
    ):
        s = s.replace(token, " ")
    s = "".join(ch if ch.isalnum() or ch.isspace() else " " for ch in s)
    return " ".join(s.split())


def _team_tokens(name: str) -> set:
    stop = {"fc", "cf", "sc", "afc", "the", "de", "la", "el", "and", "vs"}
    return {t for t in _normalize_team_name(name).split() if len(t) > 2 and t not in stop}


def _build_highlight_match_index(highlights_data):
    """
    Index több kulccsal:
    - pontos normalizált névpár
    - fordított sorrend
    - első jelentős token pár (lazább illesztés)
    """
    index = {}
    fuzzy = []  # (home_tokens, away_tokens, payload)
    if not isinstance(highlights_data, list):
        return index, fuzzy
    for hl in highlights_data:
        if not isinstance(hl, dict):
            continue
        match_obj = hl.get("match") or {}
        if not isinstance(match_obj, dict):
            continue
        home_obj = match_obj.get("homeTeam") or match_obj.get("home") or {}
        away_obj = match_obj.get("awayTeam") or match_obj.get("away") or {}
        if not isinstance(home_obj, dict):
            home_obj = {}
        if not isinstance(away_obj, dict):
            away_obj = {}
        home_raw = home_obj.get("name") or home_obj.get("team_name") or ""
        away_raw = away_obj.get("name") or away_obj.get("team_name") or ""
        home = _normalize_team_name(home_raw)
        away = _normalize_team_name(away_raw)
        if not home or not away:
            continue
        match_id = match_obj.get("id") or match_obj.get("match_id")
        if match_id is None:
            continue
        video_url = hl.get("embedUrl") or hl.get("url")
        if not video_url:
            continue
        payload = {
            "match_id": str(match_id),
            "url": video_url,
            "home": home_raw,
            "away": away_raw,
        }
        index.setdefault((home, away), payload)
        index.setdefault((away, home), payload)
        # első tokenes kulcs
        ht = home.split()[0] if home.split() else home
        at = away.split()[0] if away.split() else away
        if ht and at:
            index.setdefault((ht, at), payload)
            index.setdefault((at, ht), payload)
        fuzzy.append((_team_tokens(home_raw), _team_tokens(away_raw), payload))
    return index, fuzzy


def _lookup_highlight(home_name: str, away_name: str, index, fuzzy):
    """Pontos, majd token-alapú fuzzy keresés."""
    home = _normalize_team_name(home_name)
    away = _normalize_team_name(away_name)
    if not home or not away:
        return None

    exact = index.get((home, away)) or index.get((away, home))
    if exact:
        return exact

    ht = home.split()[0] if home.split() else home
    at = away.split()[0] if away.split() else away
    token_hit = index.get((ht, at)) or index.get((at, ht))
    if token_hit:
        return token_hit

    home_toks = _team_tokens(home_name)
    away_toks = _team_tokens(away_name)
    if not home_toks or not away_toks:
        return None

    best = None
    best_score = 0
    for fh, fa, payload in fuzzy:
        # mindkét oldalon legyen átfedés
        sh = len(home_toks & fh)
        sa = len(away_toks & fa)
        # fordított hazai/vendég is
        sh2 = len(home_toks & fa)
        sa2 = len(away_toks & fh)
        score = max(
            (sh + sa) if sh and sa else 0,
            (sh2 + sa2) if sh2 and sa2 else 0,
        )
        if score > best_score:
            best_score = score
            best = payload
    # legalább 2 token egyezés összesen
    if best_score >= 2:
        return best
    return None

@app.get("/api/matches")
def get_matches():
    if not STATPAL_KEY:
        return [{"id": "0", "league_id": "0", "league": "Hiba", "home_team": "StatPal Kulcs Hiányzik", "away_team": "Render Environment-ben", "home_score": 0, "away_score": 0, "status": "error", "minute": 0}]
    try:
        with ThreadPoolExecutor(max_workers=2) as executor:
            statpal_future = executor.submit(fetch_statpal_matches)
            highlights_future = executor.submit(fetch_highlightly_highlights)
            data = statpal_future.result()
            # Highlightly max 3 mp – utána üres/cache, ne akassza meg a listát
            try:
                highlights_data = highlights_future.result(timeout=3)
            except Exception:
                highlights_data = _highlightly_cache.get("data") or []

        matches_list = []
        live_matches_data = data.get("live_matches") or data.get("matches") or {}
        if not isinstance(live_matches_data, dict):
            live_matches_data = {}

        leagues = ensure_list(live_matches_data.get("league"))
        highlight_index, highlight_fuzzy = _build_highlight_match_index(highlights_data)

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
                home_team_id = _get_team_id(home_data)
                away_team_id = _get_team_id(away_data)
                home_logo_url = _team_image_url(home_team_id)
                away_logo_url = _team_image_url(away_team_id)

                highlight_url = None
                highlight_match_id = None
                # Nyers + fordított név is (translate előtt/után)
                home_raw_name = home_data.get("name", "") or home_name
                away_raw_name = away_data.get("name", "") or away_name
                exact_match = (
                    _lookup_highlight(home_name, away_name, highlight_index, highlight_fuzzy)
                    or _lookup_highlight(home_raw_name, away_raw_name, highlight_index, highlight_fuzzy)
                )
                if exact_match is not None:
                    highlight_match_id = exact_match.get("match_id")
                    highlight_url = exact_match.get("url")

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
                    "country": translate_text(raw_country),
                    "country_code": _country_code(raw_country),
                    "league_logo_url": None,
                    "home_team": home_name,
                    "away_team": away_name,
                    "home_logo_url": home_logo_url,
                    "away_logo_url": away_logo_url,
                    "home_score": home_score,
                    "away_score": away_score,
                    "status": adjusted_status,
                    "minute": minute_val,
                    "highlight_url": highlight_url,
                    "highlight_match_id": highlight_match_id,
                    "value_bet": True if m.get("inplay_odds_running") == "True" else False
                })

        if not matches_list:
            return [{"id": "0", "league_id": "0", "league": "Információ", "home_team": "Jelenleg nincs", "away_team": "aktív mérkőzés", "home_score": None, "away_score": None, "status": "info", "minute": 0}]

        def get_league_sort_key(item):
            league_title = str(item.get("league") or "").strip()
            if league_title in TOP_LEAGUES_ORDER:
                return (0, TOP_LEAGUES_ORDER.index(league_title))
            return (1, _hungarian_sort_key(league_title))

        matches_list.sort(key=get_league_sort_key)
        return matches_list

    except Exception as e:
        return [{"id": "err", "league_id": "0", "league": "Szerver hiba", "home_team": "API Hiba", "away_team": str(e)[:20], "home_score": None, "away_score": None, "status": "error", "minute": 0}]

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
        return {"cached": True, "age_seconds": round(age, 1), "ttl_seconds": ttl, "still_valid": age < ttl}

    return {
        "statpal": cache_info(_statpal_cache, STATPAL_CACHE_TTL),
        "highlightly": cache_info(_highlightly_cache, HIGHLIGHTLY_CACHE_TTL),
        "highlightly_count": len(_highlightly_cache["data"] or []) if _highlightly_cache["data"] else 0,
        "highlightly_match_cache_count": len(_highlightly_match_cache),
        "ai_cache_count": len(_ai_analysis_cache)
    }
