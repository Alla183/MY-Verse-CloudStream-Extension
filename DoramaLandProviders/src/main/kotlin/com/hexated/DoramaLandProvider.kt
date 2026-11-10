package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.json.JSONObject
import org.jsoup.Jsoup
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.M3u8Helper

class DoramaLandProvider : MainAPI() {

    override var mainUrl = "https://dorama.land"
    override var name = "DoramaLand"
    override var lang = "uk"

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.AsianDrama
    )

    override val hasMainPage = true
    override val mainPage = mainPageOf(
        "$mainUrl/all-dramas" to "Усі дорами",
        "$mainUrl/tags/doramy-filmy-af" to "Фільми",
        "$mainUrl/#popular" to "Популярне",
        "$mainUrl/#premieres" to "Прем'єри",
        "$mainUrl/#expected" to "Очікуване"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data
        val cleanUrl = url.substringBefore("#")
        val isAnchor = url.contains("#")

    // для якорів (#popular тощо) наступних сторінок немає
        if (isAnchor && page > 1) {
            return newHomePageResponse(request.name, emptyList<SearchResponse>(), false)
        }

        val pageUrl = if (page > 1) "$cleanUrl?page=$page" else cleanUrl
        val doc = app.get(pageUrl).document

        val items = when {
            url.contains("#popular") -> doc.select("#popular .catalog-item")
            url.contains("#premieres") -> doc.select("#premieres .catalog-item")
            url.contains("#expected") -> doc.select("#expected .catalog-item")

        // 🎬 сторінка фільмів: беремо всі картки без фільтра
            url.contains("doramy-filmy") ->
                doc.select(".catalog-item")

            else -> doc.select("div.catalog-item.catalog-item_type_poster")
        }

        val isMoviesPage = url.contains("doramy-filmy")

        val home = items.mapNotNull { el ->

            val title = el.selectFirst(".catalog-item__title")?.text() ?: return@mapNotNull null
            val href = fixUrl(el.selectFirst("a")?.attr("href") ?: return@mapNotNull null)

            val img = el.selectFirst("img")
            val src = img?.attr("src")?.trim()
            val dataSrc = img?.attr("data-src")?.trim()
            val srcset = img?.attr("srcset")?.trim()
            val dataSrcSet = img?.attr("data-srcset")?.trim()

            val posterRaw = when {
                !dataSrc.isNullOrEmpty() -> dataSrc
                !src.isNullOrEmpty() -> src
                !dataSrcSet.isNullOrEmpty() -> dataSrcSet.split(" ").firstOrNull()
                !srcset.isNullOrEmpty() -> srcset.split(" ").firstOrNull()
                else -> null
            }

            val poster = posterRaw?.let {
                if (it.startsWith("http")) it else "$mainUrl$it"
            }

        // на сторінці фільмів усе вважаємо фільмами
            val isMovie = isMoviesPage || el.selectFirst(".cinema-type-label.film") != null

            if (isMovie) {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                }
            } else {
                newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
                    this.posterUrl = poster
                }
            }
        }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        println("=== SEARCH START === $query")

        val doc = app.get("$mainUrl/search?q=$query").document
        val elements = doc.select(".search-item")

        println("FOUND ITEMS: ${elements.size}")

        return elements.mapNotNull { element ->

            val title = element.selectFirst(".search-item__title")?.text()?.trim()
            val hrefRaw = element.selectFirst("a.search-item-wrap")?.attr("href")

            if (title.isNullOrEmpty() || hrefRaw.isNullOrEmpty()) {
                println("SKIP: no title or href")
                return@mapNotNull null
            }

            val href = fixUrl(hrefRaw)

        // 🔥 беремо src
            val img = element.selectFirst("div.search-image-wrap img")

            val src = img?.attr("src")?.trim()
            val srcset = img?.attr("srcset")?.trim()
            val dataSrc = img?.attr("data-src")?.trim()
            val dataSrcSet = img?.attr("data-srcset")?.trim()

            println("SRC: $src")
            println("SRCSET: $srcset")
            println("DATA-SRC: $dataSrc")
            println("DATA-SRCSET: $dataSrcSet")

            val posterRaw = when {
                !dataSrc.isNullOrEmpty() -> dataSrc                     // 🔥 ГОЛОВНЕ
                !src.isNullOrEmpty() -> src
                !dataSrcSet.isNullOrEmpty() -> dataSrcSet.split(" ").firstOrNull()
                !srcset.isNullOrEmpty() -> srcset.split(" ").firstOrNull()
                else -> null
            }

            val poster = posterRaw?.let {
                if (it.startsWith("http")) it else "$mainUrl$it"
            }

            println("FINAL POSTER: $poster")

            newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        showToast("LOAD: start")

        val res = app.get(url)
        println("FINAL URL: ${res.url}")
        val doc = res.document

        showToast("LOAD: page loaded")

        val title = doc.selectFirst("h1")?.text() ?: "No title"
        println("TITLE: $title")

        val poster = fixUrlNull(doc.selectFirst(".about-serial-poster img")?.attr("src"))

        val description = doc.selectFirst(
            ".serial-description-text .spoiler__content[itemprop=description]"
        )?.text()

        // 🎭 ЖАНРИ
        val genresRaw = doc.selectFirst(".serial-genres-links [itemprop=genre]")?.text()
        println("GENRES RAW: $genresRaw")

        val genres = genresRaw
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }

        println("GENRES LIST: $genres")

        val score = doc.select(".about-serial-characteristics li")
            .firstOrNull { it.text().contains("MyDramaList") }
            ?.selectFirst(".font-light-18")
            ?.text()
            ?.replace(",", ".")
            ?.toDoubleOrNull()
            ?.let { Score.from10(it) }   // 🔥 ВАЖЛИВО

        // 🎬 TRAILER
        var trailer: String? = null

        val trailerIframe = doc
            .selectFirst("[data-modal-open-iframe]")
            ?.attr("data-modal-open-iframe")
            ?.trim()

        if (!trailerIframe.isNullOrEmpty()) {

  
            val trailerUrl = if (trailerIframe.startsWith("//")) {
                "https:$trailerIframe"
            } else {
                trailerIframe
            }

   
            println("🎬 TRAILER IFRAME: $trailerUrl")

    
            try {
                val trailerDoc = app.get(
                    trailerUrl,
                    headers = mapOf(
                        "Referer" to mainUrl,
                        "User-Agent" to USER_AGENT
                    )
                ).document

        // Варіант 1 — playlist <a href="...m3u8">
     
                trailer = trailerDoc
                    .selectFirst(".fp-playlist a[href$=.m3u8]")
                    ?.attr("href")
                    ?.trim()

        // Варіант 2 — data-config
                if (trailer.isNullOrEmpty()) {
                    val config = trailerDoc
                        .selectFirst("[data-config]")
                        ?.attr("data-config")

                    if (!config.isNullOrEmpty()) {
                        val json = JSONObject(config)
                        trailer = json.optString("hls").takeIf { it.isNotEmpty() }
                    }
                }

        
                println("🎬 TRAILER HLS: $trailer")
            } catch (e: Exception) {
                println("❌ TRAILER ERROR: ${e.message}")
            }
        }
        
        // 🔗 RELATED
        val related = doc.select(".related-serials .catalog-item").mapNotNull { el ->
            val href = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = el.selectFirst(".catalog-item__title")?.text() ?: return@mapNotNull null
            
            val img = el.selectFirst(".catalog-item__img img")

            val src = img?.attr("src")?.trim()
            val srcset = img?.attr("srcset")?.trim()
            val dataSrc = img?.attr("data-src")?.trim()
            val dataSrcSet = img?.attr("data-srcset")?.trim()

            val posterRaw = when {
                !dataSrc.isNullOrEmpty() -> dataSrc
                !src.isNullOrEmpty() -> src
                !dataSrcSet.isNullOrEmpty() -> dataSrcSet.split(" ").firstOrNull()
                !srcset.isNullOrEmpty() -> srcset.split(" ").firstOrNull()
                else -> null
            }

            val poster = posterRaw?.let {
                if (it.startsWith("http")) it else "$mainUrl$it"
            }

            newTvSeriesSearchResponse(title, fixUrl(href), TvType.AsianDrama) {
                this.posterUrl = poster
            }
         }

        println("RELATED SIZE: ${related.size}")

// ⭐ RECOMMENDATIONS (similar)
        val recommendations = doc.select(".similar-serials .catalog-item").mapNotNull { el ->
            val href = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = el.selectFirst(".catalog-item__title")?.text() ?: return@mapNotNull null
            
            val img = el.selectFirst(".catalog-item__img img")

            val src = img?.attr("src")?.trim()
            val srcset = img?.attr("srcset")?.trim()
            val dataSrc = img?.attr("data-src")?.trim()
            val dataSrcSet = img?.attr("data-srcset")?.trim()

            val posterRaw = when {
                !dataSrc.isNullOrEmpty() -> dataSrc
                !src.isNullOrEmpty() -> src
                !dataSrcSet.isNullOrEmpty() -> dataSrcSet.split(" ").firstOrNull()
                !srcset.isNullOrEmpty() -> srcset.split(" ").firstOrNull()
                else -> null
            }

            val poster = posterRaw?.let {
                if (it.startsWith("http")) it else "$mainUrl$it"
            }

            newTvSeriesSearchResponse(title, fixUrl(href), TvType.AsianDrama) {
                this.posterUrl = poster
            }
        }

        println("RECOMMENDATIONS SIZE: ${recommendations.size}")
        
        val episodeElements = doc.select(".short-cinematic")
        println("EP ELEMENTS SIZE: ${episodeElements.size}")

        val episodes = episodeElements.mapNotNull { ep ->
            val hrefRaw = ep.selectFirst("a")?.attr("href") ?: return@mapNotNull null

            val cleanHref = hrefRaw
                .replace("\\s+".toRegex(), "")   // 💥 КЛЮЧОВИЙ ФІКС
                .trim()

            val fixed = fixUrl(cleanHref)

            println("FIXED CLEAN: $fixed")



            val name = ep.selectFirst(".short-cinematic__episode-number")?.text()

            val episode = Regex("(\\d+)")
                .find(name ?: "")
                ?.groupValues?.getOrNull(1)
                ?.toIntOrNull()

            // 🎬 POSTER
            val img = ep.selectFirst(".short-cinematic__img img")

            val src = img?.attr("src")?.trim()
            val srcset = img?.attr("srcset")?.trim()
            val dataSrc = img?.attr("data-src")?.trim()
            val dataSrcSet = img?.attr("data-srcset")?.trim()

            val posterRaw = when {
                !dataSrc.isNullOrEmpty() -> dataSrc
                !src.isNullOrEmpty() -> src
                !dataSrcSet.isNullOrEmpty() -> dataSrcSet.split(" ").firstOrNull()
                !srcset.isNullOrEmpty() -> srcset.split(" ").firstOrNull()
                else -> null
            }

            val poster = posterRaw?.let {
                if (it.startsWith("http")) it else "$mainUrl$it"
            }
            
            newEpisode(fixed) {
                this.name = name
                this.episode = episode
                this.posterUrl = poster
                this.data = fixed
            }
        }

        showToast("LOAD: episodes = ${episodes.size}")

        if (episodes.isEmpty()) {
            println("🎬 MOVIE DETECTED (no episodes)")

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
                this.recommendations = related + recommendations
                this.tags = genres
                this.score = score

                trailer?.let {
                    println("🎬 ADD TRAILER: $it")
                    addTrailer(it)
                }
            }
        }

        println("📺 SERIES DETECTED (${episodes.size} episodes)")

        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genres
            this.recommendations = related + recommendations
            this.score = score

            trailer?.let {
                println("🎬 ADD TRAILER: $it")
                addTrailer(it)
            }
        }
    }


    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        println("LOADLINKS DATA: $data")

        val document = app.get(data).document

    // =========================================
    // 🎬 1. ПРОБУЄМО ЯК СЕРІАЛ
    // =========================================

        val players = document.select("[data-url-player]")

        if (players.isNotEmpty()) {
            println("📺 SERIES MODE")

            players.forEach { player ->

            // Назва озвучки знаходиться всередині <h3>
            // Наприклад: <h3>Озвучка SoftBox</h3>
                val voiceName = player
                    .selectFirst("h3")
                    ?.text()
                    ?.removePrefix("Озвучка ")
                    ?.trim()
                    ?.ifEmpty { "Unknown" }
                    ?: "Unknown"

                var iframeUrl = player.attr("data-url-player")

                if (iframeUrl.isEmpty()) return@forEach

                if (iframeUrl.startsWith("//")) {
                    iframeUrl = "https:$iframeUrl"
                }

                println("🎙️ VOICE: $voiceName")
                println("🔗 PLAYER: $iframeUrl")

                try {

                    val iframeHtml = app.get(
                        iframeUrl,
                        headers = mapOf(
                            "Referer" to mainUrl,
                            "User-Agent" to USER_AGENT
                        )
                    ).text

                    // 📝 SUBTITLES
                    val iframeDoc = Jsoup.parse(iframeHtml)
                    val videoPlayer = iframeDoc.selectFirst("div[id^=videoplayer]")
                    if (videoPlayer != null) {
                        val subtitles = listOf(
                            "ru" to "Russian",
                            "ua" to "Ukrainian",
                            "kg" to "Kyrgyz"
                        )
                        subtitles.forEach { (code, language) ->
                             val link = videoPlayer
                                 .attr("data-${code}_subtitle")
                                 .trim()

                             if (link.isNotEmpty()) {
                                 println("📝 SUBTITLE: $language → $link")
                                 subtitleCallback(
                                     SubtitleFile(language, link)
                                 )
                             }
                        }
                    } else {
                        println("❌ VIDEO PLAYER NOT FOUND")
                    }

                    val m3u8 = Regex("""https:\\/\\/[^"]+\.m3u8""")
                        .find(iframeHtml)
                        ?.value
                        ?.replace("\\/", "/")

                    if (m3u8.isNullOrEmpty()) {
                        println("❌ M3U8 NOT FOUND: $voiceName")
                        return@forEach
                    }

                    println("✅ SERIES: $voiceName → $m3u8")

                    M3u8Helper.generateM3u8(
                        source = "DoramaLand ($voiceName)",
                        streamUrl = m3u8,
                        referer = mainUrl
                    ).forEach(callback)

                } catch (e: Exception) {
                    println("ERROR: ${e.message}")
                }
            }

            return true
        }

    // =========================================
    // 🎬 2. ФІЛЬМ (НОВА ЛОГІКА)
    // =========================================
        println("🎬 MOVIE MODE")

        val iframe = document.selectFirst("iframe[data-src]")

        if (iframe == null) {
            println("❌ NO IFRAME")
            return false
        }

        var baseIframe = iframe.attr("data-src")
        if (baseIframe.startsWith("//")) {
            baseIframe = "https:$baseIframe"
        }

        println("🌐 BASE IFRAME: $baseIframe")

        try {
            val iframeDoc = app.get(
                baseIframe,
                headers = mapOf(
                    "Referer" to mainUrl,
                    "User-Agent" to USER_AGENT
                )
            ).document

        // 🔥 беремо inputData (голоси)
            val inputData = iframeDoc.selectFirst("#inputData")

            val voices = mutableListOf<Pair<String, String>>() // name + tag

            if (inputData != null) {
                val json = JSONObject(inputData.text())

                val seasons = json.keys()
                while (seasons.hasNext()) {
                    val season = json.getJSONObject(seasons.next())

                    val episodes = season.keys()
                    while (episodes.hasNext()) {
                        val epArray = season.getJSONArray(episodes.next())

                        for (i in 0 until epArray.length()) {
                            val item = epArray.getJSONObject(i)

                            val name = item.getString("voice_name")
                            val tag = item.getString("voice_tag")

                            voices.add(name to tag)
                        }
                    }
                }
            }

            println("🎧 FOUND VOICES: ${voices.size}")

        // якщо нема голосів — fallback
            if (voices.isEmpty()) {
                println("⚠️ NO VOICES → fallback to base iframe")

                val html = iframeDoc.html()

                val m3u8 = Regex("""https:\\/\\/[^"]+\.m3u8""")
                    .find(html)
                    ?.value
                    ?.replace("\\/", "/")

                if (!m3u8.isNullOrEmpty()) {
                    M3u8Helper.generateM3u8(
                        source = "DoramaLand",
                        streamUrl = m3u8,
                        referer = mainUrl
                    ).forEach(callback)
                }

                return true
            }

        // =========================================
        // 🔥 ГОЛОВНЕ: ПЕРЕМИКАННЯ ОЗВУЧОК
        // =========================================
            voices.forEach { (voiceName, tag) ->

                val newIframe = "$baseIframe&v=$tag"

                println("🔊 $voiceName")
                println("🌐 $newIframe")

                try {
                    val html = app.get(
                        newIframe,
                        headers = mapOf(
                            "Referer" to mainUrl,
                            "User-Agent" to USER_AGENT
                        )
                    ).text

                    val m3u8 = Regex("""https:\\/\\/[^"]+\.m3u8""")
                        .find(html)
                        ?.value
                        ?.replace("\\/", "/")

                    if (m3u8.isNullOrEmpty()) {
                        println("❌ NO M3U8")
                        return@forEach
                    }

                    println("✅ FOUND: $m3u8")

                    M3u8Helper.generateM3u8(
                        source = "DoramaLand ($voiceName)",
                        streamUrl = m3u8,
                        referer = mainUrl
                    ).forEach(callback)

                } catch (e: Exception) {
                    println("ERROR: ${e.message}")
                }
            }

        } catch (e: Exception) {
            println("ERROR: ${e.message}")
        }

        return true
    }
}
