from fastapi import FastAPI
from fastapi.responses import Response
import requests
import re
import os
import time
from datetime import datetime, timezone, timedelta
from typing import Optional
from concurrent.futures import ThreadPoolExecutor, as_completed

app = FastAPI()

STATPAL_KEY = os.getenv("STATPAL_KEY")
HIGHLIGHTLY_KEY = os.getenv("HIGHLIGHTLY_KEY")
GEMINI_KEY = os.getenv("GEMINI_KEY") or os.getenv("GOOGLE_API_KEY")

STATPAL_CACHE_TTL = 18
HIGHLIGHTLY_CACHE_TTL = 90
TEAM_IMAGE_CACHE_TTL = 21600
IMAGE_PROXY_BASE_URL = os.getenv(
    "IMAGE_PROXY_BASE_URL",
    "https://sportapp-android.onrender.com"
)

_statpal_cache = {"data": None, "ts": 0.0}
_highlightly_cache = {"data": None, "ts": 0.0}
_team_image_cache = {}
_highlightly_match_cache = {}


# =============================================================================
# JÁTÉKOSFOTÓ CACHE (Upstash Redis) – tartós, szerver-újraindítást túlélő
# =============================================================================
#
# A cél: egy adott Highlightly playerId fotó-URL-jét CSAK EGYSZER kérdezzük
# le a Highlightly /players/{id}/statistics végpontjáról, utána tartósan
# (Upstash Redis-ben) tároljuk. Ez a Football Futár 2 nevű, KÜLÖN Android
# alkalmazás Kezdő11 pályaképéhez kell -- a SportApp saját funkcióit
# (StatPal meccsadatok, FCM stb.) ez a blokk nem érinti.
#
# Az Upstash Redis "REST API"-ját natív requests-hívásokkal érjük el,
# hogy ne kelljen új Python-csomagot telepíteni.

UPSTASH_REDIS_REST_URL = os.getenv("UPSTASH_REDIS_REST_URL", "").rstrip("/")
UPSTASH_REDIS_REST_TOKEN = os.getenv("UPSTASH_REDIS_REST_TOKEN", "")

# Ha egyszer megvan egy játékos fotó-URL-je, az ritkán változik --
# 30 napig tartjuk, utána automatikusan frissül a következő kérésnél.
PLAYER_PHOTO_CACHE_TTL_SECONDS = 30 * 24 * 60 * 60

# Rövid életű, csak a szerver éber állapotában élő "gyorsítótár a
# gyorsítótár elé" -- ha egy percen belül többször kérik ugyanazt a
# játékost, ne terheljük feleslegesen a Redis-t sem.
_player_photo_memory_cache = {}
_PLAYER_PHOTO_MEMORY_TTL = 300


def _redis_available():
    return bool(UPSTASH_REDIS_REST_URL and UPSTASH_REDIS_REST_TOKEN)


def _redis_get(key: str):
    if not _redis_available():
        return None

    try:
        response = requests.get(
            f"{UPSTASH_REDIS_REST_URL}/get/{key}",
            headers={
                "Authorization": f"Bearer {UPSTASH_REDIS_REST_TOKEN}"
            },
            timeout=5
        )

        if response.status_code != 200:
            return None

        payload = response.json()

        return payload.get("result")

    except Exception:
        return None


def _redis_set(key: str, value: str, ex_seconds: int):
    if not _redis_available():
        return False

    try:
        response = requests.post(
            f"{UPSTASH_REDIS_REST_URL}/set/{key}",
            headers={
                "Authorization": f"Bearer {UPSTASH_REDIS_REST_TOKEN}"
            },
            json=[value, "EX", ex_seconds],
            timeout=5
        )

        return response.status_code == 200

    except Exception:
        return False


def fetch_player_photo_url(player_id: str):
    """
    Egy adott Highlightly playerId fotó-URL-jét adja vissza.

    Sorrend:
      1. Rövid memóriás cache (ugyanaz a szerverfolyamat, elmúlt 5 perc)
      2. Tartós Upstash Redis cache (túléli az újraindítást)
      3. Élő Highlightly /players/{id}/statistics hívás -- CSAK ha az
         előző kettőben nincs találat.
    """

    if not player_id or not HIGHLIGHTLY_KEY:
        return None

    player_id = str(player_id).strip()

    if not player_id:
        return None

    now = time.time()

    mem_hit = _player_photo_memory_cache.get(player_id)

    if mem_hit and (now - mem_hit["ts"]) < _PLAYER_PHOTO_MEMORY_TTL:
        return mem_hit["url"]

    redis_key = f"player_photo:{player_id}"

    cached_url = _redis_get(redis_key)

    if cached_url:
        _player_photo_memory_cache[player_id] = {
            "url": cached_url,
            "ts": now
        }
        return cached_url

    try:
        response = requests.get(
            f"https://soccer.highlightly.net/players/{player_id}/statistics",
            headers={
                "x-rapidapi-key": HIGHLIGHTLY_KEY
            },
            timeout=8
        )

        if response.status_code != 200:
            return None

        payload = response.json()

        entry = (
            payload[0]
            if isinstance(payload, list) and payload
            else payload if isinstance(payload, dict)
            else None
        )

        if not isinstance(entry, dict):
            return None

        logo_url = entry.get("logo")

        if (
            not isinstance(logo_url, str)
            or not logo_url.strip()
            or not logo_url.startswith(("http://", "https://"))
        ):
            return None

        logo_url = logo_url.strip()

        _redis_set(redis_key, logo_url, PLAYER_PHOTO_CACHE_TTL_SECONDS)

        _player_photo_memory_cache[player_id] = {
            "url": logo_url,
            "ts": now
        }

        return logo_url

    except Exception:
        return None


_detail_cache = {}
_lineups_cache = {}
_stats_cache = {}
_hl_date_cache = {}
_matches_list_cache = {"data": None, "ts": 0}
MATCHES_LIST_TTL = 15
_hl_h2h_cache = {}
_hl_form_cache = {}
_odds_cache_hits = 0
_odds_cache_misses = 0
DETAIL_CACHE_TTL = 20
LINEUPS_CACHE_TTL = 120
STATS_CACHE_TTL = 30



def _name_tokens(name: str):
    """Csapatnév tokenek párosításhoz (rövid/hosszú nevek)."""
    if not name:
        return set()
    stop = {
        "fc", "cf", "sc", "ac", "as", "afc", "fk", "sk", "nk", "bk", "if",
        "the", "de", "fc.", "u19", "u21", "ii", "b", "women", "w",
        "united", "city", "town", "hotspur", "wanderers", "athletic",
        "club", "sporting", "real", "sport", "calcio",
    }
    parts = "".join(ch if ch.isalnum() or ch.isspace() else " " for ch in name.lower()).split()
    tokens = {p for p in parts if len(p) >= 3 and p not in stop}
    if not tokens and parts:
        tokens = {parts[-1]}
    return tokens


def _strip_accents(s: str) -> str:
    repl = {
        "á": "a", "é": "e", "í": "i", "ó": "o", "ö": "o", "ő": "o",
        "ú": "u", "ü": "u", "ű": "u", "Á": "a", "É": "e", "Í": "i",
        "Ó": "o", "Ö": "o", "Ő": "o", "Ú": "u", "Ü": "u", "Ű": "u",
    }
    out = []
    for ch in s or "":
        out.append(repl.get(ch, ch))
    return "".join(out)


def _teams_soft_match(a: str, b: str) -> bool:
    a = _strip_accents((a or "").lower().strip())
    b = _strip_accents((b or "").lower().strip())
    if not a or not b:
        return False
    # teljes / prefix
    if a == b or a in b or b in a:
        return True
    ta, tb = _name_tokens(a), _name_tokens(b)
    if not ta or not tb:
        # token nélkül: első 4 betű egyezés
        return len(a) >= 4 and len(b) >= 4 and (a[:4] == b[:4])
    if ta & tb:
        return True
    for x in ta:
        for y in tb:
            if x in y or y in x:
                return True
            if len(x) >= 4 and len(y) >= 4 and x[:4] == y[:4]:
                return True
    return False


def _extract_odds(raw: dict):
    """StatPal odds mezők – több lehetséges kulcsnév."""
    if not isinstance(raw, dict):
        return None, None, None
    candidates = [
        ("odds_home", "odds_draw", "odds_away"),
        ("odd_1", "odd_x", "odd_2"),
        ("home_od", "draw_od", "away_od"),
        ("o1", "ox", "o2"),
    ]
    # nested odds object
    odds_obj = raw.get("odds") or raw.get("inplay_odds") or {}
    if isinstance(odds_obj, dict):
        for h, d, a in candidates:
            hv, dv, av = odds_obj.get(h), odds_obj.get(d), odds_obj.get(a)
            if hv is not None or dv is not None or av is not None:
                try:
                    return (
                        float(hv) if hv is not None else None,
                        float(dv) if dv is not None else None,
                        float(av) if av is not None else None,
                    )
                except Exception:
                    pass
        # common nested: home/draw/away
        try:
            h = odds_obj.get("home") or odds_obj.get("1")
            d = odds_obj.get("draw") or odds_obj.get("x") or odds_obj.get("X")
            a = odds_obj.get("away") or odds_obj.get("2")
            if h is not None or d is not None or a is not None:
                return (
                    float(h) if h is not None else None,
                    float(d) if d is not None else None,
                    float(a) if a is not None else None,
                )
        except Exception:
            pass
    for h, d, a in candidates:
        hv, dv, av = raw.get(h), raw.get(d), raw.get(a)
        if hv is not None or dv is not None or av is not None:
            try:
                return (
                    float(hv) if hv is not None else None,
                    float(dv) if dv is not None else None,
                    float(av) if av is not None else None,
                )
            except Exception:
                pass
    return None, None, None


def _normalize_statpal_events(events):
    """StatPal event list -> egységes, mobilbarát formátum."""
    result = []
    for ev in events or []:
        if not isinstance(ev, dict):
            continue
        raw_type = str(ev.get("type") or "").strip().lower()
        team = str(ev.get("team") or "").strip().lower()
        if team not in ("home", "away"):
            # néha "1"/"2" vagy csapatnév jön
            if team in ("1", "h"):
                team = "home"
            elif team in ("2", "a"):
                team = "away"
        minute = ev.get("minute")
        try:
            minute_int = int(str(minute).replace("'", "").split("+")[0]) if minute is not None else None
        except Exception:
            minute_int = None
        minute_display = str(minute).strip() if minute is not None else ""
        player = ev.get("player") or ev.get("player_name") or ""
        assist = ev.get("assist_player") or ev.get("assist") or ""
        result_score = ev.get("result") or ""

        # típus normalizálás
        if "goal" in raw_type and "own" in raw_type:
            norm_type = "own_goal"
        elif "goal" in raw_type:
            norm_type = "goal"
        elif "yellow" in raw_type:
            norm_type = "yellowcard"
        elif "red" in raw_type:
            norm_type = "redcard"
        elif "sub" in raw_type:
            norm_type = "substitution"
        elif "var" in raw_type:
            norm_type = "var"
        elif "pen" in raw_type and "miss" in raw_type:
            norm_type = "missed_penalty"
        elif "pen" in raw_type:
            norm_type = "penalty"
        else:
            norm_type = raw_type or "event"

        result.append({
            "type": norm_type,
            "team": team if team in ("home", "away") else "",
            "minute": minute_int,
            "minute_display": minute_display,
            "player": str(player).strip() if player else None,
            "assist": str(assist).strip() if assist else None,
            "result": str(result_score).strip() if result_score else None,
        })
    return result


def _find_statpal_raw_match(match_id: str):
    """StatPal cache-ből megkeresi a raw meccset main_id alapján."""
    data = fetch_statpal_matches()
    if not isinstance(data, dict):
        return None, None
    live_matches_data = (
        data.get("live_matches")
        or data.get("matches")
        or {}
    )
    if not isinstance(live_matches_data, dict):
        return None, None
    leagues = ensure_list(live_matches_data.get("league"))
    target = str(match_id).strip()
    for league in leagues:
        if not isinstance(league, dict):
            continue
        for m in ensure_list(league.get("match")):
            if not isinstance(m, dict):
                continue
            mid = str(m.get("main_id") or "").strip()
            if mid == target:
                return m, league
    return None, None


def _highlightly_headers(rapidapi_host: bool = False):
    h = {"x-rapidapi-key": HIGHLIGHTLY_KEY}
    if rapidapi_host:
        h["x-rapidapi-host"] = "football-highlights-api.p.rapidapi.com"
    return h


def _normalize_date_str(raw) -> str:
    """Bármilyen dátum → YYYY-MM-DD. StatPal: 23.12.2025 vagy 2025-12-23."""
    if raw is None:
        return datetime.now().strftime("%Y-%m-%d")
    s = str(raw).strip()
    if not s:
        return datetime.now().strftime("%Y-%m-%d")
    # ISO
    if len(s) >= 10 and s[4] == "-" and s[7] == "-":
        return s[:10]
    # dd.MM.yyyy
    if "." in s:
        parts = s.replace("/", ".").split(".")
        if len(parts) >= 3:
            d, m, y = parts[0], parts[1], parts[2]
            try:
                return f"{int(y):04d}-{int(m):02d}-{int(d):02d}"
            except Exception:
                pass
    # dd/MM/yyyy
    if "/" in s:
        parts = s.split("/")
        if len(parts) >= 3:
            try:
                return f"{int(parts[2]):04d}-{int(parts[1]):02d}-{int(parts[0]):02d}"
            except Exception:
                pass
    return datetime.now().strftime("%Y-%m-%d")


def fetch_highlightly_matches_by_date(date_iso: str, limit: int = 100):
    """Highlightly GET /matches?date= – lapozva, max ~500 meccs."""
    if not HIGHLIGHTLY_KEY or not date_iso:
        return []
    cache_key = date_iso.strip()[:10]
    now = time.time()
    cached = _hl_date_cache.get(cache_key)
    if cached and (now - cached["ts"]) < 90:
        return cached["data"]

    all_data = []
    page_size = min(int(limit) if limit else 100, 100)
    try:
        def _fetch_page(page: int):
            offset = page * page_size
            resp = requests.get(
                "https://soccer.highlightly.net/matches",
                headers=_highlightly_headers(),
                params={
                    "date": cache_key,
                    "timezone": "Europe/Budapest",
                    "limit": page_size,
                    "offset": offset,
                },
                timeout=8,
            )
            if resp.status_code != 200:
                return page, []
            payload = resp.json()
            if isinstance(payload, dict):
                data = payload.get("data") or []
            elif isinstance(payload, list):
                data = payload
            else:
                data = []
            return page, data if isinstance(data, list) else []

        # Párhuzamos lapozás (max 8 oldal) – ugyanaz a lefedettség, kevesebb várakozás
        pages = {}
        with ThreadPoolExecutor(max_workers=4) as pool:
            futs = [pool.submit(_fetch_page, p) for p in range(0, 8)]
            for fut in as_completed(futs):
                try:
                    page, data = fut.result()
                    pages[page] = data
                except Exception:
                    pass
        for page in sorted(pages.keys()):
            data = pages[page]
            if not data:
                break
            all_data.extend(data)
            if len(data) < page_size:
                # további oldalak lehetnek üresek – továbbra is gyűjtünk, ami megjött
                pass
        _hl_date_cache[cache_key] = {"data": all_data, "ts": now}
        return all_data
    except Exception:
        if all_data:
            _hl_date_cache[cache_key] = {"data": all_data, "ts": now}
            return all_data
        if cached:
            return cached["data"]
        return []


def _build_hl_id_lookup(hl_matches: list) -> dict:
    """Csapatnév-pár → Highlightly match id (gyors lookup a listaépítéshez)."""
    lookup = {}
    for m in hl_matches or []:
        if not isinstance(m, dict):
            continue
        mid = m.get("id")
        if mid is None:
            continue
        ht = m.get("homeTeam") if isinstance(m.get("homeTeam"), dict) else {}
        at = m.get("awayTeam") if isinstance(m.get("awayTeam"), dict) else {}
        nh = " ".join(str(ht.get("name") or "").lower().split())
        na = " ".join(str(at.get("name") or "").lower().split())
        if not nh or not na:
            continue
        lookup[(nh, na)] = str(mid)
        lookup[(na, nh)] = str(mid)
    return lookup


def _lookup_hl_id(lookup: dict, home_name: str, away_name: str):
    if not lookup:
        return None
    nh = " ".join((home_name or "").lower().split())
    na = " ".join((away_name or "").lower().split())
    return lookup.get((nh, na))




def fetch_highlightly_h2h(team_id_one, team_id_two):
    """Highlightly GET /head-2-head?teamIdOne=&teamIdTwo="""
    if not HIGHLIGHTLY_KEY or not team_id_one or not team_id_two:
        return []
    a, b = str(team_id_one), str(team_id_two)
    cache_key = f"{a}:{b}"
    now = time.time()
    cached = _hl_h2h_cache.get(cache_key)
    if cached and (now - cached["ts"]) < 300:
        return cached["data"]
    try:
        resp = requests.get(
            "https://soccer.highlightly.net/head-2-head",
            headers=_highlightly_headers(),
            params={"teamIdOne": a, "teamIdTwo": b},
            timeout=10,
        )
        if resp.status_code != 200:
            return []
        payload = resp.json()
        data = payload if isinstance(payload, list) else (payload.get("data") if isinstance(payload, dict) else [])
        if not isinstance(data, list):
            data = []
        _hl_h2h_cache[cache_key] = {"data": data, "ts": now}
        return data
    except Exception:
        return []


def fetch_highlightly_last_five(team_id):
    """Highlightly GET /last-five-games?teamId="""
    if not HIGHLIGHTLY_KEY or not team_id:
        return []
    cache_key = str(team_id)
    now = time.time()
    cached = _hl_form_cache.get(cache_key)
    if cached and (now - cached["ts"]) < 300:
        return cached["data"]
    try:
        resp = requests.get(
            "https://soccer.highlightly.net/last-five-games",
            headers=_highlightly_headers(),
            params={"teamId": cache_key},
            timeout=10,
        )
        if resp.status_code != 200:
            return []
        payload = resp.json()
        data = payload if isinstance(payload, list) else (payload.get("data") if isinstance(payload, dict) else [])
        if not isinstance(data, list):
            data = []
        _hl_form_cache[cache_key] = {"data": data, "ts": now}
        return data
    except Exception:
        return []


def _normalize_hl_match_item(m: dict) -> dict:
    """Highlightly match → app MatchResponse-szerű dict."""
    if not isinstance(m, dict):
        return {}
    home = m.get("homeTeam") if isinstance(m.get("homeTeam"), dict) else {}
    away = m.get("awayTeam") if isinstance(m.get("awayTeam"), dict) else {}
    league = m.get("league") if isinstance(m.get("league"), dict) else {}
    country = m.get("country") if isinstance(m.get("country"), dict) else {}
    state = m.get("state") if isinstance(m.get("state"), dict) else {}
    score = state.get("score") if isinstance(state.get("score"), dict) else {}
    current = str(score.get("current") or "")
    home_score = away_score = None
    if "-" in current:
        parts = current.replace(" ", "").split("-")
        if len(parts) >= 2:
            try:
                home_score = int(parts[0])
                away_score = int(parts[1])
            except Exception:
                pass
    date_raw = m.get("date") or ""
    kickoff_date = _normalize_date_str(date_raw)
    kickoff_time = None
    if "T" in str(date_raw):
        try:
            # 2023-05-20T15:30:00.000Z → local-ish HH:MM (UTC+2 approx for HU)
            from datetime import timezone as tz
            dt = datetime.fromisoformat(str(date_raw).replace("Z", "+00:00"))
            dt_hu = dt + timedelta(hours=2)
            kickoff_time = dt_hu.strftime("%H:%M")
        except Exception:
            pass
    desc = str(state.get("description") or "").lower()
    clock = state.get("clock")
    if "not started" in desc or "to be announced" in desc:
        status = kickoff_time or "NS"
    elif "finished" in desc or desc == "ft":
        status = "FT"
    elif clock is not None:
        try:
            status = str(int(clock))
        except Exception:
            status = str(clock)
    else:
        status = state.get("description") or "NS"

    country_name = country.get("name") or ""
    league_name = league.get("name") or ""
    full_league = format_league_title(country_name, league_name) if league_name else league_name

    return {
        "id": f"hl-{m.get('id')}",
        "league_id": str(league.get("id") or ""),
        "league": full_league or league_name,
        "country": translate_text(country_name) if country_name else "",
        "country_code": country.get("code") or _country_code(country_name),
        "home_team": home.get("name") or "Hazai",
        "away_team": away.get("name") or "Vendég",
        "home_logo_url": home.get("logo"),
        "away_logo_url": away.get("logo"),
        "home_score": home_score,
        "away_score": away_score,
        "status": status,
        "minute": int(clock) if isinstance(clock, (int, float)) else (int(clock) if str(clock).isdigit() else 0),
        "highlight_url": None,
        "highlight_match_id": str(m.get("id")) if m.get("id") is not None else None,
        "value_bet": False,
        "events": [],
        "odds_home": None,
        "odds_draw": None,
        "odds_away": None,
        "kickoff_date": kickoff_date,
        "kickoff_time": kickoff_time,
        "hl_home_team_id": home.get("id"),
        "hl_away_team_id": away.get("id"),
        "source": "highlightly",
    }






_odds_cache = {}  # key -> {ts, data, odds_type}
_odds_last_meta = {}


def _odds_cache_ttl(odds_type: str, status: str = None, kickoff_date: str = None, kickoff_time: str = None) -> int:
    """Prematch 6–8h; kickoff előtt rövid; live rövid."""
    ot = (odds_type or "prematch").lower()
    st = (status or "").upper().replace(".", "")
    if ot == "live" or st in ("1H", "2H", "HT", "LIVE", "ET", "INPLAY"):
        return 180  # 3 perc
    # kickoff ablak
    try:
        if kickoff_date and kickoff_time and ":" in str(kickoff_time):
            parts = str(kickoff_time).strip().split(":")
            hh, mm = int(parts[0]), int(parts[1]) if len(parts) > 1 else 0
            dt = datetime.strptime(str(kickoff_date)[:10], "%Y-%m-%d").replace(
                hour=hh, minute=mm
            )
            # Europe/Budapest közelítés: local server time
            delta_sec = (dt - datetime.now()).total_seconds()
            if 0 < delta_sec < 3 * 3600:
                return 2700  # 45 perc
    except Exception:
        pass
    return 7 * 3600  # ~7 óra prematch


def _label_to_1x2(label: str):
    lab = (label or "").strip().lower()
    if lab in ("home", "1", "hazai", "h", "team1", "home team"):
        return "h"
    if lab in ("draw", "x", "döntetlen", "dontetlen", "tie", "d"):
        return "d"
    if lab in ("away", "2", "vendég", "vendeg", "a", "team2", "away team"):
        return "a"
    # "Home (Chelsea)" stb.
    if lab.startswith("home"):
        return "h"
    if lab.startswith("away"):
        return "a"
    if lab.startswith("draw"):
        return "d"
    return None


def _is_1x2_market(market: str) -> bool:
    m = (market or "").lower()
    if not m:
        return True  # próbáljuk
    keys = (
        "full time", "fulltime", "1x2", "1 x 2", "match winner", "match result",
        "ft result", "ft result", "home/away", "home - away", "3-way", "three way",
        "match odds", "winner", "result",
    )
    return any(k in m for k in keys)


def _parse_hl_1x2(odds_list) -> tuple:
    """Highlightly odds lista → (home, draw, away). Nagyon megengedő parse."""
    if not isinstance(odds_list, list):
        return None, None, None
    best = (None, None, None)

    def consider(h, d, a):
        nonlocal best
        if h is not None and d is not None and a is not None:
            return True  # signal complete
        if best[0] is None and (h is not None or d is not None or a is not None):
            best = (h, d, a)
        return False

    for entry in odds_list:
        if not isinstance(entry, dict):
            continue
        blocks = entry.get("odds") if isinstance(entry.get("odds"), list) else None
        if blocks is None:
            blocks = [entry]
        for b in blocks:
            if not isinstance(b, dict):
                continue
            market = str(b.get("market") or b.get("name") or b.get("bet") or "")
            if market and not _is_1x2_market(market):
                continue
            vals = b.get("values") or b.get("odds") or b.get("outcomes") or []
            if isinstance(vals, dict):
                # {home: 1.5, draw: 3.2, away: 4.1}
                try:
                    h = float(vals["home"]) if vals.get("home") is not None else None
                    d = float(vals.get("draw") or vals.get("x") or vals.get("X") or 0) or None
                    a = float(vals["away"]) if vals.get("away") is not None else None
                    if vals.get("draw") is None and vals.get("x") is None:
                        d = None
                    if consider(h, d, a):
                        return h, d, a
                except Exception:
                    pass
                continue
            if not isinstance(vals, list):
                continue
            h = d = a = None
            for v in vals:
                if not isinstance(v, dict):
                    continue
                label = str(
                    v.get("value") or v.get("label") or v.get("name")
                    or v.get("selection") or v.get("outcome") or ""
                )
                side = _label_to_1x2(label)
                raw_odd = v.get("odd")
                if raw_odd is None:
                    raw_odd = v.get("price") or v.get("odds") or v.get("decimal")
                try:
                    odd = float(raw_odd)
                except Exception:
                    continue
                if side == "h":
                    h = odd
                elif side == "d":
                    d = odd
                elif side == "a":
                    a = odd
            if consider(h, d, a):
                return h, d, a
    return best


def _odds_from_predictions(pred) -> tuple:
    """Valószínűség → közelítő decimal odds (1/p)."""
    if not isinstance(pred, dict):
        return None, None, None
    # nested
    for key in ("fullTime", "match", "probabilities", "predict", "prediction"):
        if isinstance(pred.get(key), dict):
            pred = pred[key]
            break
    try:
        def pget(*keys):
            for k in keys:
                if pred.get(k) is not None:
                    return float(pred[k])
            return None
        ph = pget("home", "homeWin", "1", "home_prob")
        pd = pget("draw", "x", "X", "draw_prob")
        pa = pget("away", "awayWin", "2", "away_prob")
        # ha százalék 0–100
        if ph is not None and ph > 1:
            ph /= 100.0
        if pd is not None and pd > 1:
            pd /= 100.0
        if pa is not None and pa > 1:
            pa /= 100.0
        def to_odd(p):
            if p is None or p <= 0.01:
                return None
            return round(1.0 / p, 2)
        return to_odd(ph), to_odd(pd), to_odd(pa)
    except Exception:
        return None, None, None


def fetch_highlightly_odds(highlight_match_id: str, odds_type: str = "prematch"):
    """
    GET /odds?matchId=&oddsType=prematch|live
    Próbál: soccer.highlightly.net + RapidAPI host.
    Cache: prematch ~7h, live ~3 perc.
    Visszaad: list vagy None. plan tier üzenet a _odds_last_meta-ban.
    """
    global _odds_last_meta
    if not HIGHLIGHTLY_KEY or not highlight_match_id:
        return None
    hid = str(highlight_match_id).strip()
    ot = "live" if str(odds_type).lower() == "live" else "prematch"
    cache_key = f"{hid}:{ot}"
    now = time.time()
    cached = _odds_cache.get(cache_key)
    ttl = _odds_cache_ttl(ot)
    if cached and (now - cached.get("ts", 0)) < ttl:
        return cached.get("data")

    bases = [
        ("https://soccer.highlightly.net/odds", False),
        ("https://football-highlights-api.p.rapidapi.com/odds", True),
    ]
    params_list = [
        {"matchId": hid, "oddsType": ot, "limit": 50},
        {"matchId": hid, "oddsType": ot},
        {"matchId": hid},
    ]
    last_plan = None
    try:
        for base, use_host in bases:
            for params in params_list:
                try:
                    r = requests.get(
                        base,
                        headers=_highlightly_headers(rapidapi_host=use_host),
                        params=params,
                        timeout=12,
                    )
                    if r.status_code != 200:
                        continue
                    payload = r.json()
                    if isinstance(payload, dict):
                        last_plan = payload.get("plan")
                        data = payload.get("data")
                    else:
                        data = payload
                    if not isinstance(data, list):
                        data = []
                    # ha van tényleges odds blokk
                    has_values = False
                    for entry in data:
                        if not isinstance(entry, dict):
                            continue
                        odds = entry.get("odds")
                        if isinstance(odds, list) and len(odds) > 0:
                            has_values = True
                            break
                        if entry.get("values"):
                            has_values = True
                            break
                    _odds_last_meta = {
                        "plan": last_plan,
                        "match_id": hid,
                        "odds_type": ot,
                        "has_values": has_values,
                        "source_url": base,
                    }
                    if has_values or data:
                        _odds_cache[cache_key] = {"ts": now, "data": data, "odds_type": ot}
                        return data
                except Exception:
                    continue
        if last_plan is not None:
            _odds_last_meta = {"plan": last_plan, "match_id": hid, "odds_type": ot, "has_values": False}
        if cached:
            return cached.get("data")
        return None
    except Exception:
        if cached:
            return cached.get("data")
        return None


def enrich_odds_from_highlightly(payload: dict) -> dict:
    """1X2 + teljes markets lista Highlightly-ből. 1X2 megléte mellett is betölti a piacokat."""
    if not isinstance(payload, dict):
        return payload
    hid = payload.get("highlight_match_id")
    st = str(payload.get("status") or "").upper().replace(".", "")
    is_live = st in ("1H", "2H", "HT", "LIVE", "ET", "INPLAY")
    # Elsődleges: PREMATCH (még élő meccsnél is – a user prematch piacokat akar)
    ot = "prematch"
    h = payload.get("odds_home")
    d = payload.get("odds_draw")
    a = payload.get("odds_away")
    source = payload.get("odds_source")
    markets = list(payload.get("odds_markets") or []) if isinstance(payload.get("odds_markets"), list) else []
    raw = None
    if hid:
        try:
            raw = fetch_highlightly_odds(str(hid), "prematch")
            # Élőnél ha prematch üres, próbáljuk a live-ot másodjára
            if not raw and is_live:
                raw = fetch_highlightly_odds(str(hid), "live")
                ot = "live"
            if raw:
                markets = _normalize_all_odds_markets(raw)
                if h is None and d is None:
                    h, d, a = _parse_hl_1x2(raw)
                    if h is not None or d is not None or a is not None:
                        source = "highlightly"
        except Exception:
            pass
        if h is None and d is None:
            try:
                det = fetch_highlightly_match_detail(str(hid))
                if isinstance(det, dict):
                    h, d, a = _odds_from_predictions(det.get("predictions") or det.get("forecast") or {})
                    if h is not None:
                        source = "highlightly_pred"
            except Exception:
                pass
    if h is None and d is None:
        h, d, a = _odds_from_predictions(payload.get("predictions") or payload.get("forecast") or {})
        if h is not None:
            source = source or "prediction"
    if h is not None or d is not None or a is not None or markets:
        payload = dict(payload)
        if h is not None or d is not None or a is not None:
            payload["odds_home"] = h
            payload["odds_draw"] = d
            payload["odds_away"] = a
            payload["odds_source"] = source
            payload["odds_type"] = ot
        if markets:
            payload["odds_markets"] = markets
            payload["odds_markets_count"] = len(markets)
    return payload


def resolve_highlightly_match_id(home_name: str, away_name: str, date_iso: str = None):
    """
    Highlightly match ID feloldás:
    1) mai / megadott nap GET /matches?date=
    2) soft team name match (Kups ↔ KuPS, Shamrock Rovers, stb.)
    """
    if not HIGHLIGHTLY_KEY:
        return None
    date_iso = (date_iso or datetime.now().strftime("%Y-%m-%d"))[:10]
    matches = fetch_highlightly_matches_by_date(date_iso, limit=100)
    if not matches:
        # tegnap + holnap is (időzóna / késő meccs)
        for delta in (-1, 1):
            try:
                d = (datetime.now() + timedelta(days=delta)).strftime("%Y-%m-%d")
                matches = fetch_highlightly_matches_by_date(d, limit=100)
                if matches:
                    break
            except Exception:
                pass
    if not matches:
        return None

    nh = " ".join((home_name or "").lower().split())
    na = " ".join((away_name or "").lower().split())

    best_id = None
    for m in matches:
        if not isinstance(m, dict):
            continue
        ht = m.get("homeTeam") if isinstance(m.get("homeTeam"), dict) else {}
        at = m.get("awayTeam") if isinstance(m.get("awayTeam"), dict) else {}
        hl_h = " ".join(str(ht.get("name") or "").lower().split())
        hl_a = " ".join(str(at.get("name") or "").lower().split())
        if not hl_h or not hl_a:
            continue
        # pontos
        if (hl_h == nh and hl_a == na) or (hl_h == na and hl_a == nh):
            mid = m.get("id")
            if mid is not None:
                return str(mid)
        # soft
        if (
            (_teams_soft_match(nh, hl_h) and _teams_soft_match(na, hl_a))
            or (_teams_soft_match(nh, hl_a) and _teams_soft_match(na, hl_h))
        ):
            mid = m.get("id")
            if mid is not None:
                best_id = str(mid)
    return best_id


def fetch_highlightly_match_detail(highlight_match_id: str):
    """Highlightly GET /matches/{id} – events, stats, venue, stb."""
    if not HIGHLIGHTLY_KEY or not highlight_match_id:
        return None
    cache_key = str(highlight_match_id).strip()
    now = time.time()
    cached = _detail_cache.get(cache_key)
    if cached and (now - cached["ts"]) < DETAIL_CACHE_TTL:
        return enrich_odds_from_highlightly(dict(cached["data"]) if isinstance(cached["data"], dict) else cached["data"])
    try:
        resp = requests.get(
            f"https://soccer.highlightly.net/matches/{cache_key}",
            headers=_highlightly_headers(),
            timeout=10,
        )
        if resp.status_code != 200:
            return None
        payload = resp.json()
        # válasz lehet lista vagy dict
        if isinstance(payload, list) and payload:
            data = payload[0]
        elif isinstance(payload, dict):
            data = payload.get("data") if isinstance(payload.get("data"), dict) else payload
        else:
            data = None
        if isinstance(data, dict):
            _detail_cache[cache_key] = {"data": data, "ts": now}
            return data
    except Exception:
        pass
    return None


def fetch_highlightly_lineups(highlight_match_id: str):
    if not HIGHLIGHTLY_KEY or not highlight_match_id:
        return None
    cache_key = str(highlight_match_id).strip()
    now = time.time()
    cached = _lineups_cache.get(cache_key)
    # Üres / None válasz ne maradjon 2 percig cache-ben
    if cached and cached.get("data") and (now - cached["ts"]) < LINEUPS_CACHE_TTL:
        return cached["data"]
    if cached and not cached.get("data") and (now - cached["ts"]) < 15:
        return None
    try:
        resp = requests.get(
            f"https://soccer.highlightly.net/lineups/{cache_key}",
            headers=_highlightly_headers(),
            timeout=12,
        )
        if resp.status_code != 200:
            _lineups_cache[cache_key] = {"data": None, "ts": now}
            return None
        data = resp.json()
        if isinstance(data, list) and data:
            data = data[0] if isinstance(data[0], dict) else None
        elif isinstance(data, dict) and isinstance(data.get("data"), dict):
            data = data["data"]
        if isinstance(data, dict) and (data.get("homeTeam") or data.get("awayTeam") or data.get("home") or data.get("away")):
            _lineups_cache[cache_key] = {"data": data, "ts": now}
            return data
        _lineups_cache[cache_key] = {"data": None, "ts": now}
    except Exception:
        pass
    return None


def fetch_highlightly_statistics(highlight_match_id: str):
    """Először match detail statistics mező, fallback /statistics/{id}."""
    if not HIGHLIGHTLY_KEY or not highlight_match_id:
        return []
    cache_key = str(highlight_match_id).strip()
    now = time.time()
    cached = _stats_cache.get(cache_key)
    if cached and (now - cached["ts"]) < STATS_CACHE_TTL:
        return cached["data"]

    stats = []
    detail = fetch_highlightly_match_detail(cache_key)
    if isinstance(detail, dict):
        raw = detail.get("statistics")
        if isinstance(raw, list):
            stats = raw
    if not stats:
        try:
            resp = requests.get(
                f"https://soccer.highlightly.net/statistics/{cache_key}",
                headers=_highlightly_headers(),
                timeout=10,
            )
            if resp.status_code == 200:
                payload = resp.json()
                if isinstance(payload, list):
                    stats = payload
                elif isinstance(payload, dict):
                    inner = payload.get("data") or payload.get("statistics")
                    if isinstance(inner, list):
                        stats = inner
        except Exception:
            pass
    _stats_cache[cache_key] = {"data": stats, "ts": now}
    return stats


def _normalize_lineups(raw):
    """Highlightly lineups válasz → egyszerű home/away struktúra.

    Highlightly formátum:
      homeTeam / awayTeam: { name, formation, initialLineup: [[players]], substitutes: [] }
    """
    if not isinstance(raw, dict):
        return {"home": None, "away": None}

    def _flatten_players(initial, substitutes):
        players = []
        rows = initial or []
        # initialLineup: list of rows (each row = list of players) VAGY flat list
        if rows and isinstance(rows[0], list):
            for row in rows:
                for p in row:
                    if not isinstance(p, dict):
                        continue
                    players.append({
                        "name": p.get("name") or p.get("playerName") or p.get("player"),
                        "number": p.get("number") or p.get("shirtNumber") or p.get("shirt"),
                        "position": p.get("position") or p.get("pos"),
                        "is_bench": False,
                    })
        else:
            for p in rows:
                if not isinstance(p, dict):
                    continue
                players.append({
                    "name": p.get("name") or p.get("playerName") or p.get("player"),
                    "number": p.get("number") or p.get("shirtNumber") or p.get("shirt"),
                    "position": p.get("position") or p.get("pos"),
                    "is_bench": bool(p.get("is_bench") or p.get("substitute")),
                    "player_id": str(
                        p.get("id") or p.get("playerId") or p.get("player_id") or ""
                    ) or None,
                })
        for p in (substitutes or []):
            if not isinstance(p, dict):
                continue
            players.append({
                "name": p.get("name") or p.get("playerName") or p.get("player"),
                "number": p.get("number") or p.get("shirtNumber") or p.get("shirt"),
                "position": p.get("position") or p.get("pos"),
                "is_bench": True,
                "player_id": str(
                    p.get("id") or p.get("playerId") or p.get("player_id") or ""
                ) or None,
            })
        return players

    def side(prefer_keys):
        block = None
        for k in prefer_keys:
            b = raw.get(k)
            if isinstance(b, dict) and b:
                block = b
                break
        if not isinstance(block, dict):
            return None
        formation = (
            block.get("formation")
            or block.get("Formation")
            or block.get("formation_name")
        )
        team = block.get("team") if isinstance(block.get("team"), dict) else {}
        team_name = (
            block.get("name")
            or team.get("name")
            or block.get("teamName")
            or block.get("team_name")
        )
        initial = (
            block.get("initialLineup")
            or block.get("startXI")
            or block.get("lineup")
            or block.get("startingLineup")
            or block.get("players")
            or []
        )
        substitutes = (
            block.get("substitutes")
            or block.get("bench")
            or block.get("subs")
            or []
        )
        players = _flatten_players(initial, substitutes)
        if not players and not formation:
            return None
        return {
            "team_name": team_name,
            "formation": formation,
            "players": players,
        }

    home = side(["homeTeam", "home", "HomeTeam", "home_team"])
    away = side(["awayTeam", "away", "AwayTeam", "away_team"])
    return {"home": home, "away": away}





STAT_LABEL_HU = {
    "ball possession": "Labdabirtoklás",
    "possession": "Labdabirtoklás",
    "shots on goal": "Kapura lövések",
    "shots on target": "Kapura lövések",
    "shots off goal": "Kapu mellé",
    "shots off target": "Kapu mellé",
    "total shots": "Összes lövés",
    "shots total": "Összes lövés",
    "blocked shots": "Blokkolt lövések",
    "shots blocked": "Blokkolt lövések",
    "shots insidebox": "Lövések a 16-oson belül",
    "shots outsidebox": "Lövések a 16-oson kívül",
    "fouls": "Szabálytalanságok",
    "corner kicks": "Szögletek",
    "corners": "Szögletek",
    "offsides": "Lesek",
    "ball safe": "Biztonságos labda",
    "yellow cards": "Sárga lapok",
    "red cards": "Piros lapok",
    "goalkeeper saves": "Kapus védések",
    "saves": "Védések",
    "total passes": "Összes passz",
    "passes": "Passzok",
    "accurate passes": "Pontos passzok",
    "passes %": "Passzpontosság %",
    "passes percentage": "Passzpontosság %",
    "expected goals": "Várható gól (xG)",
    "expected goals (xg)": "Várható gól (xG)",
    "xg": "Várható gól (xG)",
    "big chances": "Nagy helyzetek",
    "big chances scored": "Kihagyott nagy helyzetekből gól",
    "big chances missed": "Kihagyott nagy helyzetek",
    "tackles": "Szerelések",
    "interceptions": "Labdaszerzések",
    "clearances": "Kimentések",
    "crosses": "Beadások",
    "accurate crosses": "Pontos beadások",
    "duels won": "Nyert párharcok",
    "aerials won": "Nyert fejpárbajok",
    "dribbles": "Cseltámadások",
    "successful dribbles": "Sikeres cselek",
    "throw ins": "Bedobások",
    "goal kicks": "Kapusrúgások",
    "free kicks": "Szabadrúgások",
    "hits woodwork": "Kapufák",
    "counter attacks": "Kontrák",
}


def _stat_label_hu(name: str) -> str:
    """Először pontos egyezés – ne legyen minden 'Passzok'."""
    if not name:
        return name
    try:
        labels = STAT_LABEL_HU if isinstance(STAT_LABEL_HU, dict) else {}
    except NameError:
        labels = {}
    key = " ".join(str(name).strip().lower().split())
    if key in labels:
        return labels[key]
    for en in sorted(labels.keys(), key=len, reverse=True):
        if en == key:
            return labels[en]
        if len(en) >= 8 and en in key:
            return labels[en]
    return str(name).strip()


def _normalize_statistics(raw_list):
    """Highlightly statistics → párosított home/away értékek."""
    if not isinstance(raw_list, list) or not raw_list:
        return []
    # Formátum A: [{team: {...}, statistics: [{displayName, value}]}]
    by_name = {}
    teams_order = []
    for block in raw_list:
        if not isinstance(block, dict):
            continue
        team = block.get("team") if isinstance(block.get("team"), dict) else {}
        team_name = team.get("name") or "Team"
        if team_name not in teams_order:
            teams_order.append(team_name)
        for s in block.get("statistics") or []:
            if not isinstance(s, dict):
                continue
            name = s.get("displayName") or s.get("name") or ""
            if not name:
                continue
            val = s.get("value")
            by_name.setdefault(name, {})[team_name] = val
    result = []
    if len(teams_order) >= 2:
        home_name, away_name = teams_order[0], teams_order[1]
        for name, vals in by_name.items():
            result.append({
                "name": _stat_label_hu(name),
                "home": vals.get(home_name),
                "away": vals.get(away_name),
            })
    elif by_name:
        only = teams_order[0] if teams_order else ""
        for name, vals in by_name.items():
            result.append({
                "name": _stat_label_hu(name),
                "home": vals.get(only),
                "away": None,
            })
    return result



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

    # További országok – zászló + magyar ország név
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
    "south sudan": "Dél-Szudán",
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
    "moldova": "Moldova",
    "bulgaria": "Bulgária"
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
    """
    Kinyeri a StatPal válaszából a csapat/league kép URL-jét,
    ha az API az adott válaszban biztosít ilyen mezőt.
    Nem gyártunk saját URL-t.
    """
    if isinstance(value, dict):
        preferred_keys = (
            "logo_url",
            "logo",
            "image_url",
            "image",
            "crest_url",
            "crest",
            "team_logo_url",
            "team_logo",
            "icon_url",
            "icon",
            "badge_url",
            "badge"
        )

        for key in preferred_keys:
            candidate = value.get(key)

            if (
                isinstance(candidate, str)
                and candidate.strip().startswith(("http://", "https://"))
            ):
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
    """
    Magyar országnevekhez tartozó ISO 3166-1 alpha-2 kód.

    A mobil kliens ez alapján rajzol zászlót. Ha egy ország itt hiányzik,
    a felületen földgömb jelenik meg, ezért a gyakori StatPal elnevezéseket
    és a mai meccslistán előforduló országokat is kezeljük.
    """
    if not raw_country:
        return ""

    key = " ".join(
        str(raw_country)
        .replace("_", " ")
        .replace("-", " ")
        .replace("&", " and ")
        .strip()
        .lower()
        .split()
    )

    aliases = {
        "england": "gb",
        "scotland": "gb",
        "wales": "gb",
        "northern ireland": "gb",

        "spain": "es",
        "italy": "it",
        "germany": "de",
        "france": "fr",
        "hungary": "hu",

        "argentina": "ar",
        "armenia": "am",
        "australia": "au",
        "austria": "at",
        "belarus": "by",
        "belgium": "be",
        "bolivia": "bo",
        "bosnia and herzegovina": "ba",
        "bosnia & herzegovina": "ba",
        "brazil": "br",
        "brazilia": "br",
        "bulgaria": "bg",
        "canada": "ca",
        "chile": "cl",
        "china": "cn",
        "china pr": "cn",
        "colombia": "co",
        "costa rica": "cr",
        "croatia": "hr",
        "cyprus": "cy",
        "czechia": "cz",
        "czech republic": "cz",
        "denmark": "dk",
        "dominican republic": "do",
        "ecuador": "ec",
        "equador": "ec",
        "egypt": "eg",
        "el salvador": "sv",
        "estonia": "ee",
        "ethiopia": "et",
        "faroe islands": "fo",
        "fiji": "fj",
        "finland": "fi",
        "georgia": "ge",
        "germany": "de",
        "ghana": "gh",
        "gibraltar": "gi",
        "greece": "gr",
        "guatemala": "gt",
        "honduras": "hn",
        "hong kong": "hk",
        "iceland": "is",
        "india": "in",
        "indonesia": "id",
        "iran": "ir",
        "iraq": "iq",
        "ireland": "ie",
        "israel": "il",
        "ivory coast": "ci",
        "cote d'ivoire": "ci",
        "jamaica": "jm",
        "japan": "jp",
        "jordan": "jo",
        "kazakhstan": "kz",
        "kenya": "ke",
        "korea": "kr",
        "korea republic": "kr",
        "kosovo": "xk",
        "kyrgyzstan": "kg",
        "kuwait": "kw",
        "latvia": "lv",
        "lebanon": "lb",
        "lithuania": "lt",
        "luxembourg": "lu",
        "malaysia": "my",
        "malta": "mt",
        "mauritius": "mu",
        "mexico": "mx",
        "moldova": "md",
        "montenegro": "me",
        "morocco": "ma",
        "mozambique": "mz",
        "netherlands": "nl",
        "new caledonia": "nc",
        "new zealand": "nz",
        "nicaragua": "ni",
        "nigeria": "ng",
        "north macedonia": "mk",
        "macedonia": "mk",
        "norway": "no",
        "oman": "om",
        "panama": "pa",
        "paraguay": "py",
        "peru": "pe",
        "philippines": "ph",
        "poland": "pl",
        "portugal": "pt",
        "qatar": "qa",
        "romania": "ro",
        "russia": "ru",
        "saudi arabia": "sa",
        "saudiarabia": "sa",
        "scotland": "gb",
        "senegal": "sn",
        "serbia": "rs",
        "singapore": "sg",
        "slovakia": "sk",
        "slovenia": "si",
        "south africa": "za",
        "south korea": "kr",
        "south sudan": "ss",
        "spain": "es",
        "sri lanka": "lk",
        "srilanka": "lk",
        "sweden": "se",
        "switzerland": "ch",
        "taiwan": "tw",
        "tanzania": "tz",
        "tanzania united republic of": "tz",
        "thailand": "th",
        "togo": "tg",
        "trinidad and tobago": "tt",
        "tunisia": "tn",
        "turkey": "tr",
        "uganda": "ug",
        "ukraine": "ua",
        "united arab emirates": "ae",
        "uae": "ae",
        "uruguay": "uy",
        "usa": "us",
        "uzbekistan": "uz",
        "venezuela": "ve",
        "vietnam": "vn",
        "wales": "gb",
        "world": "",
        "europe": "",
        "zambia": "zm",
        "zimbabwe": "zw"
    }

    return aliases.get(key, "")

def _hungarian_sort_key(value):
    """
    Magyar ábécé szerinti rendezési kulcs.

    Fontos: Python alapból Unicode-kódpont szerint rendez, ezért például
    az Üzbegisztán a lista végére kerülne. A magyar többjegyű betűk
    (Cs, Dz, Dzs, Gy, Ly, Ny, Sz, Ty, Zs) sorrendjét is kezeljük.
    """
    text = str(value or "").strip().casefold()

    # Hosszabb magyar betűk kerüljenek előbb a tokenizáláskor.
    multigraphs = (
        "dzs", "cs", "dz", "gy", "ly", "ny", "sz", "ty", "zs"
    )

    alphabet = {
        "a": 1, "á": 2,
        "b": 3,
        "c": 4, "cs": 5,
        "d": 6, "dz": 7, "dzs": 8,
        "e": 9, "é": 10,
        "f": 11,
        "g": 12, "gy": 13,
        "h": 14,
        "i": 15, "í": 16,
        "j": 17,
        "k": 18,
        "l": 19, "ly": 20,
        "m": 21,
        "n": 22, "ny": 23,
        "o": 24, "ó": 25, "ö": 26, "ő": 27,
        "p": 28,
        "q": 29,
        "r": 30,
        "s": 31, "sz": 32,
        "t": 33, "ty": 34,
        "u": 35, "ú": 36, "ü": 37, "ű": 38,
        "v": 39,
        "w": 40,
        "x": 41,
        "y": 42,
        "z": 43, "zs": 44,
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
    """StatPal csapat azonosító kinyerése több lehetséges mezőből."""
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
    """A saját backend proxy URL-je, amely a StatPal képendpointját használja."""
    if not team_id:
        return None

    return f"{IMAGE_PROXY_BASE_URL.rstrip('/')}/api/team-image/{team_id}"


def fetch_statpal_matches():
    """StatPal today/live – stale cache ha a hálózat timeoutol."""
    now = time.time()

    if (
        _statpal_cache["data"] is not None
        and (now - _statpal_cache["ts"]) < STATPAL_CACHE_TTL
    ):
        return _statpal_cache["data"]

    headers = {"Accept": "application/json"}
    last_err = None

    for path in ("matches/today", "matches/live"):
        try:
            url = f"https://statpal.io/api/v2/soccer/{path}?access_key={STATPAL_KEY}"
            response = requests.get(url, headers=headers, timeout=15)
            if response.status_code != 200:
                last_err = f"HTTP {response.status_code}"
                continue
            data = response.json()
            _statpal_cache["data"] = data
            _statpal_cache["ts"] = now
            return data
        except Exception as e:
            last_err = e
            continue

    # Stale cache (akár 10 percig) – jobb mint üres / hiba kártya
    if _statpal_cache["data"] is not None and (now - _statpal_cache["ts"]) < 600:
        return _statpal_cache["data"]

    raise RuntimeError(f"StatPal unreachable: {last_err}")


def fetch_highlightly_highlights():
    """
    A Highlightly mai highlight-listáját tölti le.

    FONTOS:
    Nem egy adott meccshez kérünk külön API-lekérést.
    A /highlights válaszban minden elem tartalmazhat egy
    match.id értéket, ezért ebből a listából párosítunk.
    """

    if not HIGHLIGHTLY_KEY:
        return []

    now = time.time()

    if (
        _highlightly_cache["data"] is not None
        and (now - _highlightly_cache["ts"]) < HIGHLIGHTLY_CACHE_TTL
    ):
        return _highlightly_cache["data"]

    today = datetime.now(timezone.utc).strftime("%Y-%m-%d")

    base_url = "https://soccer.highlightly.net/highlights"

    headers = {
        "x-rapidapi-key": HIGHLIGHTLY_KEY
    }

    all_highlights = []

    try:
        # A dokumentációban a limit akár 100 is lehet.
        # Több oldalt töltünk le, hogy a mai meccsek nagyobb eséllyel
        # bekerüljenek a listába.
        for offset in (0, 40, 80):

            resp = requests.get(
                base_url,
                headers=headers,
                params={
                    "date": today,
                    "limit": 40,
                    "offset": offset
                },
                timeout=6
            )

            if resp.status_code != 200:
                break

            payload = resp.json()

            page = (
                payload.get("data")
                if isinstance(payload, dict)
                else None
            )

            if not isinstance(page, list) or not page:
                break

            all_highlights.extend(page)

            if len(page) < 100:
                break

    except Exception:
        pass

    _highlightly_cache["data"] = all_highlights
    _highlightly_cache["ts"] = now

    return all_highlights


def fetch_highlightly_match_highlights(highlight_match_id: str):
    """
    Egy adott Highlightly match.id összes videóját keresi ki
    a már letöltött /highlights listából.

    FONTOS JAVÍTÁS:
    Nem küldünk új kérést a Highlightly /highlights végpontjára
    ?matchId=... paraméterrel.

    Ehelyett a /highlights válaszban található:

        highlight["match"]["id"]

    alapján szűrünk.
    """

    if not HIGHLIGHTLY_KEY or not highlight_match_id:
        return []

    requested_id = str(highlight_match_id).strip()

    if not requested_id:
        return []

    cache_key = requested_id
    now = time.time()

    cached = _highlightly_match_cache.get(cache_key)

    if (
        cached
        and (now - cached["ts"]) < HIGHLIGHTLY_CACHE_TTL
    ):
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

        current_match_id = str(raw_match_id).strip()

        if current_match_id != requested_id:
            continue

        # Csak olyan elemet tartunk meg, amely ténylegesen
        # tartalmaz használható videóhivatkozást.
        embed_url = highlight.get("embedUrl")
        normal_url = highlight.get("url")

        if (
            isinstance(embed_url, str)
            and embed_url.strip()
        ) or (
            isinstance(normal_url, str)
            and normal_url.strip()
        ):
            matched_highlights.append(highlight)

    _highlightly_match_cache[cache_key] = {
        "data": matched_highlights,
        "ts": now
    }

    return matched_highlights


def _highlightly_video_payload(highlight: dict):
    """
    Csak a mobil kliens számára szükséges, biztonságos mezőket
    adja vissza.
    """

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


@app.get("/api/highlights/match/{highlight_match_id}")
def get_match_highlights(highlight_match_id: str):
    """
    Egy adott Highlightly mérkőzés összes videóját adja vissza.

    A Highlightly saját match.id azonosítóját várja,
    nem a StatPal main_id-t.

    A válaszban a goal-clip és match-highlights elemek
    egyaránt megmaradnak.
    """

    if not HIGHLIGHTLY_KEY or not highlight_match_id:
        return []

    highlights = fetch_highlightly_match_highlights(
        highlight_match_id
    )

    result = []

    for highlight in highlights:

        if not isinstance(highlight, dict):
            continue

        item = _highlightly_video_payload(highlight)

        if (
            item["id"]
            and (
                item["embedUrl"]
                or item["url"]
            )
        ):
            result.append(item)

    # Goal clip legyen elöl.
    result.sort(
        key=lambda item: (
            not str(
                item.get("category") or ""
            ).lower() == "goal-clip",
            str(
                item.get("title") or ""
            ).lower()
        )
    )

    return result


@app.get("/api/team-image/{team_id}")
def get_team_image(team_id: str):
    """
    StatPal csapatkép proxy.

    A StatPal dokumentáció szerint az images endpoint PNG-t ad vissza,
    és a kérés egy 5 percig érvényes képlinkre redirectel.

    A backend követi a redirectet, majd a PNG-t saját rövid idejű
    cache-ből szolgálja ki, így az access_key nem kerül az Android
    alkalmazásba.
    """

    if (
        not STATPAL_KEY
        or not team_id
        or not str(team_id).isdigit()
    ):
        return Response(status_code=404)

    cache_key = str(team_id)
    now = time.time()

    cached = _team_image_cache.get(cache_key)

    if (
        cached
        and (now - cached["ts"]) < TEAM_IMAGE_CACHE_TTL
    ):
        return Response(
            content=cached["content"],
            media_type="image/png",
            headers={
                "Cache-Control": "public, max-age=21600"
            }
        )

    try:
        url = "https://statpal.io/api/v2/soccer/images"

        response = requests.get(
            url,
            params={
                "type": "team",
                "id": team_id,
                "access_key": STATPAL_KEY
            },
            headers={
                "Accept": "image/png, application/json"
            },
            timeout=10,
            allow_redirects=True
        )

        if (
            response.status_code != 200
            or not response.content
        ):
            return Response(
                status_code=response.status_code or 404
            )

        content_type = (
            response.headers
            .get("content-type", "image/png")
            .split(";")[0]
            .strip()
        )

        if content_type != "image/png":
            content_type = "image/png"

        _team_image_cache[cache_key] = {
            "content": response.content,
            "ts": now
        }

        return Response(
            content=response.content,
            media_type=content_type,
            headers={
                "Cache-Control": "public, max-age=21600"
            }
        )

    except Exception:
        return Response(status_code=404)


@app.get("/api/player-image/{player_id}")
def get_player_image(player_id: str):
    """
    Játékosfotó proxy -- a Football Futár 2 (KÜLÖN Android app) Kezdő11
    pályaképéhez.

    Nem tölti le és tárolja saját maga a képbájtokat (ellentétben a fenti
    /api/team-image/{team_id} végponttal) -- ehelyett a Highlightly által
    adott fotó-URL-re irányítja át a klienst (307 redirect), miután az
    URL-t egyszer megkereste és tartósan (Upstash Redis) elmentette.

    Ez azért egyszerűbb és olcsóbb, mert a Highlightly fotó-URL-je
    feltehetően stabil, nyilvánosan elérhető CDN-link (nem 5 percig
    érvényes, mint a StatPal-é), így nincs szükség saját sávszélességre/
    tárhelyre a kép bájtjaihoz, csak egy rövid URL-t kell cache-elni
    játékosonként.

    Ha a jövőben kiderülne, hogy a Highlightly URL-je mégis lejár vagy
    API-kulcsot igényel a betöltéshez, ezt a végpontot át kell alakítani
    a /api/team-image/{team_id} mintájára (tényleges bájt-proxy).
    """

    photo_url = fetch_player_photo_url(player_id)

    if not photo_url:
        return Response(status_code=404)

    from fastapi.responses import RedirectResponse

    return RedirectResponse(
        url=photo_url,
        status_code=307,
        headers={
            "Cache-Control": "public, max-age=86400"
        }
    )


@app.get("/api/matches")
def get_matches():

    if not STATPAL_KEY:
        return [{
            "id": "0",
            "league_id": "0",
            "league": "Hiba",
            "home_team": "StatPal Kulcs Hiányzik",
            "away_team": "Render Environment-ben",
            "home_score": 0,
            "away_score": 0,
            "status": "error",
            "minute": 0
        }]

    # Gyors válasz: legutóbbi sikeres lista (25 mp)
    now_c = time.time()
    cached_list = _matches_list_cache.get("data")
    if (
        isinstance(cached_list, list)
        and cached_list
        and (now_c - _matches_list_cache.get("ts", 0)) < MATCHES_LIST_TTL
        and not (len(cached_list) == 1 and str(cached_list[0].get("id")) in ("err", "0"))
    ):
        return cached_list

    try:
        matches_list = []
        today_iso = datetime.now().strftime("%Y-%m-%d")

        # StatPal + Highlightly PÁRHUZAMOSAN – kevesebb várakozás, ugyanaz az adat
        data = {}
        highlights_data = []
        hl_day = []
        with ThreadPoolExecutor(max_workers=3) as pool:
            f_sp = pool.submit(fetch_statpal_matches)
            f_hl_hi = pool.submit(fetch_highlightly_highlights)
            f_hl_day = pool.submit(fetch_highlightly_matches_by_date, today_iso, 100)
            try:
                data = f_sp.result() or {}
            except Exception:
                data = _statpal_cache.get("data") or {}
            try:
                highlights_data = f_hl_hi.result() or []
            except Exception:
                highlights_data = []
            try:
                hl_day = f_hl_day.result() or []
            except Exception:
                hl_day = []

        if not isinstance(data, dict):
            data = {}
        if not isinstance(highlights_data, list):
            highlights_data = []

        live_matches_data = (
            data.get("live_matches")
            or data.get("matches")
            or {}
        )

        if not isinstance(live_matches_data, dict):
            live_matches_data = {}

        leagues = ensure_list(
            live_matches_data.get("league")
        )

        hl_id_lookup = {}
        try:
            hl_id_lookup = _build_hl_id_lookup(hl_day)
        except Exception:
            hl_id_lookup = {}

        for league in leagues:

            if not isinstance(league, dict):
                continue

            league_id = str(
                league.get("id", "")
            )

            raw_country = league.get(
                "country",
                ""
            )

            raw_league = league.get(
                "name",
                "Egyéb Bajnokság"
            )

            full_league_title = format_league_title(
                raw_country,
                raw_league
            )

            matches = ensure_list(
                league.get("match")
            )

            for m in matches:

                if not isinstance(m, dict):
                    continue

                home_data = m.get("home") or {}
                away_data = m.get("away") or {}

                home_name = translate_text(
                    home_data.get(
                        "name",
                        "Hazai"
                    )
                )

                away_name = translate_text(
                    away_data.get(
                        "name",
                        "Vendég"
                    )
                )

                home_team_id = _get_team_id(
                    home_data
                )

                away_team_id = _get_team_id(
                    away_data
                )

                home_logo_url = _team_image_url(
                    home_team_id
                )

                away_logo_url = _team_image_url(
                    away_team_id
                )

                league_logo_url = None

                highlight_url = None
                highlight_match_id = None

                if isinstance(
                    highlights_data,
                    list
                ):

                    normalized_home = " ".join(
                        home_name.lower().split()
                    )

                    normalized_away = " ".join(
                        away_name.lower().split()
                    )

                    exact_match = None

                    # ====================================================
                    # 1. PONTOS CSAPATPÁROSÍTÁS
                    # ====================================================

                    for hl in highlights_data:

                        if not isinstance(
                            hl,
                            dict
                        ):
                            continue

                        match_obj = (
                            hl.get("match")
                            or {}
                        )

                        hl_home = " ".join(
                            str(
                                (
                                    match_obj.get(
                                        "homeTeam"
                                    )
                                    or {}
                                ).get(
                                    "name",
                                    ""
                                )
                            ).lower().split()
                        )

                        hl_away = " ".join(
                            str(
                                (
                                    match_obj.get(
                                        "awayTeam"
                                    )
                                    or {}
                                ).get(
                                    "name",
                                    ""
                                )
                            ).lower().split()
                        )

                        if (
                            hl_home == normalized_home
                            and hl_away == normalized_away
                        ) or (
                            hl_home == normalized_away
                            and hl_away == normalized_home
                        ):
                            exact_match = hl
                            break

                    # ====================================================
                    # 2. FALLBACK
                    # ====================================================

                    if exact_match is None:

                        for hl in highlights_data:

                            if not isinstance(
                                hl,
                                dict
                            ):
                                continue

                            match_obj = (
                                hl.get("match")
                                or {}
                            )

                            hl_home = " ".join(
                                str(
                                    (
                                        match_obj.get(
                                            "homeTeam"
                                        )
                                        or {}
                                    ).get(
                                        "name",
                                        ""
                                    )
                                ).lower().split()
                            )

                            hl_away = " ".join(
                                str(
                                    (
                                        match_obj.get(
                                            "awayTeam"
                                        )
                                        or {}
                                    ).get(
                                        "name",
                                        ""
                                    )
                                ).lower().split()
                            )

                            title = " ".join(
                                str(
                                    hl.get(
                                        "title",
                                        ""
                                    )
                                ).lower().split()
                            )

                            if (
                                (
                                    normalized_home
                                    in title
                                    and normalized_away
                                    in title
                                )
                                or (
                                    normalized_home
                                    in hl_home
                                    and normalized_away
                                    in hl_away
                                )
                                or (
                                    normalized_home
                                    in hl_away
                                    and normalized_away
                                    in hl_home
                                )
                                or (
                                    _teams_soft_match(normalized_home, hl_home)
                                    and _teams_soft_match(normalized_away, hl_away)
                                )
                                or (
                                    _teams_soft_match(normalized_home, hl_away)
                                    and _teams_soft_match(normalized_away, hl_home)
                                )
                            ):
                                exact_match = hl
                                break

                    # ====================================================
                    # 3. HIGHLIGHTLY SAJÁT MATCH.ID
                    # ====================================================

                    if exact_match is not None:

                        match_obj = (
                            exact_match.get(
                                "match"
                            )
                            or {}
                        )

                        raw_hl_match_id = (
                            match_obj.get("id")
                        )

                        if raw_hl_match_id is not None:
                            highlight_match_id = str(
                                raw_hl_match_id
                            )

                        highlight_url = (
                            exact_match.get(
                                "embedUrl"
                            )
                            or exact_match.get(
                                "url"
                            )
                        )

                # 4. Gyors HL id a előre épített lookup-ból (NEM API hívás / meccs)
                if not highlight_match_id and hl_id_lookup:
                    try:
                        highlight_match_id = _lookup_hl_id(
                            hl_id_lookup, home_name, away_name
                        )
                    except Exception:
                        pass

                # ========================================================
                # GÓLOK
                # ========================================================

                try:
                    home_score = int(
                        home_data.get(
                            "goals",
                            0
                        )
                    )
                except:
                    home_score = 0

                try:
                    away_score = int(
                        away_data.get(
                            "goals",
                            0
                        )
                    )
                except:
                    away_score = 0

                # ========================================================
                # PERC
                # ========================================================

                minute_val = 0

                events_container = (
                    m.get("events")
                    or {}
                )

                if not isinstance(
                    events_container,
                    dict
                ):
                    events_container = {}

                events = ensure_list(
                    events_container.get(
                        "event"
                    )
                )

                if events:

                    try:
                        last_event = events[-1]

                        minute_val = int(
                            last_event.get(
                                "minute",
                                0
                            )
                        ) if isinstance(
                            last_event,
                            dict
                        ) else 0

                    except:
                        minute_val = 0

                # ========================================================
                # STÁTUSZ
                # ========================================================

                raw_status = m.get(
                    "status",
                    "live"
                )

                adjusted_status = adjust_time(
                    raw_status
                )

                # Kickoff dátum/idő (naptárhoz)
                # StatPal live/today → általában ma; ha van date mező, azt használjuk.
                raw_date = (
                    m.get("date")
                    or m.get("match_date")
                    or m.get("formatted_date")
                )
                kickoff_date = _normalize_date_str(raw_date) if raw_date else datetime.now().strftime("%Y-%m-%d")
                kickoff_time = None
                st = str(adjusted_status or "")
                if ":" in st and len(st) <= 5 and st.replace(":", "").isdigit():
                    kickoff_time = st
                elif isinstance(m.get("time"), str) and ":" in str(m.get("time")):
                    try:
                        kickoff_time = adjust_time(str(m.get("time"))[:5])
                    except Exception:
                        kickoff_time = str(m.get("time"))[:5]
                # StatPal time mező külön
                if not kickoff_time and m.get("time"):
                    try:
                        kickoff_time = adjust_time(str(m.get("time"))[:5])
                    except Exception:
                        pass

                # ========================================================
                # MECCS
                # ========================================================

                matches_list.append({
                    "id": str(
                        m.get(
                            "main_id",
                            ""
                        )
                    ),

                    "league_id": league_id,

                    "league": full_league_title,

                    "country": translate_text(
                        raw_country
                    ),

                    "country_code": _country_code(
                        raw_country
                    ),

                    "league_logo_url": league_logo_url,

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

                    "value_bet": (
                        True
                        if m.get(
                            "inplay_odds_running"
                        ) == "True"
                        else False
                    ),

                    "events": _normalize_statpal_events(
                        events
                    ),
                    "odds_home": _extract_odds(m)[0],
                    "odds_draw": _extract_odds(m)[1],
                    "odds_away": _extract_odds(m)[2],
                    "kickoff_date": kickoff_date,
                    "kickoff_time": kickoff_time,
                })

        if not matches_list:

            return [{
                "id": "0",
                "league_id": "0",
                "league": "Információ",
                "home_team": "Jelenleg nincs",
                "away_team": "aktív mérkőzés",
                "home_score": None,
                "away_score": None,
                "status": "info",
                "minute": 0
            }]

        # ================================================================
        # KIEMELT LIGÁK SORRENDJE
        # ================================================================

        def get_league_sort_key(item):

            league_title = str(
                item.get("league") or ""
            ).strip()

            if league_title in TOP_LEAGUES_ORDER:
                return (
                    0,
                    TOP_LEAGUES_ORDER.index(
                        league_title
                    )
                )

            # A kiemelt 5 liga után minden más bajnokság
            # magyar ábécé szerint következik.
            return (
                1,
                _hungarian_sort_key(league_title)
            )

        matches_list.sort(
            key=get_league_sort_key
        )

        _matches_list_cache["data"] = matches_list
        _matches_list_cache["ts"] = time.time()
        return matches_list

    except Exception as e:
        # Friss cache ha van (ne tűnjenek el a meccsek)
        cached = _matches_list_cache.get("data")
        if isinstance(cached, list) and cached and not (
            len(cached) == 1 and str(cached[0].get("id")) in ("err", "0")
        ):
            return cached
        # Highlightly teljes napi lista (lapozva)
        try:
            today = datetime.now().strftime("%Y-%m-%d")
            hl_raw = fetch_highlightly_matches_by_date(today, limit=100)
            result = []
            for m in hl_raw:
                item = _normalize_hl_match_item(m)
                if item.get("id"):
                    result.append(item)
            if result:
                _matches_list_cache["data"] = result
                _matches_list_cache["ts"] = time.time()
                return result
        except Exception:
            pass
        return [{
            "id": "err",
            "league_id": "0",
            "league": "Szerver hiba",
            "home_team": "API átmenetileg",
            "away_team": "nem elérhető",
            "home_score": None,
            "away_score": None,
            "status": "error",
            "minute": 0,
            "kickoff_date": datetime.now().strftime("%Y-%m-%d"),
        }]


@app.get("/api/standings/{league_id}")
def get_standings(league_id: str):

    if not STATPAL_KEY or not league_id:
        return []

    try:

        url = (
            f"https://statpal.io/api/v2/soccer/leagues/"
            f"{league_id}/standings"
            f"?access_key={STATPAL_KEY}"
        )

        res = requests.get(
            url,
            timeout=10
        )

        if res.status_code != 200:
            return []

        data = res.json()

        standings_data = (
            data.get("standings", {})
        )

        tournament = (
            standings_data.get(
                "tournament",
                {}
            )
        )

        if (
            isinstance(tournament, list)
            and len(tournament) > 0
        ):
            tournament = tournament[0]

        team_list = ensure_list(
            tournament.get("team")
        )

        standings = []

        for t in team_list:

            if not isinstance(
                t,
                dict
            ):
                continue

            overall = t.get(
                "overall",
                {}
            )

            total = t.get(
                "total",
                {}
            )

            standings.append({

                "position": int(
                    t.get(
                        "position",
                        0
                    )
                ),

                "team": translate_text(
                    t.get(
                        "name",
                        "Csapat"
                    )
                ),

                "played": int(
                    overall.get(
                        "games_played",
                        0
                    )
                ),

                "wins": int(
                    overall.get(
                        "wins",
                        0
                    )
                ),

                "draws": int(
                    overall.get(
                        "draws",
                        0
                    )
                ),

                "losses": int(
                    overall.get(
                        "losses",
                        0
                    )
                ),

                "goalsScored": int(
                    overall.get(
                        "goals_scored",
                        0
                    )
                ),

                "goalsAllowed": int(
                    overall.get(
                        "goals_allowed",
                        0
                    )
                ),

                "goalDifference": str(
                    total.get(
                        "goal_difference",
                        "0"
                    )
                ),

                "points": int(
                    total.get(
                        "points",
                        0
                    )
                )
            })

        standings.sort(
            key=lambda x: x["position"]
        )

        return standings

    except Exception:
        return []




@app.get("/api/matches/highlightly/{highlight_match_id}/detail")
def get_highlightly_match_detail(highlight_match_id: str):
    """Highlightly teljes meccs részlet (events, stats, venue, predictions)."""
    data = fetch_highlightly_match_detail(highlight_match_id)
    if not data:
        return {"error": "not_found"}
    events = data.get("events") or []
    norm_events = []
    for ev in events if isinstance(events, list) else []:
        if not isinstance(ev, dict):
            continue
        team_obj = ev.get("team") if isinstance(ev.get("team"), dict) else {}
        norm_events.append({
            "type": str(ev.get("type") or "").lower(),
            "team_name": team_obj.get("name"),
            "minute_display": str(ev.get("time") or ""),
            "player": ev.get("player"),
            "assist": ev.get("assist"),
            "substituted": ev.get("substituted"),
        })
    state = data.get("state") if isinstance(data.get("state"), dict) else {}
    score = state.get("score") if isinstance(state.get("score"), dict) else {}
    home_t = data.get("homeTeam") if isinstance(data.get("homeTeam"), dict) else {}
    away_t = data.get("awayTeam") if isinstance(data.get("awayTeam"), dict) else {}
    return {
        "id": data.get("id"),
        "round": data.get("round"),
        "date": data.get("date"),
        "venue": data.get("venue"),
        "referee": data.get("referee"),
        "forecast": data.get("forecast"),
        "state": state,
        "score_current": score.get("current"),
        "clock": state.get("clock"),
        "status_description": state.get("description"),
        "events": norm_events,
        "statistics": _normalize_statistics(data.get("statistics") or []),
        "predictions": data.get("predictions"),
        "home_team": home_t.get("name"),
        "away_team": away_t.get("name"),
        "hl_home_team_id": home_t.get("id"),
        "hl_away_team_id": away_t.get("id"),
    }


@app.get("/api/matches/highlightly/{highlight_match_id}/lineups")
def get_match_lineups(highlight_match_id: str):
    """Highlightly összeállítás (kezdő + pad) – soha ne dobjon 500-at."""
    try:
        raw = fetch_highlightly_lineups(highlight_match_id)
        if not raw:
            return {"home": None, "away": None, "available": False}
        normalized = _normalize_lineups(raw)
        if not isinstance(normalized, dict):
            return {"home": None, "away": None, "available": False}
        normalized["available"] = bool(
            (normalized.get("home") and normalized["home"].get("players"))
            or (normalized.get("away") and normalized["away"].get("players"))
        )
        return normalized
    except Exception as e:
        return {"home": None, "away": None, "available": False, "error": str(e)[:120]}


@app.get("/api/matches/highlightly/{highlight_match_id}/statistics")
def get_match_statistics(highlight_match_id: str):
    """Highlightly meccs statisztika – soha ne dobjon 500-at."""
    try:
        raw = fetch_highlightly_statistics(highlight_match_id)
        items = _normalize_statistics(raw) if raw else []
        if not isinstance(items, list):
            items = []
        return {
            "items": items,
            "available": bool(items),
        }
    except Exception as e:
        return {
            "items": [],
            "available": False,
            "error": str(e)[:120],
        }




def _resolve_hl_id_for_match(match_id: str):
    """StatPal match_id → Highlightly id (detail + név-alapú resolve)."""
    try:
        detail = get_match_detail(match_id)
    except Exception:
        detail = None
    if not isinstance(detail, dict) or detail.get("error"):
        return None, detail
    hid = detail.get("highlight_match_id")
    if hid:
        return str(hid).strip(), detail
    try:
        hid = resolve_highlightly_match_id(
            detail.get("home_team") or "",
            detail.get("away_team") or "",
            detail.get("kickoff_date"),
        )
        if hid:
            detail = dict(detail)
            detail["highlight_match_id"] = hid
            return str(hid), detail
    except Exception:
        pass
    return None, detail


@app.get("/api/matches/{match_id}/lineups")
def get_match_lineups_by_statpal(match_id: str):
    """Összeállítás StatPal match id-ról – HL feloldással."""
    hid, _ = _resolve_hl_id_for_match(match_id)
    if not hid:
        return {"home": None, "away": None, "available": False, "reason": "no_highlightly_id"}
    return get_match_lineups(hid)


@app.get("/api/matches/{match_id}/statistics")
def get_match_statistics_by_statpal(match_id: str):
    """Statisztika StatPal match id-ról – HL feloldással."""
    hid, _ = _resolve_hl_id_for_match(match_id)
    if not hid:
        return {"items": [], "available": False, "reason": "no_highlightly_id"}
    return get_match_statistics(hid)


@app.get("/api/matches/{match_id}")
def get_match_detail(match_id: str):
    """
    StatPal meccs részlet main_id alapján.
    Events + alap score/status a live/today cache-ből.
    """
    if not match_id or match_id in ("0", "err"):
        return {"error": "invalid_match_id"}

    now = time.time()
    cache_key = f"statpal:{match_id}"
    cached = _detail_cache.get(cache_key)
    if cached and (now - cached["ts"]) < DETAIL_CACHE_TTL:
        return cached["data"]

    # Először a már felépített lista válaszból keresünk (highlight_id is megvan)
    try:
        all_matches = get_matches()
        if isinstance(all_matches, list):
            for item in all_matches:
                if str(item.get("id") or "") == str(match_id):
                    # Élő meccs: ha nincs HL id, feloldás /matches?date=
                    if not item.get("highlight_match_id"):
                        try:
                            hid = resolve_highlightly_match_id(
                                item.get("home_team") or "",
                                item.get("away_team") or "",
                                item.get("kickoff_date"),
                            )
                            if hid:
                                item = dict(item)
                                item["highlight_match_id"] = hid
                        except Exception:
                            pass
                    item = enrich_odds_from_highlightly(dict(item) if isinstance(item, dict) else item)
                    _detail_cache[cache_key] = {"data": item, "ts": now}
                    return item
    except Exception:
        pass

    raw, league = _find_statpal_raw_match(match_id)
    if not raw:
        return {"error": "not_found", "id": match_id}

    home_data = raw.get("home") or {}
    away_data = raw.get("away") or {}
    events_container = raw.get("events") or {}
    if not isinstance(events_container, dict):
        events_container = {}
    events = ensure_list(events_container.get("event"))
    events_norm = _normalize_statpal_events(events)

    minute_val = 0
    if events_norm:
        last = events_norm[-1]
        if last.get("minute") is not None:
            minute_val = last["minute"]

    try:
        home_score = int(home_data.get("goals", 0) or 0)
    except Exception:
        home_score = 0
    try:
        away_score = int(away_data.get("goals", 0) or 0)
    except Exception:
        away_score = 0

    league_id = str((league or {}).get("id", ""))
    raw_country = (league or {}).get("country", "")
    raw_league = (league or {}).get("name", "")
    full_league = format_league_title(raw_country, raw_league) if raw_league else ""

    oh, od, oa = _extract_odds(raw)
    payload = {
        "id": str(match_id),
        "league_id": league_id,
        "league": full_league,
        "country": translate_text(raw_country),
        "country_code": _country_code(raw_country),
        "home_team": translate_text(home_data.get("name", "Hazai")),
        "away_team": translate_text(away_data.get("name", "Vendég")),
        "home_logo_url": _team_image_url(_get_team_id(home_data)),
        "away_logo_url": _team_image_url(_get_team_id(away_data)),
        "home_score": home_score,
        "away_score": away_score,
        "status": adjust_time(raw.get("status", "live")),
        "minute": minute_val,
        "events": events_norm,
        "value_bet": True if raw.get("inplay_odds_running") == "True" else False,
        "odds_home": oh,
        "odds_draw": od,
        "odds_away": oa,
        "venue": None,
        "referee": None,
        "highlight_match_id": None,
        "highlight_url": None,
    }

    # Highlightly extras: venue, referee, jobb events ha van match id a listából
    try:
        all_m = get_matches()
        if isinstance(all_m, list):
            for item in all_m:
                if str(item.get("id") or "") == str(match_id):
                    payload["highlight_match_id"] = item.get("highlight_match_id")
                    payload["highlight_url"] = item.get("highlight_url")
                    if item.get("odds_home") is not None:
                        payload["odds_home"] = item.get("odds_home")
                        payload["odds_draw"] = item.get("odds_draw")
                        payload["odds_away"] = item.get("odds_away")
                    if item.get("events"):
                        payload["events"] = item.get("events")
                    break
        hl_id = payload.get("highlight_match_id")
        if hl_id:
            hl = fetch_highlightly_match_detail(str(hl_id))
            if isinstance(hl, dict):
                venue = hl.get("venue")
                if isinstance(venue, dict):
                    payload["venue"] = venue.get("name") or venue.get("venue_name")
                elif isinstance(venue, str):
                    payload["venue"] = venue
                ref = hl.get("referee")
                if isinstance(ref, dict):
                    payload["referee"] = ref.get("name")
                elif isinstance(ref, str):
                    payload["referee"] = ref
                payload["hl_home_team_id"] = hl.get("hl_home_team_id") or (
                    (hl.get("homeTeam") or {}).get("id") if isinstance(hl.get("homeTeam"), dict) else None
                )
                payload["hl_away_team_id"] = hl.get("hl_away_team_id") or (
                    (hl.get("awayTeam") or {}).get("id") if isinstance(hl.get("awayTeam"), dict) else None
                )
                if hl.get("predictions") is not None:
                    payload["predictions"] = hl.get("predictions")
                if hl.get("forecast") is not None:
                    payload["forecast"] = hl.get("forecast")
    except Exception:
        pass

    payload = enrich_odds_from_highlightly(payload if isinstance(payload, dict) else {})
    _detail_cache[cache_key] = {"data": payload, "ts": now}
    return payload





@app.get("/api/matches/{match_id}/h2h")
def get_match_h2h(match_id: str):
    """Highlightly GET /head-2-head – team ID-k a match detailből."""
    detail = get_match_detail(match_id)
    if not isinstance(detail, dict) or detail.get("error"):
        return {"items": [], "available": False, "message": "Meccs nem található."}

    home = detail.get("home_team") or ""
    away = detail.get("away_team") or ""
    items = []

    home_id = detail.get("hl_home_team_id")
    away_id = detail.get("hl_away_team_id")

    # team ID a Highlightly match detailből, ha még nincs
    hl_id = detail.get("highlight_match_id")
    if (not home_id or not away_id) and hl_id:
        try:
            hl = fetch_highlightly_match_detail(str(hl_id))
            if isinstance(hl, dict):
                ht = hl.get("homeTeam") if isinstance(hl.get("homeTeam"), dict) else {}
                at = hl.get("awayTeam") if isinstance(hl.get("awayTeam"), dict) else {}
                home_id = home_id or ht.get("id")
                away_id = away_id or at.get("id")
        except Exception:
            pass

    if home_id and away_id:
        raw_list = fetch_highlightly_h2h(home_id, away_id)
        for m in raw_list[:10]:
            if not isinstance(m, dict):
                continue
            state = m.get("state") if isinstance(m.get("state"), dict) else {}
            score = state.get("score") if isinstance(state.get("score"), dict) else {}
            current = str(score.get("current") or "")
            hs = as_ = None
            if "-" in current:
                parts = current.replace(" ", "").split("-")
                if len(parts) >= 2:
                    hs, as_ = parts[0], parts[1]
            league = m.get("league") if isinstance(m.get("league"), dict) else {}
            items.append({
                "date": m.get("date"),
                "home_team": (m.get("homeTeam") or {}).get("name") if isinstance(m.get("homeTeam"), dict) else None,
                "away_team": (m.get("awayTeam") or {}).get("name") if isinstance(m.get("awayTeam"), dict) else None,
                "home_score": hs,
                "away_score": as_,
                "competition": league.get("name") if isinstance(league, dict) else None,
            })

    return {
        "items": items,
        "available": bool(items),
        "message": None if items else "Nincs elérhető H2H adat ehhez a párosításhoz.",
        "home_team": home,
        "away_team": away,
        "home_team_id": home_id,
        "away_team_id": away_id,
    }


@app.get("/api/matches/{match_id}/form")
def get_match_form(match_id: str):
    """Utolsó 5 meccs forma mindkét csapatra (Highlightly /last-five-games)."""
    detail = get_match_detail(match_id)
    if not isinstance(detail, dict) or detail.get("error"):
        return {"home": [], "away": [], "available": False}

    home_id = detail.get("hl_home_team_id")
    away_id = detail.get("hl_away_team_id")
    hl_id = detail.get("highlight_match_id")
    if (not home_id or not away_id) and hl_id:
        hl = fetch_highlightly_match_detail(str(hl_id))
        if isinstance(hl, dict):
            ht = hl.get("homeTeam") if isinstance(hl.get("homeTeam"), dict) else {}
            at = hl.get("awayTeam") if isinstance(hl.get("awayTeam"), dict) else {}
            home_id = home_id or ht.get("id")
            away_id = away_id or at.get("id")

    def _form_from_games(games, team_id):
        form = []
        tid = str(team_id)
        for g in (games or [])[:5]:
            if not isinstance(g, dict):
                continue
            state = g.get("state") if isinstance(g.get("state"), dict) else {}
            score = state.get("score") if isinstance(state.get("score"), dict) else {}
            current = str(score.get("current") or "").replace(" ", "")
            if "-" not in current:
                continue
            parts = current.split("-")
            try:
                hs, aws = int(parts[0]), int(parts[1])
            except Exception:
                continue
            home = g.get("homeTeam") if isinstance(g.get("homeTeam"), dict) else {}
            is_home = str(home.get("id") or "") == tid
            my, opp = (hs, aws) if is_home else (aws, hs)
            if my > opp:
                form.append("W")
            elif my < opp:
                form.append("L")
            else:
                form.append("D")
        return form

    home_form = _form_from_games(fetch_highlightly_last_five(home_id), home_id) if home_id else []
    away_form = _form_from_games(fetch_highlightly_last_five(away_id), away_id) if away_id else []

    return {
        "home": home_form,
        "away": away_form,
        "home_team": detail.get("home_team"),
        "away_team": detail.get("away_team"),
        "available": bool(home_form or away_form),
    }


@app.get("/api/matches/by-date/{date}")
def get_matches_by_date(date: str):
    """
    Naptár nap: először StatPal ma-lista szűrve, plusz Highlightly /matches?date=.
    date: YYYY-MM-DD
    """
    date_iso = _normalize_date_str(date)
    today = datetime.now().strftime("%Y-%m-%d")

    # Mai nap: a meglévő get_matches (StatPal rich data)
    if date_iso == today:
        try:
            all_m = get_matches()
            if isinstance(all_m, list):
                filtered = [
                    m for m in all_m
                    if isinstance(m, dict)
                    and _normalize_date_str(m.get("kickoff_date") or today) == date_iso
                ]
                if filtered:
                    return filtered
                # ha nincs kickoff_date a listában, az egész mai lista
                return [m for m in all_m if isinstance(m, dict) and m.get("id") not in ("0", "err")]
        except Exception:
            pass

    # Más nap / üres: Highlightly date query
    hl_raw = fetch_highlightly_matches_by_date(date_iso, limit=100)
    result = []
    for m in hl_raw:
        item = _normalize_hl_match_item(m)
        if item.get("id"):
            result.append(item)
    return result



_ai_analysis_cache = {}
AI_ANALYSIS_TTL = 600  # 10 perc

_gemini_last_error = ""
_gemini_models_cache = {"ts": 0.0, "ids": []}


def _gemini_list_models(key: str) -> list:
    """A kulcshoz elérhető generateContent modellek (cache 1 óra)."""
    now = time.time()
    if _gemini_models_cache["ids"] and (now - _gemini_models_cache["ts"]) < 3600:
        return list(_gemini_models_cache["ids"])
    ids = []
    try:
        url = f"https://generativelanguage.googleapis.com/v1beta/models?key={key}"
        resp = requests.get(url, timeout=15)
        if resp.status_code == 200:
            for m in (resp.json().get("models") or []):
                name = (m.get("name") or "").replace("models/", "")
                methods = m.get("supportedGenerationMethods") or m.get("supported_generation_methods") or []
                if not name:
                    continue
                if methods and "generateContent" not in methods:
                    continue
                ids.append(name)
    except Exception:
        pass
    # Preferencia: újabb flash/pro elöl (3.5 → 2.5 → 2.0 …)
    def _rank(mid: str) -> tuple:
        s = mid.lower()
        score = 0
        if "3.5" in s or "3-5" in s:
            score = 50
        elif "2.5" in s or "2-5" in s:
            score = 40
        elif "2.0" in s or "2-0" in s:
            score = 30
        elif "1.5" in s:
            score = 10
        if "flash" in s:
            score += 3
        if "pro" in s:
            score += 2
        if "exp" in s or "preview" in s:
            score -= 1
        return (-score, s)

    ids = sorted(set(ids), key=_rank)
    if ids:
        _gemini_models_cache["ids"] = ids
        _gemini_models_cache["ts"] = now
    return ids


def _clean_gemini_text(text: str) -> str:
    """Draft / thinking / markdown zaj kiszűrése – csak a végleges elemzés."""
    if not text:
        return text
    # Ha van egyértelmű végleges blokk
    for marker in (
        "Draft 2 (Fully integrated)",
        "Fully integrated",
        "Végleges elemzés",
        "Összegzés:",
        "**Összegzés**",
    ):
        idx = text.find(marker)
        if idx >= 0:
            # vegyük a markertől (vagy utána) a végéig
            chunk = text[idx:]
            # ha a marker angol draft, ugorjuk a sort
            if marker.lower().startswith("draft") or "polish" in marker.lower():
                nl = chunk.find("\n")
                chunk = chunk[nl + 1 :] if nl >= 0 else chunk
            text = chunk
            break
    lines = []
    for line in text.splitlines():
        low = line.strip().lower()
        if not low:
            lines.append(line)
            continue
        if low.startswith("let's polish") or low.startswith("lets polish"):
            continue
        if "draft 1" in low or "draft 2" in low or "draft 3" in low:
            continue
        if low.startswith("*draft") or low.startswith("**draft"):
            continue
        if "thinking" in low and len(low) < 40:
            continue
        lines.append(line)
    out = "\n".join(lines).strip()
    # markdown ** egyszerűsítés opcionális – hagyjuk
    return out


def _call_gemini(prompt: str) -> Optional[str]:
    """Gemini generateContent – GEMINI_KEY; 3.5+ modellek előnyben."""
    global _gemini_last_error
    key = (GEMINI_KEY or "").strip().strip('"').strip("'")
    if not key:
        _gemini_last_error = "GEMINI_KEY üres"
        return None

    # Először a kulcshoz listázott modellek (3.5 / 2.5 elöl), aztán fallback lista
    preferred = [
        "gemini-3.5-flash",
        "gemini-3.5-pro",
        "gemini-3.0-flash",
        "gemini-3.0-pro",
        "gemini-2.5-flash",
        "gemini-2.5-flash-preview-05-20",
        "gemini-2.5-pro",
        "gemini-2.5-pro-preview-05-06",
        "gemini-2.0-flash",
        "gemini-2.0-flash-001",
        "gemini-1.5-flash",
        "gemini-1.5-pro",
    ]
    listed = _gemini_list_models(key)
    # listed elöl, aztán preferred – duplikátum nélkül
    models = []
    for m in listed + preferred:
        if m not in models:
            models.append(m)
    if not models:
        models = preferred

    body = {
        "contents": [{"role": "user", "parts": [{"text": prompt}]}],
        "generationConfig": {
            "temperature": 0.7,
            "maxOutputTokens": 4096,
        },
    }
    errors = []
    for model in models[:12]:  # max 12 próba
        for ver in ("v1beta", "v1"):
            try:
                url = (
                    f"https://generativelanguage.googleapis.com/{ver}/models/"
                    f"{model}:generateContent?key={key}"
                )
                resp = requests.post(url, json=body, timeout=35)
                if resp.status_code != 200:
                    try:
                        err = resp.json()
                        msg = (err.get("error") or {}).get("message") or resp.text[:180]
                    except Exception:
                        msg = resp.text[:180]
                    errors.append(f"{model}/{ver}: HTTP {resp.status_code} {msg}")
                    continue
                data = resp.json()
                cands = data.get("candidates") or []
                if not cands:
                    errors.append(f"{model}/{ver}: üres candidates")
                    continue
                parts = (cands[0].get("content") or {}).get("parts") or []
                texts = [
                    p.get("text") for p in parts
                    if isinstance(p, dict) and p.get("text")
                ]
                text = "\n".join(texts).strip()
                if text:
                    text = _clean_gemini_text(text)
                    if text:
                        _gemini_last_error = ""
                        return text
                errors.append(f"{model}/{ver}: üres szöveg")
            except Exception as e:
                errors.append(f"{model}/{ver}: {type(e).__name__}: {e}")
                continue
    _gemini_last_error = " | ".join(errors[:5]) if errors else "ismeretlen hiba"
    return None



def _normalize_all_odds_markets(odds_list) -> list:
    """
    Highlightly odds → egységes piaclista:
    [{ market, bookmaker, bookmaker_id, type, values: [{label, odd}] }]
    """
    out = []
    if not isinstance(odds_list, list):
        return out
    for entry in odds_list:
        if not isinstance(entry, dict):
            continue
        blocks = entry.get("odds") if isinstance(entry.get("odds"), list) else None
        if blocks is None:
            blocks = [entry]
        for b in blocks:
            if not isinstance(b, dict):
                continue
            market = str(b.get("market") or b.get("name") or b.get("bet") or "Piac").strip()
            bookmaker = str(b.get("bookmakerName") or b.get("bookmaker") or "").strip()
            bookmaker_id = b.get("bookmakerId") or b.get("bookmaker_id")
            otype = str(b.get("type") or b.get("oddsType") or "").strip() or None
            vals_raw = b.get("values") or b.get("odds") or b.get("outcomes") or []
            values = []
            if isinstance(vals_raw, dict):
                for k, v in vals_raw.items():
                    try:
                        values.append({"label": str(k), "odd": float(v)})
                    except Exception:
                        continue
            elif isinstance(vals_raw, list):
                for v in vals_raw:
                    if not isinstance(v, dict):
                        continue
                    label = str(
                        v.get("value") or v.get("label") or v.get("name")
                        or v.get("selection") or v.get("outcome") or ""
                    ).strip()
                    raw_odd = v.get("odd")
                    if raw_odd is None:
                        raw_odd = v.get("price") or v.get("odds") or v.get("decimal")
                    try:
                        odd = float(raw_odd)
                    except Exception:
                        continue
                    if label:
                        values.append({"label": label, "odd": odd})
            if not values:
                continue
            out.append({
                "market": market,
                "bookmaker": bookmaker or None,
                "bookmaker_id": bookmaker_id,
                "type": otype,
                "values": values,
            })
    def rank(m):
        name = (m.get("market") or "").lower()
        if any(x in name for x in ("full time", "1x2", "match winner", "match result", "ft result")):
            return (0, name)
        if "both teams" in name or "btts" in name:
            return (1, name)
        if "over" in name or "under" in name or "total" in name:
            return (2, name)
        if "double chance" in name:
            return (3, name)
        return (9, name)
    out.sort(key=rank)
    return out



def _compact_odds_markets(markets: list, max_bookmakers: int = 3) -> list:
    """
    1500+ sor → rendezett, rövid lista.
    - market+label szerint legjobb odd (1 bookmaker)
    - Correct Score: csak 0:0–3:3
    - max_bookmakers: opcionálisan top N bookmaker / market (alapból 1 legjobb / kimenet)
    Vissza: [{market, category, values: [{label, odd, bookmaker}]}]
    """
    if not isinstance(markets, list):
        return []

    def category(name: str) -> str:
        n = (name or "").lower()
        if any(x in n for x in ("full time", "1x2", "match winner", "match result", "match odds")):
            return "1x2"
        if "both teams" in n or "btts" in n:
            return "btts"
        if "double chance" in n:
            return "double_chance"
        if "asian handicap" in n or (n.startswith("handicap") and "card" not in n):
            return "asian_handicap"
        if "total goals" in n or ("total" in n and "goal" in n and "corner" not in n and "card" not in n):
            return "total_goals"
        # Szögletek
        if "corner" in n:
            return "corners"
        # Lapok (sárga / piros / booking)
        if any(x in n for x in ("card", "booking", "bookings", "yellow", "red card", "total cards")):
            return "cards"
        if "correct score" in n or "exact score" in n:
            return "correct_score"
        if "draw no bet" in n:
            return "dnb"
        if "odd or even" in n or n in ("odd/even", "odd even"):
            return "odd_even"
        if "first half" in n or "1st half" in n:
            return "first_half"
        if "first team to score" in n:
            return "first_goal"
        return "other"

    def allow_correct_score(label: str) -> bool:
        # "0:0", "1:2", "3:3" – max 3-3
        s = (label or "").replace(" ", "")
        if ":" not in s:
            return True
        try:
            a, b = s.split(":", 1)
            return 0 <= int(a) <= 3 and 0 <= int(b) <= 3
        except Exception:
            return False

    # key: (market_name, label) -> best (odd, bookmaker)
    best = {}
    for m in markets:
        if not isinstance(m, dict):
            continue
        market = str(m.get("market") or "").strip()
        if not market:
            continue
        bookie = str(m.get("bookmaker") or "").strip()
        cat = category(market)
        for v in m.get("values") or []:
            if not isinstance(v, dict):
                continue
            label = str(v.get("label") or v.get("value") or "").strip()
            if not label:
                continue
            if cat == "correct_score" and not allow_correct_score(label):
                continue
            try:
                odd = float(v.get("odd"))
            except Exception:
                continue
            key = (market, label)
            prev = best.get(key)
            if prev is None or odd > prev[0]:
                best[key] = (odd, bookie, cat)

    # group by market
    by_market = {}
    for (market, label), (odd, bookie, cat) in best.items():
        by_market.setdefault(market, {"market": market, "category": cat, "values": []})
        by_market[market]["values"].append({
            "label": label,
            "odd": round(odd, 2),
            "bookmaker": bookie or None,
        })

    order = {
        "1x2": 0, "btts": 1, "double_chance": 2, "total_goals": 3,
        "asian_handicap": 4, "corners": 5, "cards": 6,
        "dnb": 7, "first_half": 8, "first_goal": 9,
        "odd_even": 10, "correct_score": 11, "other": 12,
    }
    out = list(by_market.values())
    out.sort(key=lambda x: (order.get(x.get("category"), 99), x.get("market") or ""))
    # limit total markets soft-cap
    if len(out) > 80:
        # keep priority categories fully, trim correct_score/other
        primary = [x for x in out if x.get("category") not in ("correct_score", "other")]
        secondary = [x for x in out if x.get("category") in ("correct_score", "other")]
        out = primary + secondary[: max(0, 80 - len(primary))]
    return out


@app.get("/api/odds/cache-stats")
def odds_cache_stats():
    return {
        "hits": _odds_cache_hits,
        "misses": _odds_cache_misses,
        "hit_rate": round(_odds_cache_hits / max(1, _odds_cache_hits + _odds_cache_misses), 3),
    }

@app.get("/api/matches/{match_id}/odds")
def get_match_odds(match_id: str):
    """
    Teljes odds: 1X2 + az összes Highlightly piac (bookmakerenként).
    Cache: prematch ~7h, live ~3 perc. Soha ne dobjon 500-at.
    """
    try:
        try:
            detail = get_match_detail(match_id)
        except Exception:
            detail = None
        if not isinstance(detail, dict) or detail.get("error"):
            return {
                "available": False,
                "odds_home": None,
                "odds_draw": None,
                "odds_away": None,
                "markets": [],
                "source": None,
            }
        try:
            detail = enrich_odds_from_highlightly(detail)
        except Exception:
            pass
        markets = []
        hid = detail.get("highlight_match_id")
        st = str(detail.get("status") or "").upper().replace(".", "")
        is_live = st in ("1H", "2H", "HT", "LIVE", "ET", "INPLAY")
        ot = "prematch"  # elsődleges: prematch odds / piacok
        raw = None
        if hid:
            try:
                raw = fetch_highlightly_odds(str(hid), "prematch")
                if not raw and is_live:
                    raw = fetch_highlightly_odds(str(hid), "live")
                    ot = "live"
                markets = _normalize_all_odds_markets(raw or [])
                markets = _compact_odds_markets(markets)
            except Exception as e:
                markets = []
            if detail.get("odds_home") is None and raw:
                try:
                    h, d, a = _parse_hl_1x2(raw or [])
                    if h is not None or d is not None or a is not None:
                        detail = dict(detail)
                        detail["odds_home"] = h
                        detail["odds_draw"] = d
                        detail["odds_away"] = a
                        detail["odds_source"] = detail.get("odds_source") or "highlightly"
                        detail["odds_type"] = ot
                except Exception:
                    pass
        meta = {}
        try:
            meta = _odds_last_meta if isinstance(_odds_last_meta, dict) else {}
        except Exception:
            meta = {}
        plan = meta.get("plan") if isinstance(meta.get("plan"), dict) else {}
        available = (
            detail.get("odds_home") is not None
            or detail.get("odds_draw") is not None
            or bool(markets)
        )
        return {
            "match_id": str(match_id),
            "available": available,
            "odds_home": detail.get("odds_home"),
            "odds_draw": detail.get("odds_draw"),
            "odds_away": detail.get("odds_away"),
            "value_bet": detail.get("value_bet"),
            "source": detail.get("odds_source") or ("statpal" if detail.get("odds_home") is not None else None),
            "odds_type": detail.get("odds_type") or ot,
            "highlight_match_id": detail.get("highlight_match_id"),
            "markets": markets,
            "markets_count": len(markets),
            "plan_tier": plan.get("tier") if isinstance(plan, dict) else None,
            "plan_message": plan.get("message") if isinstance(plan, dict) else None,
            "hint": None if available else "Nincs elérhető odds ehhez a meccshez.",
        }
    except Exception as e:
        return {
            "available": False,
            "odds_home": None,
            "odds_draw": None,
            "odds_away": None,
            "markets": [],
            "source": None,
            "error": str(e)[:200],
        }



@app.get("/api/ai-analysis/{match_id}")
def get_ai_analysis(match_id: str):
    """
    Valódi AI elemzés Gemini-vel (GEMINI_KEY).
    A mobil kliens MatchViewModel / MatchDetail getAiAnalysis hívását szolgálja ki.
    """
    mid = str(match_id)
    now = time.time()
    cached = _ai_analysis_cache.get(mid)
    if cached and (now - cached.get("ts", 0)) < AI_ANALYSIS_TTL:
        return cached["payload"]

    detail = None
    try:
        detail = get_match_detail(match_id)
    except Exception:
        detail = None

    home = away = status = league = ""
    home_score = away_score = None
    minute = None
    events_txt = ""
    if isinstance(detail, dict) and "error" not in detail:
        home = detail.get("home_team") or ""
        away = detail.get("away_team") or ""
        status = detail.get("status") or ""
        league = detail.get("league") or ""
        home_score = detail.get("home_score")
        away_score = detail.get("away_score")
        minute = detail.get("minute")
        evs = detail.get("events") or []
        if isinstance(evs, list) and evs:
            bits = []
            for e in evs[:12]:
                if not isinstance(e, dict):
                    continue
                m = e.get("minute_display") or (f"{e.get('minute')}'" if e.get("minute") is not None else "")
                bits.append(
                    f"{m} {e.get('type') or ''} {e.get('player') or ''} ({e.get('team') or ''})".strip()
                )
            events_txt = "; ".join(bits)

    form_txt = ""
    h2h_txt = ""
    try:
        form = get_match_form(match_id)
        if isinstance(form, dict) and "error" not in form:
            form_txt = str(form)[:800]
    except Exception:
        pass
    try:
        h2h = get_match_h2h(match_id)
        if isinstance(h2h, dict) and "error" not in h2h:
            h2h_txt = str(h2h)[:800]
    except Exception:
        pass

    if not home or not away:
        summary = "Ehhez a mérkőzéshez jelenleg nincs elég adat az AI elemzéshez."
        payload = {
            "match_id": mid,
            "summary": summary,
            "analysis": summary,
            "text": summary,
            "prediction": None,
            "available": False,
        }
        return payload

    score_txt = f"{home_score}-{away_score}" if home_score is not None and away_score is not None else "–"
    prompt = f"""Te magyar futball-elemző vagy. CSAK a kész, végleges elemzést írd ki MAGYARUL.
TILOS: angol megjegyzés, draft, "Let's polish", gondolkodás hangosan, meta-szöveg.
Írj teljes, befejezett szöveget (kb. 180–320 szó), ne vágd félbe a mondatokat.

Meccs: {home} vs {away}
Bajnokság: {league or "ismeretlen"}
Állás: {score_txt}
Státusz: {status or "ismeretlen"}{"  Perc: " + str(minute) if minute else ""}
Események: {events_txt or "nincs adat"}
Forma / H2H: {form_txt[:500] if form_txt else "nincs"} | {h2h_txt[:500] if h2h_txt else "nincs"}

Formátum (mind a 4 pont kötelező, teljes mondatokkal):
**Összegzés**
(2–3 mondat)

**Kulcspontok**
(mi dönthet a meccsen)

**Csapatok**
(erősség / gyengeség mindkét oldalon)

**Forgatókönyv**
(várható alakulás; nem fogadási tanács)
"""

    gemini_text = _call_gemini(prompt)
    if gemini_text:
        summary = gemini_text
        available = True
    else:
        # Fallback ha nincs kulcs / API hiba – mégis hasznos, nem üres
        err = _gemini_last_error or "nincs részlet"
        if not (GEMINI_KEY or "").strip():
            hint = "A GEMINI_KEY nincs beállítva a Render Environment-ben."
        else:
            hint = (
                "A GEMINI_KEY be van állítva, de az API nem adott választ. "
                f"Részlet: {err}"
            )
        summary = (
            f"{home} vs {away}. Állás: {score_txt}. Státusz: {status or 'ismeretlen'}.\n\n"
            f"{hint}"
        )
        if events_txt:
            summary += f"\n\nIsmert események: {events_txt}"
        available = False

    payload = {
        "match_id": mid,
        "summary": summary,
        "analysis": summary,
        "text": summary,
        "prediction": None,
        "available": available,
    }
    _ai_analysis_cache[mid] = {"ts": now, "payload": payload}
    return payload


@app.get("/api/status")
def get_status():

    now = time.time()

    def cache_info(
        cache,
        ttl
    ):

        if cache["data"] is None:
            return {
                "cached": False
            }

        age = now - cache["ts"]

        return {
            "cached": True,
            "age_seconds": round(
                age,
                1
            ),
            "ttl_seconds": ttl,
            "still_valid": age < ttl
        }

    return {

        "statpal": cache_info(
            _statpal_cache,
            STATPAL_CACHE_TTL
        ),

        "highlightly": cache_info(
            _highlightly_cache,
            HIGHLIGHTLY_CACHE_TTL
        ),

        "highlightly_count": (
            len(
                _highlightly_cache["data"] or []
            )
            if _highlightly_cache["data"]
            else 0
        ),

        "highlightly_match_cache_count": len(
            _highlightly_match_cache
        ),

        "player_photo_cache": {
            "redis_connected": _redis_available(),
            "memory_entries": len(
                _player_photo_memory_cache
            )
        }
    }



# =============================================================================


@app.get("/api/players/{player_id}/summary")
def get_player_summary(player_id: str):
    """Highlightly játékos összefoglaló: fotó + szezonstat ha elérhető."""
    pid = str(player_id or "").strip()
    if not pid or not HIGHLIGHTLY_KEY:
        return {"available": False, "error": "no_key_or_id"}
    out = {
        "available": False,
        "player_id": pid,
        "name": None,
        "photo": None,
        "team": None,
        "position": None,
        "season": None,
        "stats": {},
        "message": None,
    }
    try:
        photo = fetch_player_photo_url(pid)
        if photo:
            out["photo"] = photo
    except Exception:
        pass
    try:
        url = f"https://soccer.highlightly.net/players/{pid}/statistics"
        headers = {
            "x-rapidapi-key": HIGHLIGHTLY_KEY,
            "x-rapidapi-host": "football-highlights-api.p.rapidapi.com",
        }
        # highlightly host variants
        for host in (
            "soccer.highlightly.net",
            "football-highlights-api.p.rapidapi.com",
        ):
            try:
                h = dict(headers)
                if "rapidapi" in host:
                    h["x-rapidapi-host"] = host
                r = requests.get(
                    f"https://{host}/players/{pid}/statistics" if "rapidapi" in host
                    else url,
                    headers=h if "rapidapi" in host else {"x-rapidapi-key": HIGHLIGHTLY_KEY},
                    timeout=12,
                )
                if r.status_code != 200:
                    continue
                data = r.json()
                # normalize various shapes
                block = data
                if isinstance(data, dict):
                    if isinstance(data.get("data"), dict):
                        block = data["data"]
                    elif isinstance(data.get("player"), dict):
                        block = data["player"]
                    elif isinstance(data.get("statistics"), list) and data["statistics"]:
                        block = data["statistics"][0] if isinstance(data["statistics"][0], dict) else data
                if not isinstance(block, dict):
                    continue
                out["name"] = block.get("name") or block.get("playerName") or out["name"]
                out["team"] = (
                    (block.get("team") or {}).get("name")
                    if isinstance(block.get("team"), dict)
                    else block.get("team") or block.get("teamName")
                )
                out["position"] = block.get("position") or block.get("pos")
                out["season"] = block.get("season") or block.get("league")
                stats = {}
                for k in (
                    "goals", "assists", "appearances", "minutes", "yellowCards",
                    "redCards", "shots", "passes", "rating", "games", "yellow",
                    "red", "goal", "assist",
                ):
                    if block.get(k) is not None:
                        stats[k] = block.get(k)
                # nested statistics
                inner = block.get("statistics") or block.get("stats") or {}
                if isinstance(inner, dict):
                    for k, v in inner.items():
                        if v is not None and k not in stats:
                            stats[str(k)] = v
                out["stats"] = stats
                out["available"] = bool(out["name"] or stats or out["photo"])
                if out["available"]:
                    break
            except Exception as ex:
                out["message"] = str(ex)[:120]
                continue
    except Exception as ex:
        out["message"] = str(ex)[:120]
    if not out["available"] and not out["message"]:
        out["message"] = "Nincs részletes játékosadat ehhez az ID-hoz."
    return out


# FCM – gól / lap / kezdés / félidő / vége push
# Env: FCM_SERVER_KEY = Firebase Cloud Messaging legacy server key
# =============================================================================
import threading
from collections import defaultdict

FCM_SERVER_KEY = os.getenv("FCM_SERVER_KEY") or os.getenv("FIREBASE_SERVER_KEY")

# token -> set(match_id)
_fcm_subs = defaultdict(set)
# match_id -> set(token)
_fcm_match_tokens = defaultdict(set)
# match_id -> snapshot for diff
_fcm_last_state = {}
_fcm_lock = threading.Lock()
_fcm_worker_started = False


def _fcm_send(token: str, title: str, body: str, ntype: str, match_id: str):
    if not FCM_SERVER_KEY or not token:
        return False
    try:
        resp = requests.post(
            "https://fcm.googleapis.com/fcm/send",
            headers={
                "Authorization": f"key={FCM_SERVER_KEY}",
                "Content-Type": "application/json",
            },
            json={
                "to": token,
                "priority": "high",
                "data": {
                    "title": title,
                    "body": body,
                    "type": ntype,
                    "match_id": str(match_id),
                },
                "notification": {
                    "title": title,
                    "body": body,
                    "sound": "default",
                },
            },
            timeout=8,
        )
        return resp.status_code == 200
    except Exception:
        return False


def _fcm_broadcast(match_id: str, title: str, body: str, ntype: str):
    with _fcm_lock:
        tokens = list(_fcm_match_tokens.get(str(match_id), set()))
    for tok in tokens:
        _fcm_send(tok, title, body, ntype, match_id)


def _event_sig(ev: dict) -> str:
    return "|".join([
        str(ev.get("minute") or ""),
        str(ev.get("type") or ev.get("event_type") or "").lower(),
        str(ev.get("player") or ev.get("player_name") or ""),
        str(ev.get("team") or ""),
    ])


def _classify_event(ev: dict):
    t = str(ev.get("type") or ev.get("event_type") or "").lower()
    if "goal" in t or t in ("g", "penalty", "own goal"):
        return "goal", "⚽ GÓL"
    if "red" in t:
        return "red", "🟥 Piros lap"
    if "yellow" in t or "card" in t:
        return "yellow", "🟨 Sárga lap"
    return None, None


def _fcm_check_match(match_id: str, detail: dict):
    """Diff score/status/events → push."""
    if not isinstance(detail, dict):
        return
    mid = str(match_id)
    home = detail.get("home_team") or detail.get("homeTeam") or "?"
    away = detail.get("away_team") or detail.get("awayTeam") or "?"
    hs = detail.get("home_score")
    aws = detail.get("away_score")
    status = str(detail.get("status") or "")
    events = detail.get("events") or []
    if not isinstance(events, list):
        events = []

    sigs = {_event_sig(e) for e in events if isinstance(e, dict)}
    snap = {
        "hs": hs,
        "as": aws,
        "status": status.upper().replace(".", ""),
        "sigs": sigs,
    }

    with _fcm_lock:
        prev = _fcm_last_state.get(mid)

    if prev is None:
        with _fcm_lock:
            _fcm_last_state[mid] = snap
        return

    # JSON persist után a sigs lista lehet
    if isinstance(prev.get("sigs"), list):
        prev["sigs"] = set(prev["sigs"])

    # Státusz váltások
    ps = str(prev.get("status") or "")
    cs = snap["status"]
    if ps != cs:
        if cs in ("1H", "LIVE") and (ps in ("NS", "TBD", "SCHEDULED", "") or ":" in ps):
            _fcm_broadcast(mid, "🏁 Kezdés", f"{home} – {away}", "kickoff")
        elif cs == "HT":
            _fcm_broadcast(
                mid, "⏸ Félidő",
                f"{home} {hs if hs is not None else '-'} – {aws if aws is not None else '-'} {away}",
                "ht",
            )
        elif cs in ("FT", "AET", "PEN", "PENS", "FINISHED"):
            _fcm_broadcast(
                mid, "🏁 Vége",
                f"{home} {hs if hs is not None else '-'} – {aws if aws is not None else '-'} {away}",
                "ft",
            )

    # Új események
    prev_sigs = prev.get("sigs") or set()
    for e in events:
        if not isinstance(e, dict):
            continue
        s = _event_sig(e)
        if s in prev_sigs:
            continue
        ntype, label = _classify_event(e)
        if not ntype:
            continue
        player = e.get("player") or e.get("player_name") or ""
        minute = e.get("minute")
        min_s = f"{minute}' " if minute is not None else ""
        body = f"{min_s}{home} {hs if hs is not None else '-'}–{aws if aws is not None else '-'} {away}"
        if player:
            body = f"{min_s}{player} · {home} {hs if hs is not None else '-'}–{aws if aws is not None else '-'} {away}"
        _fcm_broadcast(mid, label, body, ntype)

    # Gól score-ból is (ha event nem jött)
    try:
        if prev.get("hs") is not None and hs is not None and int(hs) > int(prev["hs"]):
            _fcm_broadcast(
                mid, "⚽ GÓL",
                f"{home} {hs}–{aws} {away}",
                "goal",
            )
        if prev.get("as") is not None and aws is not None and int(aws) > int(prev["as"]):
            _fcm_broadcast(
                mid, "⚽ GÓL",
                f"{home} {hs}–{aws} {away}",
                "goal",
            )
    except Exception:
        pass

    with _fcm_lock:
        _fcm_last_state[mid] = snap


def _fcm_worker_loop():
    while True:
        try:
            with _fcm_lock:
                match_ids = list(_fcm_match_tokens.keys())
            for mid in match_ids:
                if not _fcm_match_tokens.get(mid):
                    continue
                try:
                    detail = get_match_detail(mid)
                    if isinstance(detail, dict) and not detail.get("error"):
                        _fcm_check_match(mid, detail)
                except Exception:
                    pass
        except Exception:
            pass
        time.sleep(25)


def _ensure_fcm_worker():
    global _fcm_worker_started
    if _fcm_worker_started:
        return
    if not FCM_SERVER_KEY:
        return
    _fcm_worker_started = True
    th = threading.Thread(target=_fcm_worker_loop, name="fcm-worker", daemon=True)
    th.start()


@app.post("/api/fcm/register")
def fcm_register(body: dict):
    token = str((body or {}).get("token") or "").strip()
    if not token:
        return {"ok": False, "error": "token required"}
    with _fcm_lock:
        _fcm_subs.setdefault(token, set())
    _ensure_fcm_worker()
    _fcm_persist()
    return {"ok": True, "fcm_configured": bool(FCM_SERVER_KEY)}


@app.post("/api/fcm/subscribe")
def fcm_subscribe(body: dict):
    token = str((body or {}).get("token") or "").strip()
    match_id = str((body or {}).get("match_id") or "").strip()
    if not token or not match_id:
        return {"ok": False, "error": "token and match_id required"}
    if not FCM_SERVER_KEY:
        print("[FCM] WARNING: FCM_SERVER_KEY missing – push nem megy ki")
    with _fcm_lock:
        _fcm_subs[token].add(match_id)
        _fcm_match_tokens[match_id].add(token)
    _ensure_fcm_worker()
    # első snapshot
    try:
        detail = get_match_detail(match_id)
        if isinstance(detail, dict) and not detail.get("error"):
            _fcm_check_match(match_id, detail)
    except Exception:
        pass
    _fcm_persist()
    return {"ok": True, "match_id": match_id, "fcm_configured": bool(FCM_SERVER_KEY)}


@app.post("/api/fcm/unsubscribe")
def fcm_unsubscribe(body: dict):
    token = str((body or {}).get("token") or "").strip()
    match_id = str((body or {}).get("match_id") or "").strip()
    with _fcm_lock:
        if token in _fcm_subs and match_id:
            _fcm_subs[token].discard(match_id)
        if match_id in _fcm_match_tokens and token:
            _fcm_match_tokens[match_id].discard(token)
            if not _fcm_match_tokens[match_id]:
                _fcm_match_tokens.pop(match_id, None)
                _fcm_last_state.pop(match_id, None)
    return {"ok": True}


@app.get("/api/fcm/status")
def fcm_status():
    with _fcm_lock:
        return {
            "fcm_configured": bool(FCM_SERVER_KEY),
            "tokens": len(_fcm_subs),
            "followed_matches": len(_fcm_match_tokens),
            "worker": _fcm_worker_started,
        }


@app.post("/api/fcm/test")
def fcm_test(body: dict):
    """Azonnali teszt értesítés a megadott tokenre."""
    token = str((body or {}).get("token") or "").strip()
    if not token:
        return {"ok": False, "error": "token required"}
    if not FCM_SERVER_KEY:
        return {"ok": False, "error": "FCM_SERVER_KEY missing"}
    ok = _fcm_send(
        token,
        "🔔 SportApp teszt",
        "Ha ezt látod, az FCM push működik.",
        "status",
        "test",
    )
    return {"ok": ok, "fcm_configured": True}


def _fcm_persist():
    try:
        import json
        path = "/tmp/fcm_subs.json"
        with _fcm_lock:
            data = {
                "subs": {k: list(v) for k, v in _fcm_subs.items()},
                "match_tokens": {k: list(v) for k, v in _fcm_match_tokens.items()},
            }
        with open(path, "w") as f:
            json.dump(data, f)
    except Exception:
        pass


def _fcm_load():
    try:
        import json
        path = "/tmp/fcm_subs.json"
        if not os.path.exists(path):
            return
        with open(path) as f:
            data = json.load(f)
        with _fcm_lock:
            for tok, mids in (data.get("subs") or {}).items():
                _fcm_subs[tok] = set(mids)
            for mid, toks in (data.get("match_tokens") or {}).items():
                _fcm_match_tokens[mid] = set(toks)
        if _fcm_match_tokens:
            _ensure_fcm_worker()
    except Exception:
        pass


_fcm_load()

