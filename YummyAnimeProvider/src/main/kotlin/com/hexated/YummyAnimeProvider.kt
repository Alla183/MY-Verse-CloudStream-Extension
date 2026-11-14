package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.json.JSONObject
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import okhttp3.FormBody
import android.net.Uri
import android.util.Base64


class YummyAnimeProvider : MainAPI() {

    override var name = "YummyAnime"
    override var mainUrl = "https://site.yummyani.me"
    override var lang = "uk"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)

        // =========================
    // 🔥 Головна сторінка (розділи)
    // =========================

    // База для каталогу. Якщо розділи порожні на site.* — змінити на "https://old.yummyani.me"
//    private val catalogBase = "https://site.yummyani.me"

    override val mainPage = mainPageOf(
        "/catalog/category/senen-aj" to "Шьонен-ай",
        "/catalog" to "Усі аніме",
        "/catalog/ongoing" to "Онґоїнги",
        "/catalog/announcement" to "Анонси",
        "/catalog/category/komediya" to "Комедія",
        "/catalog/category/romantika" to "Романтика",
        "/catalog/category/fentezi" to "Фентезі",
        "/catalog/category/priklyucheniya" to "Пригоди",
        "/catalog/category/detektiv" to "Детектив"
    )

    private val catalogBase = "https://old.yummyani.me"

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "$catalogBase${request.data}?sort=name&page=$page"
        println("YUMMY MAIN url=$url")

        return try {
            val doc = app.get(url).document
            println("YUMMY MAIN html len=${doc.html().length} columns=${doc.select("div.anime-column").size}")

            val list = doc.select("div.anime-column").mapNotNull { element ->
                val href = element.selectFirst("a.image-block")?.attr("href") ?: return@mapNotNull null
                val slug = href.substringBefore("?").trimEnd('/').substringAfterLast("/")
                if (slug.isBlank()) return@mapNotNull null

                val poster = element.selectFirst("img")?.attr("src") ?: ""
                val title = element.selectFirst(".anime-title")?.text() ?: return@mapNotNull null

                newAnimeSearchResponse(
                    title,
                    "https://site.yummyani.me/catalog/item/$slug",
                    TvType.Anime
                ) {
                    posterUrl = when {
                        poster.startsWith("//") -> "https:$poster"
                        poster.startsWith("/") -> "$catalogBase$poster"
                        else -> poster
                    }
                }
            }

            val nextHref = doc.selectFirst("ul.pagination a[rel=next]")?.attr("href")
            val activePage = doc.selectFirst("ul.pagination li.active")?.text()
            println("YUMMY MAIN count=${list.size} first=${list.firstOrNull()?.url} active=$activePage next=$nextHref")

            newHomePageResponse(request, list, nextHref != null)
        } catch (e: Exception) {
            println("YUMMY MAIN ERROR: ${e::class.java.simpleName}: ${e.message}")
            newHomePageResponse(request, emptyList(), false)
        }
    }

    data class AnimeItem(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("anime_url") val animeUrl: String? = null,
        @JsonProperty("poster") val poster: Poster? = null,
        @JsonProperty("description") val description: String? = null
    )

    data class Poster(
        @JsonProperty("fullsize") val fullsize: String? = null
    )

    data class ApiResponse(
        @JsonProperty("response") val response: List<AnimeItem>? = null
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "https://api.yani.tv/search?q=$encoded&offset=0&limit=20"

        val headers = mapOf(
            "X-Application" to "i0zejgswfnwup27a",
            "Accept" to "application/json",
            "Lang" to "ru"
        )

        println("YUMMY SEARCH URL: $url")
        showToast("Search: $query")

        return try {
            val responseBody = app.get(url, headers).toString()

            println("YUMMY RESPONSE: $responseBody")

            val json = org.json.JSONObject(responseBody)
            val responseArray = json.optJSONArray("response")

            println("YUMMY ARRAY SIZE: ${responseArray?.length()}")

            if (responseArray == null || responseArray.length() == 0) {
                showToast("❌ Нічого не знайдено")
                return emptyList()
            }

            val results = mutableListOf<SearchResponse>()

            for (i in 0 until responseArray.length()) {
                val obj = responseArray.optJSONObject(i) ?: continue

                val title = obj.optString("title", "")
                val animeUrl = obj.optString("anime_url", "")
                val posterRaw = obj.optJSONObject("poster")?.optString("fullsize") ?: ""
                val poster = if (posterRaw.startsWith("//")) "https:$posterRaw" else posterRaw

                println("POSTER FIXED: $poster")
                println("ITEM: $title | $animeUrl")

                if (title.isBlank() || animeUrl.isBlank()) continue

                results.add(
                    newAnimeSearchResponse(
                        title,
                        "https://site.yummyani.me/catalog/item/$animeUrl",
                        TvType.Anime
                    ) {
                        this.posterUrl = poster
                    }
                )
            }

            showToast("✅ Знайдено: ${results.size}")

            results

        } catch (e: Exception) {
            e.printStackTrace()
            println("YUMMY ERROR: ${e.message}")
            showToast("❌ Error search")
            emptyList()
        }
    }

    // =========================
    // 📄 Деталі + епізоди
    // =========================
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

    // Назва
        val title = doc.selectFirst("div.titles > h1")?.text() ?: "No title"

    // Обкладинка
        val poster = doc.selectFirst("div.poster-block img")?.attr("src") ?: ""

    // Опис
        val plot = doc.selectFirst("p[itemprop=description]")?.text()

    // 🔥 отримуємо anime_url
        val animeUrl = url.substringAfterLast("/")

        val apiUrl = "https://api.yani.tv/anime/$animeUrl?need_videos=true"

        val headers = mapOf(
            "X-Application" to "i0zejgswfnwup27a",
            "Accept" to "application/json",
            "Lang" to "ru"
        )

        val json = JSONObject(app.get(apiUrl, headers = headers).text)
        val response = json.optJSONObject("response")

        val videos = response?.optJSONArray("videos")

        val episodes = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()

        if (videos != null) {
            for (i in 0 until videos.length()) {
                val obj = videos.optJSONObject(i) ?: continue

                val number = obj.optString("number").trim()
                if (number.isEmpty() || number == "-") continue
                val key = number.trimStart('0').ifEmpty { number }.lowercase()
                if (key in seen) continue
                seen.add(key)

                episodes.add(
                    newEpisode("$animeUrl|$key|${title.replace("|", " ")}") {
                        name = "Серія $key"
                        episode = key.toFloatOrNull()?.toInt()
                    }
                )
            }
        }

        // Додаємо до data загальну кількість серій (для вибору сезону на yummyanime.tv)
        episodes.forEach { it.data = it.data + "|" + episodes.size }

        // Жанри
        val genres: List<String> = run {
            val arr = response?.optJSONArray("genres")
            val list = mutableListOf<String>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val t = arr.optJSONObject(i)?.optString("title")?.trim().orEmpty()
                    if (t.isNotBlank()) list.add(t)
                }
            }
            list
        }

        //Рекомендації
        val animeId = response?.optInt("anime_id", 0) ?: 0
        val recKey = if (animeId > 0) animeId.toString() else animeUrl
        val recommendations = loadRecommendations(recKey)
        println("YUMMY RECOMMENDATIONS: key=$recKey count=${recommendations.size}")

        //Оцінка
        val ratingObj = response?.optJSONObject("rating")
        println("YUMMY RATING: $ratingObj")
        val ratingValue: Double? = listOf("average", "shikimori_rating", "myanimelist_rating")
            .firstNotNullOfOrNull { key ->
                ratingObj?.optDouble(key, Double.NaN)?.takeIf { !it.isNaN() && it > 0 }
            }
        
        return newAnimeLoadResponse(
            title,
            url,
            TvType.Anime
        ) {
            posterUrl = fixUrl(poster)
            this.plot = plot
            this.recommendations = recommendations
            this.tags = genres
            ratingValue?.let { this.score = Score.from10(it) }

        // 🔥 ВАЖЛИВО: саме так CloudStream очікує episodes
            this.episodes = mutableMapOf(
                DubStatus.Subbed to episodes.sortedBy { it.episode }
            )
        }
    }

    // =========================
    // ⭐ Рекомендації
    // =========================
    private suspend fun loadRecommendations(animeKey: String): List<SearchResponse> {
        // спершу звичайні, якщо порожньо — рекомендації від AI
        for (fromAi in listOf(false, true)) {
            try {
                val text = app.get(
                    "https://api.yani.tv/anime/$animeKey/recommendations?from_ai=$fromAi&limit=24",
                    headers = apiHeaders
                ).text
                println("YUMMY REC fromAi=$fromAi len=${text.length} head=${text.take(300)}")
                
                val arr = JSONObject(text).optJSONArray("response") ?: continue
                val items = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }

                val result = items.amap { obj ->
                    try {
                        val title = obj.optString("title").trim()
                        if (title.isBlank()) return@amap null

                        var slug = if (obj.isNull("anime_url")) "" else obj.optString("anime_url")
                        if (slug.isBlank()) {
                            val id = obj.optInt("anime_id", 0)
                            if (id <= 0) return@amap null
                            slug = JSONObject(
                                app.get("https://api.yani.tv/anime/$id", headers = apiHeaders).text
                            ).optJSONObject("response")?.optString("anime_url").orEmpty()
                        }
                        if (slug.isBlank()) return@amap null

                        val posterObj = obj.optJSONObject("poster")
                        val posterRaw = posterObj?.let {
                            listOf("fullsize", "big", "medium", "small").firstNotNullOfOrNull { k ->
                                it.optString(k).takeIf { v -> !it.isNull(k) && v.isNotBlank() }
                            }
                        }.orEmpty()
                        val poster = if (posterRaw.startsWith("//")) "https:$posterRaw" else posterRaw

                        newAnimeSearchResponse(
                            title,
                            "https://site.yummyani.me/catalog/item/$slug",
                            TvType.Anime
                        ) {
                            this.posterUrl = poster
                        }
                    } catch (e: Exception) {
                        println("YUMMY REC ITEM ERROR: ${e.message}")
                        null
                    }
                }.filterNotNull()

                if (result.isNotEmpty()) return result
            } catch (e: Exception) {
                println("YUMMY RECOMMENDATIONS ERROR (fromAi=$fromAi): ${e.message}")
            }
        }
        return emptyList()
    }

    // =========================
    // 🎥 Відео
    // =========================
    private val apiHeaders = mapOf(
        "X-Application" to "i0zejgswfnwup27a",
        "Accept" to "application/json",
        "Lang" to "ru"
    )

    private val kodikEndpointCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun String.epKey() = trim().trimStart('0').ifEmpty { trim() }.lowercase()

    private fun String.fixProto() = when {
        startsWith("//") -> "https:$this"
        startsWith("http") -> this
        else -> "https://$this"
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("YUMMY LINKS data=$data")
        val parts = data.split("|", limit = 4)
        if (parts.size < 2) {
            showToast("❌ data без '|': $data")
            return false
        }
                // data може містити повний URL — беремо лише slug
        val animeUrl = parts[0].substringBefore("?").trimEnd('/').substringAfterLast("/")
        val wantedKey = parts[1].epKey()
        val yTitle = parts.getOrNull(2).orEmpty().trim()
        val yTotal = parts.getOrNull(3)?.trim()?.toIntOrNull() ?: 0
        println("YUMMY LINKS slug=$animeUrl key=$wantedKey")

        val body = app.get(
            "https://api.yani.tv/anime/$animeUrl?need_videos=true",
            headers = apiHeaders
        ).text
        println("YUMMY LINKS api len=${body.length} head=${body.take(300)}")

        val arr = JSONObject(body).optJSONObject("response")?.optJSONArray("videos")
        // yani.tv без videos — не фатально, ще є yummyanime.tv
        val all = if (arr == null) emptyList()
        else (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        println("YUMMY LINKS numbers=${all.map { it.optString("number") }.distinct().take(30)}")

        val items = all
            .filter { it.optString("number").epKey() == wantedKey }
            .filter { it.optString("iframe_url").isNotBlank() }

        showToast("Відео: всього ${all.size}, для серії '$wantedKey': ${items.size}")
        items.forEach {
            println("YUMMY ITEM player=${it.optJSONObject("data")?.optString("player")} iframe=${it.optString("iframe_url")}")
        }

        var found = false
        items.forEach { v ->
            val iframe = v.optString("iframe_url").trim()
            val info = v.optJSONObject("data")
            val player = info?.optString("player").orEmpty()
                .removePrefix("Плеер ").removePrefix("Player ").trim()
            val dubbing = info?.optString("dubbing").orEmpty().trim()
            val label = listOf(player, dubbing).filter { it.isNotBlank() }
                .joinToString(" · ").ifBlank { name }

            try {
                val ok = when {
                    iframe.contains("kodik", true) -> invokeKodik(iframe, label, callback)
                    iframe.contains("iframecvh", true) || player.equals("cvh", true) ->
                        invokeCvh(iframe, label, callback)
                    iframe.contains("aksor.tv", true) -> invokeAksor(iframe, label, callback)
                    iframe.contains("sibnet.ru", true) -> invokeSibnet(iframe, label, callback)
                    iframe.contains("rutube.ru", true) -> invokeRutube(iframe, label, callback)
                    else -> loadExtractor(iframe.fixProto(), "https://yani.tv/", subtitleCallback, callback)
                }
                println("YUMMY RESULT [$label] ok=$ok iframe=$iframe")
                if (ok) found = true
            } catch (e: Exception) {
                println("YUMMY LINKS ERROR [$label]: ${e::class.java.simpleName}: ${e.message}")
                showToast("❌ $label: ${e.message?.take(60)}")
            }
        }

        // + джерела з yummyanime.tv (ceramet.net)
        try {
            if (invokeYummyTv(yTitle, yTotal, wantedKey, subtitleCallback, callback)) found = true
        } catch (e: Exception) {
            println("YUMMYTV ERROR: ${e::class.java.simpleName}: ${e.message}")
        }

        showToast(if (found) "✅ Посилання знайдено" else "❌ Жодного посилання")
        return found
    }

    // ---------- YummyAnime.tv (плеєр ceramet.net) ----------
    private val yummyTvBase = "https://yummyanime.tv"
    private val yummyTvPageCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private data class YummyTvPlayer(
        val pd: JSONObject,
        val iframe: String,
        val cookies: Map<String, String>
    )

    private val yummyTvPlayerCache = java.util.concurrent.ConcurrentHashMap<String, YummyTvPlayer>()
    private val yummyTvAjax = mapOf(
        "X-Requested-With" to "XMLHttpRequest",
        "Accept" to "application/json, text/plain, */*"
    )
    private val yummyTvItem = Regex("""^(?:https?://yummyanime\.tv)?/\d+-[^/]+\.html$""")

    private fun String.normTitle() =
        lowercase().replace('ё', 'е').replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    /** Шукаємо сторінку тайтла на yummyanime.tv за назвою (DLE-пошук; ендпоінт треба звірити). */
    private suspend fun findYummyTvPage(title: String): String? {
        yummyTvPageCache[title]?.let { return it }
        val want = title.normTitle()
        val q = java.net.URLEncoder.encode(want, "UTF-8")

        for (u in listOf(
            "$yummyTvBase/search?q=$q",
            "$yummyTvBase/index.php?do=search&subaction=search&story=$q"
        )) {
            try {
                val cards = app.get(u).document.select("a[href]").mapNotNull { a ->
                    val href = a.attr("href")
                    if (!yummyTvItem.matches(href)) return@mapNotNull null
                    val img = a.selectFirst("img") ?: return@mapNotNull null
                    val t = img.attr("alt").removePrefix("Постер к аниме").trim()
                        .ifBlank { a.attr("title").trim() }
                        .ifBlank { a.text().trim() }
                    if (t.isBlank()) return@mapNotNull null
                    t to (if (href.startsWith("/")) yummyTvBase + href else href)
                }.distinctBy { it.second }

                val hit = cards.firstOrNull { it.first.normTitle() == want }
                    ?: cards.filter {
                        val n = it.first.normTitle()
                        n.isNotEmpty() && want.contains(n)
                    }.maxByOrNull { it.first.normTitle().length }
                if (hit != null) {
                    yummyTvPageCache[title] = hit.second
                    return hit.second
                }
            } catch (e: Exception) {
                println("YUMMYTV SEARCH ERROR: ${e.message}")
            }
        }
        return null
    }

    /** controller.php -> iframe ceramet.net/bil/<id> -> window.playerData */
    private suspend fun yummyTvPlayer(pageUrl: String): YummyTvPlayer? {
        yummyTvPlayerCache[pageUrl]?.let { return it }
        val id = Regex("""/(\d+)-""").find(pageUrl)?.groupValues?.get(1) ?: return null

        for (slot in 0..3) {
            try {
                val res = JSONObject(
                    app.get(
                        "$yummyTvBase/engine/ajax/controller.php?mod=player&id=$id&slot=$slot",
                        referer = pageUrl,
                        headers = yummyTvAjax
                    ).text
                )
                if (!res.optBoolean("status")) continue
                val src = res.optJSONObject("data")?.optString("src").orEmpty().trim()
                if (!src.contains("ceramet")) continue

                val iframePage = app.get(src, referer = "$yummyTvBase/")
                val html = iframePage.text
                val raw = html.substringAfter("window.playerData =", "")
                    .substringBefore("</script>").trim().removeSuffix(";").trim()
                if (raw.isBlank()) continue

                return YummyTvPlayer(JSONObject(raw), src, iframePage.cookies)
                    .also { yummyTvPlayerCache[pageUrl] = it }
            } catch (e: Exception) {
                println("YUMMYTV PLAYER slot=$slot ERROR: ${e.message}")
            }
        }
        return null
    }

    private suspend fun invokeYummyTv(
        title: String,
        total: Int,
        epKey: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (title.isBlank()) return false
        val epNum = epKey.toIntOrNull() ?: return false

        val page = findYummyTvPage(title) ?: return false
        val ctx = yummyTvPlayer(page) ?: return false
        val pd = ctx.pd
        val voiceNames = pd.optJSONObject("voices")
        val seasons = pd.optJSONObject("playlist")?.optJSONObject("serial")?.optJSONArray("list")
            ?: return false

        fun positiveCount(i: Int): Int {
            val a = seasons.optJSONArray(i) ?: return 0
            return (0 until a.length()).count { (a.optJSONObject(it)?.optInt("num") ?: 0) > 0 }
        }

        // У плейлисті всі сезони франшизи: беремо той, де кількість серій збігається, інакше 1-й
        val idx = (0 until seasons.length()).firstOrNull { total > 0 && positiveCount(it) == total } ?: 0
        val eps = seasons.optJSONArray(idx) ?: return false

        val ep = (0 until eps.length()).mapNotNull { eps.optJSONObject(it) }
            .firstOrNull { it.optInt("num") == epNum } ?: return false
        val vs = ep.optJSONArray("voices") ?: return false

        val voices = (0 until vs.length()).mapNotNull { vs.optJSONObject(it) }.map {
            val vid = it.optInt("voice_id")
            it.optInt("video_id") to
                    voiceNames?.optString(vid.toString()).orEmpty().ifBlank { "Озвучка $vid" }
        }.filter { it.first > 0 }

        println("YUMMYTV page=$page season#=${idx + 1} ep=$epNum voices=${voices.map { it.second }}")

        return voices.amap { (fileId, vName) ->
            try {
                invokeCeramet(fileId, "YummyTV · $vName", ctx, subtitleCallback, callback)
            } catch (e: Exception) {
                println("YUMMYTV LINK ERROR [$vName]: ${e.message}")
                false
            }
        }.any { it }
    }

    private suspend fun invokeCeramet(
        fileId: Int,
        label: String,
        ctx: YummyTvPlayer,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Як у браузері: Referer = сторінка iframe, Cookie = PHPSESSID, виданий при її завантаженні
        val resp = app.get(
            "https://ceramet.net/videos.php?id=$fileId",
            referer = ctx.iframe,
            headers = yummyTvAjax,
            cookies = ctx.cookies
        )
        val json = JSONObject(resp.text)
        val video = json.optString("video").trim()
        if (video.isBlank()) return false

        val session = ctx.cookies + resp.cookies
        val hlsHeaders = mutableMapOf("Referer" to ctx.iframe)
        if (session.isNotEmpty()) {
            hlsHeaders["Cookie"] = session.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }

        // "cc": {"ru": "...vtt"} або "cc": []
        json.optJSONObject("cc")?.let { cc ->
            cc.keys().forEach { code ->
                val u = cc.optString(code).trim()
                if (u.isNotBlank()) subtitleCallback(SubtitleFile("$label ($code)", u))
            }
        }

        val head = try {
            app.get(video, headers = hlsHeaders).text
        } catch (e: Exception) {
            "ERR ${e.message}"
        }
        println("YUMMYTV M3U8 [$label] id=$fileId head=${head.take(200).replace("\n", "\\n")}")

        if (head.trimStart().startsWith("#EXTM3U")) {
            M3u8Helper.generateM3u8(
                source = label,
                streamUrl = video,
                referer = ctx.iframe,
                headers = hlsHeaders
            ).forEach(callback)
        } else {
            // сервер віддав не плейлист — віддаємо посилання плеєру напряму
            callback(
                newExtractorLink(label, label, video, ExtractorLinkType.M3U8) {
                    this.referer = ctx.iframe
                    this.headers = hlsHeaders
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        return true
    }

    // ---------- Kodik ----------
    private suspend fun invokeKodik(
        iframe: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fullUrl = iframe.fixProto()
        val page = app.get(fullUrl, referer = "https://yani.tv/")
        val flat = page.text.replace("\n", "").replace("\r", "")

        val urlParamsStr = Regex("""\burlParams\s*=\s*'([^']+)'""").find(flat)?.groupValues?.get(1) ?: return false
        val type = Regex("""\b(?:videoInfo|vInfo)\.type\s*=\s*'([^']+)'""").find(flat)?.groupValues?.get(1) ?: return false
        val hash = Regex("""\b(?:videoInfo|vInfo)\.hash\s*=\s*'([^']+)'""").find(flat)?.groupValues?.get(1) ?: return false
        val id = Regex("""\b(?:videoInfo|vInfo)\.id\s*=\s*'([^']+)'""").find(flat)?.groupValues?.get(1) ?: return false
        val playerSrc = Regex("""src="((?://[^"]+)?/assets/js/app\.player_single[^"]+)"""")
            .find(flat)?.groupValues?.get(1) ?: return false

        val pageOrigin = "https://" + java.net.URI(fullUrl).host
        val scriptUrl = when {
            playerSrc.startsWith("//") -> "https:$playerSrc"
            playerSrc.startsWith("/") -> pageOrigin + playerSrc
            else -> playerSrc
        }
        val origin = scriptUrl.substringBefore("/assets/js/")

        val endpointPath = kodikEndpointCache[scriptUrl] ?: run {
            val js = app.get(scriptUrl, referer = fullUrl).text
            Regex("""atob\("([A-Za-z0-9+/=]+)"\)""").findAll(js).mapNotNull { m ->
                try {
                    val d = String(Base64.decode(m.groupValues[1], Base64.DEFAULT))
                    d.takeIf { it.startsWith("/") && !it.startsWith("//") && it.length <= 10 }
                } catch (_: Exception) { null }
            }.firstOrNull()?.also { kodikEndpointCache[scriptUrl] = it }
        } ?: "/ftor"

        val p = JSONObject(urlParamsStr)
        val res = app.post(
            origin + endpointPath,
            data = mapOf(
                "d" to p.optString("d"),
                "d_sign" to p.optString("d_sign"),
                "pd" to p.optString("pd"),
                "pd_sign" to p.optString("pd_sign"),
                "ref" to java.net.URLDecoder.decode(p.optString("ref"), "UTF-8"),
                "ref_sign" to p.optString("ref_sign"),
                "bad_user" to "true",
                "cdn_is_working" to "true",
                "type" to type,
                "hash" to hash,
                "id" to id,
                "info" to "{}"
            ),
            referer = fullUrl,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            cookies = page.cookies
        ).text
        println("YUMMY KODIK res head=${res.take(200)}")

        val links = JSONObject(res).optJSONObject("links")
        if (links == null) {
            kodikEndpointCache.remove(scriptUrl)
            return false
        }

        var any = false
        for (q in listOf(240, 360, 480, 720, 1080)) {
            val src = links.optJSONArray(q.toString())?.optJSONObject(0)?.optString("src")
                ?.takeIf { it.isNotEmpty() } ?: continue
            var url = (if (src.contains("//")) src else decodeKodikSrc(src) ?: continue).fixProto()

            val m = Regex("""/(\d+)\.mp4:hls:manifest\.m3u8""").find(url)
            val actual = m?.groupValues?.get(1)?.toIntOrNull()
            if (m != null && actual != null && actual < q) {
                val repaired = url.replaceRange(m.range, "/$q.mp4:hls:manifest.m3u8")
                val ok = try { app.head(repaired).isSuccessful } catch (_: Exception) { false }
                if (ok) url = repaired
            }

            callback(
                newExtractorLink(label, label, url, ExtractorLinkType.M3U8) {
                    this.referer = ""
                    this.quality = q
                }
            )
            any = true
        }
        return any
    }

    private fun decodeKodikSrc(src: String): String? = try {
        val rotated = src.map { c ->
            if (c.isLetter()) {
                val shifted = c.code + 18
                val limit = if (c <= 'Z') 90 else 122
                if (shifted <= limit) shifted.toChar() else (shifted - 26).toChar()
            } else c
        }.joinToString("")
        val padded = rotated + "=".repeat((4 - rotated.length % 4) % 4)
        String(Base64.decode(padded, Base64.DEFAULT))
    } catch (_: Exception) { null }

    // ---------- CVH (cdnvideohub) ----------
    private suspend fun invokeCvh(
        iframe: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val query = iframe.fixProto().substringAfter("?", "")
        val params = query.split("&").mapNotNull {
            val i = it.indexOf('=')
            if (i < 0) null else it.substring(0, i) to java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.toMap()

        val animeId = params["anime_id"] ?: return false
        val episodeNum = params["episode"]?.toIntOrNull() ?: 1
        val dubbingCode = params["dubbing_code"].orEmpty()
        val dubbingLabel = (params["dubbing"].orEmpty()).removePrefix("Озвучка ").trim()

        val hdr = mapOf("Referer" to "https://ru.yummyani.me/", "Accept" to "application/json")
        val playlist = JSONObject(
            app.get(
                "https://plapi.cdnvideohub.com/api/v1/player/sv/playlist?pub=745&id=$animeId&aggr=mali",
                headers = hdr
            ).text
        )
        val arr = playlist.optJSONArray("items") ?: return false
        val isSerial = playlist.optBoolean("isSerial", true)

        fun JSONObject.str(k: String) = if (isNull(k)) "" else optString(k)

        val all = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        val candidates = if (!isSerial) all else all.filter {
            it.isNull("episode") || it.optInt("episode") == episodeNum
        }
        fun eq(a: String, b: String) = b.isNotBlank() && a.equals(b, true)

        val item = candidates.firstOrNull { eq(it.str("voiceStudio"), dubbingCode) }
            ?: candidates.firstOrNull { eq("${it.str("voiceType")} ${it.str("voiceStudio")}".trim(), dubbingLabel) }
            ?: candidates.firstOrNull { eq(it.str("voiceStudio"), dubbingLabel) }
            ?: candidates.firstOrNull()
            ?: return false

        val vkId = item.str("vkId")
        if (vkId.isEmpty()) return false
        val video = JSONObject(
            app.get("https://plapi.cdnvideohub.com/api/v1/player/sv/video/$vkId", headers = hdr).text
        )
        val failover = video.str("failoverHost").removePrefix("https://").removePrefix("http://")
            .trimEnd('/')
        val sources = video.optJSONObject("sources") ?: return false

        fun fixHost(u: String): String {
            if (failover.isBlank()) return u
            return u.replace(Regex("""^(https?://)(\d{1,3}(?:\.\d{1,3}){3})""")) {
                it.groupValues[1] + failover
            }
        }

        val map = listOf(
            240 to "mpegLowestUrl", 360 to "mpegLowUrl", 480 to "mpegMediumUrl",
            720 to "mpegHighUrl", 1080 to "mpegFullHdUrl", 1440 to "mpegQhdUrl", 2160 to "mpeg4kUrl"
        )
        var any = false
        for ((q, key) in map) {
            var u = sources.str(key)
            if (u.isBlank() && q == 1440) u = sources.str("mpeg2kUrl")
            if (u.isBlank()) continue
            callback(
                newExtractorLink(label, label, fixHost(u), ExtractorLinkType.VIDEO) {
                    this.quality = q
                }
            )
            any = true
        }
        return any
    }

    // ---------- Aksor ----------
    private suspend fun invokeAksor(
        iframe: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playerUrl = iframe.fixProto()
        val segs = java.net.URI(playerUrl).path.trim('/').split('/').filter { it.isNotBlank() }
        val vi = segs.indexOf("video")
        val hash = (if (vi >= 0) segs.getOrNull(vi + 1) else segs.lastOrNull()) ?: return false

        val json = JSONObject(
            app.get(
                "https://player.aksor.tv/api/video/$hash",
                headers = mapOf("Accept" to "application/json"),
                referer = playerUrl
            ).text
        )
        val qs = json.optJSONObject("qualities") ?: return false

        var any = false
        for ((key, q) in listOf("q360" to 360, "q480" to 480, "q720" to 720, "q1080" to 1080, "q2k" to 1440, "q4k" to 2160)) {
            val u = qs.optString(key).trim()
            if (u.isEmpty() || u.equals("null", true)) continue
            callback(
                newExtractorLink(label, label, u.replace(" ", "%20")) {
                    this.referer = playerUrl
                    this.quality = q
                }
            )
            any = true
        }
        return any
    }

    // ---------- Sibnet ----------
    private suspend fun invokeSibnet(
        iframe: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playerUrl = iframe.fixProto()
        val html = app.get(playerUrl, referer = "https://yani.tv/").text
        val raw = listOf(
            Regex("""player\.src\(\s*\[\s*\{\s*src\s*:\s*["']([^"']+)"""),
            Regex("""<source[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        ).firstNotNullOfOrNull { it.find(html)?.groupValues?.get(1) } ?: return false

        val path = raw.trim().replace("\\/", "/")
        val url = if (path.startsWith("http") || path.startsWith("//")) path.fixProto()
        else java.net.URL(java.net.URL(playerUrl), path).toString()

        callback(
            newExtractorLink(label, label, url, ExtractorLinkType.VIDEO) {
                this.referer = playerUrl
                this.headers = mapOf("Origin" to "https://video.sibnet.ru")
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    // ---------- Rutube ----------
    private suspend fun invokeRutube(
        iframe: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playerUrl = iframe.fixProto()
        val id = Regex("""(?i)([a-f0-9]{32})""").find(playerUrl)?.groupValues?.get(1) ?: return false
        val opts = JSONObject(
            app.get(
                "https://rutube.ru/api/play/options/$id/?no_404=true",
                referer = playerUrl
            ).text
        )
        val bal = opts.optJSONObject("video_balancer") ?: return false
        val stream = listOf("m3u8", "default").firstNotNullOfOrNull { k ->
            bal.optString(k).takeIf { !bal.isNull(k) && it.isNotBlank() && !it.equals("null", true) }
        } ?: return false

        callback(
            newExtractorLink(label, label, stream, ExtractorLinkType.M3U8) {
                this.referer = playerUrl
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
}
   
