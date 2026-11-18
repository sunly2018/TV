package com.sunli.tvlibrary.data

enum class MediaKind(val label: String) {
    MOVIE("电影"),
    SERIES("电视剧"),
    DOCUMENTARY("纪录片"),
    PHOTO("照片")
}

data class Episode(
    val path: String,
    val title: String,
    val season: Int,
    val number: Int,
    val sizeBytes: Long
)

data class MediaGroup(
    val id: String,
    val title: String,
    val kind: MediaKind,
    val posterPath: String?,
    val folder: String,
    val episodes: List<Episode>
) {
    val episodeCount: Int get() = episodes.size
    val isShow: Boolean get() = episodes.size > 1
}

data class Photo(
    val path: String,
    val name: String,
    val sizeBytes: Long
)

data class Library(
    val groups: List<MediaGroup> = emptyList(),
    val photos: List<Photo> = emptyList(),
    val scannedAt: Long = 0L
) {
    val isEmpty: Boolean get() = groups.isEmpty() && photos.isEmpty()

    fun groupsOf(kind: MediaKind): List<MediaGroup> = groups.filter { it.kind == kind }
}
