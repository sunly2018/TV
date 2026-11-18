package com.sunli.tvlibrary.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 置顶卡片。列表顺序就是显示顺序，最近置顶的排最前。
 */
data class Pinned(
    val groupIds: List<String> = emptyList(),
    val photoPaths: List<String> = emptyList()
)

class PinnedStore(context: Context) {

    private val prefs = context.getSharedPreferences("pinned", Context.MODE_PRIVATE)
    private val _pinned = MutableStateFlow(load())
    val pinned: StateFlow<Pinned> = _pinned.asStateFlow()

    private fun load() = Pinned(
        groupIds = decode(prefs.getString(KEY_GROUPS, null)),
        photoPaths = decode(prefs.getString(KEY_PHOTOS, null))
    )

    fun toggleGroup(id: String) {
        val current = _pinned.value
        val ids = if (id in current.groupIds) {
            current.groupIds - id
        } else {
            listOf(id) + current.groupIds
        }
        save(current.copy(groupIds = ids))
    }

    fun togglePhoto(path: String) {
        val current = _pinned.value
        val paths = if (path in current.photoPaths) {
            current.photoPaths - path
        } else {
            listOf(path) + current.photoPaths
        }
        save(current.copy(photoPaths = paths))
    }

    fun removeGroup(id: String) {
        val current = _pinned.value
        if (id !in current.groupIds) return
        save(current.copy(groupIds = current.groupIds - id))
    }

    fun removePhoto(path: String) {
        val current = _pinned.value
        if (path !in current.photoPaths) return
        save(current.copy(photoPaths = current.photoPaths - path))
    }

    fun migrate(ids: Map<String, String>, photos: Map<String, String>) {
        if (ids.isEmpty() && photos.isEmpty()) return
        val current = _pinned.value
        val nextGroups = current.groupIds.map { ids[it] ?: it }
        val nextPhotos = current.photoPaths.map { photos[it] ?: it }
        if (nextGroups != current.groupIds || nextPhotos != current.photoPaths) {
            save(Pinned(nextGroups, nextPhotos))
        }
    }

    private fun save(next: Pinned) {
        prefs.edit()
            .putString(KEY_GROUPS, encode(next.groupIds))
            .putString(KEY_PHOTOS, encode(next.photoPaths))
            .apply()
        _pinned.value = next
    }

    private fun encode(ids: List<String>): String = ids.joinToString(SEPARATOR)

    private fun decode(raw: String?): List<String> =
        raw?.split(SEPARATOR)?.filter { it.isNotBlank() }.orEmpty()

    private companion object {
        const val KEY_GROUPS = "group_ids"
        const val KEY_PHOTOS = "photo_paths"
        const val SEPARATOR = "\n"
    }
}

/** 置顶的排前面，其余保持原有排序（sortedBy 是稳定排序）。 */
fun <T> List<T>.pinnedFirst(order: List<String>, key: (T) -> String): List<T> {
    if (order.isEmpty()) return this
    val rank = order.withIndex().associate { it.value to it.index }
    return sortedBy { rank[key(it)] ?: Int.MAX_VALUE }
}
