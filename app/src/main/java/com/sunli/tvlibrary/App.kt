package com.sunli.tvlibrary

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sunli.tvlibrary.data.LibraryViewModel
import com.sunli.tvlibrary.data.MediaGroup
import com.sunli.tvlibrary.data.Photo
import com.sunli.tvlibrary.data.withoutEncrypted
import com.sunli.tvlibrary.player.PlayerScreen
import com.sunli.tvlibrary.ui.Background
import com.sunli.tvlibrary.ui.ConfirmDialog
import com.sunli.tvlibrary.ui.HomeScreen
import com.sunli.tvlibrary.ui.HomeTab
import com.sunli.tvlibrary.ui.NoticeDialog
import com.sunli.tvlibrary.ui.PhotoViewerScreen
import com.sunli.tvlibrary.ui.PinDialog
import com.sunli.tvlibrary.ui.SeriesScreen
import com.sunli.tvlibrary.ui.TextPrimary
import com.sunli.tvlibrary.ui.TextSecondary
import com.sunli.tvlibrary.ui.TvActionButton
import com.sunli.tvlibrary.ui.TvLibraryTheme

private sealed interface Screen {
    data object Home : Screen
    data class Series(val groupId: String) : Screen
    data class Player(
        val groupId: String,
        val startIndex: Int,
        val fromDetail: Boolean = false
    ) : Screen
    data class Photos(val photos: List<Photo>, val index: Int) : Screen
}

private enum class PinStage { SET_FIRST, SET_CONFIRM, VERIFY }

private fun mediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_IMAGES
        )
    } else {
        arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }

private fun requiredMediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_IMAGES
        )
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

private fun hasMediaPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 23) return true
    return requiredMediaPermissions().all { permission ->
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun purgeFailureMessage(failed: List<String>): String = buildString {
    append("有 ${failed.size} 个文件没能删除，可能是移动硬盘被写保护，或系统没有给出写入权限。")
    append("\n")
    append(failed.take(3).joinToString("\n") { it.substringAfterLast('/') })
    if (failed.size > 3) append("\n…")
}

@Composable
fun TvLibraryApp(viewModel: LibraryViewModel = viewModel()) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val pinned by viewModel.pinned.collectAsStateWithLifecycle()
    val encrypted by viewModel.encrypted.collectAsStateWithLifecycle()
    val pinSet by viewModel.pinSet.collectAsStateWithLifecycle()
    val darkTheme by viewModel.darkTheme.collectAsStateWithLifecycle()
    val photosEnabled by viewModel.photosEnabled.collectAsStateWithLifecycle()
    val skipIntro by viewModel.skipIntro.collectAsStateWithLifecycle()
    val onlineCovers by viewModel.onlineCovers.collectAsStateWithLifecycle()
    val covers by viewModel.covers.collectAsStateWithLifecycle()
    val upload by viewModel.upload.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(hasMediaPermission(context)) }
    var selectedTab by remember { mutableStateOf(HomeTab.MOVIE) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var showExitConfirm by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    var unlocked by remember { mutableStateOf(false) }
    var pinStage by remember { mutableStateOf<PinStage?>(null) }
    var firstPin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    var focusGroupId by remember { mutableStateOf<String?>(null) }
    var focusPhotoPath by remember { mutableStateOf<String?>(null) }
    var seriesFocus by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

    val groupGridStates = remember { mutableMapOf<HomeTab, LazyGridState>() }
    val photoGridState = remember { LazyGridState() }
    val historyGridState = remember { LazyGridState() }
    val favoritesListState = remember { LazyListState() }
    val encryptedGridStates = remember { mutableMapOf<String, LazyGridState>() }
    var encryptedCategoryKey by remember { mutableStateOf<String?>(null) }
    val seriesGridStates = remember { mutableMapOf<String, LazyGridState>() }

    fun startSetPin(after: (() -> Unit)?) {
        pendingAction = after
        firstPin = ""
        pinError = null
        pinStage = PinStage.SET_FIRST
    }

    fun requestEncrypt(action: () -> Unit) {
        if (pinSet) action() else startSetPin(action)
    }

    fun requestUnlock() {
        val enter = {
            unlocked = true
            selectedTab = HomeTab.LOCKED
        }
        pinError = null
        if (pinSet) {
            pendingAction = enter
            pinStage = PinStage.VERIFY
        } else {
            startSetPin(enter)
        }
    }

    fun changeTab(tab: HomeTab) {
        // 切换栏目后不再沿用上一次的定位项，避免焦点被旧目标拉回内容区
        focusGroupId = null
        focusPhotoPath = null
        // 加密栏只是切入，等用户在加密页按右键时再要密码
        if (selectedTab == HomeTab.LOCKED && tab != HomeTab.LOCKED) unlocked = false
        selectedTab = tab
    }

    fun onPinSubmit(pin: String) {
        when (pinStage) {
            PinStage.SET_FIRST -> {
                firstPin = pin
                pinError = null
                pinStage = PinStage.SET_CONFIRM
            }

            PinStage.SET_CONFIRM -> {
                if (pin == firstPin) {
                    viewModel.setPin(pin)
                    pinStage = null
                    pinError = null
                    val action = pendingAction
                    pendingAction = null
                    action?.invoke()
                } else {
                    firstPin = ""
                    pinError = "两次输入不一致，请重新设置"
                    pinStage = PinStage.SET_FIRST
                }
            }

            PinStage.VERIFY -> {
                if (viewModel.verifyPin(pin)) {
                    pinStage = null
                    pinError = null
                    val action = pendingAction
                    pendingAction = null
                    action?.invoke()
                } else {
                    pinError = "密码错误，请重试"
                }
            }

            null -> Unit
        }
    }

    fun purgeGroup(group: MediaGroup) {
        if (group.id == focusGroupId) focusGroupId = null
        val current = screen
        if (current is Screen.Player && current.groupId == group.id) screen = Screen.Home
        if (current is Screen.Series && current.groupId == group.id) screen = Screen.Home
        viewModel.purgeGroup(group.id) { failed ->
            if (failed.isNotEmpty()) notice = purgeFailureMessage(failed)
        }
    }

    fun purgePhoto(photo: Photo) {
        if (photo.path == focusPhotoPath) focusPhotoPath = null
        viewModel.purgePhoto(photo.path) { failed ->
            if (failed.isNotEmpty()) notice = purgeFailureMessage(failed)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasPermission = hasMediaPermission(context)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = hasMediaPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(mediaPermissions())
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) viewModel.ensureScanned()
    }

    TvLibraryTheme(dark = darkTheme) {
        if (!hasPermission) {
            PermissionScreen(
                onRequest = { permissionLauncher.launch(mediaPermissions()) },
                onOpenSettings = { openAppSettings(context) }
            )
            return@TvLibraryTheme
        }

        val library = state.library
        when (val current = screen) {
            Screen.Home -> {
                BackHandler { showExitConfirm = true }
                HomeScreen(
                    state = state,
                    selectedTab = selectedTab,
                    onTabChange = ::changeTab,
                    unlocked = unlocked,
                    onRequestUnlock = { requestUnlock() },
                    groupGridState = groupGridStates.getOrPut(selectedTab) { LazyGridState() },
                    photoGridState = photoGridState,
                    historyGridState = historyGridState,
                    favoritesListState = favoritesListState,
                    encryptedGridStates = encryptedGridStates,
                    encryptedCategoryKey = encryptedCategoryKey,
                    onEncryptedCategoryChange = { encryptedCategoryKey = it },
                    favorites = favorites,
                    pinned = pinned,
                    encrypted = encrypted,
                    focusGroupId = focusGroupId,
                    focusPhotoPath = focusPhotoPath,
                    darkTheme = darkTheme,
                    photosEnabled = photosEnabled,
                    skipIntro = skipIntro,
                    onlineCovers = onlineCovers,
                    covers = covers,
                    upload = upload,
                    onToggleTheme = { viewModel.toggleTheme() },
                    onToggleSkipIntro = { viewModel.toggleSkipIntro() },
                    onToggleOnlineCovers = { viewModel.toggleOnlineCovers() },
                    onMatchCovers = { viewModel.refreshCovers(force = true) },
                    onToggleUpload = { viewModel.toggleUpload() },
                    onTogglePhotos = {
                        val enable = !photosEnabled
                        viewModel.setPhotosEnabled(enable)
                        if (!enable && selectedTab == HomeTab.PHOTO) selectedTab = HomeTab.MOVIE
                    },
                    onRefresh = { viewModel.refresh() },
                    onOpenGroup = { group ->
                        focusGroupId = group.id
                        focusPhotoPath = null
                        seriesFocus = seriesFocus + (
                            group.id to if (group.episodes.size == 1) 0 else -1
                        )
                        screen = Screen.Series(group.id)
                    },
                    onPlayHistory = { group, index ->
                        focusGroupId = group.id
                        focusPhotoPath = null
                        seriesFocus = seriesFocus + (group.id to index)
                        screen = Screen.Player(group.id, index)
                    },
                    onOpenPhotos = { photos, index ->
                        focusPhotoPath = photos.getOrNull(index)?.path
                        focusGroupId = null
                        screen = Screen.Photos(photos, index)
                    },
                    onToggleGroupFavorite = { viewModel.toggleGroupFavorite(it) },
                    onTogglePhotoFavorite = { viewModel.togglePhotoFavorite(it) },
                    onRenameGroup = { id, name -> viewModel.renameGroup(id, name) },
                    onRenamePhoto = { path, name -> viewModel.renamePhoto(path, name) },
                    onToggleGroupPinned = { viewModel.toggleGroupPinned(it) },
                    onTogglePhotoPinned = { viewModel.togglePhotoPinned(it) },
                    onToggleGroupEncrypt = { id ->
                        if (id in encrypted.groupIds) {
                            viewModel.toggleGroupEncrypted(id)
                        } else {
                            requestEncrypt { viewModel.toggleGroupEncrypted(id) }
                        }
                    },
                    onTogglePhotoEncrypt = { path ->
                        if (path in encrypted.photoPaths) {
                            viewModel.togglePhotoEncrypted(path)
                        } else {
                            requestEncrypt { viewModel.togglePhotoEncrypted(path) }
                        }
                    },
                    onEncryptItems = { groupIds, photoPaths ->
                        requestEncrypt {
                            viewModel.encryptGroups(groupIds)
                            viewModel.encryptPhotos(photoPaths)
                        }
                    },
                    onDeleteGroup = {
                        if (it == focusGroupId) focusGroupId = null
                        viewModel.deleteGroup(it)
                    },
                    onDeletePhoto = {
                        if (it == focusPhotoPath) focusPhotoPath = null
                        viewModel.deletePhoto(it)
                    },
                    onPurgeGroup = ::purgeGroup,
                    onPurgePhoto = ::purgePhoto,
                    onPurgeItems = { groupIds, photoPaths ->
                        if (groupIds.contains(focusGroupId)) focusGroupId = null
                        if (photoPaths.contains(focusPhotoPath)) focusPhotoPath = null
                        viewModel.purgeItems(groupIds, photoPaths) { failed ->
                            if (failed.isNotEmpty()) notice = purgeFailureMessage(failed)
                        }
                    },
                    onLock = {
                        unlocked = false
                        selectedTab = HomeTab.MOVIE
                    },
                    onChangePin = { startSetPin(null) }
                )
            }

            is Screen.Series -> {
                val group = library?.groups?.firstOrNull { it.id == current.groupId }
                if (group == null) {
                    LaunchedEffect(current.groupId) { screen = Screen.Home }
                } else {
                    SeriesScreen(
                        group = group,
                        gridState = seriesGridStates.getOrPut(group.id) { LazyGridState() },
                        favorite = group.id in favorites.groupIds,
                        encrypted = group.id in encrypted.groupIds,
                        focusIndex = seriesFocus[group.id] ?: -1,
                        coverPath = covers[group.id],
                        onToggleFavorite = { viewModel.toggleGroupFavorite(group.id) },
                        onToggleEncrypt = {
                            if (group.id in encrypted.groupIds) {
                                viewModel.toggleGroupEncrypted(group.id)
                            } else {
                                requestEncrypt { viewModel.toggleGroupEncrypted(group.id) }
                            }
                        },
                        onDelete = {
                            if (group.id == focusGroupId) focusGroupId = null
                            viewModel.deleteGroup(group.id)
                            screen = Screen.Home
                        },
                        onPurge = { purgeGroup(group) },
                        onPlay = { index ->
                            seriesFocus = seriesFocus + (group.id to index)
                            screen = Screen.Player(group.id, index, fromDetail = true)
                        },
                        onExit = { screen = Screen.Home }
                    )
                }
            }

            is Screen.Player -> {
                val group = library?.groups?.firstOrNull { it.id == current.groupId }
                if (group == null) {
                    LaunchedEffect(current.groupId) { screen = Screen.Home }
                } else {
                    val playlist = library?.withoutEncrypted(encrypted)
                        ?.groupsOf(group.kind)
                        .orEmpty()
                    val nextGroup = if (group.episodes.size == 1) {
                        val index = playlist.indexOfFirst { it.id == group.id }
                        if (index >= 0) playlist.getOrNull(index + 1) else null
                    } else {
                        null
                    }
                    key(group.id) {
                        PlayerScreen(
                            group = group,
                            startIndex = current.startIndex,
                            skipIntro = skipIntro,
                            favorite = group.id in favorites.groupIds,
                            encrypted = group.id in encrypted.groupIds,
                            nextTitle = nextGroup?.title?.let {
                                if (it.length > 12) it.take(12) + "…" else it
                            },
                            onToggleFavorite = { viewModel.toggleGroupFavorite(group.id) },
                            onToggleEncrypt = {
                                if (group.id in encrypted.groupIds) {
                                    viewModel.toggleGroupEncrypted(group.id)
                                } else {
                                    requestEncrypt { viewModel.toggleGroupEncrypted(group.id) }
                                }
                            },
                            onDelete = {
                                if (group.id == focusGroupId) focusGroupId = null
                                viewModel.deleteGroup(group.id)
                                screen = Screen.Home
                            },
                            onRename = { name -> viewModel.renameGroup(group.id, name) },
                            onPurge = { purgeGroup(group) },
                            onPlayNext = nextGroup?.let { next ->
                                {
                                    focusGroupId = next.id
                                    focusPhotoPath = null
                                    seriesFocus = seriesFocus + (next.id to 0)
                                    screen = Screen.Player(next.id, 0)
                                }
                            },
                            onExit = { index ->
                                seriesFocus = seriesFocus + (group.id to index)
                                screen = if (current.fromDetail) {
                                    Screen.Series(group.id)
                                } else {
                                    Screen.Home
                                }
                            }
                        )
                    }
                }
            }

            is Screen.Photos -> {
                if (current.photos.isEmpty()) {
                    LaunchedEffect(Unit) { screen = Screen.Home }
                } else {
                    PhotoViewerScreen(
                        photos = current.photos,
                        initialIndex = current.index,
                        onExit = { index ->
                            focusPhotoPath = current.photos.getOrNull(index)?.path
                            focusGroupId = null
                            screen = Screen.Home
                        }
                    )
                }
            }
        }

        val stage = pinStage
        if (stage != null) {
            PinDialog(
                title = when (stage) {
                    PinStage.SET_FIRST -> "设置加密密码"
                    PinStage.SET_CONFIRM -> "再次输入密码"
                    PinStage.VERIFY -> "输入密码"
                },
                subtitle = when (stage) {
                    PinStage.SET_FIRST -> "4 位数字，用于进入加密栏"
                    PinStage.SET_CONFIRM -> "请再次输入以确认"
                    PinStage.VERIFY -> "密码正确后才能浏览加密内容"
                },
                error = pinError,
                onSubmit = ::onPinSubmit,
                onDismiss = {
                    pinStage = null
                    pinError = null
                    pendingAction = null
                }
            )
        }

        val noticeText = notice
        if (noticeText != null) {
            NoticeDialog(
                title = "删除失败",
                message = noticeText,
                onDismiss = { notice = null }
            )
        }

        if (showExitConfirm) {
            ConfirmDialog(
                title = "退出私影？",
                message = "退出后已扫描的资源库会保留，下次打开可直接浏览。",
                confirmText = "退出",
                onConfirm = {
                    showExitConfirm = false
                    (context as? Activity)?.finish()
                },
                onDismiss = { showExitConfirm = false }
            )
        }
    }
}

@Composable
private fun PermissionScreen(
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .padding(64.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("私影", color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(10.dp))
            Text(
                text = "需要读取存储的权限，用来扫描移动硬盘里的影视资源",
                color = TextSecondary,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(28.dp))
            Row {
                TvActionButton(
                    text = "授予权限",
                    onClick = onRequest,
                    primary = true,
                    modifier = Modifier.focusRequester(focus)
                )
                Spacer(Modifier.width(14.dp))
                TvActionButton(text = "打开系统设置", onClick = onOpenSettings)
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = "如果系统没有弹出授权框，请到「设置 → 应用 → 私影 → 权限」中手动允许",
                color = TextSecondary,
                fontSize = 12.sp
            )
        }
    }
}
