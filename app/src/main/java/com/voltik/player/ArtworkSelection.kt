package com.voltik.player

/** Returns the first bounded nonempty image; one unreadable track is not fatal. */
internal fun <T> firstValidArtwork(items: List<T>, maxBytes: Int, load: (T) -> ByteArray?): ByteArray? {
    for (item in items.distinct()) {
        val bytes = try { load(item) } catch (_: Exception) { null }
        if (bytes != null && bytes.isNotEmpty() && bytes.size <= maxBytes) return bytes
    }
    return null
}
