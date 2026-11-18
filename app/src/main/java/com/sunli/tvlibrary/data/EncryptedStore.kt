package com.sunli.tvlibrary.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest

data class Encrypted(
    val groupIds: Set<String> = emptySet(),
    val photoPaths: Set<String> = emptySet()
) {
    val isEmpty: Boolean get() = groupIds.isEmpty() && photoPaths.isEmpty()
}

fun Library.withoutEncrypted(encrypted: Encrypted): Library = copy(
    groups = groups.filter { it.id !in encrypted.groupIds },
    photos = photos.filter { it.path !in encrypted.photoPaths }
)

fun Library.onlyEncrypted(encrypted: Encrypted): Library = copy(
    groups = groups.filter { it.id in encrypted.groupIds },
    photos = photos.filter { it.path in encrypted.photoPaths }
)

/**
 * 加密（隐藏）的资源，只保存在本地偏好里，条目本身仍在影库缓存中。
 */
class EncryptedStore(context: Context) {

    private val prefs = context.getSharedPreferences("encrypted", Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(load())
    val items: StateFlow<Encrypted> = _items.asStateFlow()

    private val _pinSet = MutableStateFlow(prefs.getString(KEY_PIN, null) != null)
    val pinSet: StateFlow<Boolean> = _pinSet.asStateFlow()

    private fun load() = Encrypted(
        groupIds = prefs.getStringSet(KEY_GROUPS, null)?.toSet().orEmpty(),
        photoPaths = prefs.getStringSet(KEY_PHOTOS, null)?.toSet().orEmpty()
    )

    fun setPin(pin: String) {
        prefs.edit().putString(KEY_PIN, hash(pin)).apply()
        _pinSet.value = true
    }

    fun verifyPin(pin: String): Boolean {
        val saved = prefs.getString(KEY_PIN, null) ?: return false
        return saved == hash(pin)
    }

    fun toggleGroup(id: String) {
        val current = _items.value
        val next = if (id in current.groupIds) current.groupIds - id else current.groupIds + id
        prefs.edit().putStringSet(KEY_GROUPS, next).apply()
        _items.value = current.copy(groupIds = next)
    }

    fun togglePhoto(path: String) {
        val current = _items.value
        val next = if (path in current.photoPaths) {
            current.photoPaths - path
        } else {
            current.photoPaths + path
        }
        prefs.edit().putStringSet(KEY_PHOTOS, next).apply()
        _items.value = current.copy(photoPaths = next)
    }

    fun addGroups(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val current = _items.value
        val next = current.groupIds + ids
        if (next.size == current.groupIds.size) return
        prefs.edit().putStringSet(KEY_GROUPS, next).apply()
        _items.value = current.copy(groupIds = next)
    }

    fun addPhotos(paths: Collection<String>) {
        if (paths.isEmpty()) return
        val current = _items.value
        val next = current.photoPaths + paths
        if (next.size == current.photoPaths.size) return
        prefs.edit().putStringSet(KEY_PHOTOS, next).apply()
        _items.value = current.copy(photoPaths = next)
    }

    fun removeGroup(id: String) {
        val current = _items.value
        if (id !in current.groupIds) return
        val next = current.groupIds - id
        prefs.edit().putStringSet(KEY_GROUPS, next).apply()
        _items.value = current.copy(groupIds = next)
    }

    fun removePhoto(path: String) {
        val current = _items.value
        if (path !in current.photoPaths) return
        val next = current.photoPaths - path
        prefs.edit().putStringSet(KEY_PHOTOS, next).apply()
        _items.value = current.copy(photoPaths = next)
    }

    fun migrate(ids: Map<String, String>, photos: Map<String, String>) {
        if (ids.isEmpty() && photos.isEmpty()) return
        val current = _items.value
        val nextGroups = current.groupIds.mapTo(HashSet()) { ids[it] ?: it }
        val nextPhotos = current.photoPaths.mapTo(HashSet()) { photos[it] ?: it }
        if (nextGroups != current.groupIds) {
            prefs.edit().putStringSet(KEY_GROUPS, nextGroups).apply()
        }
        if (nextPhotos != current.photoPaths) {
            prefs.edit().putStringSet(KEY_PHOTOS, nextPhotos).apply()
        }
        _items.value = current.copy(groupIds = nextGroups, photoPaths = nextPhotos)
    }

    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(pin.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val KEY_GROUPS = "group_ids"
        const val KEY_PHOTOS = "photo_paths"
        const val KEY_PIN = "pin_hash"
    }
}
