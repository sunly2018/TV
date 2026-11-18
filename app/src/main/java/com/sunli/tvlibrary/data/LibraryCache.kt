package com.sunli.tvlibrary.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class LibraryCache(context: Context) {

    private val file = File(context.filesDir, "library.json")

    fun load(): Library? = read(requireVersion = true)

    /**
     * 旧版本缓存不能直接展示，但里面的路径可以用来找出历史挂载名，迁移加密/收藏 id 时需要。
     */
    fun loadAnyVersion(): Library? = read(requireVersion = false)

    private fun read(requireVersion: Boolean): Library? {
        if (!file.exists()) return null
        return try {
            val root = JSONObject(file.readText())
            if (requireVersion && root.optInt("version", 1) < VERSION) return null
            val groupsJson = root.optJSONArray("groups") ?: JSONArray()
            val photosJson = root.optJSONArray("photos") ?: JSONArray()
            val groups = buildList {
                for (i in 0 until groupsJson.length()) {
                    val g = groupsJson.getJSONObject(i)
                    val epsJson = g.optJSONArray("episodes") ?: JSONArray()
                    val episodes = buildList {
                        for (j in 0 until epsJson.length()) {
                            val e = epsJson.getJSONObject(j)
                            add(
                                Episode(
                                    path = e.getString("path"),
                                    title = e.optString("title"),
                                    season = e.optInt("season", 1),
                                    number = e.optInt("number", 0),
                                    sizeBytes = e.optLong("size")
                                )
                            )
                        }
                    }
                    if (episodes.isEmpty()) continue
                    add(
                        MediaGroup(
                            id = g.getString("id"),
                            title = g.optString("title"),
                            kind = runCatching { MediaKind.valueOf(g.getString("kind")) }
                                .getOrDefault(MediaKind.MOVIE),
                            posterPath = g.optString("poster").ifBlank { null },
                            folder = g.optString("folder"),
                            episodes = episodes
                        )
                    )
                }
            }
            val photos = buildList {
                for (i in 0 until photosJson.length()) {
                    val p = photosJson.getJSONObject(i)
                    add(
                        Photo(
                            path = p.getString("path"),
                            name = p.optString("name"),
                            sizeBytes = p.optLong("size")
                        )
                    )
                }
            }
            Library(groups = groups, photos = photos, scannedAt = root.optLong("scannedAt"))
        } catch (t: Throwable) {
            null
        }
    }

    fun save(library: Library) {
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("scannedAt", library.scannedAt)
        val groupsJson = JSONArray()
        library.groups.forEach { g ->
            val gJson = JSONObject()
            gJson.put("id", g.id)
            gJson.put("title", g.title)
            gJson.put("kind", g.kind.name)
            gJson.put("poster", g.posterPath ?: "")
            gJson.put("folder", g.folder)
            val epsJson = JSONArray()
            g.episodes.forEach { e ->
                epsJson.put(
                    JSONObject()
                        .put("path", e.path)
                        .put("title", e.title)
                        .put("season", e.season)
                        .put("number", e.number)
                        .put("size", e.sizeBytes)
                )
            }
            gJson.put("episodes", epsJson)
            groupsJson.put(gJson)
        }
        root.put("groups", groupsJson)
        val photosJson = JSONArray()
        library.photos.forEach { p ->
            photosJson.put(
                JSONObject()
                    .put("path", p.path)
                    .put("name", p.name)
                    .put("size", p.sizeBytes)
            )
        }
        root.put("photos", photosJson)

        val tmp = File(file.parentFile, "library.json.tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(root.toString())
            tmp.delete()
        }
    }

    private companion object {
        const val VERSION = 6
    }
}
