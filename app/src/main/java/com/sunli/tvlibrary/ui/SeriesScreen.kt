package com.sunli.tvlibrary.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import com.sunli.tvlibrary.data.MediaGroup
import com.sunli.tvlibrary.data.MediaPaths
import com.sunli.tvlibrary.data.PlaybackPrefs
import com.sunli.tvlibrary.player.AudioOnlyRenderersFactory
import com.sunli.tvlibrary.player.AudioTracks
import com.sunli.tvlibrary.player.VideoSurface
import kotlinx.coroutines.delay
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
    onToggleFavorite: () -> Unit,
    onToggleEncrypt: () -> Unit,
    onDelete: () -> Unit,
    onPurge: () -> Unit,
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
    var seekHintVisible by remember { mutableStateOf(false) }
    val seekHintEpoch = remember { mutableIntStateOf(0) }
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

            override fun onPlaybackStateChanged(playbackState: Int) {
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

    // 菜单露出后 3 秒没有操作就收起；有确认框时不收
    LaunchedEffect(menuVisible, menuEpoch, confirmDelete, confirmPurge) {
        if (menuVisible && !confirmDelete && !confirmPurge) {
            delay(MENU_HIDE_MS)
            menuVisible = false
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
                    }
                    showMenu()
                    return@onPreviewKeyEvent true
                }
                menuEpoch++
                false
            }
    ) {
        VideoSurface(exoPlayer, Modifier.fillMaxSize())
        AnimatedVisibility(
            visible = menuVisible,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(320))
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color(0xD90A0C10),
                                0.5f to Color(0x8C0A0C10),
                                1f to Color(0xF20A0C10)
                            )
                        )
                )
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(start = 56.dp, end = 56.dp, top = 28.dp)
                ) {
                    Text("${group.kind.label} · 按返回键退出", color = TextSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = group.title,
                        color = TextPrimary,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "共 ${group.episodes.size} 集 · ${group.folder}",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .width(if (coverPath != null) 168.dp else 340.dp)
                                .aspectRatio(if (coverPath != null) 2f / 3f else 16f / 9f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(SurfaceDark)
                        ) {
                            if (poster != null) {
                                AsyncImage(
                                    model = File(poster),
                                    contentDescription = group.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Text(
                                    "▶",
                                    color = Color(0x66FFFFFF),
                                    fontSize = 32.sp,
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            }
                        }
                        Spacer(Modifier.width(28.dp))
                        Column {
                            TvActionButton(
                                text = if (lastWatched != null) {
                                    "继续播放 第 ${lastWatched + 1} 集"
                                } else {
                                    "开始播放"
                                },
                                onClick = { openPlayer(startIndex) },
                                primary = true,
                                modifier = Modifier.focusRequester(playFocus)
                            )
                            Spacer(Modifier.height(12.dp))
                            Row {
                                TvActionButton(
                                    text = if (favorite) "★ 已收藏" else "★ 收藏",
                                    onClick = onToggleFavorite
                                )
                                Spacer(Modifier.width(12.dp))
                                TvActionButton(
                                    text = if (encrypted) "已加密" else "加密",
                                    onClick = onToggleEncrypt
                                )
                                Spacer(Modifier.width(12.dp))
                                TvActionButton(
                                    text = "移出影库",
                                    onClick = { confirmDelete = true }
                                )
                                Spacer(Modifier.width(12.dp))
                                TvActionButton(
                                    text = "彻底删除",
                                    onClick = { confirmPurge = true }
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = "提示：3 秒后自动收起；收起后左右键快进快退，其他键唤出菜单",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
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
                        }
                        Spacer(Modifier.height(12.dp))
                        val pageEpisodes = group.episodes.drop(page * PAGE_SIZE).take(PAGE_SIZE)
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(PAGE_SIZE),
                            state = gridState,
                            contentPadding = PaddingValues(bottom = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                        ) {
                            itemsIndexed(pageEpisodes, key = { _, episode -> episode.path }) { localIndex, episode ->
                                val index = page * PAGE_SIZE + localIndex
                                val progress = remember(episode.path) { prefs.progressOf(episode.path) }
                                EpisodeChip(
                                    label = episodeLabel(index, episode.season, episode.number),
                                    current = index == focusIndex,
                                    progress = progress,
                                    modifier = Modifier
                                        .height(46.dp)
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
}
