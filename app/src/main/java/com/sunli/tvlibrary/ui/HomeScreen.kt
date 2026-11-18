package com.sunli.tvlibrary.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed as rowItemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.sunli.tvlibrary.data.Episode
import com.sunli.tvlibrary.data.Encrypted
import com.sunli.tvlibrary.data.Favorites
import com.sunli.tvlibrary.data.Library
import com.sunli.tvlibrary.data.LibraryUiState
import com.sunli.tvlibrary.data.MediaGroup
import com.sunli.tvlibrary.data.MediaKind
import com.sunli.tvlibrary.data.Photo
import com.sunli.tvlibrary.data.Pinned
import com.sunli.tvlibrary.data.PlaybackPrefs
import com.sunli.tvlibrary.data.UploadState
import com.sunli.tvlibrary.data.onlyEncrypted
import com.sunli.tvlibrary.data.pinnedFirst
import com.sunli.tvlibrary.data.withoutEncrypted
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

enum class HomeTab(val label: String, val kind: MediaKind?) {
    MOVIE("电影", MediaKind.MOVIE),
    SERIES("电视剧", MediaKind.SERIES),
    DOCUMENTARY("纪录片", MediaKind.DOCUMENTARY),
    PHOTO("照片", MediaKind.PHOTO),
    HISTORY("观看历史", null),
    FAVORITES("收藏", null),
    LOCKED("加密", null),
    SETTINGS("设置", null)
}

private const val RAIL_KEY_NAV_MS = 400L

private val confirmKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

private val BULK_TABS = setOf(
    HomeTab.MOVIE,
    HomeTab.SERIES,
    HomeTab.DOCUMENTARY,
    HomeTab.PHOTO,
    HomeTab.FAVORITES
)

@Composable
fun HomeScreen(
    state: LibraryUiState,
    selectedTab: HomeTab,
    onTabChange: (HomeTab) -> Unit,
    unlocked: Boolean,
    onRequestUnlock: () -> Unit,
    groupGridState: LazyGridState,
    photoGridState: LazyGridState,
    historyGridState: LazyGridState,
    favoritesListState: LazyListState,
    encryptedGridStates: MutableMap<String, LazyGridState>,
    encryptedCategoryKey: String?,
    onEncryptedCategoryChange: (String) -> Unit,
    favorites: Favorites,
    pinned: Pinned,
    encrypted: Encrypted,
    focusGroupId: String?,
    focusPhotoPath: String?,
    darkTheme: Boolean,
    photosEnabled: Boolean,
    skipIntro: Boolean,
    onlineCovers: Boolean,
    covers: Map<String, String>,
    upload: UploadState,
    onToggleTheme: () -> Unit,
    onTogglePhotos: () -> Unit,
    onToggleSkipIntro: () -> Unit,
    onToggleOnlineCovers: () -> Unit,
    onMatchCovers: () -> Unit,
    onToggleUpload: () -> Unit,
    onRefresh: () -> Unit,
    onOpenGroup: (MediaGroup) -> Unit,
    onPlayHistory: (MediaGroup, Int) -> Unit,
    onOpenPhotos: (List<Photo>, Int) -> Unit,
    onToggleGroupFavorite: (String) -> Unit,
    onTogglePhotoFavorite: (String) -> Unit,
    onRenameGroup: (String, String) -> Unit,
    onRenamePhoto: (String, String) -> Unit,
    onToggleGroupPinned: (String) -> Unit,
    onTogglePhotoPinned: (String) -> Unit,
    onToggleGroupEncrypt: (String) -> Unit,
    onTogglePhotoEncrypt: (String) -> Unit,
    onEncryptItems: (List<String>, List<String>) -> Unit,
    onDeleteGroup: (String) -> Unit,
    onDeletePhoto: (String) -> Unit,
    onPurgeGroup: (MediaGroup) -> Unit,
    onPurgePhoto: (Photo) -> Unit,
    onPurgeItems: (List<String>, List<String>) -> Unit,
    onLock: () -> Unit,
    onChangePin: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { PlaybackPrefs(context) }
    val library = state.library
    val visible = remember(library, encrypted) { library?.withoutEncrypted(encrypted) }
    val locked = remember(library, encrypted) { library?.onlyEncrypted(encrypted) }
    var menuTarget by remember { mutableStateOf<CardTarget?>(null) }
    var renameTarget by remember { mutableStateOf<CardTarget?>(null) }
    var deleteTarget by remember { mutableStateOf<CardTarget?>(null) }
    var purgeTarget by remember { mutableStateOf<CardTarget?>(null) }
    var bulkMode by remember { mutableStateOf(false) }
    var chosenGroups by remember { mutableStateOf<Set<String>>(emptySet()) }
    var chosenPhotos by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmBulk by remember { mutableStateOf(false) }
    var confirmBulkPurge by remember { mutableStateOf(false) }
    // 左栏切换时不要抢走右栏焦点，否则方向键会被内容页吃掉
    var contentAutoFocus by remember { mutableStateOf(true) }

    val bulkGroups = remember(visible, selectedTab, favorites) {
        val source = visible ?: Library()
        when (selectedTab) {
            HomeTab.MOVIE, HomeTab.SERIES, HomeTab.DOCUMENTARY ->
                source.groupsOf(selectedTab.kind ?: MediaKind.MOVIE)

            HomeTab.FAVORITES -> source.groups.filter { it.id in favorites.groupIds }
            else -> emptyList()
        }
    }
    val bulkPhotos = remember(visible, selectedTab, favorites) {
        val source = visible ?: Library()
        when (selectedTab) {
            HomeTab.PHOTO -> source.photos
            HomeTab.FAVORITES -> source.photos.filter { it.path in favorites.photoPaths }
            else -> emptyList()
        }
    }
    val bulkAvailable = selectedTab in BULK_TABS && (bulkGroups.size + bulkPhotos.size) > 0
    val tabs = remember(photosEnabled) {
        HomeTab.entries.filter { it != HomeTab.PHOTO || photosEnabled }
    }

    fun exitBulk() {
        bulkMode = false
        chosenGroups = emptySet()
        chosenPhotos = emptySet()
    }

    fun enterBulk() {
        chosenGroups = bulkGroups.map { it.id }.toSet()
        chosenPhotos = bulkPhotos.map { it.path }.toSet()
        bulkMode = true
    }

    fun toggleChosenGroup(id: String) {
        chosenGroups = if (id in chosenGroups) chosenGroups - id else chosenGroups + id
    }

    fun toggleChosenPhoto(path: String) {
        chosenPhotos = if (path in chosenPhotos) chosenPhotos - path else chosenPhotos + path
    }

    LaunchedEffect(selectedTab) {
        exitBulk()
        confirmBulk = false
        confirmBulkPurge = false
    }

    // 解锁成功后直接把焦点交给内容区，不用再按一次右键
    LaunchedEffect(unlocked) {
        if (unlocked && selectedTab == HomeTab.LOCKED) contentAutoFocus = true
    }

    BackHandler(enabled = bulkMode) { exitBulk() }

    // 左栏只在方向键导航时切栏目；记录最近一次方向键时间，供左栏判断焦点来源
    // 用 AtomicLong 而不是 Compose 状态：焦点变化与按键在同一帧内发生，读状态会拿到旧值
    val lastNavAt = remember { AtomicLong(0L) }
    // 内容区第一行按「上」时优先落到顶部菜单；没有菜单则请求失败、焦点留在原处
    val headerFocus = remember { FocusRequester() }
    val railFocus = remember { FocusRequester() }
    val onUpFromTop: () -> Unit = {
        runCatching { headerFocus.requestFocus() }
        Unit
    }
    // 内容区最左列按「左」回到当前选中的栏目，别让默认焦点搜索跳到别的分类
    val onLeftFromContent: () -> Unit = {
        runCatching { railFocus.requestFocus() }
        Unit
    }
    // 解锁瞬间输密码那一下按键可能继续派发到内容区，短暂吞掉确认键，避免误点/误长按卡片
    val unlockKeyGuardUntil = remember { AtomicLong(0L) }
    LaunchedEffect(unlocked) {
        if (unlocked) unlockKeyGuardUntil.set(SystemClock.uptimeMillis() + 600L)
    }
    Row(
        Modifier
            .fillMaxSize()
            .background(Background)
            .onPreviewKeyEvent { event ->
                if (unlocked && SystemClock.uptimeMillis() < unlockKeyGuardUntil.get() &&
                    event.key in confirmKeys
                ) {
                    return@onPreviewKeyEvent true
                }
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.DirectionUp || event.key == Key.DirectionDown ||
                        event.key == Key.DirectionLeft || event.key == Key.DirectionRight)
                ) {
                    lastNavAt.set(SystemClock.uptimeMillis())
                }
                if (selectedTab == HomeTab.LOCKED && !unlocked &&
                    event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight
                ) {
                    onRequestUnlock()
                    true
                } else {
                    false
                }
            }
    ) {
        HomeRail(
            tabs = tabs,
            selectedTab = selectedTab,
            scanning = state.scanning,
            progress = state.progress,
            scannedAt = library?.scannedAt,
            navAt = lastNavAt,
            selectedFocus = railFocus,
            onTabChange = { tab ->
                contentAutoFocus = false
                onTabChange(tab)
            },
            onFocusRestored = { contentAutoFocus = true },
            onRefresh = onRefresh
        )
        Column(Modifier.weight(1f).fillMaxHeight()) {
            HomeContentHeader(
                selectedTab = selectedTab,
                library = visible,
                bulkMode = bulkMode,
                chosenCount = chosenGroups.size + chosenPhotos.size,
                bulkAvailable = bulkAvailable,
                onEnterBulk = ::enterBulk,
                onConfirmBulk = {
                    if (chosenGroups.isNotEmpty() || chosenPhotos.isNotEmpty()) confirmBulk = true
                },
                onPurgeBulk = {
                    if (chosenGroups.isNotEmpty() || chosenPhotos.isNotEmpty()) {
                        confirmBulkPurge = true
                    }
                },
                onClearBulk = {
                    chosenGroups = emptySet()
                    chosenPhotos = emptySet()
                },
                onExitBulk = ::exitBulk,
                firstActionFocus = headerFocus,
                onLock = onLock,
                onChangePin = onChangePin,
                unlocked = unlocked
            )
            if (state.scanning) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = TextPrimary,
                    trackColor = SurfaceDark
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.loadingCache -> EmptyHint(
                    title = "正在读取资源库…",
                    subtitle = "资源库只需扫描一次，之后打开即可直接浏览",
                    modifier = Modifier.fillMaxSize()
                )

                state.scanning && library == null -> EmptyHint(
                    title = "正在扫描移动硬盘…",
                    subtitle = state.progress.ifBlank { "请稍候" },
                    modifier = Modifier.fillMaxSize()
                )

                selectedTab == HomeTab.SETTINGS -> SettingsScreen(
                    photosEnabled = photosEnabled,
                    skipIntro = skipIntro,
                    darkTheme = darkTheme,
                    onlineCovers = onlineCovers,
                    upload = upload,
                    onTogglePhotos = onTogglePhotos,
                    onToggleSkipIntro = onToggleSkipIntro,
                    onToggleTheme = onToggleTheme,
                    onToggleOnlineCovers = onToggleOnlineCovers,
                    onMatchCovers = onMatchCovers,
                    onToggleUpload = onToggleUpload,
                    onChangePin = onChangePin,
                    onRefresh = onRefresh,
                    onUpFromTop = onUpFromTop
                )

                library == null || library.isEmpty -> EmptyLibrary(
                    roots = state.roots,
                    error = state.error,
                    onRefresh = onRefresh,
                    autoFocus = contentAutoFocus
                )

                selectedTab == HomeTab.HISTORY -> HistoryGrid(
                    entries = remember(visible) {
                        buildHistory(visible ?: Library(), prefs)
                    },
                    gridState = historyGridState,
                    focusGroupId = focusGroupId,
                    covers = covers,
                    onPlay = onPlayHistory,
                    onLongPress = { menuTarget = CardTarget.Group(it) },
                    autoFocus = contentAutoFocus,
                    onUpFromTop = onUpFromTop,
                    onLeftFromContent = onLeftFromContent
                )

                selectedTab == HomeTab.FAVORITES -> ItemSectionsView(
                    sections = remember(visible, favorites) {
                        val source = visible ?: Library()
                        buildSections(
                            source.groups.filter { it.id in favorites.groupIds },
                            source.photos.filter { it.path in favorites.photoPaths }
                        )
                    },
                    listState = favoritesListState,
                    prefs = prefs,
                    covers = covers,
                    favoriteIds = favorites.groupIds,
                    favoritePaths = favorites.photoPaths,
                    focusGroupId = focusGroupId,
                    focusPhotoPath = focusPhotoPath,
                    selectedGroupIds = if (bulkMode) chosenGroups else null,
                    selectedPhotoPaths = if (bulkMode) chosenPhotos else null,
                    emptyTitle = "暂无收藏",
                    emptySubtitle = "在影片、剧集或照片上长按遥控器 OK 键即可收藏或删除",
                    onOpenGroup = onOpenGroup,
                    onOpenPhotos = onOpenPhotos,
                    onLongPressGroup = { menuTarget = CardTarget.Group(it) },
                    onLongPressPhoto = { menuTarget = CardTarget.Image(it) },
                    onToggleGroupSelect = ::toggleChosenGroup,
                    onTogglePhotoSelect = ::toggleChosenPhoto,
                    autoFocus = contentAutoFocus,
                    onUpFromTop = onUpFromTop,
                    onLeftFromContent = onLeftFromContent
                )

                selectedTab == HomeTab.LOCKED && !unlocked -> LockedHint()

                selectedTab == HomeTab.LOCKED -> EncryptedSectionsView(
                    sections = remember(locked) {
                        val lockedLibrary = locked ?: Library()
                        buildSections(lockedLibrary.groups, lockedLibrary.photos)
                    },
                    gridStates = encryptedGridStates,
                    categoryKey = encryptedCategoryKey,
                    onCategoryChange = onEncryptedCategoryChange,
                    prefs = prefs,
                    covers = covers,
                    favoriteIds = favorites.groupIds,
                    favoritePaths = favorites.photoPaths,
                    focusGroupId = focusGroupId,
                    focusPhotoPath = focusPhotoPath,
                    emptyTitle = "暂无加密内容",
                    emptySubtitle = "在任意分类里长按 OK，选择“加密”即可隐藏到这里",
                    onOpenGroup = onOpenGroup,
                    onOpenPhotos = onOpenPhotos,
                    onLongPressGroup = { menuTarget = CardTarget.Group(it) },
                    onLongPressPhoto = { menuTarget = CardTarget.Image(it) },
                    autoFocus = contentAutoFocus,
                    onUpFromTop = onUpFromTop,
                    onLeftFromContent = onLeftFromContent
                )

                selectedTab == HomeTab.PHOTO -> PhotoGrid(
                    photos = remember(visible, pinned) {
                        (visible ?: Library()).photos.pinnedFirst(pinned.photoPaths) { it.path }
                    },
                    gridState = photoGridState,
                    favoritePaths = favorites.photoPaths,
                    focusPhotoPath = focusPhotoPath,
                    selectedPaths = if (bulkMode) chosenPhotos else null,
                    onToggleSelect = ::toggleChosenPhoto,
                    onOpenPhotos = onOpenPhotos,
                    onLongPress = { menuTarget = CardTarget.Image(it) },
                    autoFocus = contentAutoFocus,
                    onUpFromTop = onUpFromTop,
                    onLeftFromContent = onLeftFromContent
                )

                else -> GroupGrid(
                    groups = remember(visible, selectedTab, pinned) {
                        (visible ?: Library())
                            .groupsOf(selectedTab.kind ?: MediaKind.MOVIE)
                            .pinnedFirst(pinned.groupIds) { it.id }
                    },
                    tab = selectedTab,
                    gridState = groupGridState,
                    favoriteIds = favorites.groupIds,
                    prefs = prefs,
                    covers = covers,
                    focusGroupId = focusGroupId,
                    selectedIds = if (bulkMode) chosenGroups else null,
                    onToggleSelect = ::toggleChosenGroup,
                    onRefresh = onRefresh,
                    onOpenGroup = onOpenGroup,
                    onLongPress = { menuTarget = CardTarget.Group(it) },
                    autoFocus = contentAutoFocus,
                    onUpFromTop = onUpFromTop,
                    onLeftFromContent = onLeftFromContent
                )
            }
        }
        }
    }

    val target = menuTarget
    if (target != null) {
        val isFavorite = when (target) {
            is CardTarget.Group -> target.group.id in favorites.groupIds
            is CardTarget.Image -> target.photo.path in favorites.photoPaths
        }
        val isEncrypted = when (target) {
            is CardTarget.Group -> target.group.id in encrypted.groupIds
            is CardTarget.Image -> target.photo.path in encrypted.photoPaths
        }
        val isPinned = when (target) {
            is CardTarget.Group -> target.group.id in pinned.groupIds
            is CardTarget.Image -> target.photo.path in pinned.photoPaths
        }
        ItemActionsDialog(
            title = when (target) {
                is CardTarget.Group -> target.group.title
                is CardTarget.Image -> target.photo.name
            },
            favorite = isFavorite,
            pinned = isPinned,
            encrypted = isEncrypted,
            onToggleFavorite = {
                when (target) {
                    is CardTarget.Group -> onToggleGroupFavorite(target.group.id)
                    is CardTarget.Image -> onTogglePhotoFavorite(target.photo.path)
                }
                menuTarget = null
            },
            onTogglePin = {
                when (target) {
                    is CardTarget.Group -> onToggleGroupPinned(target.group.id)
                    is CardTarget.Image -> onTogglePhotoPinned(target.photo.path)
                }
                menuTarget = null
            },
            onToggleEncrypt = {
                when (target) {
                    is CardTarget.Group -> onToggleGroupEncrypt(target.group.id)
                    is CardTarget.Image -> onTogglePhotoEncrypt(target.photo.path)
                }
                menuTarget = null
            },
            onRename = {
                renameTarget = target
                menuTarget = null
            },
            onDelete = {
                deleteTarget = target
                menuTarget = null
            },
            onPurge = {
                purgeTarget = target
                menuTarget = null
            },
            onDismiss = { menuTarget = null }
        )
    }
    val renaming = renameTarget
    if (renaming != null) {
        RenameDialog(
            title = when (renaming) {
                is CardTarget.Group -> "重命名影视"
                is CardTarget.Image -> "重命名照片"
            },
            initial = when (renaming) {
                is CardTarget.Group -> renaming.group.title
                is CardTarget.Image -> renaming.photo.name
            },
            onConfirm = { name ->
                when (renaming) {
                    is CardTarget.Group -> onRenameGroup(renaming.group.id, name)
                    is CardTarget.Image -> onRenamePhoto(renaming.photo.path, name)
                }
                renameTarget = null
            },
            onDismiss = { renameTarget = null }
        )
    }
    val deleting = deleteTarget
    if (deleting != null) {
        ConfirmDialog(
            title = "移出影库？",
            message = "移出后不再显示，重新扫描也不会加载；硬盘上的原文件不会被删除。",
            confirmText = "移出",
            onConfirm = {
                when (deleting) {
                    is CardTarget.Group -> onDeleteGroup(deleting.group.id)
                    is CardTarget.Image -> onDeletePhoto(deleting.photo.path)
                }
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null }
        )
    }
    val purging = purgeTarget
    if (purging != null) {
        ConfirmDialog(
            title = "彻底删除源文件？",
            message = when (purging) {
                is CardTarget.Group -> "将直接删除硬盘上《${purging.group.title}》的 " +
                    "${purging.group.episodes.size} 个文件，删除后无法恢复。"

                is CardTarget.Image -> "将直接删除硬盘上的照片「${purging.photo.name}」，删除后无法恢复。"
            },
            confirmText = "彻底删除",
            onConfirm = {
                when (purging) {
                    is CardTarget.Group -> onPurgeGroup(purging.group)
                    is CardTarget.Image -> onPurgePhoto(purging.photo)
                }
                purgeTarget = null
            },
            onDismiss = { purgeTarget = null }
        )
    }
    if (confirmBulk) {
        val count = chosenGroups.size + chosenPhotos.size
        ConfirmDialog(
            title = "加密选中的 $count 项？",
            message = "加密后需要密码才能查看，可在「加密」栏里解除。",
            confirmText = "加密",
            onConfirm = {
                onEncryptItems(chosenGroups.toList(), chosenPhotos.toList())
                confirmBulk = false
                exitBulk()
            },
            onDismiss = { confirmBulk = false }
        )
    }
    if (confirmBulkPurge) {
        val count = chosenGroups.size + chosenPhotos.size
        ConfirmDialog(
            title = "彻底删除选中的 $count 项？",
            message = "将直接删除硬盘上对应的源文件，删除后无法恢复。",
            confirmText = "彻底删除",
            onConfirm = {
                onPurgeItems(chosenGroups.toList(), chosenPhotos.toList())
                confirmBulkPurge = false
                exitBulk()
            },
            onDismiss = { confirmBulkPurge = false }
        )
    }
}

private sealed interface CardTarget {
    data class Group(val group: MediaGroup) : CardTarget
    data class Image(val photo: Photo) : CardTarget
}

@Composable
private fun ItemActionsDialog(
    title: String,
    favorite: Boolean,
    pinned: Boolean,
    encrypted: Boolean,
    onToggleFavorite: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleEncrypt: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onPurge: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        BackHandler(enabled = true) { onDismiss() }
        val cancelFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            runCatching { cancelFocus.requestFocus() }
        }
        // 菜单由长按触发，松手那一下的抬起会落到本窗口；用 downTime 区分新旧按键，吞掉长按的抬起
        var seenDownTime by remember { mutableStateOf(0L) }
        Column(
            modifier = Modifier
                .background(SurfaceDark, RoundedCornerShape(16.dp))
                .padding(horizontal = 30.dp, vertical = 24.dp)
                .onPreviewKeyEvent { event ->
                    if (event.key !in confirmKeys) return@onPreviewKeyEvent false
                    when (event.type) {
                        KeyEventType.KeyDown -> {
                            if (event.nativeKeyEvent.repeatCount == 0) {
                                seenDownTime = event.nativeKeyEvent.downTime
                            }
                            false
                        }

                        KeyEventType.KeyUp -> {
                            if (event.nativeKeyEvent.downTime != seenDownTime) {
                                true
                            } else {
                                seenDownTime = 0L
                                false
                            }
                        }

                        else -> false
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Column(
                modifier = Modifier.padding(top = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TvActionButton(
                    text = if (favorite) "★ 取消收藏" else "★ 收藏",
                    onClick = onToggleFavorite,
                    modifier = Modifier.fillMaxWidth()
                )
                TvActionButton(
                    text = if (pinned) "取消置顶" else "置顶",
                    onClick = onTogglePin,
                    modifier = Modifier.fillMaxWidth()
                )
                TvActionButton(
                    text = if (encrypted) "解除加密" else "加密",
                    onClick = onToggleEncrypt,
                    modifier = Modifier.fillMaxWidth()
                )
                TvActionButton(
                    text = "重命名",
                    onClick = onRename,
                    modifier = Modifier.fillMaxWidth()
                )
                TvActionButton(
                    text = "移出影库",
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth()
                )
                TvActionButton(
                    text = "彻底删除源文件",
                    onClick = onPurge,
                    modifier = Modifier.fillMaxWidth()
                )
                TvActionButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(cancelFocus)
                )
            }
        }
    }
}

@Composable
private fun HomeRail(
    tabs: List<HomeTab>,
    selectedTab: HomeTab,
    scanning: Boolean,
    progress: String,
    scannedAt: Long?,
    navAt: AtomicLong,
    selectedFocus: FocusRequester,
    onTabChange: (HomeTab) -> Unit,
    onFocusRestored: () -> Unit,
    onRefresh: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(132.dp)
            .fillMaxHeight()
            .background(SurfaceDark)
            .padding(horizontal = 8.dp, vertical = 12.dp)
    ) {
        Text(
            text = "私影",
            color = Accent,
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)
        )
        tabs.forEachIndexed { index, tab ->
            RailItem(
                label = tab.label,
                selected = tab == selectedTab,
                navAt = navAt,
                onClick = { onTabChange(tab) },
                onFocused = { if (tab != selectedTab) onTabChange(tab) },
                onFocusRestored = onFocusRestored,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (tab == selectedTab) Modifier.focusRequester(selectedFocus) else Modifier
                    )
            )
            if (index != tabs.lastIndex) Spacer(Modifier.height(2.dp))
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = if (scanning) {
                progress.ifBlank { "正在扫描…" }
            } else {
                "上次更新 ${formatScanTime(scannedAt ?: 0L)}"
            },
            color = TextSecondary,
            fontSize = 9.sp,
            maxLines = if (scanning) 2 else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
        )
        TvActionButton(
            text = if (scanning) "扫描中…" else "刷新资源库",
            onClick = onRefresh,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun RailItem(
    label: String,
    selected: Boolean,
    navAt: AtomicLong,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    onFocusRestored: () -> Unit,
    modifier: Modifier = Modifier
) {
    FocusableCard(
        onClick = onClick,
        modifier = modifier.onFocusChanged {
            if (!it.isFocused) return@onFocusChanged
            // 只有方向键导航才切栏目；删除条目后系统重建焦点落到左栏时，把焦点交还内容区
            val byKey = SystemClock.uptimeMillis() - navAt.get() < RAIL_KEY_NAV_MS
            if (byKey) onFocused() else onFocusRestored()
        },
        shape = RoundedCornerShape(10.dp),
        background = if (selected) {
            if (IsDarkTheme) Color(0x22FFFFFF) else Color(0x1A000000)
        } else {
            Color.Transparent
        },
        focusedBackground = FocusFill
    ) { focused ->
        Text(
            text = label,
            color = when {
                focused -> FocusText
                selected -> TextPrimary
                else -> TextSecondary
            },
            fontSize = 14.sp,
            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun HomeContentHeader(
    selectedTab: HomeTab,
    library: Library?,
    bulkMode: Boolean,
    chosenCount: Int,
    bulkAvailable: Boolean,
    onEnterBulk: () -> Unit,
    onConfirmBulk: () -> Unit,
    onPurgeBulk: () -> Unit,
    onClearBulk: () -> Unit,
    onExitBulk: () -> Unit,
    firstActionFocus: FocusRequester,
    onLock: () -> Unit,
    onChangePin: () -> Unit,
    unlocked: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, end = 36.dp, top = 20.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = selectedTab.label,
            color = TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(14.dp))
        if (library != null && selectedTab != HomeTab.SETTINGS) {
            Text(
                text = "${library.groups.size} 部影片 · ${library.photos.size} 张照片 · " +
                    "上次更新 ${formatScanTime(library.scannedAt)}",
                color = TextSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.weight(1f))
        when {
            bulkMode -> {
                Text(
                    text = "已选 $chosenCount 项",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(12.dp))
                TvActionButton(
                    text = "加密选中",
                    onClick = onConfirmBulk,
                    primary = true,
                    modifier = Modifier.focusRequester(firstActionFocus)
                )
                Spacer(Modifier.width(10.dp))
                TvActionButton(text = "彻底删除选中", onClick = onPurgeBulk)
                Spacer(Modifier.width(10.dp))
                TvActionButton(text = "取消全选", onClick = onClearBulk)
                Spacer(Modifier.width(10.dp))
                TvActionButton(text = "取消", onClick = onExitBulk)
            }

            bulkAvailable -> TvActionButton(
                text = "全选",
                onClick = onEnterBulk,
                modifier = Modifier.focusRequester(firstActionFocus)
            )

            selectedTab == HomeTab.LOCKED && unlocked -> {
                TvActionButton(
                    text = "修改密码",
                    onClick = onChangePin,
                    modifier = Modifier.focusRequester(firstActionFocus)
                )
                Spacer(Modifier.width(12.dp))
                TvActionButton(text = "锁定", onClick = onLock)
            }
        }
    }
}

private fun coverModel(group: MediaGroup, covers: Map<String, String>): Any? =
    covers[group.id]?.let { File(it) } ?: group.posterPath?.let { File(it) }

@Composable
private fun GroupGrid(
    groups: List<MediaGroup>,
    tab: HomeTab,
    gridState: LazyGridState,
    favoriteIds: Set<String>,
    prefs: PlaybackPrefs,
    covers: Map<String, String>,
    focusGroupId: String?,
    selectedIds: Set<String>?,
    onToggleSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenGroup: (MediaGroup) -> Unit,
    onLongPress: (MediaGroup) -> Unit,
    autoFocus: Boolean = true,
    onUpFromTop: () -> Unit = {},
    onLeftFromContent: () -> Unit = {}
) {
    if (groups.isEmpty()) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(autoFocus) {
            if (autoFocus) runCatching { focus.requestFocus() }
        }
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            EmptyHint(
                title = "暂无${tab.label}",
                subtitle = "把文件放进移动硬盘后，点击下方按钮或右上角“刷新”",
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(20.dp))
            TvActionButton(
                text = "刷新资源库",
                onClick = onRefresh,
                primary = true,
                modifier = Modifier.focusRequester(focus)
            )
        }
        return
    }
    // 返回时优先回到刚打开的那一项，其次才是离开前的首个可见项
    fun atTopRow(index: Int): Boolean {
        if (gridState.firstVisibleItemIndex != 0) return false
        val visible = gridState.layoutInfo.visibleItemsInfo
        val item = visible.firstOrNull { it.index == index } ?: return false
        val top = visible.minOfOrNull { it.offset.y } ?: return false
        return item.offset.y == top
    }
    fun atLeftColumn(index: Int): Boolean {
        val visible = gridState.layoutInfo.visibleItemsInfo
        val item = visible.firstOrNull { it.index == index } ?: return false
        val left = visible.minOfOrNull { it.offset.x } ?: return false
        return item.offset.x == left
    }
    val targetIndex = remember(groups, focusGroupId) {
        focusGroupId
            ?.let { id -> groups.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?: gridState.firstVisibleItemIndex.coerceIn(0, groups.size - 1)
    }
    val cardFocus = remember { FocusRequester() }
    LaunchedEffect(tab, groups, focusGroupId, autoFocus) {
        if (autoFocus) focusWithRetry(cardFocus) { gridState.scrollToItem(targetIndex) }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(200.dp),
        state = gridState,
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 10.dp, bottom = 44.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        gridItemsIndexed(groups, key = { _, group -> group.id }) { index, group ->
            val progress = remember(group.id) {
                group.episodes.maxOfOrNull { prefs.progressOf(it.path) } ?: 0f
            }
            MediaCard(
                title = group.title,
                imageModel = coverModel(group, covers),
                badge = if (group.episodes.size > 1) "共${group.episodes.size}集" else null,
                subtitle = group.episodes.firstOrNull()?.let { formatSize(it.sizeBytes) },
                progress = progress,
                favorite = group.id in favoriteIds,
                selectionMark = selectedIds?.contains(group.id),
                fitImage = covers[group.id] != null,
                onLongClick = { onLongPress(group) },
                modifier = Modifier
                    .aspectRatio(16f / 9f)
                    .then(if (index == targetIndex) Modifier.focusRequester(cardFocus) else Modifier)
                    .blockUpFromTop({ atTopRow(index) }, onUpFromTop)
                    .blockLeftFromEdge({ atLeftColumn(index) }, onLeftFromContent),
                onClick = {
                    if (selectedIds != null) onToggleSelect(group.id) else onOpenGroup(group)
                }
            )
        }
    }
}

private data class HistoryEntry(
    val group: MediaGroup,
    val episodeIndex: Int,
    val lastTime: Long,
    val progress: Float,
    val positionMs: Long
)

private fun buildHistory(library: Library, prefs: PlaybackPrefs): List<HistoryEntry> =
    library.groups.mapNotNull { group ->
        var best: Episode? = null
        var bestTime = 0L
        group.episodes.forEach { episode ->
            val time = prefs.lastPlayedOf(episode.path)
            if (time > bestTime) {
                bestTime = time
                best = episode
            }
        }
        val episode = best ?: return@mapNotNull null
        if (bestTime <= 0L) return@mapNotNull null
        HistoryEntry(
            group = group,
            episodeIndex = group.episodes.indexOf(episode),
            lastTime = bestTime,
            progress = prefs.progressOf(episode.path),
            positionMs = prefs.positionOf(episode.path)
        )
    }.sortedByDescending { it.lastTime }

@Composable
private fun HistoryGrid(
    entries: List<HistoryEntry>,
    gridState: LazyGridState,
    focusGroupId: String?,
    covers: Map<String, String>,
    onPlay: (MediaGroup, Int) -> Unit,
    onLongPress: (MediaGroup) -> Unit,
    autoFocus: Boolean = true,
    onUpFromTop: () -> Unit = {},
    onLeftFromContent: () -> Unit = {}
) {
    if (entries.isEmpty()) {
        EmptyHint(
            title = "暂无观看记录",
            subtitle = "播放过的影片会出现在这里，方便随时接着看",
            modifier = Modifier.fillMaxSize()
        )
        return
    }
    val targetIndex = remember(entries, focusGroupId) {
        focusGroupId
            ?.let { id -> entries.indexOfFirst { it.group.id == id } }
            ?.takeIf { it >= 0 }
            ?: gridState.firstVisibleItemIndex.coerceIn(0, entries.size - 1)
    }
    fun atTopRow(index: Int): Boolean {
        if (gridState.firstVisibleItemIndex != 0) return false
        val visible = gridState.layoutInfo.visibleItemsInfo
        val item = visible.firstOrNull { it.index == index } ?: return false
        val top = visible.minOfOrNull { it.offset.y } ?: return false
        return item.offset.y == top
    }
    fun atLeftColumn(index: Int): Boolean {
        val visible = gridState.layoutInfo.visibleItemsInfo
        val item = visible.firstOrNull { it.index == index } ?: return false
        val left = visible.minOfOrNull { it.offset.x } ?: return false
        return item.offset.x == left
    }
    val cardFocus = remember { FocusRequester() }
    LaunchedEffect(entries, focusGroupId, autoFocus) {
        if (autoFocus) focusWithRetry(cardFocus) { gridState.scrollToItem(targetIndex) }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(200.dp),
        state = gridState,
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 10.dp, bottom = 44.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        gridItemsIndexed(entries, key = { _, entry -> entry.group.id }) { index, entry ->
            MediaCard(
                title = entry.group.title,
                imageModel = coverModel(entry.group, covers),
                badge = if (entry.group.episodes.size > 1) {
                    "第 ${entry.episodeIndex + 1} 集"
                } else {
                    null
                },
                subtitle = "看到 ${formatDuration(entry.positionMs)}  ·  ${formatAgo(entry.lastTime)}",
                progress = entry.progress,
                fitImage = covers[entry.group.id] != null,
                onLongClick = { onLongPress(entry.group) },
                modifier = Modifier
                    .aspectRatio(16f / 9f)
                    .then(if (index == targetIndex) Modifier.focusRequester(cardFocus) else Modifier)
                    .blockUpFromTop({ atTopRow(index) }, onUpFromTop)
                    .blockLeftFromEdge({ atLeftColumn(index) }, onLeftFromContent),
                onClick = { onPlay(entry.group, entry.episodeIndex) }
            )
        }
    }
}

@Composable
private fun PhotoGrid(
    photos: List<Photo>,
    gridState: LazyGridState,
    favoritePaths: Set<String>,
    focusPhotoPath: String?,
    selectedPaths: Set<String>?,
    onToggleSelect: (String) -> Unit,
    onOpenPhotos: (List<Photo>, Int) -> Unit,
    onLongPress: (Photo) -> Unit,
    autoFocus: Boolean = true,
    onUpFromTop: () -> Unit = {},
    onLeftFromContent: () -> Unit = {}
) {
    if (photos.isEmpty()) {
        EmptyHint(
            title = "暂无照片",
            subtitle = "支持 jpg / png / heic 等常见格式",
            modifier = Modifier.fillMaxSize()
        )
        return
    }
    val targetIndex = remember(photos, focusPhotoPath) {
        focusPhotoPath
            ?.let { path -> photos.indexOfFirst { it.path == path } }
            ?.takeIf { it >= 0 }
            ?: gridState.firstVisibleItemIndex.coerceIn(0, photos.size - 1)
    }
    fun atTopRow(index: Int): Boolean {
        if (gridState.firstVisibleItemIndex != 0) return false
        val visible = gridState.layoutInfo.visibleItemsInfo
        val item = visible.firstOrNull { it.index == index } ?: return false
        val top = visible.minOfOrNull { it.offset.y } ?: return false
        return item.offset.y == top
    }
    fun atLeftColumn(index: Int): Boolean {
        val visible = gridState.layoutInfo.visibleItemsInfo
        val item = visible.firstOrNull { it.index == index } ?: return false
        val left = visible.minOfOrNull { it.offset.x } ?: return false
        return item.offset.x == left
    }
    val cardFocus = remember { FocusRequester() }
    LaunchedEffect(photos, focusPhotoPath, autoFocus) {
        if (autoFocus) focusWithRetry(cardFocus) { gridState.scrollToItem(targetIndex) }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(180.dp),
        state = gridState,
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 10.dp, bottom = 44.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        gridItemsIndexed(photos, key = { _, photo -> photo.path }) { index, photo ->
            PhotoTile(
                photo = photo,
                favorite = photo.path in favoritePaths,
                selectionMark = selectedPaths?.contains(photo.path),
                onLongClick = { onLongPress(photo) },
                modifier = Modifier
                    .aspectRatio(1f)
                    .then(if (index == targetIndex) Modifier.focusRequester(cardFocus) else Modifier)
                    .blockUpFromTop({ atTopRow(index) }, onUpFromTop)
                    .blockLeftFromEdge({ atLeftColumn(index) }, onLeftFromContent),
                onClick = {
                    if (selectedPaths != null) onToggleSelect(photo.path) else onOpenPhotos(photos, index)
                }
            )
        }
    }
}

private suspend fun focusWithRetry(
    requester: FocusRequester,
    scrollToTarget: suspend () -> Unit
) {
    repeat(4) {
        if (runCatching { requester.requestFocus() }.isSuccess) return
        runCatching { scrollToTarget() }
        delay(80)
    }
    runCatching { requester.requestFocus() }
}

@Composable
private fun PhotoTile(
    photo: Photo,
    favorite: Boolean,
    modifier: Modifier = Modifier,
    selectionMark: Boolean? = null,
    onLongClick: () -> Unit,
    onClick: () -> Unit
) {
    FocusableCard(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
    ) {
        Box(Modifier.fillMaxSize()) {
            AsyncImage(
                model = File(photo.path),
                contentDescription = photo.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (favorite && selectionMark == null) {
                Text(
                    text = "★",
                    color = Accent,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color(0xB3000000), RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 1.dp)
                )
            }
            if (selectionMark != null) {
                SelectionBadge(
                    selected = selectionMark,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                )
            }
        }
    }
}

private data class ItemSection(
    val key: String,
    val title: String,
    val groups: List<MediaGroup> = emptyList(),
    val photos: List<Photo> = emptyList()
)

private val SECTION_KINDS = listOf(MediaKind.MOVIE, MediaKind.SERIES, MediaKind.DOCUMENTARY)

private fun buildSections(groups: List<MediaGroup>, photos: List<Photo>): List<ItemSection> =
    buildList {
        SECTION_KINDS.forEach { kind ->
            val ofKind = groups.filter { it.kind == kind }
            if (ofKind.isNotEmpty()) add(ItemSection(kind.name, kind.label, groups = ofKind))
        }
        val others = groups.filter { it.kind !in SECTION_KINDS }
        if (others.isNotEmpty()) add(ItemSection("OTHER", "其他", groups = others))
        if (photos.isNotEmpty()) {
            add(ItemSection(MediaKind.PHOTO.name, MediaKind.PHOTO.label, photos = photos))
        }
    }

private data class FocusTarget(val section: Int, val row: Int)

@Composable
private fun ItemSectionsView(
    sections: List<ItemSection>,
    listState: LazyListState,
    prefs: PlaybackPrefs,
    covers: Map<String, String>,
    favoriteIds: Set<String>,
    favoritePaths: Set<String>,
    focusGroupId: String?,
    focusPhotoPath: String?,
    selectedGroupIds: Set<String>? = null,
    selectedPhotoPaths: Set<String>? = null,
    emptyTitle: String,
    emptySubtitle: String,
    onOpenGroup: (MediaGroup) -> Unit,
    onOpenPhotos: (List<Photo>, Int) -> Unit,
    onLongPressGroup: (MediaGroup) -> Unit,
    onLongPressPhoto: (Photo) -> Unit,
    onToggleGroupSelect: (String) -> Unit = {},
    onTogglePhotoSelect: (String) -> Unit = {},
    autoFocus: Boolean = true,
    onUpFromTop: () -> Unit = {},
    onLeftFromContent: () -> Unit = {}
) {
    if (sections.isEmpty()) {
        EmptyHint(
            title = emptyTitle,
            subtitle = emptySubtitle,
            modifier = Modifier.fillMaxSize()
        )
        return
    }
    val rowStates = remember(sections) {
        sections.associate { it.key to LazyListState() }
    }
    val target = remember(sections, focusGroupId, focusPhotoPath) {
        if (focusGroupId != null) {
            for ((sectionIndex, section) in sections.withIndex()) {
                val row = section.groups.indexOfFirst { it.id == focusGroupId }
                if (row >= 0) return@remember FocusTarget(sectionIndex, row)
            }
        }
        if (focusPhotoPath != null) {
            for ((sectionIndex, section) in sections.withIndex()) {
                val row = section.photos.indexOfFirst { it.path == focusPhotoPath }
                if (row >= 0) return@remember FocusTarget(sectionIndex, section.groups.size + row)
            }
        }
        null
    }
    val targetFocus = remember { FocusRequester() }
    LaunchedEffect(sections, target, autoFocus) {
        if (autoFocus) {
            val focusTarget = target
            focusWithRetry(targetFocus) {
                if (focusTarget != null) {
                    runCatching { listState.scrollToItem(focusTarget.section * 2 + 1) }
                    rowStates[sections[focusTarget.section].key]?.let { rowState ->
                        runCatching { rowState.scrollToItem(focusTarget.row) }
                    }
                }
            }
        }
    }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 6.dp, bottom = 44.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        sections.forEachIndexed { sectionIndex, section ->
            item(key = "title-${section.key}") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = section.title,
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "共 ${section.groups.size + section.photos.size} 项",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
            item(key = "row-${section.key}") {
                LazyRow(
                    state = rowStates[section.key] ?: LazyListState(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    rowItemsIndexed(
                        section.groups,
                        key = { _, group -> group.id }
                    ) { index, group ->
                        val progress = remember(group.id) {
                            group.episodes.maxOfOrNull { prefs.progressOf(it.path) } ?: 0f
                        }
                        val focused = target?.let { it.section == sectionIndex && it.row == index }
                            ?: (sectionIndex == 0 && index == 0)
                        MediaCard(
                            title = group.title,
                            imageModel = coverModel(group, covers),
                            badge = if (group.episodes.size > 1) {
                                "共${group.episodes.size}集"
                            } else {
                                null
                            },
                            subtitle = group.episodes.firstOrNull()?.let { formatSize(it.sizeBytes) },
                            progress = progress,
                            favorite = group.id in favoriteIds,
                            selectionMark = selectedGroupIds?.contains(group.id),
                            fitImage = covers[group.id] != null,
                            onLongClick = { onLongPressGroup(group) },
                            modifier = Modifier
                                .width(220.dp)
                                .aspectRatio(16f / 9f)
                                .then(if (focused) Modifier.focusRequester(targetFocus) else Modifier)
                                .blockUpFromTop({ sectionIndex == 0 }, onUpFromTop)
                                .blockLeftFromEdge({ index == 0 }, onLeftFromContent),
                            onClick = {
                                if (selectedGroupIds != null) {
                                    onToggleGroupSelect(group.id)
                                } else {
                                    onOpenGroup(group)
                                }
                            }
                        )
                    }
                    rowItemsIndexed(
                        section.photos,
                        key = { _, photo -> photo.path }
                    ) { index, photo ->
                        val row = section.groups.size + index
                        val focused = target?.let { it.section == sectionIndex && it.row == row }
                            ?: (sectionIndex == 0 && index == 0)
                        PhotoTile(
                            photo = photo,
                            favorite = photo.path in favoritePaths,
                            selectionMark = selectedPhotoPaths?.contains(photo.path),
                            onLongClick = { onLongPressPhoto(photo) },
                            modifier = Modifier
                                .width(170.dp)
                                .aspectRatio(1f)
                                .then(if (focused) Modifier.focusRequester(targetFocus) else Modifier)
                                .blockUpFromTop({ sectionIndex == 0 }, onUpFromTop)
                                .blockLeftFromEdge({ section.groups.isEmpty() && index == 0 }, onLeftFromContent),
                            onClick = {
                                if (selectedPhotoPaths != null) {
                                    onTogglePhotoSelect(photo.path)
                                } else {
                                    onOpenPhotos(section.photos, index)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EncryptedSectionsView(
    sections: List<ItemSection>,
    gridStates: MutableMap<String, LazyGridState>,
    categoryKey: String?,
    onCategoryChange: (String) -> Unit,
    prefs: PlaybackPrefs,
    covers: Map<String, String>,
    favoriteIds: Set<String>,
    favoritePaths: Set<String>,
    focusGroupId: String?,
    focusPhotoPath: String?,
    emptyTitle: String,
    emptySubtitle: String,
    onOpenGroup: (MediaGroup) -> Unit,
    onOpenPhotos: (List<Photo>, Int) -> Unit,
    onLongPressGroup: (MediaGroup) -> Unit,
    onLongPressPhoto: (Photo) -> Unit,
    autoFocus: Boolean = true,
    onUpFromTop: () -> Unit = {},
    onLeftFromContent: () -> Unit = {}
) {
    if (sections.isEmpty()) {
        EmptyHint(
            title = emptyTitle,
            subtitle = emptySubtitle,
            modifier = Modifier.fillMaxSize()
        )
        return
    }
    val selectedIndex = remember(sections, categoryKey) {
        sections.indexOfFirst { it.key == categoryKey }.takeIf { it >= 0 } ?: 0
    }
    val section = sections[selectedIndex]
    val gridState = remember(section.key) {
        gridStates.getOrPut(section.key) { LazyGridState() }
    }
    val tabsFocus = remember { FocusRequester() }
    val cardFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var tabsFocused by remember { mutableStateOf(false) }

    val itemCount = section.groups.size + section.photos.size
    val targetIndex = remember(section, focusGroupId, focusPhotoPath, gridState) {
        val found = if (section.photos.isNotEmpty()) {
            section.photos.indexOfFirst { it.path == focusPhotoPath }
        } else {
            section.groups.indexOfFirst { it.id == focusGroupId }
        }
        val fallback = gridState.firstVisibleItemIndex
        (if (found >= 0) found else fallback).coerceIn(0, (itemCount - 1).coerceAtLeast(0))
    }
    val hasFocusTarget = (focusGroupId != null && section.groups.any { it.id == focusGroupId }) ||
        (focusPhotoPath != null && section.photos.any { it.path == focusPhotoPath })

    // 从播放页返回时，切到被打开那一项所在的类型
    LaunchedEffect(sections, focusGroupId, focusPhotoPath) {
        val index = when {
            focusGroupId != null -> sections.indexOfFirst { s -> s.groups.any { it.id == focusGroupId } }
            focusPhotoPath != null -> sections.indexOfFirst { s -> s.photos.any { it.path == focusPhotoPath } }
            else -> -1
        }
        if (index >= 0) onCategoryChange(sections[index].key)
    }

    LaunchedEffect(section.key, targetIndex, autoFocus) {
        if (!autoFocus) return@LaunchedEffect
        // 正在用顶部类型栏切换时焦点留在栏上，不要把焦点抢到列表
        if (tabsFocused) return@LaunchedEffect
        if (hasFocusTarget) {
            focusWithRetry(cardFocus) { gridState.scrollToItem(targetIndex) }
        } else {
            focusWithRetry(tabsFocus) { }
        }
    }

    val focusTabs: () -> Unit = { runCatching { tabsFocus.requestFocus() } }
    val focusGrid: () -> Unit = {
        if (itemCount > 0) {
            scope.launch { focusWithRetry(cardFocus) { gridState.scrollToItem(targetIndex) } }
        }
    }
    fun atTopRow(index: Int): Boolean {
        if (gridState.firstVisibleItemIndex != 0) return false
        val visible = gridState.layoutInfo.visibleItemsInfo
        val item = visible.firstOrNull { it.index == index } ?: return false
        val top = visible.minOfOrNull { it.offset.y } ?: return false
        return item.offset.y == top
    }
    fun atLeftColumn(index: Int): Boolean {
        val visible = gridState.layoutInfo.visibleItemsInfo
        val item = visible.firstOrNull { it.index == index } ?: return false
        val left = visible.minOfOrNull { it.offset.x } ?: return false
        return item.offset.x == left
    }

    Column(Modifier.fillMaxSize()) {
        // 顶部类型栏：聚焦后左右切换类型，向下进入平铺列表
        Row(
            modifier = Modifier
                .padding(start = 48.dp, end = 48.dp, top = 2.dp, bottom = 12.dp)
                .clip(RoundedCornerShape(50))
                .background(if (tabsFocused) SurfaceDark else Color.Transparent)
                .border(
                    width = 2.dp,
                    color = if (tabsFocused) FocusRing else Color.Transparent,
                    shape = RoundedCornerShape(50)
                )
                .focusRequester(tabsFocus)
                .onFocusChanged { tabsFocused = it.isFocused }
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> {
                            val next = (selectedIndex - 1 + sections.size) % sections.size
                            onCategoryChange(sections[next].key)
                            true
                        }

                        Key.DirectionRight -> {
                            val next = (selectedIndex + 1) % sections.size
                            onCategoryChange(sections[next].key)
                            true
                        }

                        Key.DirectionDown -> {
                            focusGrid()
                            true
                        }

                        Key.DirectionUp -> {
                            onUpFromTop()
                            true
                        }

                        else -> false
                    }
                }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            sections.forEachIndexed { index, item ->
                val selected = index == selectedIndex
                Text(
                    text = item.title,
                    color = when {
                        selected && tabsFocused -> FocusText
                        selected -> PrimaryButtonText
                        else -> TextSecondary
                    },
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            when {
                                selected && tabsFocused -> FocusFill
                                selected -> PrimaryButton
                                else -> Color.Transparent
                            }
                        )
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (section.photos.isNotEmpty()) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(180.dp),
                    state = gridState,
                    contentPadding = PaddingValues(
                        start = 48.dp, end = 48.dp, top = 4.dp, bottom = 44.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    gridItemsIndexed(
                        section.photos,
                        key = { _, photo -> photo.path }
                    ) { index, photo ->
                        PhotoTile(
                            photo = photo,
                            favorite = photo.path in favoritePaths,
                            onLongClick = { onLongPressPhoto(photo) },
                            modifier = Modifier
                                .aspectRatio(1f)
                                .then(
                                    if (index == targetIndex) {
                                        Modifier.focusRequester(cardFocus)
                                    } else {
                                        Modifier
                                    }
                                )
                                .blockUpFromTop({ atTopRow(index) }, focusTabs)
                                .blockLeftFromEdge({ atLeftColumn(index) }, onLeftFromContent),
                            onClick = { onOpenPhotos(section.photos, index) }
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(200.dp),
                    state = gridState,
                    contentPadding = PaddingValues(
                        start = 48.dp, end = 48.dp, top = 4.dp, bottom = 44.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(26.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    gridItemsIndexed(
                        section.groups,
                        key = { _, group -> group.id }
                    ) { index, group ->
                        val progress = remember(group.id) {
                            group.episodes.maxOfOrNull { prefs.progressOf(it.path) } ?: 0f
                        }
                        MediaCard(
                            title = group.title,
                            imageModel = coverModel(group, covers),
                            badge = if (group.episodes.size > 1) {
                                "共${group.episodes.size}集"
                            } else {
                                null
                            },
                            subtitle = group.episodes.firstOrNull()?.let { formatSize(it.sizeBytes) },
                            progress = progress,
                            favorite = group.id in favoriteIds,
                            fitImage = covers[group.id] != null,
                            onLongClick = { onLongPressGroup(group) },
                            modifier = Modifier
                                .aspectRatio(16f / 9f)
                                .then(
                                    if (index == targetIndex) {
                                        Modifier.focusRequester(cardFocus)
                                    } else {
                                        Modifier
                                    }
                                )
                                .blockUpFromTop({ atTopRow(index) }, focusTabs)
                                .blockLeftFromEdge({ atLeftColumn(index) }, onLeftFromContent),
                            onClick = { onOpenGroup(group) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LockedHint() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "加密内容已锁定",
            color = TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "按右键输入密码后查看",
            color = TextSecondary,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun EmptyLibrary(
    roots: List<String>,
    error: String?,
    onRefresh: () -> Unit,
    autoFocus: Boolean = true
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(autoFocus) {
        if (autoFocus) runCatching { focus.requestFocus() }
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        EmptyHint(
            title = if (error != null) "扫描出现问题" else "还没有扫描到影片",
            subtitle = error ?: "确认移动硬盘已插好，然后重新扫描",
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(24.dp))
        TvActionButton(
            text = "重新扫描",
            onClick = onRefresh,
            primary = true,
            modifier = Modifier.focusRequester(focus)
        )
        if (roots.isNotEmpty()) {
            Spacer(Modifier.height(26.dp))
            Text(
                text = "已检测到存储：\n" + roots.joinToString("\n"),
                color = TextSecondary,
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "支持 mp4 / mkv / avi / ts / rmvb 视频，自动识别电视剧集",
            color = TextSecondary,
            fontSize = 12.sp
        )
    }
}
