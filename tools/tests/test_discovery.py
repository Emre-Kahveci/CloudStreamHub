import pytest
from tools.scraping.discovery import (
    discover_homepage_cards,
    parse_detail_page,
    evaluate_player_discovery,
    classify_subtitles
)

def test_discover_homepage_cards_strict():
    html_with_cards = """
    <div class="movies">
        <a class="poster" href="/film/movie-1" title="Movie One"><img src="/img1.jpg"/></a>
        <a class="poster" href="/film/movie-2" title="Movie Two"><img src="/img2.jpg"/></a>
    </div>
    """
    cards = discover_homepage_cards(html_with_cards, "https://moviesite.test")
    assert len(cards) == 2
    assert cards[0]["title"] == "Movie One"
    assert cards[0]["url"] == "https://moviesite.test/film/movie-1"

    empty_html = "<div><p>No movies available</p></div>"
    assert len(discover_homepage_cards(empty_html, "https://moviesite.test")) == 0

def test_parse_detail_page_and_soft_404():
    soft_404_html = "<html><title>404 — Sayfa Bulunamadı | Belgeselx</title><body>Böyle bir sayfa yok</body></html>"
    res_404 = parse_detail_page(soft_404_html, 200, "https://belgeselx.com/belgesel/non-existent")
    assert res_404["status"] == "FAIL"
    assert res_404["isSoft404"] is True
    assert res_404["reason"] == "SOFT_404_PAGE"

    valid_html = """
    <html>
        <h1>Silo 1. Sezon</h1>
        <div class="episodes">
            <a href="/dizi/silo/1-sezon-1-bolum">1. Bölüm</a>
            <a href="/dizi/silo/1-sezon-2-bolum">2. Bölüm</a>
        </div>
    </html>
    """
    res_valid = parse_detail_page(valid_html, 200, "https://dizipal.test/dizi/silo")
    assert res_valid["status"] == "PASS"
    assert res_valid["title"] == "Silo 1. Sezon"
    assert len(res_valid["episodeLinks"]) == 2
    assert res_valid["episodeLinks"][0] == "https://dizipal.test/dizi/silo/1-sezon-1-bolum"

def test_evaluate_player_discovery_avoids_generic_substring():
    # Mere presence of word 'player' should NOT trigger discovery
    fake_html = "<html><body><div class='player-stats-section'><h3>Top Players</h3><p>Cristiano Ronaldo</p></div></body></html>"
    stat, info = evaluate_player_discovery(fake_html, "https://sports.test")
    assert stat == "PLAYER_NOT_FOUND"

    # Real iframe embed or video player script triggers discovery
    real_iframe_html = "<html><body><iframe src='https://vidpapi.xyz/video/12345' width='640'></iframe></body></html>"
    stat, info = evaluate_player_discovery(real_iframe_html, "https://stream.test")
    assert stat == "PLAYER_DISCOVERED"
    assert len(info["iframes"]) == 1

def test_classify_subtitles_truthful():
    assert classify_subtitles("<html><track src='sub.vtt' kind='subtitles'></html>") == "VTT/SRT"
    assert classify_subtitles("<html><h1>Movie Title (Türkçe Dublaj)</h1></html>", "Movie Title (Türkçe Dublaj)") == "DUBBED"
    assert classify_subtitles("<html><h1>Movie Title (Türkçe Altyazılı)</h1></html>", "Movie Title (Türkçe Altyazılı)") == "HARDSUB"
    assert classify_subtitles("<html><h1>Regular Movie</h1></html>", "Regular Movie") == "NONE"
