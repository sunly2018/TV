package com.sunli.tvlibrary.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class CoverStore(context: Context) {

    private val dir = File(context.filesDir, "covers").apply { mkdirs() }

    fun fileOf(groupId: String): File = File(dir, "$groupId.jpg")

    fun missFile(groupId: String): File = File(dir, "$groupId.miss")

    fun readAll(): Map<String, String> =
        dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".jpg") && it.length() > 0 }
            ?.mapNotNull { file ->
                if (isLandscape(file)) {
                    file.nameWithoutExtension to file.absolutePath
                } else {
                    null
                }
            }
            ?.toMap()
            .orEmpty()

    private fun isLandscape(file: File): Boolean {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outWidth > 0 && options.outWidth >= options.outHeight * 1.2f
    }

    fun save(groupId: String, bytes: ByteArray): String? = runCatching {
        val target = fileOf(groupId)
        FileOutputStream(target).use { it.write(bytes) }
        missFile(groupId).delete()
        target.absolutePath.takeIf { target.length() > 0 }
    }.getOrNull()

    fun markMiss(groupId: String) {
        runCatching { missFile(groupId).writeText("1") }
    }

    fun clear(groupId: String) {
        runCatching { fileOf(groupId).delete() }
        runCatching { missFile(groupId).delete() }
    }
}

class CoverCandidate(val full: ByteArray, val preview: ByteArray)

/**
 * 按片名从 Bing 图片搜索抓海报，只保留横版（宽高比 ≥ 1.2）的图片；找不到时返回 null。
 */
class CoverFetcher {

    suspend fun fetchCover(title: String): ByteArray? = withContext(Dispatchers.IO) {
        val query = normalize(title)
        if (query.length < 2 || isCameraName(title)) return@withContext null
        val wanted = compact(query)
        for (search in searchQueries(title, query)) {
            val url = try {
                "https://www.bing.com/images/async?q=" +
                    URLEncoder.encode(search, "UTF-8") +
                    "&first=1&count=35&mkt=zh-CN"
            } catch (t: Throwable) {
                continue
            }
            val body = request(url)?.toString(Charsets.UTF_8) ?: continue
            for (thumb in candidates(body, wanted)) {
                val bytes = request(thumb, binary = true) ?: continue
                if (isLandscape(bytes)) return@withContext bytes
            }
        }
        null
    }

    /** 同一次搜索取多个候选，供手动挑选海报。 */
    suspend fun fetchCandidates(title: String, limit: Int = 12): List<CoverCandidate> =
        withContext(Dispatchers.IO) {
            val query = normalize(title)
            if (query.length < 2 || isCameraName(title)) return@withContext emptyList()
            val wanted = compact(query)
            val result = mutableListOf<CoverCandidate>()
            val seen = HashSet<String>()
            for (search in searchQueries(title, query)) {
                val url = try {
                    "https://www.bing.com/images/async?q=" +
                        URLEncoder.encode(search, "UTF-8") +
                        "&first=1&count=35&mkt=zh-CN"
                } catch (t: Throwable) {
                    continue
                }
                val body = request(url)?.toString(Charsets.UTF_8) ?: continue
                for (thumb in candidates(body, wanted)) {
                    if (!seen.add(thumb)) continue
                    val bytes = request(thumb, binary = true) ?: continue
                    if (!isLandscape(bytes)) continue
                    result += CoverCandidate(bytes, previewOf(bytes))
                    if (result.size >= limit) return@withContext result
                }
            }
            result
        }

    private fun previewOf(bytes: ByteArray): ByteArray = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 360) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return@runCatching bytes
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bitmap.recycle()
        out.toByteArray()
    }.getOrDefault(bytes)

    private fun searchQueries(title: String, query: String): List<String> {
        val base = title.trim().take(40).ifBlank { query }
        // 「大决战之平津战役」这类片名，去掉「之」搜到的结果更贴切
        val plain = base.replace("之", " ")
        return listOf("$base 海报", "$base 电影", plain, base)
            .map { SPACES.replace(it, " ").trim() }
            .distinct()
    }

    private fun candidates(body: String, wanted: String): List<String> {
        val result = mutableListOf<String>()
        for (match in ENTRY.findAll(body)) {
            val raw = decodeEntities(match.groupValues[1])
            val turl = field(raw, "turl") ?: continue
            val text = field(raw, "t") ?: field(raw, "desc") ?: ""
            val key = compact(text)
            if (!key.contains(wanted) && !wanted.contains(key)) continue
            result += posterUrl(turl)
        }
        return result
    }

    private fun posterUrl(thumb: String): String =
        thumb + (if (thumb.contains("?")) "&" else "?") + "w=1280&rs=1"

    private fun isLandscape(bytes: ByteArray): Boolean {
        if (bytes.size < 4_000) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return options.outWidth >= 300 && options.outWidth >= options.outHeight * 1.2f
    }

    private fun field(raw: String, name: String): String? =
        Regex("\"" + name + "\":\"([^\"]*)\"").find(raw)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    private fun compact(text: String): String =
        normalize(text).replace(PUNCTUATION, "").replace("之", "")

    private fun decodeEntities(raw: String): String = raw
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    private fun request(url: String, binary: Boolean = false): ByteArray? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 12_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"
        )
        connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        try {
            val code = connection.responseCode
            if (code !in 200..299) return null
            val bytes = connection.inputStream.use { it.readBytes() }
            if (binary) bytes.takeIf { it.size > 1024 } else bytes
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    companion object {
        private val ENTRY = Regex("""m="(\{[^"]*?\})"""")
        private val PUNCTUATION = Regex("""[\s\p{P}]""")
        private val BRACKETS = Regex("""[【\[（(].*?[】\]）)]""")
        private val YEAR = Regex("""(?i)\b(19|20)\d{2}\b""")
        private val QUALITY = Regex(
            """(?i)\b(2160p|1080p|720p|480p|4k|8k|uhd|hdr|bluray|blu-ray|web-?dl|webrip|""" +
                """hdrip|dvdrip|brrip|remux|x264|x265|h\.?264|h\.?265|hevc|aac|dts|""" +
                """ac3|10bit|60fps|hq|hd|sd|v\d)\b"""
        )
        private val SEPARATORS = Regex("""[._\-+·:：,，!！?？'"“”‘’|/\\]""")
        private val NOISE = Regex(
            """(?i)(全集|完结|未删减|国语|粤语|中字|双语|中英字幕|高清|超清|蓝光|""" +
                """导演剪辑版|加长版|修复版|重制版)"""
        )
        private val SPACES = Regex("""\s+""")
        private val CAMERA = Regex(
            """(?i)^(img|vid|dsc|dscf|dscn|pxl|mvimg|mov_|mvi|dji_|gopr|g0\d{6}|p\d{7})[\s_-]*\d"""
        )
        private val CJK = Regex("""[\u4e00-\u9fff]""")

        private fun isCameraName(raw: String): Boolean {
            val text = raw.trim()
            if (CAMERA.containsMatchIn(text)) return true
            if (CJK.containsMatchIn(text)) return false
            val letters = text.count { it.isLetter() }
            return letters < 3
        }

        fun normalize(raw: String): String {
            var text = raw.trim()
            text = BRACKETS.replace(text, " ")
            text = QUALITY.replace(text, " ")
            text = NOISE.replace(text, " ")
            text = YEAR.replace(text, " ")
            text = SEPARATORS.replace(text, " ")
            text = SPACES.replace(text, " ").trim()
            // 电视剧文件常见「某某 第01集」「某某 S01E02」，只保留片名
            text = text.substringBefore(" 第").trim()
            text = Regex("""(?i)\bs\d{1,2}(e\d{1,3})?\b""").replace(text, " ")
            text = Regex("""\d{1,3}\s*集""").replace(text, " ")
            text = text.lowercase(Locale.ROOT)
            return SPACES.replace(text, " ").trim()
        }
    }
}
