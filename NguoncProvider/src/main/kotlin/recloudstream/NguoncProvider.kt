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
    override var mainUrl = "https://ophim17.cc"
    private val apiBase = "$mainUrl/v1/api"
    override var name = "OPhim17"
    override var lang = "vi"
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

    private fun detectType(searchItem: OphimSearchItem): TvType {
        val current = searchItem.episodeCurrent.orEmpty().lowercase()
        return if (
            current.contains("hoan tat") ||
            current.contains("tap") ||
            current.contains("episode")
        ) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val endpoint = "$apiBase/tim-kiem?keyword=${query.encodeUri()}&page=1"
        val response = app.get(endpoint).parsedSafe<OphimSearchResponse>() ?: return emptyList()
        val items = response.data?.items.orEmpty()

        return items.mapNotNull { item ->
            val title = item.name ?: return@mapNotNull null
            val slug = item.slug ?: return@mapNotNull null
            newMovieSearchResponse(title, toDetailUrl(slug), detectType(item)) {
                posterUrl = item.posterUrl ?: item.thumbUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = extractSlug(url)
        if (slug.isBlank()) return null

        val endpoint = "$mainUrl/phim/$slug"
        val response = app.get(endpoint).parsedSafe<OphimDetailResponse>() ?: return null
        val movie = response.movie ?: return null

        val detailUrl = toDetailUrl(movie.slug ?: slug)

        val episodes = response.episodes.orEmpty().flatMap { server ->
            server.serverData.orEmpty().mapNotNull { item ->
                val m3u8 = item.linkM3u8?.trim()
                val embed = item.linkEmbed?.trim()
                if (m3u8.isNullOrBlank() && embed.isNullOrBlank()) return@mapNotNull null

                newEpisode(
                    LinkPayload(
                        m3u8 = m3u8,
                        embed = embed,
                        referer = detailUrl
                    ).toJson()
                ) {
                    name = item.name ?: "Episode"
                }
            }
        }

        val isSeries = (movie.type ?: "").contains("series", ignoreCase = true) ||
            episodes.size > 1

        return if (isSeries) {
            newTvSeriesLoadResponse(movie.name ?: return null, detailUrl, TvType.TvSeries, episodes) {
                posterUrl = movie.posterUrl ?: movie.thumbUrl
                plot = movie.content
            }
        } else {
            val movieData = episodes.firstOrNull()?.data ?: LinkPayload(
                m3u8 = null,
                embed = null,
                referer = detailUrl
            ).toJson()

            newMovieLoadResponse(movie.name ?: return null, detailUrl, TvType.Movie, movieData) {
                posterUrl = movie.posterUrl ?: movie.thumbUrl
                plot = movie.content
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

        // Some app/provider flows may pass a raw URL directly.
        if (payload == null) {
            val raw = data.trim()
            if (!raw.startsWith("http")) return false

            return if (raw.contains(".m3u8", ignoreCase = true)) {
                callback(
                    newExtractorLink(name, "$name HLS", raw) {
                        referer = mainUrl
                        quality = Qualities.Unknown.value
                        type = ExtractorLinkType.M3U8
                    }
                )
                true
            } else {
                var found = false
                loadExtractor(raw, mainUrl, subtitleCallback) { link ->
                    found = true
                    callback(link)
                }
                found
            }
        }

        val reqReferer = payload.referer ?: mainUrl
        var foundAny = false

        // Prefer embed extraction first.
        payload.embed?.let { embed ->
            loadExtractor(embed, reqReferer, subtitleCallback) { link ->
                foundAny = true
                callback(link)
            }
        }

        // Fallback to direct HLS link.
        if (!foundAny) {
            payload.m3u8?.let { hls ->
                callback(
                    newExtractorLink(name, "$name HLS", hls) {
                        referer = reqReferer
                        quality = Qualities.Unknown.value
                        type = ExtractorLinkType.M3U8
                    }
                )
                foundAny = true
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

data class OphimSearchResponse(
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("data") val data: OphimSearchData? = null
)

data class OphimSearchData(
    @JsonProperty("items") val items: List<OphimSearchItem> = emptyList()
)

data class OphimSearchItem(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("thumb_url") val thumbUrl: String? = null,
    @JsonProperty("poster_url") val posterUrl: String? = null,
    @JsonProperty("episode_current") val episodeCurrent: String? = null
)

data class OphimDetailResponse(
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("movie") val movie: OphimMovie? = null,
    @JsonProperty("episodes") val episodes: List<OphimEpisodeServer>? = null
)

data class OphimMovie(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("thumb_url") val thumbUrl: String? = null,
    @JsonProperty("poster_url") val posterUrl: String? = null,
    @JsonProperty("content") val content: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("episode_current") val episodeCurrent: String? = null
)

data class OphimEpisodeServer(
    @JsonProperty("server_name") val serverName: String? = null,
    @JsonProperty("server_data") val serverData: List<OphimEpisodeItem>? = null
)

data class OphimEpisodeItem(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("link_embed") val linkEmbed: String? = null,
    @JsonProperty("link_m3u8") val linkM3u8: String? = null
)
