package com.example.legalapi

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson

class LegalApiProvider : MainAPI() {
    override var mainUrl = "https://phim.nguonc.com"
    private val apiBase = "https://phim.nguonc.com/api"
    override var name = "NguonC Phim"
    override val hasMainPage = false
    override var lang = "vi"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Search endpoint: /api/films/search?keyword={query}
    override suspend fun search(query: String): List<SearchResponse> {
        val endpoint = "$apiBase/films/search?keyword=${query.urlEncode()}"
        val response = app.get(endpoint).parsedSafe<SearchEnvelope>() ?: return emptyList()

        return response.items.mapNotNull { item ->
            val url = item.slug ?: return@mapNotNull null
            newMovieSearchResponse(item.name ?: return@mapNotNull null, url) {
                this.posterUrl = item.posterUrl
            }
        }
    }

    // Detail endpoint: /api/film/{slug}
    override suspend fun load(url: String): LoadResponse? {
        val endpoint = "$apiBase/film/$url"
        val response = app.get(endpoint).parsedSafe<FilmDetailResponse>() ?: return null
        val detail = response.movie ?: return null

        val episodes = mutableListOf<Episode>()
        detail.episodes.orEmpty().forEach { serverGroup ->
            serverGroup.items.forEach { episodeItem ->
                episodes.add(
                    Episode(
                        data = LinkPayload(
                            m3u8 = episodeItem.m3u8,
                            embed = episodeItem.embed,
                            referer = mainUrl,
                            posterUrl = detail.posterUrl
                        ).toJson(),
                        name = episodeItem.name
                    )
                )
            }
        }

        return if (episodes.isEmpty()) {
            Episode(
                data = LinkPayload(
                    m3u8 = detail.m3u8,
                    embed = detail.embed,
                    referer = mainUrl,
                    posterUrl = detail.posterUrl
                ).toJson(),
                name = "Full"
            )
        )

            newMovieLoadResponse(detail.name ?: return null, url, TvType.Movie, url) {
                posterUrl = detail.posterUrl
                plot = detail.description
            }
        } else {
            newTvSeriesLoadResponse(detail.name ?: return null, url, TvType.TvSeries, episodes) {
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
        val payload = tryParseJson<LinkPayload>(data) ?: return false

        // Prefer m3u8 for direct streaming
        payload.m3u8?.let { m3u8Url ->
            callback(
                ExtractorLink(
                    source = name,
                    name = "$name HLS",
                    url = m3u8Url,
                    referer = payload.referer ?: mainUrl,
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.M3U8,
                    isM3u8 = true
                )
            )
            return true
        }

        // Fallback to embed if m3u8 not available
        payload.embed?.let { embedUrl ->
            // For embeds, you may need to resolve them further
            // depending on the host (streamc.xyz, etc.)
            callback(
                ExtractorLink(
                    source = name,
                    name = "$name Embed",
                    url = embedUrl,
                    referer = payload.referer ?: mainUrl,
                    quality = Qualities.Unknown.value,
                   type = INFER_TYPE
                )
            )
            return true
        }

        return false
    }
}





data class LinkPayload(
    @JsonProperty("m3u8") val m3u8: String? = null,
    @JsonProperty("embed") val embed: String? = null,
    @JsonProperty("referer") val referer: String? = null,
    @JsonProperty("posterUrl") val posterUrl: String? = null
)

// Search API Response: /api/films/search?keyword={query}
data class SearchEnvelope(
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("items") val items: List<SearchItem> = emptyList()
)

data class SearchItem(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("poster_url") val posterUrl: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("total_episodes") val totalEpisodes: Int? = null
)

// Detail API Response: /api/film/{slug}
data class FilmDetailResponse(
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("movie") val movie: MovieDetail? = null
)

data class MovieDetail(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("poster_url") val posterUrl: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("total_episodes") val totalEpisodes: Int? = null,
    @JsonProperty("episodes") val episodes: List<EpisodeServer> = emptyList()
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
