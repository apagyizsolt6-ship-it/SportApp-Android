from fastapi import FastAPI
import uvicorn

app = FastAPI()

@app.get("/")
def home():
    return {"status": "ok", "message": "A szerver fut!"}

@app.get("/api/matches")
def get_matches():
    return [
        {
            "id": "M_101",
            "home_team": "Real Madrid",
            "away_team": "FC Barcelona",
            "home_score": 2,
            "away_score": 1,
            "status": "live",
            "minute": 78,
            "highlight_url": "https://highlightly.net/embed/clip_demo.mp4",
            "odds_home": 1.85,
            "value_bet": True
        },
        {
            "id": "M_102",
            "home_team": "Arsenal",
            "away_team": "Chelsea",
            "home_score": 0,
            "away_score": 0,
            "status": "live",
            "minute": 15,
            "highlight_url": None,
            "odds_home": 2.10,
            "value_bet": False
        }
    ]

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8080)
