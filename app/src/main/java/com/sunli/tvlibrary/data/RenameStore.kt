package com.sunli.tvlibrary.data

import android.content.Context

/**
 * 只改影库里的显示名称，不动硬盘上的文件/文件夹。
 * 组 id 是按「原片名」算出来的，所以改名时要记住原片名，
 * 下次启动重算 id 才不会和收藏/加密里的记录对不上。
 */
class RenameStore(context: Context) {

    private val prefs = context.getSharedPreferences("renames", Context.MODE_PRIVATE)

    fun groupTitle(id: String): String? = prefs.getString(KEY_GROUP + id, null)

    fun originalGroupTitle(id: String): String? = prefs.getString(KEY_GROUP_ORIGINAL + id, null)

    fun photoName(path: String): String? = prefs.getString(KEY_PHOTO + path, null)

    fun episodeSubtitle(path: String): String? = prefs.getString(KEY_EPISODE + path, null)

    fun renameGroup(id: String, currentTitle: String, title: String) {
        val original = originalGroupTitle(id) ?: currentTitle
        prefs.edit()
            .putString(KEY_GROUP + id, title)
            .putString(KEY_GROUP_ORIGINAL + id, original)
            .apply()
    }

    fun renamePhoto(path: String, name: String) {
        prefs.edit().putString(KEY_PHOTO + path, name).apply()
    }

    fun renameEpisode(path: String, subtitle: String) {
        prefs.edit().putString(KEY_EPISODE + path, subtitle).apply()
    }

    fun clearEpisode(path: String) {
        prefs.edit().remove(KEY_EPISODE + path).apply()
    }

    fun clearGroup(id: String) {
        prefs.edit().remove(KEY_GROUP + id).remove(KEY_GROUP_ORIGINAL + id).apply()
    }

    fun clearPhoto(path: String) {
        prefs.edit().remove(KEY_PHOTO + path).apply()
    }

    /**
     * 挂载名变了以后路径会整体换掉，把按路径存的自定义名跟着搬到新路径。
     */
    fun migrate(paths: Map<String, String>) {
        if (paths.isEmpty()) return
        val edit = prefs.edit()
        var changed = false
        prefs.all.forEach { (key, value) ->
            if (value !is String) return@forEach
            val prefix = when {
                key.startsWith(KEY_EPISODE) -> KEY_EPISODE
                key.startsWith(KEY_PHOTO) -> KEY_PHOTO
                else -> return@forEach
            }
            val next = paths[key.removePrefix(prefix)] ?: return@forEach
            edit.remove(key).putString(prefix + next, value)
            changed = true
        }
        if (changed) edit.apply()
    }

    fun applyTo(library: Library): Library = library.copy(
        groups = library.groups.map { group ->
            val custom = groupTitle(group.id)
            val renamed =
                if (custom.isNullOrBlank() || custom == group.title) group
                else group.copy(title = custom)
            val episodes = renamed.episodes.map { episode ->
                val subtitle = episodeSubtitle(episode.path)
                if (subtitle.isNullOrBlank() || subtitle == episode.subtitle) episode
                else episode.copy(subtitle = subtitle)
            }
            if (episodes == renamed.episodes) renamed else renamed.copy(episodes = episodes)
        },
        photos = library.photos.map { photo ->
            val custom = photoName(photo.path)
            if (custom.isNullOrBlank() || custom == photo.name) photo else photo.copy(name = custom)
        }
    )

    private companion object {
        const val KEY_GROUP = "g_"
        const val KEY_GROUP_ORIGINAL = "go_"
        const val KEY_PHOTO = "p_"
        const val KEY_EPISODE = "e_"
    }
}
