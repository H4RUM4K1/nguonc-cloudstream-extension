package com.example.legalapi

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LegalApiPlugin : Plugin() {
    override fun load() {
        registerMainAPI(LegalApiProvider())
    }
}
