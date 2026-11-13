package org.polyfrost.polymedia

import net.fabricmc.api.ClientModInitializer

object PolyMediaMod : ClientModInitializer {
    override fun onInitializeClient() {
        PolyMediaConfig.preload()
        PolyMedia // touch: registers the shutdown hook
    }
}
