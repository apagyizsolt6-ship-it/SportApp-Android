from fastapi import FastAPI
from fastapi.responses import Response
import requests
import re
import os
import time
from datetime import datetime, timezone, timedelta

app = FastAPI()

STATPAL_KEY = os.getenv("STATPAL_KEY")
HIGHLIGHTLY_KEY = os.getenv("HIGHLIGHTLY_KEY")

STATPAL_CACHE_TTL = 20
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


_detail_cache = {}
_lineups_cache = {}
_stats_cache = {}
_hl_date_cache = {}
_hl_h2h_cache = {}
_hl_form_cache = {}
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


def _teams_soft_match(a: str, b: str) -> bool:
    a = (a or "").lower().strip()
    b = (b or "").lower().strip()
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


def _highlightly_headers():
    return {"x-rapidapi-key": HIGHLIGHTLY_KEY}


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
    """Highlightly GET /matches?date=YYYY-MM-DD&timezone=Europe/Budapest"""
    if not HIGHLIGHTLY_KEY or not date_iso:
        return []
    cache_key = date_iso.strip()[:10]
    now = time.time()
    cached = _hl_date_cache.get(cache_key)
    if cached and (now - cached["ts"]) < 60:
        return cached["data"]
    try:
        resp = requests.get(
            "https://soccer.highlightly.net/matches",
            headers=_highlightly_headers(),
            params={
                "date": cache_key,
                "timezone": "Europe/Budapest",
                "limit": limit,
                "offset": 0,
            },
            timeout=12,
        )
        if resp.status_code != 200:
            return []
        payload = resp.json()
        if isinstance(payload, dict):
            data = payload.get("data") or []
        elif isinstance(payload, list):
            data = payload
        else:
            data = []
        if not isinstance(data, list):
            data = []
        _hl_date_cache[cache_key] = {"data": data, "ts": now}
        return data
    except Exception:
        return []


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
        return cached["data"]
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
    if cached and (now - cached["ts"]) < LINEUPS_CACHE_TTL:
        return cached["data"]
    try:
        resp = requests.get(
            f"https://soccer.highlightly.net/lineups/{cache_key}",
            headers=_highlightly_headers(),
            timeout=10,
        )
        if resp.status_code != 200:
            return None
        data = resp.json()
        if isinstance(data, dict):
            _lineups_cache[cache_key] = {"data": data, "ts": now}
            return data
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
    """Highlightly lineups válasz → egyszerű home/away struktúra."""
    if not isinstance(raw, dict):
        return {"home": None, "away": None}

    def side(key):
        block = raw.get(key) or raw.get(key.capitalize()) or {}
        if not isinstance(block, dict):
            return None
        formation = block.get("formation") or block.get("Formation")
        initial = block.get("initialLineup") or block.get("lineup") or []
        bench = block.get("bench") or block.get("substitutes") or []
        team = block.get("team") if isinstance(block.get("team"), dict) else {}
        players = []
        # initialLineup gyakran list of rows (list of lists)
        if isinstance(initial, list):
            for row in initial:
                if isinstance(row, list):
                    for p in row:
                        if isinstance(p, dict):
                            players.append({
                                "name": p.get("name") or p.get("playerName"),
                                "number": p.get("number") or p.get("shirtNumber"),
                                "position": p.get("position"),
                                "is_bench": False,
                            })
                elif isinstance(row, dict):
                    players.append({
                        "name": row.get("name") or row.get("playerName"),
                        "number": row.get("number") or row.get("shirtNumber"),
                        "position": row.get("position"),
                        "is_bench": False,
                    })
        if isinstance(bench, list):
            for p in bench:
                if isinstance(p, dict):
                    players.append({
                        "name": p.get("name") or p.get("playerName"),
                        "number": p.get("number") or p.get("shirtNumber"),
                        "position": p.get("position"),
                        "is_bench": True,
                    })
        return {
            "team_name": team.get("name") or block.get("teamName"),
            "formation": formation,
            "players": players,
        }

    return {
        "home": side("home"),
        "away": side("away"),
    }


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
                "name": name,
                "home": vals.get(home_name),
                "away": vals.get(away_name),
            })
    elif by_name:
        only = teams_order[0] if teams_order else ""
        for name, vals in by_name.items():
            result.append({
                "name": name,
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
    now = time.time()

    if (
        _statpal_cache["data"] is not None
        and (now - _statpal_cache["ts"]) < STATPAL_CACHE_TTL
    ):
        return _statpal_cache["data"]

    headers = {"Accept": "application/json"}

    url = (
        "https://statpal.io/api/v2/soccer/matches/today"
        f"?access_key={STATPAL_KEY}"
    )

    response = requests.get(
        url,
        headers=headers,
        timeout=10
    )

    if response.status_code != 200:
        url = (
            "https://statpal.io/api/v2/soccer/matches/live"
            f"?access_key={STATPAL_KEY}"
        )

        response = requests.get(
            url,
            headers=headers,
            timeout=10
        )

    data = response.json()

    _statpal_cache["data"] = data
    _statpal_cache["ts"] = now

    return data


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
        for offset in (0, 40, 80, 120):

            resp = requests.get(
                base_url,
                headers=headers,
                params={
                    "date": today,
                    "limit": 40,
                    "offset": offset
                },
                timeout=8
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

    try:
        data = fetch_statpal_matches()

        matches_list = []

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

        # A Highlightly lista egyszer töltődik le,
        # utána ebből párosítjuk a meccseket.
        highlights_data = fetch_highlightly_highlights()

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

                # 4. FALLBACK: Highlightly napi matches (élő kupa – még nincs videó highlight)
                if not highlight_match_id:
                    try:
                        highlight_match_id = resolve_highlightly_match_id(
                            home_name, away_name, None
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

        return matches_list

    except Exception as e:

        return [{
            "id": "err",
            "league_id": "0",
            "league": "Szerver hiba",
            "home_team": "API Hiba",
            "away_team": str(e)[:20],
            "home_score": None,
            "away_score": None,
            "status": "error",
            "minute": 0
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
    """Highlightly összeállítás (kezdő + pad)."""
    raw = fetch_highlightly_lineups(highlight_match_id)
    if not raw:
        return {"home": None, "away": None, "available": False}
    normalized = _normalize_lineups(raw)
    normalized["available"] = bool(
        (normalized.get("home") and normalized["home"].get("players"))
        or (normalized.get("away") and normalized["away"].get("players"))
    )
    return normalized


@app.get("/api/matches/highlightly/{highlight_match_id}/statistics")
def get_match_statistics(highlight_match_id: str):
    """Highlightly meccs statisztika (birtoklás, lövések, stb.)."""
    raw = fetch_highlightly_statistics(highlight_match_id)
    return {
        "items": _normalize_statistics(raw),
        "available": bool(raw),
    }


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



@app.get("/api/ai-analysis/{match_id}")
def get_ai_analysis(match_id: str):
    """
    Placeholder AI elemzés endpoint.
    A mobil kliens MatchViewModel getAiAnalysis hívását szolgálja ki.
    Később ide köthető valódi modell / prompt.
    """
    detail = None
    try:
        detail = get_match_detail(match_id)
    except Exception:
        detail = None

    home = away = status = ""
    home_score = away_score = None
    if isinstance(detail, dict) and "error" not in detail:
        home = detail.get("home_team") or ""
        away = detail.get("away_team") or ""
        status = detail.get("status") or ""
        home_score = detail.get("home_score")
        away_score = detail.get("away_score")

    if home and away:
        score_txt = ""
        if home_score is not None and away_score is not None:
            score_txt = f" Állás: {home_score}-{away_score}."
        summary = (
            f"{home} vs {away}.{score_txt} "
            f"Státusz: {status or 'ismeretlen'}. "
            "Részletes AI elemzés hamarosan."
        )
    else:
        summary = "Ehhez a mérkőzéshez jelenleg nincs AI elemzés."

    return {
        "match_id": str(match_id),
        "summary": summary,
        "analysis": summary,
        "text": summary,
        "prediction": None,
        "available": False,
    }


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
        )
    }
