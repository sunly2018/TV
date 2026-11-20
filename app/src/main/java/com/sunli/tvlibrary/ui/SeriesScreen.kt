package com.sunli.tvlibrary.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import com.sunli.tvlibrary.data.CoverCandidate
import com.sunli.tvlibrary.data.MediaGroup
import com.sunli.tvlibrary.data.MediaPaths
import com.sunli.tvlibrary.data.PlaybackPrefs
import com.sunli.tvlibrary.player.AudioOnlyRenderersFactory
import com.sunli.tvlibrary.player.AudioTracks
import com.sunli.tvlibrary.player.VideoSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

private const val PAGE_SIZE = 10
private const val MENU_HIDE_MS = 3000L
private const val SEEK_STEP_MS = 10_000L

// 音量键交给系统处理，不唤出也不影响菜单倒计时
private val VOLUME_KEYS = setOf(Key.VolumeUp, Key.VolumeDown, Key.VolumeMute)

@OptIn(UnstableApi::class)
@Composable
fun SeriesScreen(
    group: MediaGroup,
    gridState: LazyGridState,
    favorite: Boolean,
    encrypted: Boolean,
    focusIndex: Int,
    coverPath: String? = null,
    // 从播放页返回时为 true：菜单直接盖在画面上，不再铺海报
    initialPlayed: Boolean = false,
    onToggleFavorite: () -> Unit,
    onToggleEncrypt: () -> Unit,
    onDelete: () -> Unit,
    onPurge: () -> Unit,
    onLoadCoverCandidates: ((List<CoverCandidate>) -> Unit) -> Unit,
    onChooseCover: (ByteArray) -> Unit,
    onRenameEpisode: (String, String) -> Unit = { _, _ -> },
    onPlay: (Int) -> Unit,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { PlaybackPrefs(context) }
    val view = LocalView.current
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmPurge by remember { mutableStateOf(false) }
    val lastWatched = remember(group.id) {
        group.episodes.indices
            .filter { prefs.positionOf(group.episodes[it].path) > 3000L }
            .maxByOrNull { prefs.positionOf(group.episodes[it].path) }
    }
    val startIndex = lastWatched ?: 0
    val startEpisode = group.episodes[startIndex]
    val playFocus = remember { FocusRequester() }
    val episodeFocus = remember { FocusRequester() }
    val rootFocus = remember { FocusRequester() }
    var menuVisible by remember(group.id) { mutableStateOf(true) }
    var menuEpoch by remember { mutableIntStateOf(0) }
    // 首屏菜单收起（开播）后不再铺海报，之后唤出的菜单直接盖在正在播放的画面上
    var playedOnce by remember(group.id) { mutableStateOf(initialPlayed) }
    var previewPlaying by remember { mutableStateOf(false) }
    var previewBuffering by remember { mutableStateOf(true) }
    // OK 暂停后正中显示 ▶，再按继续播放后短暂显示 ⏸
    var showPauseHint by remember { mutableStateOf(false) }
    var pauseHintEpoch by remember { mutableIntStateOf(0) }
    var seekHintVisible by remember { mutableStateOf(false) }
    val seekHintEpoch = remember { mutableIntStateOf(0) }
    // 改回目的目标集：跟随遥控器在选集格上移动，对话框打开时锁定
    var episodeCursor by remember(group.id) {
        mutableIntStateOf(if (focusIndex in group.episodes.indices) focusIndex else 0)
    }
    var renameEpisodeIndex by remember { mutableStateOf<Int?>(null) }
    val seekPosition = remember { mutableLongStateOf(0L) }
    val seekDuration = remember { mutableLongStateOf(0L) }
    val pageCount = ((group.episodes.size + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)
    var page by remember(group.id) {
        mutableIntStateOf(
            ((if (focusIndex >= 0) focusIndex else startIndex) / PAGE_SIZE).coerceIn(0, pageCount - 1)
        )
    }

    fun showMenu() {
        menuVisible = true
        menuEpoch++
    }

    var coverDialogOpen by remember { mutableStateOf(false) }
    var coverCandidates by remember { mutableStateOf<List<CoverCandidate>?>(null) }

    // 进入详情页/从播放页返回时直接在背景里续播，菜单收起后就是全屏视频
    val exoPlayer = remember(group.id) {
        val renderersFactory = AudioOnlyRenderersFactory(context.applicationContext)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .build().apply {
                setMediaItem(
                    MediaItem.fromUri(
                        Uri.fromFile(
                            File(MediaPaths.resolve(context.applicationContext, startEpisode.path))
                        )
                    )
                )
                val saved = prefs.positionOf(startEpisode.path)
                if (saved > 3000L) seekTo(saved)
                playWhenReady = true
                prepare()
            }
    }
    DisposableEffect(group.id) {
        onDispose {
            runCatching {
                // stop() 之后位置会归零，那说明进度已经存过了
                if (exoPlayer.playbackState != Player.STATE_IDLE) {
                    prefs.saveProgress(
                        startEpisode.path,
                        exoPlayer.currentPosition.coerceAtLeast(0L),
                        exoPlayer.duration
                    )
                }
            }
            exoPlayer.release()
        }
    }

    // 双音轨时背景播放也默认国语，和播放页保持一致
    DisposableEffect(exoPlayer) {
        var applied = false
        fun syncAudio() {
            if (applied) return
            val tracks = exoPlayer.currentTracks
            val options = AudioTracks.options(tracks)
            if (options.size < 2) return
            applied = true
            val target = AudioTracks.defaultMandarin(options) ?: return
            if (!AudioTracks.isCurrent(AudioTracks.current(tracks), target)) {
                AudioTracks.select(exoPlayer, target)
            }
        }

        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) = syncAudio()

            override fun onIsPlayingChanged(playing: Boolean) {
                previewPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                previewBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) syncAudio()
            }
        }
        exoPlayer.addListener(listener)
        syncAudio()
        onDispose { exoPlayer.removeListener(listener) }
    }
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // 菜单收起后 OK 直接暂停/继续，不再用 OK 唤起菜单
    fun togglePreview() {
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        } else {
            exoPlayer.play()
            pauseHintEpoch++
        }
    }

    LaunchedEffect(pauseHintEpoch) {
        if (pauseHintEpoch == 0) return@LaunchedEffect
        showPauseHint = true
        delay(1_200L)
        showPauseHint = false
    }

    // 选海报要从同一个文件抽帧，必须放掉背景播放器的硬件解码器，否则抽帧会卡死；
    // 关闭后从原位恢复播放
    var resumePosition by remember(group.id) { mutableLongStateOf(-1L) }
    LaunchedEffect(coverDialogOpen) {
        if (coverDialogOpen) {
            resumePosition = runCatching { exoPlayer.currentPosition.coerceAtLeast(0L) }
                .getOrDefault(-1L)
            runCatching {
                prefs.saveProgress(startEpisode.path, resumePosition, exoPlayer.duration)
                exoPlayer.stop()
            }
        } else if (resumePosition >= 0L) {
            val position = resumePosition
            resumePosition = -1L
            runCatching {
                exoPlayer.seekTo(position)
                exoPlayer.playWhenReady = true
                exoPlayer.prepare()
            }
        }
    }

    // 进播放页前先停掉背景播放，避免两个播放器同时占着同一个文件
    fun openPlayer(index: Int) {
        runCatching {
            prefs.saveProgress(
                startEpisode.path,
                exoPlayer.currentPosition.coerceAtLeast(0L),
                exoPlayer.duration
            )
        }
        runCatching { exoPlayer.pause() }
        runCatching { exoPlayer.stop() }
        onPlay(index)
    }

    // 菜单露出后 3 秒没有操作就收起，直接进入播放；选海报/确认框/改回目期间不收起
    LaunchedEffect(menuVisible, menuEpoch, confirmDelete, confirmPurge, coverDialogOpen, renameEpisodeIndex) {
        if (menuVisible && !confirmDelete && !confirmPurge && !coverDialogOpen &&
            renameEpisodeIndex == null
        ) {
            delay(MENU_HIDE_MS)
            menuVisible = false
            playedOnce = true
            runCatching { rootFocus.requestFocus() }
        }
    }

    // 快进/快退的进度条露一下就走
    LaunchedEffect(seekHintEpoch.intValue) {
        if (seekHintEpoch.intValue > 0) {
            delay(1_800L)
            seekHintVisible = false
        }
    }

    // 菜单重新露出时把焦点还给播放按钮或当前集；浮层刚插入时 FocusRequester 可能还没挂上，重试几次
    LaunchedEffect(menuVisible) {
        if (!menuVisible) return@LaunchedEffect
        repeat(10) {
            val ok = runCatching {
                if (group.episodes.size > 1 && focusIndex in group.episodes.indices) {
                    episodeFocus.requestFocus()
                } else {
                    playFocus.requestFocus()
                }
            }.isSuccess
            if (ok) return@LaunchedEffect
            delay(60L)
        }
    }

    LaunchedEffect(group.id, focusIndex) {
        if (group.episodes.size > 1 && focusIndex in group.episodes.indices) {
            page = focusIndex / PAGE_SIZE
            episodeCursor = focusIndex
            repeat(4) {
                if (runCatching { episodeFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                runCatching { gridState.scrollToItem(focusIndex % PAGE_SIZE) }
                delay(80)
            }
        } else {
            runCatching { playFocus.requestFocus() }
        }
    }

    // 菜单收起后，左右/快进快退键直接调整进度，不唤出菜单
    fun seekBy(deltaMs: Long) {
        val duration = exoPlayer.duration
        val target = (exoPlayer.currentPosition + deltaMs).coerceAtLeast(0L)
        val clamped = if (duration > 0L) target.coerceAtMost(duration) else target
        exoPlayer.seekTo(clamped)
        seekPosition.longValue = clamped
        seekDuration.longValue = duration.coerceAtLeast(0L)
        seekHintVisible = true
        seekHintEpoch.intValue++
    }

    // 返回键：菜单露出时退出项目；菜单收起时先唤回菜单，再按一次才退出
    BackHandler(enabled = true) {
        if (menuVisible) onExit() else showMenu()
    }

    val poster = coverPath ?: group.posterPath
    var posterAspect by remember(poster) { mutableStateOf<Float?>(null) }
    LaunchedEffect(poster) {
        posterAspect = null
        if (poster != null) {
            posterAspect = withContext(Dispatchers.IO) {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(poster, options)
                if (options.outWidth > 0 && options.outHeight > 0) {
                    options.outWidth.toFloat() / options.outHeight
                } else {
                    null
                }
            }
        }
    }
    val portraitPoster = posterAspect?.let { it < 1f } == true
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.key in VOLUME_KEYS) return@onPreviewKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (event.key == Key.Back || event.key == Key.Escape) {
                    if (menuVisible) onExit() else showMenu()
                    return@onPreviewKeyEvent true
                }
                if (!menuVisible) {
                    when (event.key) {
                        Key.DirectionRight, Key.MediaFastForward -> {
                            seekBy(SEEK_STEP_MS)
                            return@onPreviewKeyEvent true
                        }

                        Key.DirectionLeft, Key.MediaRewind -> {
                            seekBy(-SEEK_STEP_MS)
                            return@onPreviewKeyEvent true
                        }

                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause -> {
                            if (!previewBuffering) togglePreview()
                            return@onPreviewKeyEvent true
                        }
                    }
                    showMenu()
                    return@onPreviewKeyEvent true
                }
                menuEpoch++
                false
            }
    ) {
        VideoSurface(exoPlayer, Modifier.fillMaxSize())

        // OK 暂停时正中显示 ▶，再按继续播放后短暂显示 ⏸
        val pausedForHint = !previewPlaying && !previewBuffering
        if (!menuVisible && (pausedForHint || showPauseHint)) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(132.dp)
                    .background(Color(0x73000000), RoundedCornerShape(50)),
                contentAlignment = Alignment.Center
            ) {
                if (pausedForHint) {
                    Canvas(Modifier.size(56.dp)) {
                        val triangle = Path().apply {
                            moveTo(size.width * 0.16f, 0f)
                            lineTo(size.width, size.height / 2f)
                            lineTo(size.width * 0.16f, size.height)
                            close()
                        }
                        drawPath(triangle, Color.White)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(
                            Modifier
                                .width(16.dp)
                                .height(48.dp)
                                .background(Color.White, RoundedCornerShape(4.dp))
                        )
                        Box(
                            Modifier
                                .width(16.dp)
                                .height(48.dp)
                                .background(Color.White, RoundedCornerShape(4.dp))
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = menuVisible,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(320))
        ) {
            Box(Modifier.fillMaxSize()) {
                if (!playedOnce && poster != null) {
                    AsyncImage(
                        model = File(poster),
                        contentDescription = null,
                        contentScale = if (portraitPoster) ContentScale.Fit else ContentScale.Crop,
                        alignment = if (portraitPoster) Alignment.CenterStart else Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                0f to Color(if (portraitPoster) 0x8C0A0C10 else 0xE60A0C10),
                                0.55f to Color(0x990A0C10),
                                1f to Color(0x400A0C10)
                            )
                        )
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.55f to Color(0x400A0C10),
                                1f to Color(0xF20A0C10)
                            )
                        )
                )
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(start = 56.dp, end = 56.dp, top = 28.dp, bottom = 16.dp)
                ) {
                    Text("${group.kind.label} · 按返回键退出", color = Color(0xCCFFFFFF), fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = group.title,
                        color = Color.White,
                        fontSize = 38.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = buildString {
                            append(group.kind.label)
                            if (group.episodes.size > 1) append(" · 共 ${group.episodes.size} 集")
                            val size = formatSize(group.episodes.sumOf { it.sizeBytes })
                            if (size.isNotEmpty()) append(" · $size")
                        },
                        color = Color(0xFFE7EAEE),
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = group.folder,
                        color = Color(0x99FFFFFF),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TvActionButton(
                            text = when {
                                group.episodes.size <= 1 ->
                                    if (lastWatched != null) "继续播放" else "开始播放"
                                lastWatched != null -> "播放第 ${lastWatched + 1} 集"
                                else -> "开始播放"
                            },
                            onClick = { openPlayer(startIndex) },
                            primary = true,
                            large = true,
                            modifier = Modifier.focusRequester(playFocus)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TvActionButton(
                            text = if (favorite) "★ 已收藏" else "★ 收藏",
                            onClick = onToggleFavorite,
                            large = true
                        )
                        Spacer(Modifier.width(12.dp))
                        TvActionButton(
                            text = if (encrypted) "已加密" else "加密",
                            onClick = onToggleEncrypt,
                            large = true
                        )
                        Spacer(Modifier.width(12.dp))
                        TvActionButton(
                            text = if (poster != null) "更换海报" else "选择海报",
                            onClick = {
                                coverDialogOpen = true
                                coverCandidates = null
                                onLoadCoverCandidates { coverCandidates = it }
                            },
                            large = true
                        )
                        Spacer(Modifier.width(12.dp))
                        TvActionButton(
                            text = "移出影库",
                            onClick = { confirmDelete = true },
                            large = true
                        )
                        Spacer(Modifier.width(12.dp))
                        TvActionButton(
                            text = "彻底删除",
                            onClick = { confirmPurge = true },
                            large = true
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "3 秒后自动播放；收起后左右键快进快退，其他键唤出菜单",
                        color = Color(0x99FFFFFF),
                        fontSize = 11.sp
                    )
                    if (group.episodes.size > 1) {
                        Spacer(Modifier.height(22.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("选集", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            if (pageCount > 1) {
                                Spacer(Modifier.width(16.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    for (p in 0 until pageCount) {
                                        val from = p * PAGE_SIZE + 1
                                        val to = minOf((p + 1) * PAGE_SIZE, group.episodes.size)
                                        TabChip(
                                            label = if (from == to) "$from" else "$from-$to",
                                            selected = p == page,
                                            onClick = { page = p }
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            TvActionButton(
                                text = "改回目 · 第 ${episodeCursor + 1} 集",
                                onClick = { renameEpisodeIndex = episodeCursor }
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        val pageEpisodes = group.episodes.drop(page * PAGE_SIZE).take(PAGE_SIZE)
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(PAGE_SIZE),
                            state = gridState,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                        ) {
                            itemsIndexed(pageEpisodes, key = { _, episode -> episode.path }) { localIndex, episode ->
                                val index = page * PAGE_SIZE + localIndex
                                val progress = remember(episode.path) { prefs.progressOf(episode.path) }
                                EpisodeChip(
                                    label = episodeLabel(index, episode.season, episode.number),
                                    current = index == focusIndex,
                                    progress = progress,
                                    subtitle = episode.subtitle,
                                    modifier = Modifier
                                        .height(46.dp)
                                        .onFocusChanged {
                                            if (it.isFocused) episodeCursor = index
                                        }
                                        .then(
                                            if (index == focusIndex) {
                                                Modifier.focusRequester(episodeFocus)
                                            } else {
                                                Modifier
                                            }
                                        ),
                                    onClick = { openPlayer(index) }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (seekHintVisible && !menuVisible) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "${formatDuration(seekPosition.longValue)} / ${formatDuration(seekDuration.longValue)}",
                    color = Color.White,
                    fontSize = 16.sp
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0x59FFFFFF))
                ) {
                    val fraction = if (seekDuration.longValue > 0L) {
                        (seekPosition.longValue.toFloat() / seekDuration.longValue).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .background(Color.White)
                    )
                }
            }
        }
    }


    if (coverDialogOpen) {
        Dialog(
            onDismissRequest = { coverDialogOpen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            BackHandler(enabled = true) { coverDialogOpen = false }
            val candidates = coverCandidates
            Column(
                modifier = Modifier
                    .width(708.dp)
                    .background(SurfaceDark, RoundedCornerShape(16.dp))
                    .padding(horizontal = 24.dp, vertical = 20.dp)
            ) {
                Text("选择海报", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                when {
                    candidates == null -> Text(
                        "正在获取海报…",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 28.dp)
                    )

                    candidates.isEmpty() -> Text(
                        "没有找到可用海报，请检查网络后重试",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 28.dp)
                    )

                    else -> {
                        val firstFocus = remember { FocusRequester() }
                        LaunchedEffect(candidates) {
                            runCatching { firstFocus.requestFocus() }
                        }
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .width(660.dp)
                                .height(292.dp)
                        ) {
                            itemsIndexed(candidates) { index, candidate ->
                                val bitmap = remember(candidate) {
                                    BitmapFactory.decodeByteArray(
                                        candidate.preview,
                                        0,
                                        candidate.preview.size
                                    )?.asImageBitmap()
                                }
                                FocusableCard(
                                    onClick = {
                                        onChooseCover(candidate.full)
                                        coverDialogOpen = false
                                    },
                                    modifier = Modifier
                                        .width(156.dp)
                                        .aspectRatio(16f / 9f)
                                        .then(
                                            if (index == 0) Modifier.focusRequester(firstFocus)
                                            else Modifier
                                        )
                                ) { _ ->
                                    if (bitmap != null) {
                                        Image(
                                            bitmap = bitmap,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                TvActionButton(text = "取消", onClick = { coverDialogOpen = false })
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "移出影库《${group.title}》？",
            message = "只从影库移除，硬盘上的原文件保留；重新扫描也不会再出现。",
            confirmText = "移出",
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false }
        )
    }

    if (confirmPurge) {
        ConfirmDialog(
            title = "彻底删除《${group.title}》？",
            message = "将直接删除硬盘上的 ${group.episodes.size} 个源文件，删除后无法恢复。",
            confirmText = "彻底删除",
            onConfirm = {
                confirmPurge = false
                onPurge()
            },
            onDismiss = { confirmPurge = false }
        )
    }

    val renameTarget = renameEpisodeIndex
    if (renameTarget != null && renameTarget in group.episodes.indices) {
        val episode = group.episodes[renameTarget]
        RenameDialog(
            title = "修改回目",
            initial = episode.subtitle,
            hint = "只改影库里这一集的回目，硬盘上的文件不动",
            maxLength = 60,
            onConfirm = { text ->
                renameEpisodeIndex = null
                onRenameEpisode(episode.path, text)
                showMenu()
                runCatching { episodeFocus.requestFocus() }
            },
            onDismiss = {
                renameEpisodeIndex = null
                showMenu()
                runCatching { episodeFocus.requestFocus() }
            }
        )
    }
}
