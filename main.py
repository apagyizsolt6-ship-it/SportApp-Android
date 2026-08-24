from fastapi import FastAPI
from fastapi.responses import Response
import requests
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


TOP_LEAGUES_ORDER = [
    "ANGLIA: Premier League",
    "SPANYOLORSZÁG: La Liga",
    "OLASZORSZÁG: Serie A",
    "NÉMETORSZÁG: Bundesliga",
    "FRANCIAORSZÁG: Ligue 1"
]


TRANSLATIONS = {
    'Afghanistan': 'Afganisztán',
    'Albania': 'Albánia',
    'Algeria': 'Algéria',
    'Andorra': 'Andorra',
    'Angola': 'Angola',
    'Argentina': 'Argentína',
    'Armenia': 'Örményország',
    'Australia': 'Ausztrália',
    'Austria': 'Ausztria',
    'Azerbaijan': 'Azerbajdzsán',
    'Bahamas': 'Bahama-szigetek',
    'Bahrain': 'Bahrein',
    'Bangladesh': 'Banglades',
    'Barbados': 'Barbados',
    'Belarus': 'Fehéroroszország',
    'Belgium': 'Belgium',
    'Belize': 'Belize',
    'Benin': 'Benin',
    'Bermuda': 'Bermuda',
    'Bhutan': 'Bhután',
    'Bolivia': 'Bolívia',
    'Bosnia and Herzegovina': 'Bosznia-Hercegovina',
    'Botswana': 'Botswana',
    'Brazil': 'Brazília',
    'Bulgaria': 'Bulgária',
    'Burkina Faso': 'Burkina Faso',
    'Burundi': 'Burundi',
    'Cambodia': 'Kambodzsa',
    'Cameroon': 'Kamerun',
    'Canada': 'Kanada',
    'Cape Verde': 'Zöld-foki-szigetek',
    'Cayman Islands': 'Kajmán-szigetek',
    'Central African Republic': 'Közép-afrikai Köztársaság',
    'Chad': 'Csád',
    'Chile': 'Chile',
    'China': 'Kína',
    'Chinese Taipei': 'Tajvan',
    'Colombia': 'Kolumbia',
    'Comoros': 'Comore-szigetek',
    'Congo': 'Kongó',
    'Congo DR': 'Kongói Demokratikus Köztársaság',
    'Costa Rica': 'Costa Rica',
    'Croatia': 'Horvátország',
    'Cuba': 'Kuba',
    'Cyprus': 'Ciprus',
    'Czech Republic': 'Csehország',
    'Czechia': 'Csehország',
    'Denmark': 'Dánia',
    'Djibouti': 'Dzsibuti',
    'Dominica': 'Dominika',
    'Dominican Republic': 'Dominikai Köztársaság',
    'Ecuador': 'Ecuador',
    'Egypt': 'Egyiptom',
    'El Salvador': 'El Salvador',
    'England': 'Anglia',
    'Equatorial Guinea': 'Egyenlítői-Guinea',
    'Eritrea': 'Eritrea',
    'Estonia': 'Észtország',
    'Eswatini': 'Eswatini',
    'Ethiopia': 'Etiópia',
    'Faroe Islands': 'Feröer-szigetek',
    'Fiji': 'Fidzsi-szigetek',
    'Finland': 'Finnország',
    'France': 'Franciaország',
    'Gabon': 'Gabon',
    'Gambia': 'Gambia',
    'Georgia': 'Grúzia',
    'Germany': 'Németország',
    'Ghana': 'Ghána',
    'Gibraltar': 'Gibraltár',
    'Greece': 'Görögország',
    'Grenada': 'Grenada',
    'Guatemala': 'Guatemala',
    'Guinea': 'Guinea',
    'Guinea-Bissau': 'Bissau-Guinea',
    'Guyana': 'Guyana',
    'Haiti': 'Haiti',
    'Honduras': 'Honduras',
    'Hong Kong': 'Hongkong',
    'Hungary': 'Magyarország',
    'Iceland': 'Izland',
    'India': 'India',
    'Indonesia': 'Indonézia',
    'Iran': 'Irán',
    'Iraq': 'Irak',
    'Ireland': 'Írország',
    'Israel': 'Izrael',
    'Italy': 'Olaszország',
    'Ivory Coast': 'Elefántcsontpart',
    'Jamaica': 'Jamaica',
    'Japan': 'Japán',
    'Jordan': 'Jordánia',
    'Kazakhstan': 'Kazahsztán',
    'Kenya': 'Kenya',
    'Kuwait': 'Kuvait',
    'Kyrgyzstan': 'Kirgizisztán',
    'Latvia': 'Lettország',
    'Lebanon': 'Libanon',
    'Lesotho': 'Lesotho',
    'Liberia': 'Libéria',
    'Libya': 'Líbia',
    'Liechtenstein': 'Liechtenstein',
    'Lithuania': 'Litvánia',
    'Luxembourg': 'Luxemburg',
    'Macau': 'Makaó',
    'Madagascar': 'Madagaszkár',
    'Malawi': 'Malawi',
    'Malaysia': 'Malajzia',
    'Maldives': 'Maldív-szigetek',
    'Mali': 'Mali',
    'Malta': 'Málta',
    'Mauritania': 'Mauritánia',
    'Mauritius': 'Mauritius',
    'Mexico': 'Mexikó',
    'Moldova': 'Moldova',
    'Monaco': 'Monaco',
    'Mongolia': 'Mongólia',
    'Montenegro': 'Montenegró',
    'Morocco': 'Marokkó',
    'Mozambique': 'Mozambik',
    'Myanmar': 'Mianmar',
    'Namibia': 'Namíbia',
    'Nepal': 'Nepál',
    'Netherlands': 'Hollandia',
    'New Zealand': 'Új-Zéland',
    'Nicaragua': 'Nicaragua',
    'Niger': 'Niger',
    'Nigeria': 'Nigéria',
    'North Korea': 'Észak-Korea',
    'North Macedonia': 'Észak-Macedónia',
    'Northern Ireland': 'Észak-Írország',
    'Norway': 'Norvégia',
    'Oman': 'Omán',
    'Pakistan': 'Pakisztán',
    'Palestine': 'Palesztina',
    'Panama': 'Panama',
    'Papua New Guinea': 'Pápua Új-Guinea',
    'Paraguay': 'Paraguay',
    'Peru': 'Peru',
    'Philippines': 'Fülöp-szigetek',
    'Poland': 'Lengyelország',
    'Portugal': 'Portugália',
    'Puerto Rico': 'Puerto Rico',
    'Qatar': 'Katar',
    'Republic of Ireland': 'Írország',
    'Romania': 'Románia',
    'Russia': 'Oroszország',
    'Rwanda': 'Ruanda',
    'Saint Kitts and Nevis': 'Saint Kitts és Nevis',
    'Saint Lucia': 'Saint Lucia',
    'Saint Vincent and the Grenadines': 'Saint Vincent és a Grenadine-szigetek',
    'San Marino': 'San Marino',
    'Saudi Arabia': 'Szaúd-Arábia',
    'Scotland': 'Skócia',
    'Senegal': 'Szenegál',
    'Serbia': 'Szerbia',
    'Seychelles': 'Seychelle-szigetek',
    'Sierra Leone': 'Sierra Leone',
    'Singapore': 'Szingapúr',
    'Slovakia': 'Szlovákia',
    'Slovenia': 'Szlovénia',
    'Somalia': 'Szomália',
    'South Africa': 'Dél-Afrika',
    'South Korea': 'Dél-Korea',
    'Korea Republic': 'Dél-Korea',
    'Republic of Korea': 'Dél-Korea',
    'South Sudan': 'Dél-Szudán',
    'Spain': 'Spanyolország',
    'Sri Lanka': 'Srí Lanka',
    'Sudan': 'Szudán',
    'Suriname': 'Suriname',
    'Sweden': 'Svédország',
    'Switzerland': 'Svájc',
    'Syria': 'Szíria',
    'Taiwan': 'Tajvan',
    'Tajikistan': 'Tádzsikisztán',
    'Tanzania': 'Tanzánia',
    'Thailand': 'Thaiföld',
    'Togo': 'Togo',
    'Trinidad and Tobago': 'Trinidad és Tobago',
    'Tunisia': 'Tunézia',
    'Turkey': 'Törökország',
    'Turkmenistan': 'Türkmenisztán',
    'Uganda': 'Uganda',
    'Ukraine': 'Ukrajna',
    'United Arab Emirates': 'Egyesült Arab Emírségek',
    'Uruguay': 'Uruguay',
    'USA': 'USA',
    'United States': 'USA',
    'United States of America': 'USA',
    'Uzbekistan': 'Üzbegisztán',
    'Venezuela': 'Venezuela',
    'Vietnam': 'Vietnám',
    'Wales': 'Wales',
    'Zambia': 'Zambia',
    'Zimbabwe': 'Zimbabwe',
    'Kosovo': 'Koszovó',
    'World': 'Nemzetközi',
    'Europe': 'Európa',
    'Oceania': 'Óceánia',
    'Asia': 'Ázsia',
    'Africa': 'Afrika',
    'South America': 'Dél-Amerika',
    'North America': 'Észak-Amerika',
    'Central America': 'Közép-Amerika',
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


# ================================================================
# TELJES KÖRŰ MAGYAR BAJNOKSÁG-NÉV NORMALIZÁLÁS
# ================================================================
LEAGUE_EXACT_TRANSLATIONS = {
    "premier league": "Premier League",
    "championship": "Championship",
    "league one": "League One",
    "league two": "League Two",
    "la liga": "La Liga",
    "laliga": "La Liga",
    "la liga 2": "La Liga 2",
    "segunda division": "Segunda División",
    "serie a": "Serie A",
    "serie b": "Serie B",
    "serie c": "Serie C",
    "bundesliga": "Bundesliga",
    "2. bundesliga": "2. Bundesliga",
    "ligue 1": "Ligue 1",
    "ligue 2": "Ligue 2",
    "liga portugal": "Liga Portugal",
    "primeira liga": "Primeira Liga",
    "eredivisie": "Eredivisie",
    "super lig": "Süper Lig",
    "superliga": "Superliga",
    "allsvenskan": "Allsvenskan",
    "superettan": "Superettan",
    "eliteserien": "Eliteserien",
    "premiership": "Premiership",
    "mls": "MLS",
    "liga mx": "Liga MX",
    "brasileirao": "Brazil Serie A",
    "brasileirão": "Brazil Serie A",
    "j1 league": "J1 Liga",
    "k league 1": "K League 1",
    "k league 2": "K League 2",
    "a-league": "A-League",
    "a-league men": "A-League Men",
}

LEAGUE_WORD_TRANSLATIONS = {
    "league": "Liga",
    "leagues": "Ligák",
    "championship": "Bajnokság",
    "championships": "Bajnokságok",
    "cup": "Kupa",
    "cups": "Kupák",
    "super league": "Szuperliga",
    "premier league": "Premier Liga",
    "women": "Női",
    "woman": "Női",
    "men": "Férfi",
    "youth": "Ifjúsági",
    "reserve": "Tartalék",
    "reserves": "Tartalék",
    "qualification": "Selejtező",
    "qualifying": "Selejtező",
    "playoff": "Rájátszás",
    "play-offs": "Rájátszás",
    "playoffs": "Rájátszás",
    "regular season": "Alapszakasz",
    "group": "Csoport",
    "stage": "Szakasz",
    "division": "Osztály",
    "national": "Nemzeti",
    "professional": "Profi",
    "professional league": "Profi Liga",
    "friendly": "Barátságos",
    "friendly international": "Nemzetközi barátságos",
    "international": "Nemzetközi",
}

def translate_league_name(raw_league):
    clean = str(raw_league or "Egyéb Bajnokság").replace("_", " ").strip()
    key = " ".join(clean.lower().split())
    if key in LEAGUE_EXACT_TRANSLATIONS:
        return LEAGUE_EXACT_TRANSLATIONS[key]

    # Hosszabb kifejezéseket előbb cserélünk, hogy ne rontsuk el őket.
    result = clean
    replacements = sorted(LEAGUE_WORD_TRANSLATIONS.items(), key=lambda x: len(x[0]), reverse=True)
    for source_word, target_word in replacements:
        result = re.sub(rf"(?<!\\w){re.escape(source_word)}(?!\\w)", target_word, result, flags=re.IGNORECASE)

    # Gyakori API-formátumok egységesítése.
    result = re.sub(r"\\s+", " ", result).strip()
    result = result.replace(" - ", " – ")
    return result


def format_league_title(raw_country, raw_league):
    country_hu = translate_text(raw_country).upper()
    league_clean = str(raw_league or "Egyéb Bajnokság").strip()

    if ":" in league_clean:
        parts = league_clean.split(":")
        league_clean = parts[-1].strip()

    league_clean = translate_league_name(league_clean)

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
    if not raw_country:
        return ""

    key = " ".join(
        str(raw_country)
        .replace("_", " ")
        .strip()
        .lower()
        .split()
    )

    aliases = {
        'england': 'gb',
        'scotland': 'gb',
        'wales': 'gb',
        'northern ireland': 'gb',
        'spain': 'es',
        'italy': 'it',
        'germany': 'de',
        'france': 'fr',
        'hungary': 'hu',
        'netherlands': 'nl',
        'portugal': 'pt',
        'belgium': 'be',
        'austria': 'at',
        'poland': 'pl',
        'croatia': 'hr',
        'serbia': 'rs',
        'romania': 'ro',
        'slovakia': 'sk',
        'czech republic': 'cz',
        'czechia': 'cz',
        'greece': 'gr',
        'switzerland': 'ch',
        'denmark': 'dk',
        'sweden': 'se',
        'norway': 'no',
        'ukraine': 'ua',
        'russia': 'ru',
        'turkey': 'tr',
        'finland': 'fi',
        'iceland': 'is',
        'ireland': 'ie',
        'israel': 'il',
        'japan': 'jp',
        'china': 'cn',
        'india': 'in',
        'iran': 'ir',
        'kazakhstan': 'kz',
        'south korea': 'kr',
        'korea republic': 'kr',
        'republic of korea': 'kr',
        'north korea': 'kp',
        'canada': 'ca',
        'usa': 'us',
        'united states': 'us',
        'mexico': 'mx',
        'brazil': 'br',
        'argentina': 'ar',
        'chile': 'cl',
        'colombia': 'co',
        'costa rica': 'cr',
        'ecuador': 'ec',
        'peru': 'pe',
        'bolivia': 'bo',
        'uruguay': 'uy',
        'paraguay': 'py',
        'venezuela': 've',
        'nicaragua': 'ni',
        'panama': 'pa',
        'guatemala': 'gt',
        'el salvador': 'sv',
        'dominican republic': 'do',
        'jamaica': 'jm',
        'australia': 'au',
        'new zealand': 'nz',
        'fiji': 'fj',
        'qatar': 'qa',
        'saudi arabia': 'sa',
        'united arab emirates': 'ae',
        'jordan': 'jo',
        'kuwait': 'kw',
        'lebanon': 'lb',
        'uzbekistan': 'uz',
        'tajikistan': 'tj',
        'kyrgyzstan': 'kg',
        'turkmenistan': 'tm',
        'azerbaijan': 'az',
        'georgia': 'ge',
        'armenia': 'am',
        'egypt': 'eg',
        'morocco': 'ma',
        'algeria': 'dz',
        'tunisia': 'tn',
        'south africa': 'za',
        'nigeria': 'ng',
        'ghana': 'gh',
        'senegal': 'sn',
        'kenya': 'ke',
        'tanzania': 'tz',
        'uganda': 'ug',
        'zambia': 'zm',
        'zimbabwe': 'zw',
        'angola': 'ao',
        'mozambique': 'mz',
        'ethiopia': 'et',
        'cameroon': 'cm',
        'mali': 'ml',
        'guinea': 'gn',
        'guinea-bissau': 'gw',
        'burkina faso': 'bf',
        'benin': 'bj',
        'togo': 'tg',
        'gambia': 'gm',
        'sierra leone': 'sl',
        'liberia': 'lr',
        'cape verde': 'cv',
        'mauritius': 'mu',
        'malta': 'mt',
        'luxembourg': 'lu',
        'cyprus': 'cy',
        'estonia': 'ee',
        'latvia': 'lv',
        'lithuania': 'lt',
        'slovenia': 'si',
        'bosnia and herzegovina': 'ba',
        'montenegro': 'me',
        'kosovo': 'xk',
        'moldova': 'md',
        'gibraltar': 'gi',
        'faroe islands': 'fo',
        'andorra': 'ad',
        'liechtenstein': 'li',
        'monaco': 'mc',
        'san marino': 'sm',
        'palestine': 'ps',
        'singapore': 'sg',
        'malaysia': 'my',
        'indonesia': 'id',
        'thailand': 'th',
        'vietnam': 'vn',
        'philippines': 'ph',
        'hong kong': 'hk',
        'chinese taipei': 'tw',
        'taiwan': 'tw',
        'sri lanka': 'lk',
        'bangladesh': 'bd',
        'nepal': 'np',
        'pakistan': 'pk',
        'myanmar': 'mm',
        'cambodia': 'kh',
        'mongolia': 'mn',
        'oman': 'om',
        'bahrain': 'bh',
        'iraq': 'iq',
        'syria': 'sy',
        'yemen': 'ye',
        'afghanistan': 'af',
        'albania': 'al',
        'bulgaria': 'bg',
        'north macedonia': 'mk',
        'republic of ireland': 'ie',
        'ivory coast': 'ci',
        'congo': 'cg',
        'congo dr': 'cd',
        'rwanda': 'rw',
        'namibia': 'na',
        'botswana': 'bw',
        'lesotho': 'ls',
        'eswatini': 'sz',
        'madagascar': 'mg',
        'malawi': 'mw',
        'sudan': 'sd',
        'south sudan': 'ss',
        'somalia': 'so',
        'libya': 'ly',
        'mauritania': 'mr',
        'niger': 'ne',
        'chad': 'td',
        'central african republic': 'cf',
        'equatorial guinea': 'gq',
        'gabon': 'ga',
        'comoros': 'km',
        'seychelles': 'sc',
        'djibouti': 'dj',
        'eritrea': 'er',
        'papua new guinea': 'pg',
        'solomon islands': 'sb',
        'vanuatu': 'vu',
        'samoa': 'ws',
        'tonga': 'to',
        'american samoa': 'as',
        'puerto rico': 'pr',
        'world': '',
        'europe': '',
        'oceania': '',
        'asia': '',
        'africa': '',
        'south america': '',
        'north america': '',
        'central america': '',
    }

    return aliases.get(key, "")


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
                    )
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

            league_title = item["league"]

            if league_title in TOP_LEAGUES_ORDER:
                return (
                    0,
                    TOP_LEAGUES_ORDER.index(
                        league_title
                    )
                )

            return (
                1,
                league_title
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
