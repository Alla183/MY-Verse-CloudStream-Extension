package com.hexated

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class YummyAnimeProviderPlugin : Plugin() {
    override fun load() {
        registerMainAPI(YummyAnimeProvider())
    }
}
