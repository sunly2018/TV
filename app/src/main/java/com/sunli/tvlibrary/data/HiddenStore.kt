package com.sunli.tvlibrary.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HiddenItems(
    val groupIds: Set<String> = emptySet(),
    val photoPaths: Set<String> = emptySet()
)

/**
 * 用户删除的资源：只从影库隐藏，不删除硬盘上的原文件，重新扫描也不会再出现。
 */
class HiddenStore(context: Context) {

    private val prefs = context.getSharedPreferences("hidden_items", Context.MODE_PRIVATE)
    private val _hidden = MutableStateFlow(load())
    val hidden: StateFlow<HiddenItems> = _hidden.asStateFlow()

    private fun load() = HiddenItems(
        groupIds = prefs.getStringSet(KEY_GROUPS, null)?.toSet().orEmpty(),
        photoPaths = prefs.getStringSet(KEY_PHOTOS, null)?.toSet().orEmpty()
    )

    fun hideGroup(id: String) {
        val current = _hidden.value
        if (id in current.groupIds) return
        val next = current.groupIds + id
        prefs.edit().putStringSet(KEY_GROUPS, next).apply()
        _hidden.value = current.copy(groupIds = next)
    }

    fun hidePhoto(path: String) {
        val current = _hidden.value
        if (path in current.photoPaths) return
        val next = current.photoPaths + path
        prefs.edit().putStringSet(KEY_PHOTOS, next).apply()
        _hidden.value = current.copy(photoPaths = next)
    }

    fun migrate(ids: Map<String, String>, photos: Map<String, String>) {
        if (ids.isEmpty() && photos.isEmpty()) return
        val current = _hidden.value
        val nextGroups = current.groupIds.mapTo(HashSet()) { ids[it] ?: it }
        val nextPhotos = current.photoPaths.mapTo(HashSet()) { photos[it] ?: it }
        if (nextGroups != current.groupIds) {
            prefs.edit().putStringSet(KEY_GROUPS, nextGroups).apply()
        }
        if (nextPhotos != current.photoPaths) {
            prefs.edit().putStringSet(KEY_PHOTOS, nextPhotos).apply()
        }
        _hidden.value = current.copy(groupIds = nextGroups, photoPaths = nextPhotos)
    }

    fun applyTo(library: Library): Library {
        val hidden = _hidden.value
        if (hidden.groupIds.isEmpty() && hidden.photoPaths.isEmpty()) return library
        return library.copy(
            groups = library.groups.filter { it.id !in hidden.groupIds },
            photos = library.photos.filter { it.path !in hidden.photoPaths }
        )
    }

    private companion object {
        const val KEY_GROUPS = "group_ids"
        const val KEY_PHOTOS = "photo_paths"
    }
}
