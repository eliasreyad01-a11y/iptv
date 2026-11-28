package com.lite.iptv

/** Built-in list: Arab-world channels plus well-known international ones (a trimmed copy of the free iptv-org list). */
object Defaults {
    const val VERSION = 18
    const val ID = "m3u18"
    fun list() = listOf(
        Playlist(ID, "Arab & world channels", "m3u", "asset:index.country.m3u"),
        Playlist("srv3", "Server 3", "xtream", "http://195.128.27.253:2095", "4116039046", "6281134156"))
}
