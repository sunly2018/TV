package com.sunli.tvlibrary.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Favorites(
    val groupIds: Set<String> = emptySet(),
    val photoPaths: Set<String> = emptySet()
) {
    val isEmpty: Boolean get() = groupIds.isEmpty() && photoPaths.isEmpty()
}

class FavoritesStore(context: Context) {

    private val prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)
    private val _favorites = MutableStateFlow(load())
    val favorites: StateFlow<Favorites> = _favorites.asStateFlow()

    private fun load() = Favorites(
        groupIds = prefs.getStringSet(KEY_GROUPS, null)?.toSet().orEmpty(),
        photoPaths = prefs.getStringSet(KEY_PHOTOS, null)?.toSet().orEmpty()
    )

    fun toggleGroup(id: String) {
        val current = _favorites.value
        val next = if (id in current.groupIds) current.groupIds - id else current.groupIds + id
        prefs.edit().putStringSet(KEY_GROUPS, next).apply()
        _favorites.value = current.copy(groupIds = next)
    }

    fun togglePhoto(path: String) {
        val current = _favorites.value
        val next = if (path in current.photoPaths) {
            current.photoPaths - path
        } else {
            current.photoPaths + path
        }
        prefs.edit().putStringSet(KEY_PHOTOS, next).apply()
        _favorites.value = current.copy(photoPaths = next)
    }

    fun removeGroup(id: String) {
        val current = _favorites.value
        if (id !in current.groupIds) return
        val next = current.groupIds - id
        prefs.edit().putStringSet(KEY_GROUPS, next).apply()
        _favorites.value = current.copy(groupIds = next)
    }

    fun removePhoto(path: String) {
        val current = _favorites.value
        if (path !in current.photoPaths) return
        val next = current.photoPaths - path
        prefs.edit().putStringSet(KEY_PHOTOS, next).apply()
        _favorites.value = current.copy(photoPaths = next)
    }

    fun migrate(ids: Map<String, String>, photos: Map<String, String>) {
        if (ids.isEmpty() && photos.isEmpty()) return
        val current = _favorites.value
        val nextGroups = current.groupIds.mapTo(HashSet()) { ids[it] ?: it }
        val nextPhotos = current.photoPaths.mapTo(HashSet()) { photos[it] ?: it }
        if (nextGroups != current.groupIds) {
            prefs.edit().putStringSet(KEY_GROUPS, nextGroups).apply()
        }
        if (nextPhotos != current.photoPaths) {
            prefs.edit().putStringSet(KEY_PHOTOS, nextPhotos).apply()
        }
        _favorites.value = current.copy(groupIds = nextGroups, photoPaths = nextPhotos)
    }

    private companion object {
        const val KEY_GROUPS = "group_ids"
        const val KEY_PHOTOS = "photo_paths"
    }
}
