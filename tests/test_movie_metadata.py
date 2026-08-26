from app.movie_metadata import movie_search_candidates, parse_movie_filename


def test_parse_movie_filename_uses_embedded_tmdb_id():
    parsed = parse_movie_filename("公元2000.2000.[tmdbid=32249].720p.mkv")

    assert parsed.tmdb_id == 32249
    assert parsed.year == 2000
    assert "tmdbid" not in parsed.display_title.lower()


def test_movie_search_candidates_splits_and_repairs_noisy_titles():
    candidates = movie_search_candidates("冰：重生之门 国粤 The Iceman")
    assert "冰：重生之门" in candidates
    assert "The Iceman" in candidates

    repaired = movie_search_candidates("TwiIight of the Warriors WaIIed In")[-1]
    assert repaired == "Twilight of the Warriors Walled In"
