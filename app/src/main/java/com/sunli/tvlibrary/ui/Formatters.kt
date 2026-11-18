package com.sunli.tvlibrary.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

fun formatScanTime(ms: Long): String {
    if (ms <= 0L) return "从未扫描"
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
}

fun formatSize(bytes: Long): String {
    if (bytes <= 0L) return ""
    val gb = bytes / 1024.0 / 1024.0 / 1024.0
    if (gb >= 1.0) return String.format(Locale.US, "%.1f GB", gb)
    val mb = bytes / 1024.0 / 1024.0
    return String.format(Locale.US, "%.0f MB", mb)
}

fun episodeLabel(index: Int, season: Int, number: Int): String =
    when {
        number <= 0 -> "${index + 1}"
        season > 1 -> "$season-$number"
        else -> "$number"
    }

fun formatAgo(timeMs: Long): String {
    if (timeMs <= 0L) return ""
    val diff = System.currentTimeMillis() - timeMs
    return when {
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
        diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
        diff < 2 * 86_400_000L -> "昨天"
        diff < 30 * 86_400_000L -> "${diff / 86_400_000L} 天前"
        else -> SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(timeMs))
    }
}
