package com.sunli.tvlibrary.data

import android.content.Context
import android.os.Environment
import java.io.File
import java.util.Locale

/**
 * U 盘重新挂载后挂载名会变（/storage/z6861-FED4 → /storage/a6861-FED4），
 * 缓存里的绝对路径就可能失效。真正访问文件前用 [resolve] 按相对路径重新定位。
 */
object MediaPaths {

    private val INTERNAL_NAMES = setOf("self", "emulated", "enc_emulated")

    fun roots(context: Context): List<File> {
        val roots = LinkedHashSet<File>()
        File("/storage").listFiles()?.forEach { dir ->
            val name = dir.name.lowercase(Locale.ROOT)
            if (dir.isDirectory && name !in INTERNAL_NAMES) roots.add(dir)
        }
        context.getExternalFilesDirs(null).filterNotNull().forEach { appDir ->
            var f: File? = appDir
            while (f != null && !f.name.equals("Android", ignoreCase = true)) f = f.parentFile
            f?.parentFile?.takeIf { it.isDirectory }?.let { roots.add(it) }
        }
        Environment.getExternalStorageDirectory()?.takeIf { it.isDirectory }?.let { roots.add(it) }
        return roots.filter { it.isDirectory && it.canRead() }
    }

    fun resolve(context: Context, storedPath: String): String {
        if (readable(storedPath)) return storedPath
        val (primary, rel) = GroupIds.split(storedPath) ?: return storedPath
        if (rel.isEmpty()) return storedPath
        val result = roots(context).asSequence()
            .map { it.absolutePath }
            .filter { GroupIds.isPrimaryRoot(it) == primary }
            .map { it.trimEnd('/') + "/" + rel }
            .firstOrNull { it != storedPath && readable(it) }
            ?: storedPath
        android.util.Log.i("MediaPaths", "resolve $storedPath -> $result")
        return result
    }

    /**
     * 换过挂载名后残留的旧挂载是“僵尸”目录：stat 能过，真读就 EIO。
     * 所以不能只看 exists()，得实际读一个字节。
     */
    private fun readable(path: String): Boolean = runCatching {
        File(path).inputStream().use { it.read() }
        true
    }.getOrDefault(false)
}
