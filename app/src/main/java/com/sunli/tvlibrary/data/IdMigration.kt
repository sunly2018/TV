package com.sunli.tvlibrary.data

import java.io.File

/**
 * 把加密/收藏/隐藏里旧版按绝对路径生成的 id 迁移到新的相对路径 id。
 * 扫描结果和缓存里的条目 id 也会按新规则重算，保证两边对得上。
 */
internal object IdMigration {

    fun legacyRoots(seedPaths: Collection<String>): Set<String> =
        seedPaths.mapNotNullTo(LinkedHashSet()) { GroupIds.storageRootOf(it) }

    fun withStableIds(
        library: Library,
        originalTitle: (MediaGroup) -> String = { it.title }
    ): Library = library.copy(
        groups = library.groups.map { group ->
            val isSeries = group.episodes.size >= 2
            val file = group.episodes.firstOrNull()?.path ?: group.folder
            val title = originalTitle(group)
            val id = if (isSeries) {
                GroupIds.forFolder(group.folder, title)
            } else {
                GroupIds.forFile(file, title)
            }
            group.copy(id = id)
        }
    )

    fun idMap(
        library: Library,
        roots: Collection<String>,
        originalTitle: (MediaGroup) -> String = { it.title }
    ): Map<String, String> {
        val map = HashMap<String, String>()
        library.groups.forEach { group ->
            val isSeries = group.episodes.size >= 2
            val file = group.episodes.firstOrNull()?.path ?: group.folder
            GroupIds.legacyIds(group.folder, file, isSeries, originalTitle(group), roots)
                .forEach { legacy ->
                    if (legacy != group.id) map[legacy] = group.id
                }
        }
        return map
    }

    /**
     * 存储挂载名变了以后，绝对路径也要跟着换到当前挂载下；换不过来的保持原样。
     */
    fun remapPaths(
        roots: Collection<String>,
        storedPaths: Collection<String>
    ): Map<String, String> {
        if (storedPaths.isEmpty()) return emptyMap()
        val primaryRoots = roots.filter { GroupIds.isPrimaryRoot(it) }
        val externalRoots = roots.filter { !GroupIds.isPrimaryRoot(it) }
        val map = HashMap<String, String>()
        storedPaths.forEach { old ->
            val (primary, rel) = GroupIds.split(old) ?: return@forEach
            val candidates = (if (primary) primaryRoots else externalRoots)
                .map { it.trimEnd('/') + "/" + rel }
            val hit = candidates.firstOrNull { it != old && File(it).exists() }
            if (hit != null) map[old] = hit
        }
        return map
    }
}
