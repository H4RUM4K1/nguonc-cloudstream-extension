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

    private suspend fun emitM3u8IfValid(
        m3u8Url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val probe = app.get(m3u8Url, referer = referer).text
            if (!probe.contains("#EXTM3U", ignoreCase = true)) return false

            callback(
                newExtractorLink(name, "$name HLS", m3u8Url) {
                    this.referer = referer
                    quality = Qualities.Unknown.value
                    type = ExtractorLinkType.M3U8
                }
            )
            true
        } catch (_: Throwable) {
            false
        }
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
                foundRaw = emitM3u8IfValid(raw, mainUrl, callback)
            } else {
                loadExtractor(raw, subtitleCallback) { link ->
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
        var foundAny = false

        // Prefer host extractor first because many m3u8 URLs are hotlink-protected.
        payload.embed?.let { embed ->
            loadExtractor(
                embed,
                subtitleCallback
            ) { link ->
                foundAny = true
                callback(link)
            }
        }

        // Fallback to direct HLS if extractor did not return any link.
        if (!foundAny) {
            payload.m3u8?.let { hls ->
                foundAny = emitM3u8IfValid(hls, hlsReferer, callback)
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
