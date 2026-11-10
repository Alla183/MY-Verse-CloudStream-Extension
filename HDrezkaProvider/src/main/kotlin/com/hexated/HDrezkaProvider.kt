package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.*

/**
 * HDrezka — scrape of public mirrors.
 * Clears Techaro Anubis via an OkHttp interceptor ([anubisKiller]): detects the challenge page,
 * solves the SHA-256 PoW, calls pass-challenge on a no-redirect client and caches the
 * `*-anubis-auth` cookie. If the PoW path fails, a hidden WebView solves it (needs
 * [HDrezkaProvider.context] to be set from the Plugin class).
 */
class HDrezkaProvider : MainAPI() {
    override var mainUrl = "https://rezka.ag"
    override var name = "HDrezka"
    override val hasMainPage = true
    override var lang = "ru"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    companion object {
        /** Set from HDrezkaProviderPlugin.load(); used only by the WebView fallback. */
        @Volatile
        var context: Context? = null

        /** Open-source defaults from n0madic/go-hdrezka + common public mirrors. */
        private val MIRRORS = listOf(
            "https://rezka.ag",
            "https://hdrezka-home.tv",
            "https://hdrezka.ag",
            "https://hdrzk.org",
            "https://hdrezka.co",
            "https://rezka-ua.in",
        )

        private val ANUBIS_SCRIPT = Regex(
            """<script[^>]*id=["']anubis_challenge["'][^>]*>([\s\S]*?)</script>""",
            RegexOption.IGNORE_CASE,
        )
        private val ANUBIS_PREFIX = Regex(
            """<script[^>]*id=["']anubis_base_prefix["'][^>]*>([\s\S]*?)</script>""",
            RegexOption.IGNORE_CASE,
        )
    }

    @Volatile
    private var mirrorReady = false

    private fun isAnubisChallenge(html: String): Boolean =
        html.contains("anubis_challenge", ignoreCase = true) ||
            html.contains("не бот")

    /** Keep [mainUrl] on the host OkHttp actually landed on (ag → home.tv redirects). */
    private fun syncMainUrl(finalUrl: String?) {
        if (finalUrl.isNullOrBlank()) return
        try {
            val u = java.net.URI(finalUrl)
            if (!u.scheme.isNullOrBlank() && !u.host.isNullOrBlank()) {
                mainUrl = "${u.scheme}://${u.host}"
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Anubis 1.25 "fast" PoW: leading zero **bytes** (and high nibble if odd difficulty),
     * matching `sha256-purejs.mjs` worker — not hex-char prefix alone.
     */
    private fun solveAnubisPow(randomData: String, difficulty: Int): Pair<Int, String> {
        val fullBytes = difficulty / 2
        val odd = difficulty % 2 != 0
        val md = MessageDigest.getInstance("SHA-256")
        var nonce = 0
        while (true) {
            val digest = md.digest("$randomData$nonce".toByteArray(Charsets.UTF_8))
            md.reset()
            var ok = true
            for (i in 0 until fullBytes) {
                if (digest[i] != 0.toByte()) {
                    ok = false
                    break
                }
            }
            if (ok && odd && ((digest[fullBytes].toInt() and 0xff) shr 4) != 0) {
                ok = false
            }
            if (ok) {
                val hex = buildString(digest.size * 2) {
                    for (b in digest) append("%02x".format(b))
                }
                return nonce to hex
            }
            nonce++
            if (nonce > 50_000_000) error("Anubis PoW too hard")
        }
    }

    // ---------------------------------------------------------------------------------
    // Anubis handling (ported from the working .cs3 build)
    // ---------------------------------------------------------------------------------
    private val anubisUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"

    @Volatile
    private var anubisCookie: String = ""
    private val anubisLock = Any()

    /** No-redirect client: the 302 from pass-challenge carries the auth Set-Cookie. */
    private val anubisClient: OkHttpClient by lazy {
        app.baseClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    private val anubisKiller = object : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val original = chain.request()
            val used = anubisCookie
            val first = original.newBuilder().apply {
                if (used.isNotEmpty()) header("Cookie", used)
                header("User-Agent", anubisUa)
            }.build()

            val resp = chain.proceed(first)
            val body = try {
                resp.peekBody(512L * 1024L).string()
            } catch (_: Throwable) {
                return resp
            }
            if (!body.contains("id=\"anubis_challenge\"")) return resp

            // Solve on the host that actually served the challenge (after redirects).
            val challengeUrl = resp.request.url.toString()
            val challengeCookies = resp.headers("Set-Cookie")
            resp.close()

            synchronized(anubisLock) {
                if (anubisCookie.isEmpty() || anubisCookie == used) {
                    var cookie = solveAnubis(challengeUrl, body, challengeCookies)
                    if (cookie.isEmpty()) cookie = getAnubisCookie(challengeUrl)
                    if (cookie.isNotEmpty()) anubisCookie = cookie
                    Log.i("HDrezka", "anubis solved host=${original.url.host} gotCookie=${cookie.isNotEmpty()}")
                }
            }

            val retry = original.newBuilder().apply {
                if (anubisCookie.isNotEmpty()) header("Cookie", anubisCookie)
                header("User-Agent", anubisUa)
            }.build()
            return chain.proceed(retry)
        }
    }

    /** Returns the `anubis-auth` cookie string ("name=value; ...") or "" on failure. */
    private fun solveAnubis(url: String, html: String, challengeSetCookies: List<String>): String {
        try {
            val raw = ANUBIS_SCRIPT.find(html)?.groupValues?.getOrNull(1)?.trim() ?: return ""
            val payload = tryParseJson<AnubisPayload>(raw) ?: return ""
            val ch = payload.challenge ?: return ""
            val method = ch.method ?: payload.rules?.algorithm
            // Only the SHA-256 PoW variants are solvable here; everything else -> WebView.
            if (method != null && method != "fast" && method != "slow") return ""
            val difficulty = ch.difficulty ?: payload.rules?.difficulty ?: return ""
            val id = ch.id ?: return ""
            val randomData = ch.randomData ?: return ""

            val started = System.currentTimeMillis()
            val (nonce, response) = solveAnubisPow(randomData, difficulty)
            val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(50L)

            val u = url.toHttpUrl()
            val origin = "${u.scheme}://${u.host}"
            val basePrefix = ANUBIS_PREFIX.find(html)?.groupValues?.getOrNull(1)
                ?.trim()?.trim('"')?.trim().orEmpty()

            val passUrl = "$origin$basePrefix/.within.website/x/cmd/anubis/api/pass-challenge"
                .toHttpUrl().newBuilder()
                .addQueryParameter("id", id)
                .addQueryParameter("response", response)
                .addQueryParameter("nonce", nonce.toString())
                .addQueryParameter("redir", url)
                .addQueryParameter("elapsedTime", elapsed.toString())
                .build()

            // Cookies set together with the challenge page (e.g. *-cookie-verification) go back.
            val sendCookies = challengeSetCookies
                .map { it.substringBefore(";").trim() }
                .filter { it.substringBefore("=").contains("anubis", ignoreCase = true) && it.substringAfter("=", "").isNotEmpty() }

            val req = Request.Builder()
                .url(passUrl)
                .header("User-Agent", anubisUa)
                .header("Referer", url)
                .apply { if (sendCookies.isNotEmpty()) header("Cookie", sendCookies.joinToString("; ")) }
                .build()

            val cookie = anubisClient.newCall(req).execute().use { r ->
                val auth = r.headers("Set-Cookie")
                    .map { it.substringBefore(";").trim() }
                    .filter { it.substringBefore("=").contains("anubis-auth", ignoreCase = true) && it.substringAfter("=", "").isNotEmpty() }
                Log.i("HDrezka", "anubis pass code=${r.code} nonce=$nonce ${elapsed}ms auth=${auth.size}")
                auth.joinToString("; ")
            }
            return cookie
        } catch (t: Throwable) {
            Log.w("HDrezka", "solveAnubis failed: ${t.message}")
            return ""
        }
    }

    /** Fallback: let a real WebView run the Anubis JS and read the auth cookie back. */
    private fun getAnubisCookie(url: String): String {
        val ctx = context ?: return ""
        val latch = CountDownLatch(1)
        val result = AtomicReference("")
        val main = Handler(Looper.getMainLooper())

        main.post {
            try {
                val cm = CookieManager.getInstance()
                cm.getCookie(url).orEmpty().split(";")
                    .map { it.trim().substringBefore("=") }
                    .filter { it.contains("anubis", ignoreCase = true) }
                    .forEach { cm.setCookie(url, "$it=; Max-Age=0; Path=/") }

                val wv = WebView(ctx)
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadsImagesAutomatically = false
                    blockNetworkImage = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    userAgentString = anubisUa
                }
                wv.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false
                }

                fun finish() {
                    try {
                        wv.stopLoading()
                        wv.destroy()
                    } catch (_: Throwable) {
                    }
                    if (latch.count > 0) latch.countDown()
                }

                val deadline = System.currentTimeMillis() + 25_000L
                val checker = object : Runnable {
                    override fun run() {
                        val cookies = CookieManager.getInstance().getCookie(url).orEmpty()
                            .split(";").map { it.trim() }
                            .filter { it.substringBefore("=").contains("anubis", ignoreCase = true) }
                        if (cookies.any { it.substringBefore("=").contains("anubis-auth", ignoreCase = true) }) {
                            result.set(cookies.joinToString("; "))
                            finish()
                        } else if (System.currentTimeMillis() < deadline) {
                            main.postDelayed(this, 500L)
                        } else {
                            finish()
                        }
                    }
                }
                wv.loadUrl(url)
                main.postDelayed(checker, 800L)
            } catch (t: Throwable) {
                Log.w("HDrezka", "webview anubis failed: ${t.message}")
                latch.countDown()
            }
        }
        latch.await(30, TimeUnit.SECONDS)
        return result.get()
    }

    // All site requests go through [anubisKiller].
    private suspend fun httpGet(url: String, referer: String? = null) =
        app.get(url, referer = referer, interceptor = anubisKiller)

    private suspend fun httpPost(url: String, data: Map<String, String>, referer: String? = null) =
        app.post(url, data = data, referer = referer, interceptor = anubisKiller)

    private suspend fun fetchDocument(url: String): Document {
        val resp = httpGet(url)
        syncMainUrl(resp.url)
        return Jsoup.parse(resp.text, resp.url)
    }

    private fun filmPath(url: String): String {
        return try {
            val u = java.net.URI(if (url.startsWith("http")) url else pageUrl(url))
            (u.rawPath ?: "/") + (u.rawQuery?.let { "?$it" } ?: "")
        } catch (_: Exception) {
            url.substringAfter(mainUrl, url).let { if (it.startsWith("/")) it else "/$it" }
        }
    }

    private fun isUsableFilmPage(doc: Document): Boolean {
        if (isAnubisChallenge(doc.html())) return false
        val hasFavs = !doc.selectFirst("input#ctrl_favs")?.attr("value").isNullOrBlank()
        val hasTr = doc.select("ul#translators-list li").isNotEmpty()
        val hasTitle = doc.selectFirst("div.b-post__title h1, div.b-post__origtitle") != null
        return hasTitle && (hasFavs || hasTr)
    }

    /**
     * Film pages are host-sensitive (dead mirrors like rezka-ua.in).
     * Try the path on the given host, then on every mirror; Anubis is cleared by the interceptor.
     */
    private suspend fun fetchFilmDocument(url: String): Pair<Document, String> {
        val path = filmPath(url)
        val tried = LinkedHashSet<String>()
        val candidates = mutableListOf<String>()
        candidates += pageUrl(url)
        candidates += "$mainUrl$path"
        for (m in MIRRORS) {
            candidates += m.trimEnd('/') + path
        }
        var lastDoc: Document? = null
        var lastUrl = pageUrl(url)
        for (candidate in candidates) {
            if (!tried.add(candidate)) continue
            try {
                val doc = withTimeoutOrNull(40_000L) { fetchDocument(candidate) } ?: continue
                lastDoc = doc
                lastUrl = candidate
                if (isUsableFilmPage(doc)) {
                    syncMainUrl(candidate)
                    Log.i("HDrezka", "film OK $candidate translators=${doc.select("ul#translators-list li").size}")
                    return doc to candidate
                }
                Log.w(
                    "HDrezka",
                    "film unusable $candidate len=${doc.html().length} anubis=${isAnubisChallenge(doc.html())}",
                )
            } catch (t: Throwable) {
                Log.w("HDrezka", "film try failed $candidate: ${t.message}")
            }
        }
        return (lastDoc ?: fetchDocument(pageUrl(url))) to lastUrl
    }

    private suspend fun ensureWorkingMirror() {
        if (mirrorReady) return
        for (mirror in MIRRORS) {
            try {
                val base = mirror.trimEnd('/')
                val resp = withTimeoutOrNull(40_000L) { httpGet(base) } ?: continue
                syncMainUrl(resp.url)
                val html = resp.text
                val doc = Jsoup.parse(html, resp.url)
                val ok = doc.selectFirst("div.b-content__inline_items, #search, form#search") != null
                    || doc.select("div.b-content__inline_item").isNotEmpty()
                    || doc.selectFirst("a[href*=/films/], a[href*=/series/]") != null
                    || html.length > 20_000
                if (ok && !isAnubisChallenge(html)) {
                    mirrorReady = true
                    return
                }
            } catch (_: Exception) {
                // try next
            }
        }
        // Keep constructor default if nothing answered
        mirrorReady = true
    }

    private fun pageUrl(pathQuery: String): String {
        val p = pathQuery.trim()
        if (p.startsWith("http://") || p.startsWith("https://")) {
            // Keep the search-result host (often hdrezka-home.tv); only rewrite path onto
            // current mainUrl when we already synced to that mirror.
            return try {
                val u = java.net.URI(p)
                val path = u.rawPath ?: "/"
                val q = u.rawQuery?.let { "?$it" }.orEmpty()
                val host = u.host
                if (!host.isNullOrBlank() && mainUrl.contains(host, ignoreCase = true)) {
                    p
                } else if (!host.isNullOrBlank()) {
                    // Prefer the URL's own host — Anubis cookies are host-scoped.
                    "${u.scheme}://$host$path$q"
                } else {
                    "$mainUrl$path$q"
                }
            } catch (_: Exception) {
                p
            }
        }
        return mainUrl + if (p.startsWith("/")) p else "/$p"
    }

    // Paths only — host resolved via [ensureWorkingMirror]
    override val mainPage = mainPageOf(
        "/films/?filter=last" to "фильмы — новинки",
        "/films/?filter=watching" to "фильмы — смотрят",
        "/films/?filter=popular" to "фильмы — популярные",
        "/series/?filter=last" to "сериалы — новинки",
        "/series/?filter=watching" to "сериалы — смотрят",
        "/series/?filter=popular" to "сериалы — популярные",
        "/cartoons/?filter=last" to "мультфильмы — новинки",
        "/cartoons/?filter=watching" to "мультфильмы — смотрят",
        "/animation/?filter=last" to "аниме — новинки",
        "/animation/?filter=watching" to "аниме — смотрят",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        ensureWorkingMirror()
        val parts = request.data.split("?", limit = 2)
        val path = parts.first()
        val query = parts.getOrNull(1)?.let { "?$it" }.orEmpty()
        val home = fetchDocument(pageUrl("${path}page/$page/$query")).select(
            "div.b-content__inline_items div.b-content__inline_item"
        ).map {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title =
            this.selectFirst("div.b-content__inline_item-link > a")?.text()?.trim().toString()
        val href = this.selectFirst("a")?.attr("href").toString()
        val posterUrl = this.select("img").attr("src")
        val year = this.selectFirst("div.b-content__inline_item-link > div")?.text()
            ?.let { Regex("""(?:19|20)\d{2}""").find(it)?.value?.toIntOrNull() }
            ?: Regex("""(?<![0-9])((?:19|20)\d{2})(?![0-9])""").findAll(href)
                .mapNotNull { it.groupValues[1].toIntOrNull() }.lastOrNull()
        val type = if (this.select("span.info").isNotEmpty()) TvType.TvSeries else TvType.Movie
        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = year
            }
        } else {
            val episode =
                this.select("span.info").text().substringAfter(",").replace(Regex("[^0-9]"), "")
                    .toIntOrNull()
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year = year
                addDubStatus(
                    dubExist = true,
                    dubEpisodes = episode,
                    subExist = true,
                    subEpisodes = episode
                )
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ensureWorkingMirror()
        val link = "$mainUrl/search/?do=search&subaction=search&q=$query"
        val document = fetchDocument(link)

        return document.select("div.b-content__inline_items div.b-content__inline_item").map {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        ensureWorkingMirror()
        val (document, resolved) = fetchFilmDocument(url)

        val id = resolved.split("/").last().split("-").first()
        val title = (document.selectFirst("div.b-post__title h1")?.text()?.trim()
            ?: document.selectFirst("div.b-post__origtitle")?.text()?.trim()).toString()
            
        val poster = fixUrlNull(document.selectFirst("div.b-sidecover img")?.attr("src"))
        val tags =
            document.select("table.b-post__info > tbody > tr:contains(Жанр) span[itemprop=genre]")
                .map { it.text() }
        val year = document.select("div.film-info > div:nth-child(2) a").text().toIntOrNull()
        val tvType = if (document.select("div#simple-episodes-tabs")
                .isNullOrEmpty()
        ) TvType.Movie else TvType.TvSeries
        val description = document.selectFirst("div.b-post__description_text")?.text()?.trim()
        val trailer = httpPost(
            "$mainUrl/engine/ajax/gettrailervideo.php",
            data = mapOf("id" to id),
            referer = resolved
        ).parsedSafe<Trailer>()?.code.let {
            Jsoup.parse(it.toString()).select("iframe").attr("src")
        }
        val rating =
            document.selectFirst("table.b-post__info > tbody > tr:nth-child(1) span.bold")?.text()
        val actors =
            document.select("table.b-post__info > tbody > tr:last-child span.item").mapNotNull {
                Actor(
                    it.selectFirst("span[itemprop=name]")?.text() ?: return@mapNotNull null,
                    it.selectFirst("span[itemprop=actor]")?.attr("data-photo")
                )
            }

        val recommendations = buildList {
            // Старые рекомендации
            addAll(
                document.select("div.b-sidelist div.b-content__inline_item")
                    .mapNotNull { it.toSearchResult() }
            )

    // Новые рекомендации
            addAll(
                document.select("div.b-post__partcontent_item[data-url]")
                    .mapNotNull { item ->
                        val href = item.attr("data-url")
                            .ifBlank { item.selectFirst("a")?.attr("href") ?: "" }

                        val title = item.selectFirst(".title")?.text()?.trim()
                            ?: item.selectFirst("a")?.text()?.trim()
                            ?: return@mapNotNull null

                        val year = item.selectFirst(".year")
                            ?.text()
                            ?.filter(Char::isDigit)
                            ?.toIntOrNull()

                        val num = item.selectFirst(".td.num")?.text()?.trim().orEmpty()

                        newMovieSearchResponse(
                            "$num. $title",
                            fixUrl(href),
                            TvType.Movie
                        ) {
                            this.year = year
                        }
                    }
            )
        }.distinctBy { it.url }



        val data = HashMap<String, Any>()
        val server = ArrayList<Map<String, String>>()

        data["id"] = id
        data["favs"] = document.selectFirst("input#ctrl_favs")?.attr("value").orEmpty()
        data["ref"] = resolved

        return if (tvType == TvType.TvSeries) {
            // Series-only: movies keep the branch below untouched.
            val translators = document.select("#translators-list li, #translators-list a")
            if (translators.isNotEmpty()) {
                translators.forEach { res ->
                    val node = if (res.tagName() == "li") {
                        res.selectFirst("a") ?: res
                    } else {
                        res
                    }
        
                    val tid = node.attr("data-translator_id")
                    if (tid.isBlank()) return@forEach
                    server.add(
                        mapOf(
                            "translator_name" to res.text().trim(),
                            "translator_id" to tid,
                        )
                    )
                }
            } else {
                document.select("script").forEach { script ->
                    val match = Regex(
                        """initCDNSeriesEvents\(\d+,\s*(\d+)"""
                    ).find(script.data())
                    if (match != null) {
                        server.add(
                            mapOf(
                                "translator_name" to "HDrezka",
                                "translator_id" to match.groupValues[1],
                            )
                        )
                    }
                }
            }

            // Site uses <a class="b-simple_episode__item">, not <ul><li> — old
            // "ul li" selector returned 0 episodes for Simpsons / long-running shows.
            val episodeKeys = LinkedHashSet<Pair<Int, Int>>()
            collectEpisodePairs(document).forEach { episodeKeys += it }
            // Initial DOM often only has the latest seasons for the default voiceover.
            // get_episodes expands the full S×E grid for a translator (e.g. 2x2 → S1…).
            expandSeriesEpisodes(id, data["favs"] as String, resolved, server, episodeKeys)

            val episodes = episodeKeys.sortedWith(compareBy({ it.first }, { it.second })).map { (season, episode) ->
                val episodeData = HashMap<String, Any>(data).apply {
                    this["season"] = "$season"
                    this["episode"] = "$episode"
                    this["server"] = server
                    this["action"] = "get_stream"
                }
                newEpisode(episodeData.toJson(), {
                    this.name = "Episode $episode"
                    this.season = season
                    this.episode = episode
                }, fix = false)
            }
            Log.i(
                "HDrezka",
                "load series id=$id host=$mainUrl translators=${server.size} episodes=${episodes.size} " +
                    "seasons=${episodeKeys.map { it.first }.toSet().sorted()} anubisLeft=${isAnubisChallenge(document.html())}",
            )

            newTvSeriesLoadResponse(title, resolved, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        } else {
            val translators = document.select("#translators-list li, #translators-list a")

            translators.forEach { el ->
                val node = if (el.tagName() == "li") {
                    el.selectFirst("a") ?: el
                } else {
                    el
                }

                val tid = node.attr("data-translator_id")
                if (tid.isBlank()) return@forEach

                server.add(
                    mapOf(
                        "translator_name" to node.text().trim(),
                        "translator_id" to tid,
                        "camrip" to node.attr("data-camrip"),
                        "ads" to node.attr("data-ads"),
                        "director" to node.attr("data-director"),
                    )
                )
            }

            data["server"] = server
            data["action"] = "get_movie"
            Log.i(
                "HDrezka",
                "load movie id=$id host=$mainUrl translators=${server.size} anubisLeft=${isAnubisChallenge(document.html())}",
            )

            newMovieLoadResponse(title, resolved, TvType.Movie, data.toJson()) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        }
    }

    /** Episode nodes on series pages (anchors or list items). */
    private fun collectEpisodePairs(root: org.jsoup.nodes.Element): List<Pair<Int, Int>> {
        return root.select(
            "div#simple-episodes-tabs a.b-simple_episode__item, " +
                "div#simple-episodes-tabs li.b-simple_episode__item, " +
                "ul.b-simple_episodes__list [data-season_id][data-episode_id], " +
                "[data-season_id][data-episode_id]"
        ).mapNotNull { el ->
            val season = el.attr("data-season_id").toIntOrNull() ?: return@mapNotNull null
            val episode = el.attr("data-episode_id").toIntOrNull() ?: return@mapNotNull null
            season to episode
        }
    }

    /**
     * Long-running series only ship the latest seasons in the first HTML.
     * Merge episode grids from get_episodes across voiceovers until season 1
     * appears (or a few translators were tried). Movie path never calls this.
     */
    private suspend fun expandSeriesEpisodes(
        id: String,
        favs: String,
        referer: String,
        translators: List<Map<String, String>>,
        into: LinkedHashSet<Pair<Int, Int>>,
    ) {
        if (translators.isEmpty()) return
        val before = into.size
        for (tr in translators.take(6)) {
            val tid = tr["translator_id"] ?: continue
            try {
                val ajax = httpPost(
                    url = "$mainUrl/ajax/get_cdn_series/?t=${Date().time}",
                    data = mapOf(
                        "id" to id,
                        "translator_id" to tid,
                        "favs" to favs,
                        "season" to "1",
                        "action" to "get_episodes",
                    ),
                    referer = referer,
                ).parsedSafe<EpisodesAjax>() ?: continue
                if (ajax.success == false) continue
                val html = buildString {
                    append(ajax.episodes.orEmpty())
                    append(ajax.seasons.orEmpty())
                }
                if (html.isBlank()) continue
                collectEpisodePairs(Jsoup.parse(html)).forEach { into += it }
            } catch (t: Throwable) {
                Log.w("HDrezka", "get_episodes failed tr=$tid: ${t.message}")
            }
            // Early seasons present → catalog S1/S2 lookups will work.
            if (into.any { it.first == 1 }) break
        }
        Log.i("HDrezka", "expand episodes $before → ${into.size}")
    }

    private fun decryptStreamUrl(data: String): String {
        // Newer mirrors sometimes return cleartext "[720p]https://... ,[1080p]https://..."
        // instead of the legacy #h / //_// trash-encoded blob.
        val trimmed = data.trim()
        if (trimmed.contains("[") && trimmed.contains("http") && !trimmed.contains("#h")) {
            return trimmed.replace("\\/", "/")
        }

        fun getTrash(arr: List<String>, item: Int): List<String> {
            val trash = ArrayList<List<String>>()
            for (i in 1..item) {
                trash.add(arr)
            }
            return trash.reduce { acc, list ->
                val temp = ArrayList<String>()
                acc.forEach { ac ->
                    list.forEach { li ->
                        temp.add(ac.plus(li))
                    }
                }
                return@reduce temp
            }
        }

        val trashList = listOf("@", "#", "!", "^", "$")
        val trashSet = getTrash(trashList, 2) + getTrash(trashList, 3)
        var trashString = data.replace("#h", "").split("//_//").joinToString("")

        trashSet.forEach {
            val temp = base64Encode(it.toByteArray())
            trashString = trashString.replace(temp, "")
        }

        return try {
            base64Decode(trashString)
        } catch (_: Exception) {
            // Last resort: treat as cleartext if decode fails
            trimmed.replace("\\/", "/")
        }
    }

    private suspend fun cleanCallback(
        source: String,
        url: String,
        quality: String,
        isM3u8: Boolean,
        sourceCallback: (ExtractorLink) -> Unit
    ) {
        sourceCallback.invoke(
            newExtractorLink(
                source,
                source,
                url,
                if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = "$mainUrl/"
                this.quality = getQuality(quality)
                this.headers = mapOf(
                    "Origin" to mainUrl
                )
            }
        )
    }

    private fun getLanguage(str: String): String {
        return when (str) {
            "Русский" -> "Russian"
            "Українська" -> "Ukrainian"
            else -> str
        }
    }

    private fun getQuality(str: String): Int {
        return when (str) {
            "360p" -> Qualities.P240.value
            "480p" -> Qualities.P360.value
            "720p" -> Qualities.P480.value
            "1080p" -> Qualities.P720.value
            "1080p Ultra" -> Qualities.P1080.value
            else -> getQualityFromName(str)
        }
    }

    private suspend fun invokeSources(
        source: String,
        url: String,
        subtitle: String,
        subCallback: (SubtitleFile) -> Unit,
        sourceCallback: (ExtractorLink) -> Unit
    ) {
        for (links in decryptStreamUrl(url).split(",")) {
            val quality =
                Regex("\\[([0-9]{3,4}p\\s?\\w*?)]").find(links)?.groupValues?.getOrNull(1)
                    ?.trim() ?: continue
            for (raw in links.replace("[$quality]", "").split(" or ")) {
                val link = raw.trim()
                val type = if (link.contains(".m3u8")) "Main" else "Backup"
                // "Studio • 1080p (Main)" — voiceover picker parses studio before quality
                cleanCallback(
                    "$source • $quality ($type)",
                    link,
                    quality,
                    link.contains(".m3u8"),
                    sourceCallback,
                )
            }
        }

        for (sub in subtitle.split(",")) {
            val language =
                Regex("\\[(.*)]").find(sub)?.groupValues?.getOrNull(1) ?: continue
            val link = sub.replace("[$language]", "").trim()
            subCallback.invoke(
                newSubtitleFile(
                    getLanguage(language),
                    link
                )
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ensureWorkingMirror()

        tryParseJson<Data>(data)?.let { res ->
            if (res.server?.isEmpty() == true) {
                val document = fetchDocument(res.ref ?: return@let)
                document.select("script").map { script ->
                    if (script.data().contains("sof.tv.initCDNMoviesEvents(")) {
                        val dataJson =
                            script.data().substringAfter("false, {").substringBefore("});")
                        tryParseJson<LocalSources>("{$dataJson}")?.let { source ->
                            invokeSources(
                                this.name,
                                source.streams,
                                source.subtitle.toString(),
                                subtitleCallback,
                                callback
                            )
                        }
                    }
                }
            } else {
                res.server?.amap { server ->
                    httpPost(
                        url = "$mainUrl/ajax/get_cdn_series/?t=${Date().time}",
                        data = mapOf(
                            "id" to res.id,
                            "translator_id" to server.translator_id,
                            "favs" to res.favs,
                            "is_camrip" to server.camrip,
                            "is_ads" to server.ads,
                            "is_director" to server.director,
                            "season" to res.season,
                            "episode" to res.episode,
                            "action" to res.action,
                        ).filterValues { it != null }.mapValues { it.value as String },
                        referer = res.ref
                    ).parsedSafe<Sources>()?.let { source ->
                        invokeSources(
                            server.translator_name.toString(),
                            source.url,
                            source.subtitle.toString(),
                            subtitleCallback,
                            callback
                        )
                    }
                }
            }
        }

        return true
    }

    data class EpisodesAjax(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("episodes") val episodes: String? = null,
        @JsonProperty("seasons") val seasons: String? = null,
        @JsonProperty("message") val message: String? = null,
    )

    data class LocalSources(
        @JsonProperty("streams") val streams: String,
        @JsonProperty("subtitle") val subtitle: Any?,
    )

    data class Sources(
        @JsonProperty("url") val url: String,
        @JsonProperty("subtitle") val subtitle: Any?,
    )

    data class Server(
        @JsonProperty("translator_name") val translator_name: String?,
        @JsonProperty("translator_id") val translator_id: String?,
        @JsonProperty("camrip") val camrip: String?,
        @JsonProperty("ads") val ads: String?,
        @JsonProperty("director") val director: String?,
    )

    data class Data(
        @JsonProperty("id") val id: String?,
        @JsonProperty("favs") val favs: String?,
        @JsonProperty("server") val server: List<Server>?,
        @JsonProperty("season") val season: String?,
        @JsonProperty("episode") val episode: String?,
        @JsonProperty("action") val action: String?,
        @JsonProperty("ref") val ref: String?,
    )

    data class Trailer(
        @JsonProperty("success") val success: Boolean?,
        @JsonProperty("code") val code: String?,
    )

    data class AnubisPayload(
        @JsonProperty("rules") val rules: AnubisRules? = null,
        @JsonProperty("challenge") val challenge: AnubisChallenge? = null,
    )

    data class AnubisRules(
        @JsonProperty("algorithm") val algorithm: String? = null,
        @JsonProperty("difficulty") val difficulty: Int? = null,
    )

    data class AnubisChallenge(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("randomData") val randomData: String? = null,
        @JsonProperty("difficulty") val difficulty: Int? = null,
        @JsonProperty("method") val method: String? = null,
    )
}

