package com.example.legalapi

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson

class LegalApiProvider : MainAPI() {
    override var mainUrl = "https://example.com"
    private val apiBase = "https://example.com/api"
    override var name = "Legal API Provider"
    override val hasMainPage = false
    override var lang = "vi"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Replace endpoint and field mappings with your official API schema.
    override suspend fun search(query: String): List<SearchResponse> {
        val endpoint = "$apiBase/search?keyword=${query.urlEncode()}"
        val response = app.get(endpoint).parsedSafe<SearchEnvelope>() ?: return emptyList()

        return response.items.mapNotNull { item ->
            val url = item.slug ?: return@mapNotNull null
            newMovieSearchResponse(item.title ?: return@mapNotNull null, url) {
                this.posterUrl = item.poster
            }
        }
    }

    // `url` may be a slug/id from search results.
    override suspend fun load(url: String): LoadResponse? {
        val endpoint = "$apiBase/film/$url"
        val detail = app.get(endpoint).parsedSafe<DetailEnvelope>() ?: return null

        val episodes = detail.episodes.orEmpty().mapIndexed { index, ep ->
            Episode(
                data = LinkPayload(
                    stream = ep.streamUrl,
                    referer = ep.referer ?: mainUrl,
                    subtitle = ep.subtitle
                ).toJson(),
                name = ep.name ?: "Episode ${index + 1}"
            )
        }

        return if (episodes.isEmpty()) {
            newMovieLoadResponse(detail.title ?: return null, url, TvType.Movie, url) {
                posterUrl = detail.poster
                plot = detail.description
                year = detail.year
            }
        } else {
            newTvSeriesLoadResponse(detail.title ?: return null, url, TvType.TvSeries, episodes) {
                posterUrl = detail.poster
                plot = detail.description
                year = detail.year
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

        payload.subtitle?.let {
            subtitleCallback(
                SubtitleFile(
                    lang = "Vietnamese",
                    url = it
                )
            )
        }

        val finalUrl = payload.stream ?: return false
        callback(
            ExtractorLink(
                source = name,
                name = "$name Stream",
                url = finalUrl,
                referer = payload.referer ?: mainUrl,
                quality = Qualities.Unknown.value,
                type = INFER_TYPE
            )
        )

        return true
    }
}

data class SearchEnvelope(
    @JsonProperty("items") val items: List<SearchItem> = emptyList()
)

data class SearchItem(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("poster") val poster: String? = null
)

data class DetailEnvelope(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("year") val year: Int? = null,
    @JsonProperty("episodes") val episodes: List<ApiEpisode>? = null
)

data class ApiEpisode(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("streamUrl") val streamUrl: String? = null,
    @JsonProperty("referer") val referer: String? = null,
    @JsonProperty("subtitle") val subtitle: String? = null
)

data class LinkPayload(
    @JsonProperty("stream") val stream: String? = null,
    @JsonProperty("referer") val referer: String? = null,
    @JsonProperty("subtitle") val subtitle: String? = null
)
