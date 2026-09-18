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


def test_parse_movie_filename_ignores_domain_prefix_before_at_sign():
    parsed = parse_movie_filename("hhd800.com@NMYK-002.mp4")
    assert parsed.code == "NMYK-002"

    parsed = parse_movie_filename("rh2048.com@MGMP-061.mp4")
    assert parsed.code == "MGMP-061"


def test_parse_movie_filename_accepts_numeric_site_prefixes():
    assert parse_movie_filename("277DCV-303.mp4").code == "DCV-303"
    assert parse_movie_filename("300MIUM-995.mp4").code == "MIUM-995"
    assert parse_movie_filename("0808cjod094FHD.mp4").code == "CJOD-094"


def test_parse_movie_filename_keeps_the_code_and_tmdb_id_separate():
    parsed = parse_movie_filename("ABP-485.2024.[tmdbid=32249].mkv")

    assert parsed.code == "ABP-485"
    assert parsed.tmdb_id == 32249
