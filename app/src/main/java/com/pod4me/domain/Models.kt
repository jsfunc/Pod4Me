package com.pod4me.domain

data class Feed(val id: String, val url: String, val name: String, val expiryDays: Int?, val defaults: Set<String> = emptySet(), val error: String? = null, val artworkUrl: String? = null)
data class Episode(
    val id: String, val feedId: String, val title: String, val audioUrl: String,
    val description: String, val published: Long, val duration: Long = 0,
    val downloaded: Long? = null, val path: String? = null, val expiry: Long? = null,
    val stars: Int = 0, val position: Long = 0, val heard: Boolean = false,
    val downloadId: Long? = null, val error: String? = null, val lists: Set<String> = emptySet(),
) {
    val isDownloaded get() = path != null
    val progress get() = if (duration > 0) (position.toDouble() / duration).coerceIn(0.0, 1.0) else 0.0
    val protected get() = "4ever" in lists
}
data class TrackList(val id: String, val name: String, val system: Boolean)
data class Bookmark(val id: Long, val episodeId: String, val position: Long, val name: String)
data class Library(
    val ready: Boolean = false, val feeds: List<Feed> = emptyList(), val episodes: List<Episode> = emptyList(),
    val lists: List<TrackList> = emptyList(), val bookmarks: List<Bookmark> = emptyList(), val queue: List<String> = emptyList(),
) {
    fun episode(id: String?) = episodes.find { it.id == id }
    fun feedName(id: String) = feeds.find { it.id == id }?.name ?: "Podcast"
    fun feedArtwork(id: String) = feeds.find { it.id == id }?.artworkUrl
}
enum class TrackSort(val label: String) {
    PUBLICATION("Publication date"), DOWNLOAD("Download date"), STARS("Stars"), FEED("Feed name"), PROGRESS("Played percentage")
}
fun List<Episode>.sorted(sort: TrackSort, library: Library): List<Episode> {
    val comparator = when (sort) {
        TrackSort.PUBLICATION -> compareByDescending<Episode> { it.published }
        TrackSort.DOWNLOAD -> compareByDescending<Episode> { it.downloaded ?: 0 }
        TrackSort.STARS -> compareByDescending<Episode> { it.stars }
        TrackSort.FEED -> compareBy<Episode> { library.feedName(it.feedId).lowercase() }
        TrackSort.PROGRESS -> compareBy<Episode> { it.progress }
    }
    return sortedWith(comparator.thenByDescending { it.published }.thenBy { it.id })
}
