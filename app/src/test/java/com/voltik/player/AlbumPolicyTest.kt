package com.voltik.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumPolicyTest {
 @Test fun openingAnAlbumDoesNotStartPlayback() { assertFalse(AlbumPolicy.startsPlaybackOnOpen()) }
 @Test fun choosingATrackStartsPlayback() { assertTrue(AlbumPolicy.startsPlaybackOnTrackSelection()) }
}
