package com.sunli.tvlibrary.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class ThumbnailStore(context: Context) {

    private val dir = File(context.cacheDir, "posters2").apply { mkdirs() }

    init {
        // 旧目录的封面是未裁黑边的视频帧，换目录让它们重新生成
        releaseExecutor.execute {
            runCatching { File(context.cacheDir, "posters").deleteRecursively() }
        }
    }

    val directory: File get() = dir

    fun posterFile(groupId: String): File = File(dir, "$groupId.jpg")

    private var framePath: String? = null
    private var frameRetriever: MediaMetadataRetriever? = null

    /** 提前打开解码器，避免第一次快进时临时建索引导致缩略图迟迟不出现 */
    @Synchronized
    fun prepare(videoPath: String) {
        if (framePath == videoPath && frameRetriever != null) return
        releaseFrameRetriever()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoPath)
            frameRetriever = retriever
            framePath = videoPath
        } catch (t: Throwable) {
            runCatching { retriever.release() }
        }
    }

    @Synchronized
    fun releaseFrameRetriever() {
        runCatching { frameRetriever?.release() }
        frameRetriever = null
        framePath = null
    }

    /**
     * 退出播放页时不能直接在主线程 release：解码线程可能正卡在 getFrameAtTime/setDataSource，
     * 主线程等锁会把返回操作冻住，画面黑掉但播放器还没被释放、声音继续。
     */
    fun releaseAsync() {
        releaseExecutor.execute { releaseFrameRetriever() }
    }

    @Synchronized
    fun extractFrame(videoPath: String, timeMs: Long, width: Int = 480): Bitmap? {
        if (framePath != videoPath || frameRetriever == null) prepare(videoPath)
        val retriever = frameRetriever
        if (retriever == null) return null
        return try {
            val useMs = timeMs.coerceAtLeast(0L)
            val frame = retriever.getFrameAtTime(
                useMs * 1000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: retriever.getFrameAtTime(useMs * 1000L)
            ?: return null
            val target = width.coerceIn(160, 960)
            val height = (frame.height.toFloat() / frame.width.toFloat() * target)
                .toInt().coerceIn(1, 1080)
            val scaled = Bitmap.createScaledBitmap(frame, target, height, true)
            if (scaled !== frame) frame.recycle()
            scaled
        } catch (t: Throwable) {
            // 解码器异常后丢弃，下次重新打开
            releaseFrameRetriever()
            null
        }
    }

    fun extractPoster(videoPath: String, groupId: String): Boolean {
        val target = posterFile(groupId)
        if (target.exists() && target.length() > 0) return true
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoPath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val scaled = posterFrame(retriever, durationMs) ?: return false
            val poster = cropBlackBars(scaled)
            FileOutputStream(target).use { out ->
                poster.compress(Bitmap.CompressFormat.JPEG, 82, out)
            }
            if (poster !== scaled) poster.recycle()
            scaled.recycle()
            target.length() > 0
        } catch (t: Throwable) {
            target.delete()
            false
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * 在线搜不到海报时，用影片自身的画面当候选，保证换海报永远有得选。
     * TS/超长文件 seek 可能卡在解码器上，到点强制 release 让抽取退出，已拿到的帧照常返回。
     */
    fun frameCandidates(
        videoPath: String,
        count: Int = 6,
        budgetMs: Long = 15_000L
    ): List<ByteArray> {
        val retriever = MediaMetadataRetriever()
        val result = mutableListOf<ByteArray>()
        val deadline = System.currentTimeMillis() + budgetMs
        val watchdog = Thread {
            runCatching { Thread.sleep(budgetMs) }
            runCatching { retriever.release() }
        }.apply {
            isDaemon = true
            name = "frame-budget"
        }
        return try {
            retriever.setDataSource(videoPath)
            watchdog.start()
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val fractions = if (durationMs > 0) {
                List(count) { (it + 1).toFloat() / (count + 1) }
            } else {
                listOf(0f)
            }
            for (fraction in fractions) {
                if (System.currentTimeMillis() >= deadline) break
                val timeUs = (durationMs * fraction * 1000L).toLong().coerceAtLeast(0L)
                val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(timeUs)
                    ?: continue
                val width = 640
                val height = (frame.height.toFloat() / frame.width.toFloat() * width)
                    .toInt().coerceIn(1, 1280)
                val scaled = Bitmap.createScaledBitmap(frame, width, height, true)
                if (scaled !== frame) frame.recycle()
                if (isMostlyDark(scaled)) {
                    scaled.recycle()
                    continue
                }
                val poster = cropBlackBars(scaled)
                if (poster !== scaled) scaled.recycle()
                val bytes = ByteArrayOutputStream().use { out ->
                    poster.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    out.toByteArray()
                }
                poster.recycle()
                if (bytes.isNotEmpty()) result += bytes
            }
            result
        } catch (t: Throwable) {
            result
        } finally {
            watchdog.interrupt()
            runCatching { retriever.release() }
        }
    }

    /** 片头常见黑场，按几个时间点依次试，取第一张不是全黑的帧。 */
    private fun posterFrame(retriever: MediaMetadataRetriever, durationMs: Long): Bitmap? {
        val fractions = if (durationMs > 0) listOf(0.2f, 0.5f, 0.75f) else listOf(0f)
        for (fraction in fractions) {
            val timeUs = (durationMs * fraction * 1000L).toLong().coerceAtLeast(0L)
            val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(timeUs)
                ?: continue
            val width = 480
            val height = (frame.height.toFloat() / frame.width.toFloat() * width)
                .toInt().coerceIn(1, 1080)
            val scaled = Bitmap.createScaledBitmap(frame, width, height, true)
            if (scaled !== frame) frame.recycle()
            if (isMostlyDark(scaled)) {
                scaled.recycle()
                continue
            }
            return scaled
        }
        return null
    }

    private fun isMostlyDark(bitmap: Bitmap): Boolean {
        val stepX = (bitmap.width / 32).coerceAtLeast(1)
        val stepY = (bitmap.height / 18).coerceAtLeast(1)
        var sum = 0L
        var count = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val color = bitmap.getPixel(x, y)
                sum += (((color shr 16) and 0xFF) + ((color shr 8) and 0xFF) + (color and 0xFF)) / 3
                count++
                x += stepX
            }
            y += stepY
        }
        return count > 0 && sum / count < 12
    }

    /** 宽银幕片源抽出的帧自带上下黑边，裁掉后卡片才能铺满。 */
    private fun cropBlackBars(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        if (width < 16 || height < 16) return source
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        fun dark(color: Int): Boolean =
            (color and 0xFF) < 24 &&
                ((color shr 8) and 0xFF) < 24 &&
                ((color shr 16) and 0xFF) < 24

        fun rowDark(y: Int): Boolean {
            var count = 0
            val base = y * width
            for (x in 0 until width) if (dark(pixels[base + x])) count++
            return count >= width * 0.995f
        }

        fun columnDark(x: Int): Boolean {
            var count = 0
            for (y in 0 until height) if (dark(pixels[y * width + x])) count++
            return count >= height * 0.995f
        }

        val maxSide = height / 4
        val maxEdge = width / 4
        var top = 0
        while (top < maxSide && rowDark(top)) top++
        var bottom = height - 1
        while (bottom > height - 1 - maxSide && rowDark(bottom)) bottom--
        var left = 0
        while (left < maxEdge && columnDark(left)) left++
        var right = width - 1
        while (right > width - 1 - maxEdge && columnDark(right)) right--
        if (top == 0 && left == 0 && bottom == height - 1 && right == width - 1) return source
        val croppedWidth = right - left + 1
        val croppedHeight = bottom - top + 1
        if (croppedWidth < width / 3 || croppedHeight < height / 3) return source
        return Bitmap.createBitmap(source, left, top, croppedWidth, croppedHeight)
    }

    companion object {
        private val releaseExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "thumbnail-release").apply { isDaemon = true }
        }
    }
}
