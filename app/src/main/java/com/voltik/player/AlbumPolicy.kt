package com.voltik.player

object AlbumPolicy {
    fun startsPlaybackOnOpen(): Boolean = false
    fun startsPlaybackOnTrackSelection(): Boolean = true
}
