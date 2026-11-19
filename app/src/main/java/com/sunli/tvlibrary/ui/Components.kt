package com.sunli.tvlibrary.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val LONG_PRESS_MS = 2000L

/**
 * 内容区第一行拦截「上」：不让焦点顺着默认规则跳回左侧栏，
 * 有顶部菜单时交给 onUp 聚焦菜单，没有菜单时按键被吃掉、焦点留在当前项。
 */
fun Modifier.blockUpFromTop(isTopRow: () -> Boolean, onUp: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp && isTopRow()) {
            onUp()
            true
        } else {
            false
        }
    }

/**
 * 内容区最左一列拦截「左」：默认焦点搜索会挑几何上最近的栏目，常常跳到别的分类，
 * 这里统一把焦点交还给当前选中的栏目。
 */
fun Modifier.blockLeftFromEdge(isLeftEdge: () -> Boolean, onLeft: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft && isLeftEdge()) {
            onLeft()
            true
        } else {
            false
        }
    }

@Composable
fun FocusableCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(14.dp),
    background: Color = SurfaceDark,
    focusedBackground: Color? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable (focused: Boolean) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    var longPressFired by remember { mutableStateOf(false) }
    var activeDownTime by remember { mutableStateOf(0L) }
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, label = "cardScale")
    val borderColor by animateColorAsState(
        if (focused) FocusRing else if (IsDarkTheme) Color(0x14FFFFFF) else Color(0x1A000000),
        label = "cardBorder"
    )
    LaunchedEffect(activeDownTime) {
        val token = activeDownTime
        if (token == 0L) return@LaunchedEffect
        delay(LONG_PRESS_MS)
        // 按满时长立即弹菜单，不用等松手；这次长按的抬起事件由弹窗吞掉
        if (pressed && activeDownTime == token) {
            longPressFired = true
            onLongClick?.invoke()
        }
    }
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(
                if (focused && focusedBackground != null) focusedBackground else background,
                shape
            )
            .border(if (focused) 2.dp else 1.dp, borderColor, shape)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (onLongClick == null) return@onPreviewKeyEvent false
                val centerKey = event.key == Key.DirectionCenter ||
                    event.key == Key.Enter ||
                    event.key == Key.NumPadEnter
                if (!centerKey) return@onPreviewKeyEvent false
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        val downTime = event.nativeKeyEvent.downTime
                        // 弹窗会抢走抬起事件，用 downTime 识别新的一次按键，避免状态卡住
                        if (downTime != activeDownTime) {
                            activeDownTime = downTime
                            pressed = true
                            longPressFired = false
                        }
                        true
                    }

                    KeyEventType.KeyUp -> {
                        val wasLong =
                            longPressFired && event.nativeKeyEvent.downTime == activeDownTime
                        pressed = false
                        longPressFired = false
                        // 长按已弹过菜单，这次抬起不再当作短按；短按仍走点击
                        if (!wasLong) onClick()
                        true
                    }

                    else -> false
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        content(focused)
    }
}

@Composable
fun MediaCard(
    title: String,
    imageModel: Any?,
    modifier: Modifier = Modifier,
    badge: String? = null,
    subtitle: String? = null,
    progress: Float = 0f,
    favorite: Boolean = false,
    selectionMark: Boolean? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    FocusableCard(onClick = onClick, onLongClick = onLongClick, modifier = modifier) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(SurfaceLight)
            )
            if (imageModel != null) {
                AsyncImage(
                    model = imageModel,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("▶", color = Color(0x66FFFFFF), fontSize = 30.sp)
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.45f to Color.Transparent,
                            1f to Color(0xD9000000)
                        )
                    )
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
            if (badge != null) {
                Text(
                    text = badge,
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(if (selectionMark != null) Alignment.TopStart else Alignment.TopEnd)
                        .padding(8.dp)
                        .background(Color(0xB3000000), RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 9.dp)
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        color = Color(0xFFB9C0C9),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (progress > 0.01f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color(0x66000000))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(Accent)
                    )
                }
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

@Composable
fun SelectionBadge(selected: Boolean, modifier: Modifier = Modifier) {
    val fill = if (selected) Color.White else Color(0x99000000)
    val border = if (selected) Color.White else Color(0xCCFFFFFF)
    Box(
        modifier = modifier
            .size(26.dp)
            .background(fill, RoundedCornerShape(50))
            .border(if (selected) 0.dp else 2.dp, border, RoundedCornerShape(50)),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Text(
                text = "✓",
                color = Color(0xFF12151A),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun TabChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FocusableCard(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        background = if (selected) {
            if (IsDarkTheme) Color(0x26FFFFFF) else Color(0x1F000000)
        } else {
            if (IsDarkTheme) Color(0x0DFFFFFF) else Color(0x0A000000)
        },
        focusedBackground = FocusFill
    ) { focused ->
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                color = when {
                    focused -> FocusText
                    selected -> TextPrimary
                    else -> TextSecondary
                },
                fontSize = 14.sp,
                fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 9.dp)
            )
        }
    }
}

@Composable
fun TvActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    large: Boolean = false
) {
    FocusableCard(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        background = if (primary) PrimaryButton else SurfaceLight,
        focusedBackground = FocusFill
    ) { focused ->
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                color = when {
                    focused -> FocusText
                    primary -> PrimaryButtonText
                    else -> TextPrimary
                },
                fontSize = if (large) 18.sp else 14.sp,
                fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.padding(
                    horizontal = if (large) 24.dp else 14.dp,
                    vertical = if (large) 15.dp else 10.dp
                )
            )
        }
    }
}

@Composable
fun TvSwitch(
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "switchScale")
    val trackColor by animateColorAsState(
        if (checked) {
            if (IsDarkTheme) Color(0xFF2196F3) else Color(0xFF1976D2)
        } else if (IsDarkTheme) {
            Color(0xFF3B434F)
        } else {
            Color(0xFFC3CAD4)
        },
        label = "switchTrack"
    )
    val borderColor by animateColorAsState(
        if (focused) FocusRing else Color.Transparent,
        label = "switchBorder"
    )
    val thumbX by animateDpAsState(if (checked) 27.dp else 3.dp, label = "switchThumb")
    Box(
        modifier = modifier
            .size(width = 56.dp, height = 32.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(50))
            .background(trackColor)
            .border(2.dp, borderColor, RoundedCornerShape(50))
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                val centerKey = event.key == Key.DirectionCenter ||
                    event.key == Key.Enter ||
                    event.key == Key.NumPadEnter
                if (!centerKey) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyUp) onToggle()
                true
            }
            .focusable()
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = thumbX)
                .size(26.dp)
                .background(if (checked) Color.White else Color(0xFFE9EDF2), CircleShape)
        )
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        BackHandler(enabled = true) { onDismiss() }
        val cancelFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        LaunchedEffect(Unit) {
            runCatching { cancelFocus.requestFocus() }
        }
        Column(
            modifier = Modifier
                .background(SurfaceDark, RoundedCornerShape(16.dp))
                .padding(horizontal = 30.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                text = message,
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 10.dp)
            )
            Row(Modifier.padding(top = 22.dp)) {
                TvActionButton(
                    text = confirmText,
                    onClick = onConfirm
                )
                Spacer(Modifier.width(14.dp))
                TvActionButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(cancelFocus)
                )
            }
        }
    }
}

@Composable
fun RenameDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val maxLength = 40
    Dialog(onDismissRequest = onDismiss) {
        BackHandler(enabled = true) { onDismiss() }
        var text by remember(initial) { mutableStateOf(initial) }
        val fieldFocus = remember { FocusRequester() }
        // 弹窗窗口挂载有延迟，一次 requestFocus 可能落空，焦点会漏到下面的页面上
        LaunchedEffect(Unit) {
            repeat(12) {
                runCatching { fieldFocus.requestFocus() }
                delay(60L)
            }
        }
        Column(
            modifier = Modifier
                .background(SurfaceDark, RoundedCornerShape(16.dp))
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                text = "只改影库里的显示名称，硬盘上的文件不动",
                color = TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
            BasicTextField(
                value = text,
                onValueChange = { if (it.length <= maxLength) text = it },
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 17.sp),
                cursorBrush = SolidColor(Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { if (text.isNotBlank()) onConfirm(text.trim()) }
                ),
                modifier = Modifier
                    .padding(top = 16.dp)
                    .width(360.dp)
                    .focusRequester(fieldFocus)
                    .background(SurfaceLight, RoundedCornerShape(10.dp))
                    .border(1.dp, Accent.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            )
            Text(
                text = "${text.length}/$maxLength",
                color = TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .width(360.dp)
            )
            Row(Modifier.padding(top = 16.dp)) {
                TvActionButton(
                    text = "保存",
                    onClick = { if (text.isNotBlank()) onConfirm(text.trim()) },
                    primary = true
                )
                Spacer(Modifier.width(14.dp))
                TvActionButton(
                    text = "取消",
                    onClick = onDismiss
                )
            }
        }
    }
}

@Composable
fun NoticeDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        BackHandler(enabled = true) { onDismiss() }
        val okFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            runCatching { okFocus.requestFocus() }
        }
        Column(
            modifier = Modifier
                .background(SurfaceDark, RoundedCornerShape(16.dp))
                .padding(horizontal = 34.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, color = Accent, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(
                text = message,
                color = TextPrimary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 12.dp)
            )
            Spacer(Modifier.height(20.dp))
            TvActionButton(
                text = "知道了",
                onClick = onDismiss,
                modifier = Modifier.focusRequester(okFocus)
            )
        }
    }
}

@Composable
fun EpisodeChip(
    label: String,
    current: Boolean,
    progress: Float,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    FocusableCard(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        background = if (current) {
            if (IsDarkTheme) Color(0x26FFFFFF) else Color(0x1F000000)
        } else {
            SurfaceLight
        }
    ) { focused ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                color = when {
                    focused -> FocusText
                    current -> TextPrimary
                    else -> TextPrimary
                },
                fontSize = 17.sp,
                fontWeight = if (current || focused) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1
            )
            if (progress > 0.01f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color(0x55000000))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(if (IsDarkTheme) Color.White else Color(0xFF1B1F26))
                    )
                }
            }
        }
    }
}

@Composable
fun PinDialog(
    title: String,
    subtitle: String,
    error: String?,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        BackHandler(enabled = true) { onDismiss() }
        var pin by remember { mutableStateOf("") }
        val firstKey = remember { FocusRequester() }
        val scope = rememberCoroutineScope()
        var keypadFocused by remember { mutableStateOf(false) }
        // 弹窗窗口挂载有延迟，一次 requestFocus 可能落空，按键就会漏到下面的页面
        LaunchedEffect(Unit) {
            repeat(12) {
                if (keypadFocused) return@LaunchedEffect
                runCatching { firstKey.requestFocus() }
                delay(60L)
            }
        }
        Column(
            modifier = Modifier
                .background(SurfaceDark, RoundedCornerShape(16.dp))
                .padding(horizontal = 34.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                text = subtitle,
                color = TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                text = "●".repeat(pin.length) + "○".repeat((PIN_LENGTH - pin.length).coerceAtLeast(0)),
                color = Accent,
                fontSize = 20.sp,
                modifier = Modifier.padding(top = 14.dp)
            )
            if (error != null) {
                Text(
                    text = error,
                    color = Accent,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            Spacer(Modifier.height(18.dp))
            listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("删除", "0", "取消")
            ).forEach { keys ->
                Row(
                    modifier = Modifier.width(252.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    keys.forEach { key ->
                        TvActionButton(
                            text = key,
                            onClick = {
                                when (key) {
                                    "删除" -> pin = pin.dropLast(1)
                                    "取消" -> onDismiss()
                                    else -> if (pin.length < PIN_LENGTH) {
                                        pin += key
                                        if (pin.length == PIN_LENGTH) {
                                            val entered = pin
                                            pin = ""
                                            // 稍后再处理：最后一次按键的 KeyUp 若在弹窗关闭后继续派发到下层列表会误触卡片
                                            scope.launch {
                                                delay(150L)
                                                onSubmit(entered)
                                            }
                                        }
                                    }
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .then(
                                    if (key == "1") {
                                        Modifier
                                            .focusRequester(firstKey)
                                            .onFocusChanged { keypadFocused = it.isFocused }
                                    } else {
                                        Modifier
                                    }
                                )
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

private const val PIN_LENGTH = 4

@Composable
fun ScanStatus(
    scanning: Boolean,
    progress: String,
    scannedAt: Long,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.End) {
        Text(
            text = if (scanning) progress.ifBlank { "正在扫描…" } else "上次更新 ${formatScanTime(scannedAt)}",
            color = if (scanning) Accent else TextSecondary,
            fontSize = 12.sp
        )
    }
}

@Composable
fun EmptyHint(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🎬", fontSize = 44.sp)
        Text(
            text = title,
            color = TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            text = subtitle,
            color = TextSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
