package com.sunli.tvlibrary.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sunli.tvlibrary.data.UploadState

@Composable
fun SettingsScreen(
    photosEnabled: Boolean,
    skipIntro: Boolean,
    darkTheme: Boolean,
    onlineCovers: Boolean,
    upload: UploadState,
    onTogglePhotos: () -> Unit,
    onToggleSkipIntro: () -> Unit,
    onToggleTheme: () -> Unit,
    onToggleOnlineCovers: () -> Unit,
    onMatchCovers: () -> Unit,
    onToggleUpload: () -> Unit,
    onChangePin: () -> Unit,
    onRefresh: () -> Unit,
    onUpFromTop: () -> Unit = {}
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 28.dp,
            end = 36.dp,
            top = 4.dp,
            bottom = 44.dp
        ),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item(key = "playback") {
            SettingsGroup(title = "播放") {
                SettingsRow(
                    title = "跳过片头片尾",
                    subtitle = "片头片尾各跳过 90 秒，仅对 10 分钟以上的影片生效",
                    checked = skipIntro,
                    onAction = onToggleSkipIntro,
                    onUpFromTop = onUpFromTop
                )
            }
        }
        item(key = "content") {
            SettingsGroup(title = "内容") {
                SettingsRow(
                    title = "显示照片",
                    subtitle = "关闭后照片栏隐藏，扫描时也不再加载照片",
                    checked = photosEnabled,
                    onAction = onTogglePhotos
                )
                SettingsRow(
                    title = "夜间模式",
                    subtitle = "深色背景更适合在电视上观看",
                    checked = darkTheme,
                    onAction = onToggleTheme
                )
            }
        }
        item(key = "covers") {
            SettingsGroup(title = "封面") {
                SettingsRow(
                    title = "在线匹配封面",
                    subtitle = "按片名从网络匹配海报，用作影片卡片和详情页背景",
                    checked = onlineCovers,
                    onAction = onToggleOnlineCovers
                )
                SettingsRow(
                    title = "立即匹配封面",
                    subtitle = "重新为所有影片匹配一次网络封面",
                    action = "开始匹配",
                    onAction = onMatchCovers
                )
            }
        }
        item(key = "upload") {
            SettingsGroup(title = "电脑传文件") {
                SettingsRow(
                    title = "局域网传文件",
                    subtitle = when {
                        upload.error != null -> upload.error
                        upload.running -> "已接收 ${upload.count} 个文件，保存在 ${upload.target}"
                        else -> "开启后在同一 Wi-Fi 的电脑浏览器里打开下面的地址"
                    },
                    checked = upload.running,
                    onAction = onToggleUpload
                )
                if (upload.running) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = upload.url,
                            color = TextPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(14.dp))
                        Text(
                            text = "在电脑浏览器中输入此地址，拖入文件即可上传",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
        item(key = "security") {
            SettingsGroup(title = "安全") {
                SettingsRow(
                    title = "修改访问密码",
                    subtitle = "加密栏需要此密码才能进入",
                    action = "修改密码",
                    onAction = onChangePin
                )
            }
        }
        item(key = "maintenance") {
            SettingsGroup(title = "维护") {
                SettingsRow(
                    title = "刷新资源库",
                    subtitle = "重新扫描移动硬盘和内存储中的影片与照片",
                    action = "开始扫描",
                    onAction = onRefresh
                )
            }
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable () -> Unit
) {
    Column {
        Text(
            text = title,
            color = TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark, RoundedCornerShape(14.dp))
                .padding(horizontal = 20.dp, vertical = 6.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    onAction: () -> Unit,
    action: String? = null,
    checked: Boolean? = null,
    onUpFromTop: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                color = TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Spacer(Modifier.width(20.dp))
        val actionModifier = if (onUpFromTop != null) {
            Modifier.blockUpFromTop({ true }, onUpFromTop)
        } else {
            Modifier
        }
        if (checked != null) {
            TvSwitch(
                checked = checked,
                onToggle = onAction,
                modifier = actionModifier
            )
        } else if (action != null) {
            TvActionButton(
                text = action,
                onClick = onAction,
                modifier = actionModifier
            )
        }
    }
}
