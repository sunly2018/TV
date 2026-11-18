package com.sunli.tvlibrary.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class ThumbnailStore(context: Context) {

    private val dir = File(context.cacheDir, "posters").apply { mkdirs() }

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
            val timeUs = if (durationMs > 0) (durationMs * 200L).coerceAtLeast(0L) else 0L
            val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
                ?: return false
            val width = 480
            val height = (frame.height.toFloat() / frame.width.toFloat() * width)
                .toInt().coerceIn(1, 1080)
            val scaled = Bitmap.createScaledBitmap(frame, width, height, true)
            FileOutputStream(target).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
            }
            if (scaled !== frame) scaled.recycle()
            frame.recycle()
            target.length() > 0
        } catch (t: Throwable) {
            target.delete()
            false
        } finally {
            runCatching { retriever.release() }
        }
    }

    companion object {
        private val releaseExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "thumbnail-release").apply { isDaemon = true }
        }
    }
}
