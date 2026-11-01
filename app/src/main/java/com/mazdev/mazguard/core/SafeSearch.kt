package com.mazdev.mazguard.core

/**
 * DNS-level SafeSearch enforcement.
 *
 * Google, Bing, DuckDuckGo and YouTube all publish hostnames that serve their
 * search front-end with the safe filter permanently enabled. Redirecting the
 * normal hostname to one of those hostnames locks the filter on at the network
 * layer, without decrypting any traffic.
 */
object SafeSearch {

    private const val GOOGLE = "forcesafesearch.google.com"
    private const val YOUTUBE = "restrict.youtube.com"
    private const val BING = "strict.bing.com"
    private const val DDG = "safe.duckduckgo.com"

    fun target(rawHost: String): String? {
        val h = rawHost.lowercase().trimEnd('.')
        if (h.isEmpty()) return null

        if (h == "youtube.com" || h == "www.youtube.com" || h == "m.youtube.com" ||
            h == "youtube-nocookie.com" || h == "www.youtube-nocookie.com" ||
            h == "youtu.be" || h == "www.youtu.be"
        ) return YOUTUBE

        if (h == "bing.com" || h == "www.bing.com" || h == "cn.bing.com") return BING

        if (h == "duckduckgo.com" || h == "www.duckduckgo.com" || h == "start.duckduckgo.com") return DDG

        if (h == "google.com" || h.startsWith("www.google.") ||
            (h.startsWith("google.") && h.count { it == '.' } <= 2)
        ) return GOOGLE

        return null
    }
}
