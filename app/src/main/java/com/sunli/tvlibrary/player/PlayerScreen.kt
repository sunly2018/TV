package com.sunli.tvlibrary.player

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.sunli.tvlibrary.data.MediaGroup
import com.sunli.tvlibrary.data.MediaPaths
import com.sunli.tvlibrary.data.PlaybackPrefs
import com.sunli.tvlibrary.data.ThumbnailStore
import com.sunli.tvlibrary.ui.Accent
import com.sunli.tvlibrary.ui.ConfirmDialog
import com.sunli.tvlibrary.ui.EpisodeChip
import com.sunli.tvlibrary.ui.FocusableCard
import com.sunli.tvlibrary.ui.RenameDialog
import com.sunli.tvlibrary.ui.TabChip
import com.sunli.tvlibrary.ui.TextPrimary
import com.sunli.tvlibrary.ui.TextSecondary
import com.sunli.tvlibrary.ui.TvActionButton
import com.sunli.tvlibrary.ui.episodeLabel
import com.sunli.tvlibrary.ui.formatDuration
import com.sunli.tvlibrary.ui.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val SEEK_STEP_MS = 10_000L
private const val PANEL_PAGE_SIZE = 10
private const val SKIP_INTRO_MS = 90_000L
private const val SKIP_MIN_DURATION_MS = 10 * 60_000L
private val ENDED_CONFIRM_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
private val ENDED_NAV_KEYS = setOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown)

private fun openWithExternalPlayer(context: Context, path: String): Boolean = runCatching {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "video/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    true
}.getOrDefault(false)

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    group: MediaGroup,
    startIndex: Int,
    favorite: Boolean = false,
    encrypted: Boolean = false,
    skipIntro: Boolean = false,
    nextTitle: String? = null,
    onToggleFavorite: () -> Unit = {},
    onToggleEncrypt: () -> Unit = {},
    onRename: (String) -> Unit = {},
    onDelete: () -> Unit = {},
    onPurge: () -> Unit = {},
    onPlayNext: (() -> Unit)? = null,
    onExit: (Int) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { PlaybackPrefs(context) }
    val view = LocalView.current

    // 挂载名随时可能变，真正打开文件前按相对路径重新找一下
    fun mediaUri(path: String): Uri =
        Uri.fromFile(File(MediaPaths.resolve(context.applicationContext, path)))

    val episodeIndex = remember {
        mutableIntStateOf(startIndex.coerceIn(0, group.episodes.lastIndex))
    }
    val currentTitle = remember { mutableStateOf(group.episodes[episodeIndex.intValue].title) }
    val isPlaying = remember { mutableStateOf(false) }
    val buffering = remember { mutableStateOf(true) }
    val controlsVisible = remember { mutableStateOf(true) }
    val panelVisible = remember { mutableStateOf(false) }
    val ended = remember { mutableStateOf(false) }
    val errorMessage = remember { mutableStateOf<String?>(null) }
    val autoNext = remember { mutableStateOf(prefs.autoNext()) }
    val resumeApplied = remember { mutableStateOf(false) }
    val ioRetries = remember { mutableIntStateOf(0) }
    var outroSkipped by remember { mutableStateOf(false) }
    val positionMs = remember { mutableLongStateOf(0L) }
    val durationMs = remember { mutableLongStateOf(0L) }
    val skipIntroNow = rememberUpdatedState(skipIntro)
    val seekTick = remember { mutableIntStateOf(0) }
    val seekPreview = remember { mutableStateOf<Bitmap?>(null) }
    val previewJob = remember { mutableStateOf<Job?>(null) }
    val thumbs = remember { ThumbnailStore(context) }
    val scope = rememberCoroutineScope()

    val confirmDelete = remember { mutableStateOf(false) }
    val confirmPurge = remember { mutableStateOf(false) }
    val showRename = remember { mutableStateOf(false) }
    val externalHint = remember { mutableStateOf<String?>(null) }
    val audioOptions = remember { mutableStateOf<List<AudioTrackOption>>(emptyList()) }
    val audioCurrent = remember { mutableStateOf<AudioTrackOption?>(null) }
    var audioDefaultApplied by remember { mutableStateOf(false) }

    val rootFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }
    val panelActionFocus = remember { FocusRequester() }
    val errorFocus = remember { FocusRequester() }
    val endedFocuses = remember { List(3) { FocusRequester() } }
    var endedFocusIndex by remember { mutableIntStateOf(0) }
    // 播放结束瞬间还在按住的方向键（多半是快进到结尾）会继续发重复事件，先吞掉再交给面板
    val endedNavBlockedUntil = remember { mutableLongStateOf(0L) }
    val panelListState = rememberLazyListState()
    val panelPageCount =
        ((group.episodes.size + PANEL_PAGE_SIZE - 1) / PANEL_PAGE_SIZE).coerceAtLeast(1)
    val panelPage = remember {
        mutableIntStateOf(episodeIndex.intValue / PANEL_PAGE_SIZE)
    }

    val exoPlayer = remember {
        // 平台解码器优先，平台没有的音频（如 MP2）交给内置 ffmpeg。
        // 视频不走 ffmpeg：部分 HEVC 文件的等级超出盒子硬解范围时，软件解码会把播放拖死。
        val renderersFactory = AudioOnlyRenderersFactory(context.applicationContext)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .build().apply {
            playWhenReady = true
            setMediaItem(
                MediaItem.fromUri(mediaUri(group.episodes[episodeIndex.intValue].path))
            )
            prepare()
        }
    }

    fun saveCurrentProgress() {
        val episode = group.episodes[episodeIndex.intValue]
        val duration = exoPlayer.duration.coerceAtLeast(0L)
        prefs.saveProgress(episode.path, exoPlayer.currentPosition.coerceAtLeast(0L), duration)
    }

    fun syncProgress() {
        positionMs.longValue = exoPlayer.currentPosition.coerceAtLeast(0L)
        durationMs.longValue = exoPlayer.duration.coerceAtLeast(0L)
    }

    /**
     * U 盘偶发读取失败（比如系统媒体扫描同时在读同一块盘）时，
     * 按当前挂载重新解析路径再开一次，多数情况能直接续上。
     */
    fun retryPlayback(auto: Boolean) {
        val episode = group.episodes[episodeIndex.intValue]
        val resume = if (auto) {
            prefs.positionOf(episode.path)
        } else {
            resumeApplied.value = false
            0L
        }
        errorMessage.value = null
        externalHint.value = null
        buffering.value = true
        scope.launch {
            if (auto) delay(800L)
            runCatching {
                exoPlayer.setMediaItem(
                    MediaItem.fromUri(mediaUri(episode.path)),
                    if (resume > 3_000L) resume else 0L
                )
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
            }
        }
    }

    fun switchTo(index: Int) {
        if (index !in group.episodes.indices) return
        saveCurrentProgress()
        episodeIndex.intValue = index
        currentTitle.value = group.episodes[index].title
        resumeApplied.value = false
        outroSkipped = false
        ended.value = false
        errorMessage.value = null
        ioRetries.intValue = 0
        seekPreview.value = null
        previewJob.value?.cancel()
        audioDefaultApplied = false
        audioOptions.value = emptyList()
        audioCurrent.value = null
        exoPlayer.setMediaItem(
            MediaItem.fromUri(mediaUri(group.episodes[index].path))
        )
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        controlsVisible.value = true
    }

    fun cycleAudio() {
        val options = audioOptions.value
        if (options.size < 2) return
        val current = audioCurrent.value
        val index = options.indexOfFirst { AudioTracks.isCurrent(current, it) }
        val next = options[(index + 1).mod(options.size)]
        audioDefaultApplied = true
        AudioTracks.select(exoPlayer, next)
        controlsVisible.value = true
    }

    // 播放器在监听器挂上之前就可能解析完音轨，所以这里主动同步一次
    fun syncAudioTracks() {
        val tracks = exoPlayer.currentTracks
        val options = AudioTracks.options(tracks)
        val current = AudioTracks.current(tracks)
        audioOptions.value = options
        audioCurrent.value = current
        if (!audioDefaultApplied && options.size > 1) {
            audioDefaultApplied = true
            val target = AudioTracks.defaultMandarin(options)
            if (target != null && !AudioTracks.isCurrent(current, target)) {
                AudioTracks.select(exoPlayer, target)
            }
        }
        android.util.Log.i(
            "AudioTracks",
            "options=" + options.joinToString { "#${it.trackIndex}:${it.format.id}|${it.displayName}/${it.language}" } +
                " current=" + current?.let { "#${it.trackIndex}:${it.format.id}" }
        )
    }

    fun closePanel() {
        panelVisible.value = false
        runCatching { rootFocus.requestFocus() }
    }

    fun seekBy(deltaMs: Long) {
        val duration = exoPlayer.duration
        val upper = if (duration > 0) duration else Long.MAX_VALUE
        val base = positionMs.longValue.takeIf { it > 0 } ?: exoPlayer.currentPosition
        val target = (base + deltaMs).coerceIn(0L, upper)
        positionMs.longValue = target
        exoPlayer.seekTo(target)
        controlsVisible.value = true
        seekTick.intValue++
        previewJob.value?.cancel()
        val path = mediaUri(group.episodes[episodeIndex.intValue].path).path.orEmpty()
        previewJob.value = scope.launch {
            delay(120)
            val frame = withContext(Dispatchers.IO) {
                runCatching { thumbs.extractFrame(path, target) }.getOrNull()
            }
            // 取帧失败时保留上一张，避免缩略图闪没
            if (frame != null) seekPreview.value = frame
        }
    }

    fun togglePlay() {
        if (ended.value || errorMessage.value != null) {
            onExit(episodeIndex.intValue)
            return
        }
        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
        syncProgress()
        controlsVisible.value = true
    }

    fun restart() {
        ended.value = false
        outroSkipped = false
        seekPreview.value = null
        previewJob.value?.cancel()
        exoPlayer.seekTo(0L)
        exoPlayer.play()
        controlsVisible.value = true
        runCatching { rootFocus.requestFocus() }
    }

    val endedHasNextEpisode = episodeIndex.intValue + 1 < group.episodes.size
    val endedHasNextGroup = onPlayNext != null
    val endedPrimaryText = when {
        endedHasNextEpisode -> "播放下一集"
        endedHasNextGroup -> "播放下一部《$nextTitle》"
        else -> "重播"
    }
    // 结束面板按钮数：主按钮（下一集/下一部/重播）+ 重播 + 返回列表
    val endedButtonCount = if (endedHasNextEpisode || endedHasNextGroup) 3 else 2

    fun endedPrimaryAction() {
        when {
            endedHasNextEpisode -> switchTo(episodeIndex.intValue + 1)
            endedHasNextGroup -> onPlayNext?.invoke()
            else -> restart()
        }
    }

    fun requestEndedFocus(index: Int) {
        val target = when {
            index <= 0 -> endedFocuses[0]
            index >= endedButtonCount - 1 -> endedFocuses[endedButtonCount - 1]
            else -> endedFocuses[1]
        }
        runCatching { target.requestFocus() }
    }

    fun runEndedAction(index: Int) {
        when {
            index <= 0 -> endedPrimaryAction()
            index >= endedButtonCount - 1 -> onExit(episodeIndex.intValue)
            else -> restart()
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying.value = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering.value = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    ioRetries.intValue = 0
                    syncAudioTracks()
                }
                if (playbackState == Player.STATE_READY && !resumeApplied.value) {
                    resumeApplied.value = true
                    val episode = group.episodes[episodeIndex.intValue]
                    val saved = prefs.positionOf(episode.path)
                    val duration = exoPlayer.duration
                    val nearEnd = duration > 0 && duration - saved < 8_000L
                    when {
                        saved > 3_000L && !nearEnd -> exoPlayer.seekTo(saved)
                        skipIntroNow.value && duration >= SKIP_MIN_DURATION_MS ->
                            exoPlayer.seekTo(SKIP_INTRO_MS)
                    }
                }
                if (playbackState == Player.STATE_ENDED) {
                    val index = episodeIndex.intValue
                    val next = index + 1
                    if (autoNext.value && next < group.episodes.size) {
                        switchTo(next)
                    } else {
                        saveCurrentProgress()
                        ended.value = true
                        controlsVisible.value = true
                        exoPlayer.pause()
                    }
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                syncAudioTracks()
            }

            override fun onPlayerError(error: PlaybackException) {
                val ioError = error.errorCodeName.startsWith("ERROR_CODE_IO")
                android.util.Log.e(
                    "PlayerScreen",
                    "error ${error.errorCodeName} on " +
                        mediaUri(group.episodes[episodeIndex.intValue].path).path +
                        " msg=" + error.message
                )
                if (ioError && ioRetries.intValue < 2) {
                    ioRetries.intValue++
                    retryPlayback(auto = true)
                    return
                }
                saveCurrentProgress()
                errorMessage.value = if (ioError) {
                    "读取文件失败：${error.errorCodeName}"
                } else {
                    "内置播放器无法解码：${error.errorCodeName}"
                }
                controlsVisible.value = true
            }
        }
        exoPlayer.addListener(listener)
        syncAudioTracks()
        scope.launch {
            delay(400L)
            syncAudioTracks()
        }
        onDispose {
            saveCurrentProgress()
            previewJob.value?.cancel()
            exoPlayer.removeListener(listener)
            exoPlayer.clearVideoSurface()
            exoPlayer.release()
        }
    }

    // 切到后台或退出应用时立刻停播，别让声音和硬盘读取继续跑
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                saveCurrentProgress()
                exoPlayer.pause()
                controlsVisible.value = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(exoPlayer) {
        var sinceSave = 0L
        while (true) {
            // 控件收起时没人看进度，别每 500ms 写一次状态，弱机上会拖慢整帧导致按键要等很久
            if (controlsVisible.value || panelVisible.value) {
                positionMs.longValue = exoPlayer.currentPosition.coerceAtLeast(0L)
                durationMs.longValue = exoPlayer.duration.coerceAtLeast(0L)
            }
            if (exoPlayer.isPlaying) {
                sinceSave += 500L
                if (sinceSave >= 5_000L) {
                    sinceSave = 0L
                    saveCurrentProgress()
                }
                if (skipIntroNow.value && !outroSkipped && !ended.value) {
                    val duration = exoPlayer.duration.coerceAtLeast(0L)
                    val remaining = duration - exoPlayer.currentPosition.coerceAtLeast(0L)
                    if (duration >= SKIP_MIN_DURATION_MS && remaining in 1..SKIP_INTRO_MS) {
                        outroSkipped = true
                        exoPlayer.seekTo(duration)
                    }
                }
            }
            delay(500L)
        }
    }

    LaunchedEffect(controlsVisible.value, isPlaying.value, panelVisible.value, seekTick.intValue) {
        if (controlsVisible.value && isPlaying.value && !panelVisible.value) {
            delay(4_500L)
            controlsVisible.value = false
            seekPreview.value = null
        }
    }

    LaunchedEffect(episodeIndex.intValue) {
        val path = mediaUri(group.episodes[episodeIndex.intValue].path).path.orEmpty()
        withContext(Dispatchers.IO) { thumbs.prepare(path) }
    }
    DisposableEffect(thumbs) {
        onDispose { thumbs.releaseAsync() }
    }

    LaunchedEffect(panelVisible.value) {
        if (panelVisible.value) {
            if (group.episodes.size > 1) {
                panelPage.intValue = episodeIndex.intValue / PANEL_PAGE_SIZE
                val local = episodeIndex.intValue % PANEL_PAGE_SIZE
                runCatching { panelListState.scrollToItem(local) }
                delay(80L)
                runCatching { panelFocus.requestFocus() }
            } else {
                delay(80L)
                runCatching { panelActionFocus.requestFocus() }
            }
        }
    }

    LaunchedEffect(errorMessage.value) {
        externalHint.value = null
        if (errorMessage.value != null) {
            delay(60L)
            runCatching { errorFocus.requestFocus() }
        } else {
            runCatching { rootFocus.requestFocus() }
        }
    }

    LaunchedEffect(ended.value) {
        if (ended.value) {
            endedFocusIndex = 0
            endedNavBlockedUntil.longValue = SystemClock.uptimeMillis() + 500L
            panelVisible.value = false
            // 盒子渲染慢，面板节点可能还没挂上，封锁期内反复要焦点
            repeat(6) {
                delay(80L)
                if (endedFocusIndex != 0) return@LaunchedEffect
                requestEndedFocus(0)
            }
        }
    }

    LaunchedEffect(Unit) {
        runCatching { rootFocus.requestFocus() }
        view.keepScreenOn = true
    }
    DisposableEffect(Unit) {
        onDispose { view.keepScreenOn = false }
    }

    if (confirmDelete.value) {
        ConfirmDialog(
            title = "移出影库《${group.title}》？",
            message = "只从影库移除，硬盘上的原文件保留；重新扫描也不会再出现。",
            confirmText = "移出",
            onConfirm = {
                confirmDelete.value = false
                exoPlayer.pause()
                onDelete()
            },
            onDismiss = {
                confirmDelete.value = false
                runCatching { rootFocus.requestFocus() }
            }
        )
    }

    if (confirmPurge.value) {
        ConfirmDialog(
            title = "彻底删除《${group.title}》？",
            message = "将直接删除硬盘上的 ${group.episodes.size} 个源文件，删除后无法恢复。",
            confirmText = "彻底删除",
            onConfirm = {
                confirmPurge.value = false
                exoPlayer.pause()
                onPurge()
            },
            onDismiss = {
                confirmPurge.value = false
                runCatching { rootFocus.requestFocus() }
            }
        )
    }

    if (showRename.value) {
        RenameDialog(
            title = "重命名影视",
            initial = group.title,
            onConfirm = { name ->
                showRename.value = false
                onRename(name)
                closePanel()
            },
            onDismiss = {
                showRename.value = false
                val back = if (group.episodes.size > 1) panelFocus else panelActionFocus
                runCatching { back.requestFocus() }
            }
        )
    }

    BackHandler(enabled = panelVisible.value) { closePanel() }
    BackHandler(enabled = !panelVisible.value) {
        saveCurrentProgress()
        // 先停声音，别等播放器释放走完；释放偶尔会被解码线程拖住
        exoPlayer.pause()
        onExit(episodeIndex.intValue)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                val endedOverlay = ended.value && errorMessage.value == null && !panelVisible.value
                if (event.type != KeyEventType.KeyDown) {
                    if (endedOverlay && event.key in ENDED_NAV_KEYS) {
                        // 松手即解除封锁，之后的方向键才用来切换按钮
                        endedNavBlockedUntil.longValue = 0L
                        return@onPreviewKeyEvent false
                    }
                    // 结束面板的确认键已在 KeyDown 里执行过，吞掉 KeyUp 避免按钮再触发一次
                    if (endedOverlay && event.key in ENDED_CONFIRM_KEYS) {
                        return@onPreviewKeyEvent true
                    }
                    return@onPreviewKeyEvent false
                }
                // 菜单打开时优先吃掉返回键，保证一定能关掉菜单
                if (event.key == Key.Back || event.key == Key.Escape) {
                    if (panelVisible.value) {
                        closePanel()
                        return@onPreviewKeyEvent true
                    }
                    return@onPreviewKeyEvent false
                }
                // 遥控器菜单键与「下」一样开关操作面板
                if (event.key == Key.Menu && !endedOverlay && errorMessage.value == null) {
                    if (panelVisible.value) {
                        closePanel()
                    } else {
                        panelVisible.value = true
                        controlsVisible.value = true
                    }
                    return@onPreviewKeyEvent true
                }
                if (endedOverlay) {
                    // 结束面板自己管理焦点：甩尾的方向键不会把焦点甩到最后一项
                    when (event.key) {
                        Key.DirectionLeft, Key.DirectionUp -> {
                            val now = SystemClock.uptimeMillis()
                            if (now < endedNavBlockedUntil.longValue) {
                                endedNavBlockedUntil.longValue = now + 400L
                                return@onPreviewKeyEvent true
                            }
                            endedFocusIndex = (endedFocusIndex - 1).coerceAtLeast(0)
                            requestEndedFocus(endedFocusIndex)
                            return@onPreviewKeyEvent true
                        }

                        Key.DirectionRight, Key.DirectionDown -> {
                            val now = SystemClock.uptimeMillis()
                            if (now < endedNavBlockedUntil.longValue) {
                                endedNavBlockedUntil.longValue = now + 400L
                                return@onPreviewKeyEvent true
                            }
                            endedFocusIndex =
                                (endedFocusIndex + 1).coerceAtMost(endedButtonCount - 1)
                            requestEndedFocus(endedFocusIndex)
                            return@onPreviewKeyEvent true
                        }
                    }
                    if (event.key in ENDED_CONFIRM_KEYS) {
                        runEndedAction(endedFocusIndex)
                        return@onPreviewKeyEvent true
                    }
                }
                if (panelVisible.value || errorMessage.value != null || ended.value) {
                    return@onPreviewKeyEvent false
                }
                when (event.key) {
                    Key.DirectionCenter, Key.Enter -> {
                        togglePlay()
                        true
                    }

                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                        togglePlay()
                        true
                    }

                    Key.DirectionLeft -> {
                        seekBy(-SEEK_STEP_MS)
                        true
                    }

                    Key.DirectionRight -> {
                        seekBy(SEEK_STEP_MS)
                        true
                    }

                    Key.DirectionDown -> {
                        panelVisible.value = true
                        controlsVisible.value = true
                        true
                    }

                    Key.DirectionUp -> {
                        syncProgress()
                        controlsVisible.value = true
                        true
                    }

                    Key.MediaNext -> {
                        switchTo(episodeIndex.intValue + 1)
                        true
                    }

                    Key.MediaPrevious -> {
                        if (exoPlayer.currentPosition > 5_000L) {
                            exoPlayer.seekTo(0)
                        } else {
                            switchTo(episodeIndex.intValue - 1)
                        }
                        true
                    }

                    else -> false
                }
            }
    ) {
        VideoSurface(exoPlayer, Modifier.fillMaxSize())

        if (buffering.value && !ended.value && errorMessage.value == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Accent
            )
        }

        if (errorMessage.value != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xE60E1116), RoundedCornerShape(14.dp))
                    .padding(horizontal = 36.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("播放失败", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text(
                    text = errorMessage.value ?: "",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (errorMessage.value?.startsWith("读取文件失败") == true) {
                        "U 盘读取异常或文件正被其他程序占用，可直接重试"
                    } else {
                        "rmvb / rm 等格式内置播放器无法解码，可交给系统里已安装的播放器"
                    },
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TvActionButton(
                        text = "重试",
                        onClick = { retryPlayback(auto = false) },
                        primary = true,
                        modifier = Modifier.focusRequester(errorFocus)
                    )
                    Spacer(Modifier.width(12.dp))
                    TvActionButton(
                        text = "用系统播放器打开",
                        onClick = {
                            val opened = openWithExternalPlayer(
                                context,
                                MediaPaths.resolve(
                                    context.applicationContext,
                                    group.episodes[episodeIndex.intValue].path
                                )
                            )
                            externalHint.value =
                                if (opened) null else "没有找到可用的外部播放器"
                        }
                    )
                    Spacer(Modifier.width(12.dp))
                    TvActionButton(
                        text = "移出影库",
                        onClick = { confirmDelete.value = true }
                    )
                    Spacer(Modifier.width(12.dp))
                    TvActionButton(
                        text = "彻底删除",
                        onClick = { confirmPurge.value = true }
                    )
                    Spacer(Modifier.width(12.dp))
                    TvActionButton(
                        text = "返回列表",
                        onClick = { onExit(episodeIndex.intValue) }
                    )
                }
                val hint = externalHint.value
                if (hint != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(hint, color = Accent, fontSize = 12.sp)
                }
            }
        } else if (ended.value) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xE60E1116), RoundedCornerShape(14.dp))
                    .padding(horizontal = 34.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("播放结束", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = group.title + "  ·  " + currentTitle.value,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TvActionButton(
                        text = endedPrimaryText,
                        onClick = ::endedPrimaryAction,
                        modifier = Modifier.focusRequester(endedFocuses[0])
                    )
                    if (endedHasNextEpisode || endedHasNextGroup) {
                        Spacer(Modifier.width(12.dp))
                        TvActionButton(
                            text = "重播",
                            onClick = ::restart,
                            modifier = Modifier.focusRequester(endedFocuses[1])
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    TvActionButton(
                        text = "返回列表",
                        onClick = { onExit(episodeIndex.intValue) },
                        modifier = Modifier.focusRequester(endedFocuses[endedButtonCount - 1])
                    )
                }
            }
        }

        if (controlsVisible.value) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to Color(0xE6000000),
                            1f to Color.Transparent
                        )
                    )
                    .padding(horizontal = 40.dp, vertical = 22.dp)
            ) {
                Text(
                    text = group.title,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append(currentTitle.value)
                        append("  ·  第 ${episodeIndex.intValue + 1} / ${group.episodes.size} 集")
                    },
                    color = Color(0xCCFFFFFF),
                    fontSize = 13.sp
                )
            }

            if (!ended.value && errorMessage.value == null && !panelVisible.value) Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color(0xF2000000)
                        )
                    )
                    .padding(vertical = 20.dp)
            ) {
                val preview = seekPreview.value
                val fraction = if (durationMs.longValue > 0) {
                    (positionMs.longValue.toFloat() / durationMs.longValue.toFloat())
                        .coerceIn(0f, 1f)
                } else {
                    0f
                }
                if (preview != null) {
                    BoxWithConstraints(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 40.dp)
                    ) {
                        val thumbWidth = 160.dp
                        val travel = (maxWidth - thumbWidth).coerceAtLeast(0.dp)
                        Image(
                            bitmap = preview.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .offset(x = travel * fraction)
                                .width(thumbWidth)
                                .height(90.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 40.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatDuration(positionMs.longValue),
                        color = Color.White,
                        fontSize = 12.sp
                    )
                    Text(
                        text = if (durationMs.longValue > 0) {
                            formatDuration(durationMs.longValue)
                        } else {
                            "--:--"
                        },
                        color = Color(0xCCFFFFFF),
                        fontSize = 12.sp
                    )
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color(0x55FFFFFF))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .height(4.dp)
                            .background(Accent)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = if (group.episodes.size > 1) {
                        "OK 播放/暂停    ◀ ▶ 快退/快进    ▼/菜单键 选集    返回 退出"
                    } else {
                        "OK 播放/暂停    ◀ ▶ 快退/快进    ▼/菜单键 操作    返回 退出"
                    },
                    color = TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 40.dp)
                )
            }
        }

        if (panelVisible.value) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xF70E1116))
                    .padding(horizontal = 40.dp, vertical = 18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (group.episodes.size > 1) {
                            "选集  ·  第 ${episodeIndex.intValue + 1} / ${group.episodes.size} 集"
                        } else {
                            group.title
                        },
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(24.dp))
                    if (audioOptions.value.size > 1) {
                        TvActionButton(
                            text = AudioTracks.buttonLabel(
                                audioCurrent.value,
                                audioOptions.value
                            ),
                            onClick = ::cycleAudio
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    TvActionButton(
                        text = if (favorite) "★ 已收藏" else "★ 收藏",
                        onClick = onToggleFavorite,
                        modifier = if (group.episodes.size > 1) Modifier else Modifier.focusRequester(panelActionFocus)
                    )
                    Spacer(Modifier.width(12.dp))
                    TvActionButton(
                        text = if (encrypted) "已加密" else "加密",
                        onClick = onToggleEncrypt
                    )
                    Spacer(Modifier.width(12.dp))
                    TvActionButton(
                        text = "重命名",
                        onClick = { showRename.value = true }
                    )
                    Spacer(Modifier.width(12.dp))
                    TvActionButton(
                        text = "移出影库",
                        onClick = { confirmDelete.value = true }
                    )
                    Spacer(Modifier.width(12.dp))
                    TvActionButton(
                        text = "彻底删除",
                        onClick = { confirmPurge.value = true }
                    )
                }
                if (group.episodes.size > 1) {
                    Spacer(Modifier.height(10.dp))
                    if (panelPageCount > 1) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (p in 0 until panelPageCount) {
                                val from = p * PANEL_PAGE_SIZE + 1
                                val to = minOf((p + 1) * PANEL_PAGE_SIZE, group.episodes.size)
                                TabChip(
                                    label = if (from == to) "$from" else "$from-$to",
                                    selected = p == panelPage.intValue,
                                    onClick = {
                                        panelPage.intValue = p
                                        scope.launch { runCatching { panelListState.scrollToItem(0) } }
                                    }
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    } else {
                        Spacer(Modifier.height(2.dp))
                    }
                    val pageEpisodes = group.episodes
                        .drop(panelPage.intValue * PANEL_PAGE_SIZE)
                        .take(PANEL_PAGE_SIZE)
                    LazyRow(
                        state = panelListState,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(74.dp)
                    ) {
                        itemsIndexed(pageEpisodes, key = { _, episode -> episode.path }) { localIndex, episode ->
                            val index = panelPage.intValue * PANEL_PAGE_SIZE + localIndex
                            val current = index == episodeIndex.intValue
                            EpisodeChip(
                                label = episodeLabel(index, episode.season, episode.number),
                                current = current,
                                progress = prefs.progressOf(episode.path),
                                modifier = Modifier
                                    .size(62.dp)
                                    .then(
                                        if (current) Modifier.focusRequester(panelFocus) else Modifier
                                    ),
                                onClick = {
                                    switchTo(index)
                                    closePanel()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

