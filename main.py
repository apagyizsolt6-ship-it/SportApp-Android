from fastapi import FastAPI
import requests
import os

app = FastAPI()

STATPAL_KEY = os.getenv("STATPAL_KEY", "YOUR_STATPAL_KEY")
HIGHLIGHTLY_KEY = os.getenv("HIGHLIGHTLY_KEY", "YOUR_HIGHLIGHTLY_KEY")

@app.get("/api/matches")
def get_matches():
    return [
        {
            "id": "REAL_1",
            "home_team": "Real Madrid",
            "away_team": "FC Barcelona",
            "home_score": 2,
            "away_score": 1,
            "status": "live",
            "minute": 82,
            "highlight_url": "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
            "odds_home": 2.10,
            "value_bet": True
        },
        {
            "id": "REAL_2",
            "home_team": "Liverpool",
            "away_team": "Manchester City",
            "home_score": 1,
            "away_score": 1,
            "status": "live",
            "minute": 45,
            "highlight_url": "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4",
            "odds_home": 3.40,
            "value_bet": False
        }
    ]
