package com.sunli.tvlibrary.data

import android.content.Context

class PlaybackPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)

    fun positionOf(path: String): Long = prefs.getLong("pos:$path", 0L)

    fun progressOf(path: String): Float = prefs.getFloat("frac:$path", 0f)

    fun lastPlayedOf(path: String): Long = prefs.getLong("time:$path", 0L)

    fun saveProgress(path: String, positionMs: Long, durationMs: Long) {
        val fraction = if (durationMs > 0) {
            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        prefs.edit()
            .putLong("pos:$path", positionMs.coerceAtLeast(0L))
            .putFloat("frac:$path", fraction)
            .putLong("time:$path", System.currentTimeMillis())
            .apply()
    }

    fun autoNext(): Boolean = prefs.getBoolean("auto_next", true)

    /**
     * 存储重挂载后路径变了，把续播进度和历史记录改挂到新路径，否则这些记录都会失联。
     */
    fun migrate(paths: Map<String, String>) {
        if (paths.isEmpty()) return
        val editor = prefs.edit()
        var changed = false
        prefs.all.forEach { (key, value) ->
            val prefix = key.substringBefore(':', "")
            if (prefix != "pos" && prefix != "frac" && prefix != "time") return@forEach
            val old = key.substringAfter(':', "")
            val new = paths[old] ?: return@forEach
            if (new == old) return@forEach
            when (value) {
                is Long -> editor.putLong("$prefix:$new", value)
                is Float -> editor.putFloat("$prefix:$new", value)
            }
            editor.remove(key)
            changed = true
        }
        if (changed) editor.apply()
    }

    /**
     * 所有记录过的播放路径，用于找出历史上出现过的存储挂载名（迁移条目 id 需要）。
     */
    fun knownPaths(): Set<String> = prefs.all.keys.mapNotNullTo(HashSet()) { key ->
        key.substringAfter(':', "").takeIf { it.startsWith("/") }
    }
}
