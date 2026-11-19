package com.sunli.tvlibrary.data

import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

class MediaScanner(private val context: Context) {

    fun storageRoots(): List<File> = MediaPaths.roots(context)

    suspend fun scan(
        includePhotos: Boolean = true,
        onProgress: (String) -> Unit = {}
    ): Library = withContext(Dispatchers.IO) {
        val roots = storageRoots()
        if (roots.isEmpty()) {
            return@withContext Library(scannedAt = System.currentTimeMillis())
        }

        val videos = mutableListOf<File>()
        val photos = mutableListOf<File>()
        val seen = HashSet<String>()
        var seenVideos = 0

        for (root in roots) {
            val queue = ArrayDeque<Pair<File, Int>>()
            queue.add(root to 0)
            while (queue.isNotEmpty()) {
                coroutineContext.ensureActive()
                val (dir, depth) = queue.removeFirst()
                if (depth > MAX_DEPTH) continue
                val children = dir.listFiles() ?: continue
                for (child in children) {
                    if (child.name.startsWith(".")) continue
                    if (child.isDirectory) {
                        if (!isSkippedDir(child)) queue.add(child to depth + 1)
                        continue
                    }
                    if (isOperaName(child.name)) continue
                    if (child.length() <= 0 || !child.canRead()) continue
                    val ext = child.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    if (ext in IGNORED_EXTENSIONS) continue
                    val path = child.absolutePath
                    when {
                        ext in VIDEO_EXTENSIONS && seen.add(path) -> {
                            videos.add(child)
                            seenVideos++
                            if (seenVideos % 40 == 0) onProgress("已找到 $seenVideos 个视频…")
                        }
                        includePhotos && ext in PHOTO_EXTENSIONS && seen.add(path) -> photos.add(child)
                    }
                }
            }
        }

        if (!includePhotos) {
            onProgress("正在整理剧集…")
            return@withContext Library(
                groups = buildGroups(videos, roots),
                photos = emptyList(),
                scannedAt = System.currentTimeMillis()
            )
        }

        if (photos.isNotEmpty()) {
            onProgress("正在检查照片 0/${photos.size}…")
        }
        val readablePhotos = validatePhotos(photos, onProgress)

        onProgress("正在整理剧集…")
        Library(
            groups = buildGroups(videos, roots),
            photos = readablePhotos
                .distinctBy { it.name.lowercase(Locale.ROOT) + "|" + it.length() }
                .sortedBy { it.name.lowercase(Locale.ROOT) }
                .map { Photo(it.absolutePath, it.name, it.length()) },
            scannedAt = System.currentTimeMillis()
        )
    }

    /**
     * 逐张校验能否解码，解不出来的（如 Android 9 不支持的 AVIF、损坏文件）不进入资源库。
     */
    private suspend fun validatePhotos(files: List<File>, onProgress: (String) -> Unit): List<File> {
        if (files.isEmpty()) return files
        val total = files.size
        val checked = AtomicInteger(0)
        return coroutineScope {
            val semaphore = Semaphore(4)
            files.map { file ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val readable = isReadableImage(file)
                        val done = checked.incrementAndGet()
                        if (done % 200 == 0 || done == total) {
                            onProgress("正在检查照片 $done/$total…")
                        }
                        if (readable) file else null
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private fun isReadableImage(file: File): Boolean = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        options.outWidth > 0 && options.outHeight > 0
    }.getOrDefault(false)

    private fun isSkippedDir(dir: File): Boolean {
        if (!dir.canRead()) return true
        if (isOperaName(dir.name)) return true
        val name = dir.name.lowercase(Locale.ROOT)
        if (name.startsWith(".")) return true
        if (name in SKIP_DIRS) return true
        if (dir.absolutePath.startsWith(context.filesDir.absolutePath)) return true
        if (dir.absolutePath.startsWith(context.cacheDir.absolutePath)) return true
        return false
    }

    private data class ParsedFile(
        val file: File,
        val showName: String,
        val season: Int,
        val number: Int,
        val selfShot: Boolean
    )

    private fun parseFile(file: File, seriesPrefixes: Set<String>, roots: List<File>): ParsedFile {
        val base = file.name.substringBeforeLast('.')
        val inSeriesFolder = isInSeriesFolder(file, roots)
        // 放在「电视剧」这类目录下的片子不按个人素材处理，避免片名里的「中秋」等词被误判
        val selfShot = !inSeriesFolder && isSelfShot(base, file.absolutePath)
        var number = if (selfShot) null else parseEpisodeNumber(base)
        if (number == null && !selfShot) {
            // 名字直接接集数的写法（如「奋斗01」）只在同目录有多个同名前缀文件时才算剧集，
            // 否则「流浪地球2」这类片名自带数字的电影会被拆成剧集
            val attached = attachedEpisode(base)
            if (attached != null && attached.prefix in seriesPrefixes) number = attached.number
        }
        // 「01.第一回…」这类以序号开头的剧集文件，只在剧集目录下才按集数处理
        if (number == null && !selfShot && inSeriesFolder) number = leadingEpisodeNumber(base)
        return ParsedFile(
            file = file,
            showName = if (number != null) showNameOf(base) else "",
            season = if (number != null) parseSeason(base) else 1,
            number = number ?: 0,
            selfShot = selfShot
        )
    }

    private fun leadingEpisodeNumber(base: String): Int? {
        val match = RE_LEADING_EPISODE.find(base) ?: return null
        return match.groupValues[1].toIntOrNull()?.takeIf { it in 1..999 }
    }

    private data class AttachedEpisode(val prefix: String, val number: Int)

    private fun attachedEpisode(base: String): AttachedEpisode? {
        for (regex in ATTACHED_EPISODE_PATTERNS) {
            val match = regex.find(base) ?: continue
            val number = match.groupValues[2].toIntOrNull() ?: continue
            if (number !in 1..999) continue
            val prefix = cleanTitle(match.groupValues[1]).lowercase(Locale.ROOT)
            if (prefix.isNotBlank()) return AttachedEpisode(prefix, number)
        }
        return null
    }

    private fun seriesPrefixes(videos: List<File>): Map<String, Set<String>> {
        val counts = HashMap<String, HashMap<String, Int>>()
        videos.forEach { file ->
            val base = file.name.substringBeforeLast('.')
            // 已有明确集数标记的文件不参与统计，避免把「亮剑 01」和续集「亮剑2」混在一起
            if (parseEpisodeNumber(base) != null) return@forEach
            val attached = attachedEpisode(base) ?: return@forEach
            val folder = file.parentFile?.absolutePath ?: ""
            val perFolder = counts.getOrPut(folder) { HashMap() }
            perFolder[attached.prefix] = (perFolder[attached.prefix] ?: 0) + 1
        }
        return counts.mapValues { (_, perFolder) -> perFolder.filterValues { it >= 2 }.keys.toSet() }
    }

    private fun buildGroups(videos: List<File>, roots: List<File>): List<MediaGroup> {
        val seriesBuckets = LinkedHashMap<Pair<String, String>, MutableList<ParsedFile>>()
        val singles = mutableListOf<ParsedFile>()
        val seriesPrefixesByFolder = seriesPrefixes(videos)

        videos.forEach { file ->
            val folder = file.parentFile?.absolutePath ?: ""
            val parsed = parseFile(file, seriesPrefixesByFolder[folder].orEmpty(), roots)
            if (parsed.number > 0) {
                val key = folder to parsed.showName.lowercase(Locale.ROOT)
                seriesBuckets.getOrPut(key) { mutableListOf() }.add(parsed)
            } else {
                singles.add(parsed)
            }
        }

        mergeUnnamedEpisodes(seriesBuckets)

        val groups = mutableListOf<MediaGroup>()
        seriesBuckets.values.forEach { bucket ->
            if (bucket.size >= 2) {
                groups.add(buildSeriesGroup(bucket, roots))
            } else {
                singles.addAll(bucket)
            }
        }
        singles.forEach { groups.add(buildSingleGroup(it, roots)) }

        return dedupeGroups(groups).sortedWith(
            compareBy({ it.kind.ordinal }, { it.title.lowercase(Locale.ROOT) })
        )
    }

    /**
     * 同一目录同名（如 .mp4 与 .mov 双格式），或跨目录同名且总大小相同，视为同一资源只保留一份。
     */
    private fun dedupeGroups(groups: List<MediaGroup>): List<MediaGroup> {
        val seenByLocation = HashSet<String>()
        val seenByContent = HashSet<String>()
        return groups.filter { group ->
            val title = group.title.lowercase(Locale.ROOT)
            val location = group.folder.lowercase(Locale.ROOT) + "|" + title
            val content = title + "|" + group.episodes.sumOf { it.sizeBytes }
            if (location in seenByLocation || content in seenByContent) {
                false
            } else {
                seenByLocation.add(location)
                seenByContent.add(content)
                true
            }
        }
    }

    /**
     * 同一文件夹里部分文件没有剧名（例如 "01.mkv"），合并到唯一的具名剧集分组里。
     */
    private fun mergeUnnamedEpisodes(
        buckets: LinkedHashMap<Pair<String, String>, MutableList<ParsedFile>>
    ) {
        val byFolder = buckets.keys.groupBy { it.first }
        byFolder.values.forEach { keys ->
            val named = keys.filter { it.second.isNotBlank() }
            val unnamed = keys.filter { it.second.isBlank() }
            if (named.size == 1 && unnamed.isNotEmpty()) {
                val target = buckets[named.first()] ?: return@forEach
                unnamed.forEach { key ->
                    buckets[key]?.let {
                        target.addAll(it)
                        it.clear()
                    }
                }
            }
        }
        buckets.entries.removeAll { it.value.isEmpty() }
    }

    private fun buildSeriesGroup(bucket: List<ParsedFile>, roots: List<File>): MediaGroup {
        val first = bucket.first()
        val folder = first.file.parentFile
        val folderTitle = folderTitle(folder, roots)
        val title = cleanTitle(
            first.showName.ifBlank { folderTitle }
                .ifBlank { first.file.name.substringBeforeLast('.') }
        )
        val isDoc = isDocumentary(
            (folder?.absolutePath ?: "") + " " + title + " " +
                bucket.joinToString(" ") { it.file.name }
        )
        val episodes = bucket
            .sortedWith(compareBy({ it.season }, { it.number }, { it.file.name.lowercase(Locale.ROOT) }))
            .map { p ->
                Episode(
                    path = p.file.absolutePath,
                    title = episodeTitle(p),
                    season = p.season,
                    number = p.number,
                    sizeBytes = p.file.length()
                )
            }
        return MediaGroup(
            id = GroupIds.forFolder(folder?.absolutePath ?: title, title),
            title = title,
            kind = if (isDoc) MediaKind.DOCUMENTARY else MediaKind.SERIES,
            posterPath = null,
            folder = folder?.absolutePath ?: "",
            episodes = episodes
        )
    }

    private fun buildSingleGroup(parsed: ParsedFile, roots: List<File>): MediaGroup {
        val file = parsed.file
        val base = file.name.substringBeforeLast('.')
        val fileTitle = cleanTitle(base)
        val title = parsed.showName
            .ifBlank { fileTitle.takeIf { !isGenericName(it) } ?: "" }
            .ifBlank { folderTitle(file.parentFile, roots) }
            .ifBlank { base }
        val kind = when {
            parsed.selfShot || isDocumentary(file.absolutePath) -> MediaKind.DOCUMENTARY
            isInSeriesFolder(file, roots) -> MediaKind.SERIES
            else -> MediaKind.MOVIE
        }
        return MediaGroup(
            id = GroupIds.forFile(file.absolutePath, title),
            title = title,
            kind = kind,
            posterPath = null,
            folder = file.parentFile?.absolutePath ?: "",
            episodes = listOf(
                Episode(
                    path = file.absolutePath,
                    title = title,
                    season = 1,
                    number = 0,
                    sizeBytes = file.length()
                )
            )
        )
    }

    private fun episodeTitle(p: ParsedFile): String =
        when {
            p.season > 1 -> "第${p.season}季 第${p.number}集"
            p.number > 0 -> "第${p.number}集"
            else -> p.file.name.substringBeforeLast('.')
        }

    private fun parseEpisodeNumber(base: String): Int? {
        RE_SXXEXX.find(base)?.let { return it.groupValues[2].toIntOrNull() }
        RE_1X01.find(base)?.let { return it.groupValues[2].toIntOrNull() }
        RE_CN_EP.find(base)?.let { return it.groupValues[1].toIntOrNull() }
        RE_EP_WORD.find(base)?.let { return it.groupValues[1].toIntOrNull() }
        // 集数标记直接粘在中文后面（如「走向共和未删减EP01」），中间没有分隔符
        RE_EP_GLUED.find(base)?.let { return it.groupValues[1].toIntOrNull() }
        RE_CN_EP2.find(base)?.let { return it.groupValues[1].toIntOrNull() }
        RE_CN_EP_CN.find(base)?.let { match ->
            val n = chineseNumber(match.groupValues[1])
            if (n > 0) return n
        }
        // 文件名只有括号数字（如「(1).mp4」「（01）.mp4」），剧名取自所在目录
        RE_BARE_EPISODE.find(base)?.let { return it.groupValues[1].toIntOrNull() }
        RE_TRAILING_NUM.find(base)?.let { match ->
            val n = match.groupValues[1].toIntOrNull() ?: return null
            if (n in 1..999) return n
        }
        return null
    }

    private fun parseSeason(base: String): Int {
        RE_SEASON_CN.find(base)?.let { match ->
            val season = chineseNumber(match.groupValues[1])
            if (season > 0) return season
        }
        RE_SXXEXX.find(base)?.let { return it.groupValues[1].toIntOrNull() ?: 1 }
        RE_SEASON.find(base)?.let { return it.groupValues[1].toIntOrNull() ?: 1 }
        return 1
    }

    private fun showNameOf(base: String): String {
        var cut = base.length
        for (regex in SHOW_NAME_MARKERS) {
            val match = regex.find(base) ?: continue
            if (match.range.first < cut) cut = match.range.first
        }
        return cleanTitle(base.substring(0, cut))
    }

    private fun folderTitle(folder: File?, roots: List<File>): String {
        val rootPaths = roots.map { it.absolutePath }.toSet()
        val fallback = folder?.name ?: ""
        var current = folder
        while (current != null) {
            val name = current.name
            if (name.isNotBlank() && !isGenericName(name)) {
                val cleaned = cleanTitle(name)
                if (cleaned.isNotBlank()) return cleaned
            }
            if (current.absolutePath in rootPaths) break
            current = current.parentFile
        }
        return cleanTitle(fallback)
    }

    /** 暂时不收录戏曲类资源：目录或文件名里带戏曲/剧种关键词的整段跳过。 */
    private fun isOperaName(name: String): Boolean =
        OPERA_KEYWORDS.any { name.contains(it) }

    private fun isGenericName(name: String): Boolean {
        val n = name.trim().lowercase(Locale.ROOT)
        if (n in GENERIC_NAMES) return true
        return GENERIC_PATTERNS.any { it.matches(n) }
    }

    private fun isDocumentary(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return DOCUMENTARY_KEYWORDS.any { lower.contains(it) }
    }

    /**
     * 单个文件没有集数信息时按目录兜底：放在「电视剧」这类目录下的按剧集处理，
     * 兼顾整部剧打包成一个文件、或剧集命名不规范的情况。
     */
    private fun isInSeriesFolder(file: File, roots: List<File>): Boolean {
        val rootPaths = roots.map { it.absolutePath }.toSet()
        var dir = file.parentFile
        while (dir != null) {
            if (dir.name.trim().lowercase(Locale.ROOT) in SERIES_FOLDER_NAMES) return true
            if (dir.absolutePath in rootPaths) break
            dir = dir.parentFile
        }
        return false
    }

    /**
     * 自己拍的素材：相机/手机命名规则，或位于婚礼、旅游、聚会等个人目录里。
     */
    private fun isSelfShot(base: String, path: String): Boolean {
        if (CAMERA_NAME_PATTERNS.any { it.containsMatchIn(base) }) return true
        val lower = path.lowercase(Locale.ROOT)
        return PERSONAL_KEYWORDS.any { lower.contains(it) }
    }

    private fun cleanTitle(raw: String): String {
        var s = raw.replace(RE_BRACKETS, " ")
        s = s.replace(RE_SEASON_CN, " ")
        s = s.replace(RE_RELEASE_JUNK, " ")
        s = s.replace(Regex("""[\s._]+"""), " ")
        return s.trim(' ', '-', '_', '.', '·').ifBlank { raw.trim() }
    }

    private fun chineseNumber(raw: String): Int {
        raw.toIntOrNull()?.let { return it }
        val digits = mapOf(
            '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5,
            '六' to 6, '七' to 7, '八' to 8, '九' to 9
        )
        if (raw == "十") return 10
        if (raw.length == 2 && raw[0] == '十') return 10 + (digits[raw[1]] ?: 0)
        if (raw.length == 2 && raw[1] == '十') return (digits[raw[0]] ?: 0) * 10
        if (raw.length == 3 && raw[1] == '十') {
            return (digits[raw[0]] ?: 0) * 10 + (digits[raw[2]] ?: 0)
        }
        return digits[raw.firstOrNull()] ?: 0
    }

    companion object {
        private const val MAX_DEPTH = 8

        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "ts", "m2ts", "mts",
            "rmvb", "rm", "webm", "m4v", "mpg", "mpeg", "3gp", "vob", "iso"
        )

        private val IGNORED_EXTENSIONS = setOf("mp3")

        private val OPERA_KEYWORDS = listOf(
            "戏曲", "戲曲", "秦腔", "京剧", "京劇", "豫剧", "豫劇", "越剧", "越劇",
            "黄梅戏", "黃梅戲", "评剧", "評劇", "昆曲", "崑曲", "川剧", "川劇",
            "粤剧", "粵劇", "二人转", "二人轉", "梆子", "曲剧", "曲劇", "吕剧", "呂劇",
            "沪剧", "滬劇", "淮剧", "淮劇", "晋剧", "晉劇", "梨园", "梨園",
            "折子戏", "折子戲"
        )

        private val PHOTO_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "bmp", "webp", "gif", "heic", "heif", "avif"
        )

        private val SKIP_DIRS = setOf(
            "android", "lost.dir", "system volume information", "miui",
            ".thumbnails", ".trash", "recycler", "recycle", "\$recycle.bin", "found.000"
        )

        private val DOCUMENTARY_KEYWORDS = listOf(
            "纪录片", "紀錄片", "记录片", "documentary", "bbc", "国家地理", "探索",
            "discovery", "nhk", "历史", "人文", "自然", "地球", "planet", "nature",
            "舌尖", "disney nature"
        )

        private val CAMERA_NAME_PATTERNS = listOf(
            Regex("""(?i)^(?:IMG|MVI|VID|MOV|DSC|DSCF|DSCN|DSC_|PXL|MVIMG|CIMG|PANO|GOPR|GX\d{2}|GH\d{2}|DJI)[\s_-]*\d"""),
            Regex("""^\d{3}[A-Z]\d{4}"""),
            Regex("""^\d{8}[\s_-]\d{6}"""),
            Regex("""^\d{4}[-_]\d{2}[-_]\d{2}"""),
            Regex("""(?i)^(?:capture|screenrecord|screen record|start|end)$""")
        )

        private val PERSONAL_KEYWORDS = listOf(
            "婚礼", "婚纱", "结婚", "录像", "聚会", "聚餐", "旅游", "旅行", "游玩",
            "生日", "家庭", "宝宝", "毕业", "纪念", "生活", "素材", "中秋", "春节",
            "国庆", "端午", "元旦", "圣诞", "自驾", "全家", "奶奶", "爷爷", "外婆",
            "外公", "爸妈", "父亲", "母亲", "周岁", "百天"
        )

        private val SERIES_FOLDER_NAMES = setOf(
            "电视剧", "连续剧", "剧集", "美剧", "韩剧", "日剧", "英剧", "港剧", "台剧",
            "国产剧", "tv", "tv shows", "tv series", "series"
        )

        private val GENERIC_NAMES = setOf(
            "season", "seasons", "s", "video", "videos", "movie", "movies", "tv",
            "tv shows", "series", "shows", "media", "download", "downloads",
            "电视剧", "电影", "纪录片", "视频", "影片", "下载", "影视", "合集", "其他"
        )

        private val GENERIC_PATTERNS = listOf(
            Regex("""^(?:season|series|s)\s*\d{1,2}$"""),
            Regex("""^第\s*[0-9一二三四五六七八九十]+\s*季$"""),
            Regex("""^(?:disc|disk|cd|dvd|part|碟)\s*\d{1,2}$""")
        )

        private val RE_SXXEXX = Regex("""(?i)S(\d{1,2})[\s._-]*E(\d{1,3})""")
        private val RE_1X01 = Regex("""(?i)(?:^|[\s._\[(-])(\d{1,2})x(\d{1,3})(?:[\s._\])-]|$)""")
        private val RE_CN_EP = Regex("""第\s*(\d{1,4})\s*[集话話期回]""")
        private val RE_EP_WORD = Regex("""(?i)(?:^|[\s._\[(-])(?:EP?|EPISODE)\s*[._-]?\s*(\d{1,4})\b""")
        private val RE_EP_GLUED = Regex("""(?i)EP\s*[._-]?\s*(\d{1,3})\b""")
        private val RE_CN_EP2 = Regex("""(?:^|[\s._\[(-])(\d{1,3})\s*[集话話]""")
        private val RE_CN_EP_CN = Regex("""第\s*([一二三四五六七八九十]{1,3})\s*[集话話期回]""")
        private val RE_BARE_EPISODE = Regex("""^[\(（\[]\s*(\d{1,3})\s*[\)）\]]$""")
        // 「01.第一回…」「12 第二十回…」这类以集数开头的剧集文件
        private val RE_LEADING_EPISODE = Regex("""^(\d{1,3})[.、_\-\s]+(?=\S)""")
        private val RE_TRAILING_NUM = Regex("""(?:^|[\s._\[(-])(\d{1,4})\s*$""")
        // 「奋斗01」「奋斗(01)」这类直接粘在剧名后的集数
        private val RE_ATTACHED_EPISODE = Regex("""^(.*?[\u4e00-\u9fff])\s*(\d{1,3})\s*$""")
        private val RE_PAREN_EPISODE =
            Regex("""^(.*?[\u4e00-\u9fff])\s*[\(（\[]\s*(\d{1,3})\s*[\)）\]]\s*$""")
        // 「奋斗01.HDTV」这类集数后面还跟着清晰度/字幕等后缀
        private val RE_ATTACHED_EPISODE_SUFFIX =
            Regex("""^(.*?[\u4e00-\u9fff])\s*(\d{1,3})(?=[\s._-]).*$""")
        private val ATTACHED_EPISODE_PATTERNS =
            listOf(RE_PAREN_EPISODE, RE_ATTACHED_EPISODE, RE_ATTACHED_EPISODE_SUFFIX)
        private val RE_ATTACHED_EP_MARK = Regex("""(?<=[\u4e00-\u9fff])\s*\d{1,3}\s*$""")
        private val RE_PAREN_EP_MARK = Regex("""[\(（\[]\s*\d{1,3}\s*[\)）\]]\s*$""")
        private val RE_ATTACHED_EP_SUFFIX_MARK = Regex("""(?<=[\u4e00-\u9fff])\s*\d{1,3}(?=[\s._-])""")
        private val RE_SEASON_CN = Regex("""第\s*([0-9一二三四五六七八九十]{1,3})\s*季""")
        private val RE_SEASON = Regex("""(?i)(?:^|[\s._\[(-])S(?:EASON)?\s*(\d{1,2})(?:[\s._\])-]|$)""")
        private val RE_BRACKETS = Regex("""[\[\(【（][^\]\)】）]*[\]\)】）]""")
        private val RE_RELEASE_JUNK = Regex(
            """(?i)[\s._-]*(?:2160p|1080p|720p|480p|4k|uhd|hdr10|hdr|bluray|blu-ray|bdrip|""" +
                """web-?dl|webrip|hdrip|dvdrip|remux|x264|x265|h\.?264|h\.?265|hevc|avc|""" +
                """aac|ac3|dts|ddp?5\.1|10bit|proper|repack|国语|中字|中英字幕|双语|高清).*"""
        )

        private val SHOW_NAME_MARKERS = listOf(
            RE_SXXEXX, RE_CN_EP, RE_EP_WORD, RE_EP_GLUED, RE_CN_EP2, RE_CN_EP_CN, RE_1X01,
            RE_TRAILING_NUM, RE_LEADING_EPISODE, RE_ATTACHED_EP_MARK, RE_PAREN_EP_MARK,
            RE_ATTACHED_EP_SUFFIX_MARK
        )
    }
}
