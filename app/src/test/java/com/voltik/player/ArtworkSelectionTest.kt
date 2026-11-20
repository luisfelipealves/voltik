package com.voltik.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkSelectionTest {
    @Test fun usesCoverFromLaterTrackWhenFirstHasNoCover() {
        val art = byteArrayOf(1, 2, 3)
        val result = firstValidArtwork(listOf("track1", "track2"), 100) {
            if (it == "track2") art else null
        }
        assertArrayEquals(art, result)
    }

    @Test fun skipsUnreadableEmptyAndTooLargeImages() {
        val expected = byteArrayOf(4, 5)
        val result = firstValidArtwork(listOf("broken", "empty", "large", "valid"), 3) {
            when (it) {
                "broken" -> throw IllegalArgumentException("unreadable")
                "empty" -> byteArrayOf()
                "large" -> byteArrayOf(1, 2, 3, 4)
                else -> expected
            }
        }
        assertArrayEquals(expected, result)
    }

    @Test fun noValidCoverReturnsNull() {
        assertNull(firstValidArtwork(listOf("track1", "track2"), 3) { null })
    }

    @Test fun repeatedTrackIsReadOnlyOnceAndStopsAfterFirstValidCover() {
        val visited = mutableListOf<String>()
        val result = firstValidArtwork(listOf("no-cover", "no-cover", "cover", "after"), 2) {
            visited.add(it)
            if (it == "cover") byteArrayOf(9) else null
        }
        assertArrayEquals(byteArrayOf(9), result)
        assertEquals(listOf("no-cover", "cover"), visited)
    }
}
