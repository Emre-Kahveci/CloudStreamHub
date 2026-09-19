package com.cloudstream.tr.hub

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class CloudStreamHubPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(CloudStreamHub())
    }
}
