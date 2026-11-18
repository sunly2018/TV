package com.sunli.tvlibrary.data

/**
 * 影库条目 id 只由「相对存储根的路径 + 标题」生成。
 * 以前直接用绝对路径，U 盘挂载名一变（/storage/6861-FED4 → /storage/x6861-FED4），
 * 所有 id 都会变，加密/收藏/隐藏里存下的 id 就全部对不上了。
 */
internal object GroupIds {

    private val STORAGE_ROOT =
        Regex("""^(/storage/(?:emulated|self)/[^/]+|/storage/[^/]+)""")

    private val PRIMARY_PREFIXES = listOf("/storage/emulated/", "/storage/self/")

    fun storageRootOf(path: String): String? =
        STORAGE_ROOT.find(path)?.groupValues?.get(1)

    fun isPrimaryRoot(root: String): Boolean {
        val normalized = root.trimEnd('/')
        return PRIMARY_PREFIXES.any { normalized.startsWith(it) }
    }

    /**
     * 拆出 (是否主存储, 相对存储根的路径)；不在 /storage 下时返回 null。
     * 路径恰好是存储根本身时相对路径为空串。
     */
    fun split(path: String): Pair<Boolean, String>? {
        val root = storageRootOf(path) ?: return null
        val rel = path.removePrefix(root).trimStart('/')
        return isPrimaryRoot(root) to rel
    }

    private fun canonical(path: String): String? {
        val (primary, rel) = split(path) ?: return null
        return (if (primary) "p:" else "e:") + rel
    }

    fun forFolder(folder: String, title: String): String =
        stableId(canonical(folder) ?: folder, title)

    fun forFile(file: String, title: String): String =
        stableId(canonical(file) ?: file, title)

    /**
     * 旧版本 id 是绝对路径哈希。按历史出现过的存储根把路径拼回去，得到可用于迁移的旧 id。
     */
    fun legacyIds(
        folder: String,
        file: String,
        isSeries: Boolean,
        title: String,
        roots: Collection<String>
    ): Set<String> {
        val target = if (isSeries) folder else file
        val (primary, rel) = split(target) ?: return emptySet()
        return roots.mapNotNullTo(LinkedHashSet()) { root ->
            val normalized = root.trimEnd('/')
            if (normalized.isBlank() || isPrimaryRoot(normalized) != primary) {
                null
            } else {
                stableId(if (rel.isEmpty()) normalized else "$normalized/$rel", title)
            }
        }
    }

    fun stableId(vararg parts: String): String =
        Integer.toHexString(parts.joinToString("|").hashCode())
}
