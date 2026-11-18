package com.sunli.tvlibrary.player

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioRenderer

/**
 * 只把 ffmpeg 音频解码器挂进播放器，视频始终走平台硬件解码。
 * 盒子对部分 HEVC 文件（profile/level 超出声明范围）会退到 ffmpeg 软件视频解码，
 * 弱机上根本解不动，表现就是切换剧集后一直转圈。
 */
@OptIn(UnstableApi::class)
class AudioOnlyRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out
        )
        if (extensionRendererMode == EXTENSION_RENDERER_MODE_OFF) return
        out.add(FfmpegAudioRenderer(eventHandler, eventListener, audioSink))
    }
}

@OptIn(UnstableApi::class)
@Composable
fun VideoSurface(player: Player, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                this.player = player
                // 盒子系统的字幕样式默认带黑底，会挡住画面；这里改成透明底、白字黑边
                subtitleView?.setStyle(
                    CaptionStyleCompat(
                        android.graphics.Color.WHITE,
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT,
                        CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                        android.graphics.Color.BLACK,
                        null
                    )
                )
            }
        },
        update = { playerView -> playerView.player = player },
        // 主动解绑，避免 PlayerView 的 SurfaceView 在离开播放页后还挂着上一次的画面
        onRelease = { playerView -> playerView.player = null },
        modifier = modifier
    )
}
