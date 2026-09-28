package com.cloudstream.tr.core.resolvers

object DebridConfig {
    /**
     * Optional Real-Debrid API Token (set via plugin settings or user config)
     */
    var realDebridToken: String? = null

    /**
     * Optional Torbox API Token
     */
    var torboxToken: String? = null

    /**
     * Optional AllDebrid API Token
     */
    var allDebridToken: String? = null

    fun getActiveDebridPrefix(): String? {
        realDebridToken?.takeIf { it.isNotBlank() }?.let { return "realdebrid=$it" }
        torboxToken?.takeIf { it.isNotBlank() }?.let { return "torbox=$it" }
        allDebridToken?.takeIf { it.isNotBlank() }?.let { return "alldebrid=$it" }
        return null
    }

    val isDebridEnabled: Boolean
        get() = getActiveDebridPrefix() != null
}
