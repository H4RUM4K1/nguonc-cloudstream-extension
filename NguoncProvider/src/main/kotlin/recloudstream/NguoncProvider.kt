package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

class NguoncProvider : MainAPI() {
    override var mainUrl = "https://phim.nguonc.com"
    private val apiBase = "$mainUrl/api"
    override var name = "NguonC"
    override var lang = "en"
    override val hasMainPage = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private fun toDetailUrl(slug: String): String = "$mainUrl/phim/$slug"

    private fun extractSlug(url: String): String {
        return url
            .substringBefore("?")
            .substringBefore("#")
            .trimEnd('/')
            .substringAfterLast('/')
    }

    private fun baseOrigin(url: String?): String? {
        if (url.isNullOrBlank() || !url.startsWith("http")) return null
        val scheme = url.substringBefore("://", missingDelimiterValue = "https")
        val host = url.substringAfter("://", "").substringBefore('/')
        if (host.isBlank()) return null
        return "$scheme://$host"
    }

    private fun isIgnoredM3u8(url: String?): Boolean {
        return url?.lowercase()?.contains("sing.phimmoi.net") == true
    }

    private fun refererVariants(vararg refs: String?): List<String> {
        return refs.asSequence()
            .filterNotNull()
            .map { it.trim() }
            .filter { it.startsWith("http") }
            .flatMap { sequenceOf(it, if (it.endsWith('/')) it else "$it/") }
            .distinct()
            .toList()
    }

    private fun buildStreamHeaders(referer: String): Map<String, String> {
        val safeReferer = referer.ifBlank { "$mainUrl/" }
        val scheme = safeReferer.substringBefore("://", missingDelimiterValue = "https")
        val hostPart = safeReferer.substringAfter("://", "").substringBefore('/')
        val origin = if (hostPart.isBlank()) mainUrl else "$scheme://$hostPart"

        return mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9",
            "Connection" to "keep-alive",
            "Range" to "bytes=0-",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Referer" to safeReferer,
            "Origin" to origin
        )
    }

    private fun extractM3u8FromText(text: String): String? {
        val patterns = listOf(
            Regex("""https?://[^\"'\\\s]+\.m3u8[^\"'\\\s]*""", RegexOption.IGNORE_CASE),
            Regex("""file\s*[:=]\s*[\"'](https?://[^\"']+\.m3u8[^\"']*)[\"']""", RegexOption.IGNORE_CASE),
            Regex("""sources\s*[:=]\s*\[[^\]]*?[\"'](https?://[^\"']+\.m3u8[^\"']*)[\"']""", RegexOption.IGNORE_CASE)
        )

        return patterns.asSequence()
            .mapNotNull { it.find(text)?.groupValues?.lastOrNull() }
            .map { it.replace("\\/", "/").trim() }
            .firstOrNull { it.startsWith("http") && it.contains(".m3u8", ignoreCase = true) }
    }

    private suspend fun extractM3u8FromEmbed(embedUrl: String, pageReferer: String): String? {
        return runCatching {
            val response = app.get(
                embedUrl,
                referer = pageReferer,
                headers = buildStreamHeaders(pageReferer)
            )
            extractM3u8FromText(response.text)
        }.getOrNull()
    }

    private suspend fun emitM3u8Candidates(
        m3u8Url: String,
        referers: List<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val candidates = referers
            .map { it.trim() }
            .filter { it.startsWith("http") }
            .distinct()

        if (candidates.isEmpty()) return false

        candidates.forEach { ref ->
            callback(
                newExtractorLink(name, "$name HLS", m3u8Url) {
                    this.referer = ref
                    this.headers = buildStreamHeaders(ref)
                    quality = Qualities.Unknown.value
                    type = ExtractorLinkType.M3U8
                }
            )
        }

        return true
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val endpoint = "$apiBase/films/search?keyword=${query.encodeUri()}"
        val response = app.get(endpoint).parsedSafe<SearchEnvelope>() ?: return emptyList()

        return response.items.mapNotNull { item ->
            val slug = item.slug ?: return@mapNotNull null
            val title = item.name ?: return@mapNotNull null
            val type = if ((item.totalEpisodes ?: 0) > 1) TvType.TvSeries else TvType.Movie
            newMovieSearchResponse(title, toDetailUrl(slug), type) {
                posterUrl = item.posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = extractSlug(url)
        if (slug.isBlank()) return null

        val endpoint = "$apiBase/film/$slug"
        val response = app.get(endpoint).parsedSafe<FilmDetailResponse>() ?: return null
        val detail = response.movie ?: return null
        val detailUrl = toDetailUrl(detail.slug ?: slug)

        val episodePayloads = detail.episodes.orEmpty().flatMap { serverGroup ->
            serverGroup.items.mapNotNull { item ->
                if (item.m3u8.isNullOrBlank() && item.embed.isNullOrBlank()) return@mapNotNull null
                newEpisode(
                    LinkPayload(
                        m3u8 = item.m3u8?.trim(),
                        embed = item.embed?.trim(),
                        referer = detailUrl
                    ).toJson()
                ) {
                    name = item.name ?: "Episode"
                }
            }
        }

        return if (episodePayloads.isEmpty() || (detail.totalEpisodes ?: 1) <= 1) {
            val movieData = episodePayloads.firstOrNull()?.data ?: LinkPayload(
                m3u8 = null,
                embed = null,
                referer = detailUrl
            ).toJson()

            newMovieLoadResponse(detail.name ?: return null, detailUrl, TvType.Movie, movieData) {
                posterUrl = detail.posterUrl
                plot = detail.description
            }
        } else {
            newTvSeriesLoadResponse(detail.name ?: return null, detailUrl, TvType.TvSeries, episodePayloads) {
                posterUrl = detail.posterUrl
                plot = detail.description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = tryParseJson<LinkPayload>(data)

        // Some app versions/providers may pass raw URLs instead of JSON payloads.
        if (payload == null) {
            val raw = data.trim()
            if (!raw.startsWith("http")) return false

            var foundRaw = false

            if (raw.contains(".m3u8", ignoreCase = true)) {
                if (isIgnoredM3u8(raw)) return false
                foundRaw = emitM3u8Candidates(raw, listOf(mainUrl), callback)
            } else {
                loadExtractor(raw, mainUrl, subtitleCallback) { link ->
                    foundRaw = true
                    callback(link)
                }
            }
            return foundRaw
        }

        val reqReferer = payload.referer ?: mainUrl
        val hlsReferer = payload.embed
            ?.substringBefore("/embed.php")
            ?.takeIf { it.startsWith("http") }
            ?: reqReferer
        val m3u8Origin = baseOrigin(payload.m3u8?.takeUnless { isIgnoredM3u8(it) })
        val embedOrigin = baseOrigin(payload.embed)
        var foundAny = false

        // Try to resolve from embed first to obtain the freshest tokenized m3u8.
        val refreshedM3u8 = payload.embed?.let { embed ->
            extractM3u8FromEmbed(embed, reqReferer)
        }

        // Attempt extractor on embed early, but don't rely solely on it.
        payload.embed?.let { embed ->
            loadExtractor(
                embed,
                reqReferer,
                subtitleCallback
            ) { link ->
                foundAny = true
                callback(link)
            }
        }

        // Fallback to direct HLS if embed extraction did not produce playable links.
        if (!foundAny) {
            val fallbackM3u8 = listOfNotNull(
                refreshedM3u8?.takeUnless { isIgnoredM3u8(it) },
                payload.m3u8?.takeUnless { isIgnoredM3u8(it) }
            ).firstOrNull()

            fallbackM3u8?.let { hls ->
                foundAny = emitM3u8Candidates(
                    hls,
                    refererVariants(
                        m3u8Origin,
                        embedOrigin,
                        hlsReferer,
                        reqReferer,
                        mainUrl
                    ),
                    callback
                )
            }
        }

        return foundAny
    }
}

data class LinkPayload(
    @JsonProperty("m3u8") val m3u8: String? = null,
    @JsonProperty("embed") val embed: String? = null,
    @JsonProperty("referer") val referer: String? = null
)

data class SearchEnvelope(
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("items") val items: List<SearchItem> = emptyList()
)

data class SearchItem(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("poster_url") val posterUrl: String? = null,
    @JsonProperty("total_episodes") val totalEpisodes: Int? = null
)

data class FilmDetailResponse(
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("movie") val movie: MovieDetail? = null
)

data class MovieDetail(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("poster_url") val posterUrl: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("total_episodes") val totalEpisodes: Int? = null,
    @JsonProperty("episodes") val episodes: List<EpisodeServer>? = null
)

data class EpisodeServer(
    @JsonProperty("server_name") val serverName: String? = null,
    @JsonProperty("items") val items: List<EpisodeItem> = emptyList()
)

data class EpisodeItem(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("embed") val embed: String? = null,
    @JsonProperty("m3u8") val m3u8: String? = null
)
