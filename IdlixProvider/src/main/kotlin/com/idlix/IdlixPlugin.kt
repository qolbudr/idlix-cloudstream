package com.idlix

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class IdlixPlugin : Plugin() {
    override fun load(context: android.content.Context) {
        registerMainAPI(IdlixProvider())
    }
}
