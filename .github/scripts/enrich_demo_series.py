"""Complète le catalogue des séries de la démo (assets/demo/series.json) avec TMDB.

Remplit ce qui manque (nombre de saisons, statut, dernière année, durée d'un épisode,
créateurs, casting, bande-annonce…) sans toucher à ce qui est déjà renseigné.
Clé : variable d'environnement TMDB_BUILTIN_KEY.
"""
import json
import os
import statistics
import sys
import time
import urllib.request

PATH = "app/src/main/assets/demo/series.json"
KEY = os.environ.get("TMDB_BUILTIN_KEY", "").strip()
MAX_FINALE_MINUTES = 70


def fetch(show_id: int) -> dict:
    url = (
        f"https://api.themoviedb.org/3/tv/{show_id}?api_key={KEY}&language=fr-FR"
        "&append_to_response=aggregate_credits,videos&include_video_language=fr,en"
    )
    for attempt in range(4):
        try:
            with urllib.request.urlopen(url, timeout=20) as response:
                return json.load(response)
        except Exception as error:  # réseau ou trop de requêtes : on réessaie
            if attempt == 3:
                print(f"{show_id} : {error}", file=sys.stderr)
                return {}
            time.sleep(2 * (attempt + 1))
    return {}


def episode_runtime(show: dict):
    announced = sorted(m for m in show.get("episode_run_time") or [] if m and m > 0)
    if announced:
        return announced[len(announced) // 2]
    episodes = [e for e in (show.get("last_episode_to_air"), show.get("next_episode_to_air")) if e]
    standard = [e.get("runtime") for e in episodes if e.get("runtime") and (e.get("episode_type") in (None, "standard"))]
    if standard:
        return standard[0]
    short = [e.get("runtime") for e in episodes if e.get("runtime") and e["runtime"] <= MAX_FINALE_MINUTES]
    return short[0] if short else None


def year(date):
    return int(date[:4]) if date and len(date) >= 4 and date[:4].isdigit() else None


def main() -> int:
    if not KEY:
        print("Pas de clé TMDB (secret TMDB_BUILTIN_KEY).", file=sys.stderr)
        return 1
    with open(PATH, encoding="utf-8") as source:
        catalog = json.load(source)
    changed = 0
    for film in catalog["films"]:
        show = fetch(film["id"])
        if not show:
            continue
        before = dict(film)

        def fill(key, value):
            if value not in (None, "", []) and film.get(key) in (None, "", []):
                film[key] = value

        fill("seasonCount", show.get("number_of_seasons"))
        fill("status", show.get("status"))
        fill("endYear", year(show.get("last_air_date")))
        fill("runtime", episode_runtime(show))
        fill("overview", show.get("overview"))
        fill("director", ", ".join(c["name"] for c in show.get("created_by") or [] if c.get("name")))
        cast = (show.get("aggregate_credits") or {}).get("cast") or []
        fill("cast", [c["name"] for c in sorted(cast, key=lambda c: c.get("order", 99))[:5] if c.get("name")])
        videos = [v for v in (show.get("videos") or {}).get("results") or [] if v.get("site") == "YouTube" and v.get("type") == "Trailer"]
        fill("trailer", videos[0]["key"] if videos else None)
        fill("genres", [g["id"] for g in show.get("genres") or []])
        fill("rating", show.get("vote_average"))
        fill("votes", show.get("vote_count"))
        fill("language", show.get("original_language"))
        if film != before:
            changed += 1
        time.sleep(0.05)
    with open(PATH, "w", encoding="utf-8") as target:
        json.dump(catalog, target, ensure_ascii=False, separators=(",", ":"))
    complete = sum(1 for f in catalog["films"] if f.get("seasonCount"))
    print(f"{changed} séries complétées ; {complete}/{len(catalog['films'])} avec leur nombre de saisons.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
