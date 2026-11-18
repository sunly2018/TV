package com.sunli.tvlibrary.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi

private enum class AudioLanguageKind { MANDARIN, CANTONESE, OTHER }

/**
 * 同一个片子里常见的双音轨：国语/粤语。文件标签五花八门（国配、粤语、cmn、yue……），
 * 这里统一识别成“国语/粤语”，识别不出来就按文件名或序号展示。
 */
@UnstableApi
data class AudioTrackOption(
    val group: TrackGroup,
    val trackIndex: Int,
    val format: Format,
    val order: Int = 0
) {
    val language: String? = format.language
    val rawLabel: String? = format.label?.trim()?.takeIf { it.isNotEmpty() }

    // und / unknown 这类占位符不算语言
    private val normalizedLanguage: String? = language?.lowercase()
        ?.takeIf { it.isNotBlank() && it != "und" && it != "unknown" }

    private val kind: AudioLanguageKind by lazy {
        val label = rawLabel.orEmpty().lowercase()
        val lang = normalizedLanguage.orEmpty()
        when {
            CANTONESE_HINTS.any { label.contains(it) } || lang in CANTONESE_LANGS ->
                AudioLanguageKind.CANTONESE

            MANDARIN_HINTS.any { label.contains(it) } || lang in MANDARIN_LANGS ->
                AudioLanguageKind.MANDARIN

            else -> AudioLanguageKind.OTHER
        }
    }

    val isMandarin: Boolean get() = kind == AudioLanguageKind.MANDARIN
    val isCantonese: Boolean get() = kind == AudioLanguageKind.CANTONESE

    // 能说清楚是什么语言的短名，识别不出来就是 null
    val title: String?
        get() = when (kind) {
            AudioLanguageKind.MANDARIN -> "国语"
            AudioLanguageKind.CANTONESE -> "粤语"
            AudioLanguageKind.OTHER -> rawLabel ?: normalizedLanguage?.let(::languageName)
        }

    val displayName: String get() = title ?: "音轨 ${if (order > 0) order else trackIndex + 1}"

    companion object {
        private val MANDARIN_HINTS = listOf(
            "国语", "國語", "普通话", "普通話", "国配", "國配", "mandarin", "guoyu", "putonghua"
        )
        private val CANTONESE_HINTS = listOf(
            "粤语", "粵語", "粤配", "粵配", "cantonese", "guangdong"
        )
        private val MANDARIN_LANGS = setOf("cmn", "zh", "zho", "chi", "chs", "mandarin")
        private val CANTONESE_LANGS = setOf("yue", "cantonese")

        private fun languageName(code: String): String = when (code.lowercase()) {
            "en", "eng" -> "英语"
            "ja", "jpn" -> "日语"
            "ko", "kor" -> "韩语"
            "fr", "fra", "fre" -> "法语"
            "de", "deu", "ger" -> "德语"
            "ru", "rus" -> "俄语"
            "es", "spa" -> "西班牙语"
            "th", "tha" -> "泰语"
            else -> code.uppercase()
        }
    }
}

@UnstableApi
object AudioTracks {

    fun options(tracks: Tracks): List<AudioTrackOption> = buildList {
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (index in 0 until group.length) {
                if (!group.isTrackSupported(index)) continue
                add(
                    AudioTrackOption(
                        group.mediaTrackGroup,
                        index,
                        group.getTrackFormat(index),
                        size + 1
                    )
                )
            }
        }
    }

    fun current(tracks: Tracks): AudioTrackOption? {
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (index in 0 until group.length) {
                if (group.isTrackSelected(index)) {
                    return AudioTrackOption(group.mediaTrackGroup, index, group.getTrackFormat(index))
                }
            }
        }
        return null
    }

    fun isCurrent(current: AudioTrackOption?, option: AudioTrackOption): Boolean =
        current != null &&
            current.group.id == option.group.id &&
            current.trackIndex == option.trackIndex

    fun buttonLabel(current: AudioTrackOption?, options: List<AudioTrackOption>): String {
        current?.title?.let { return "音轨：$it" }
        val index = options.indexOfFirst { isCurrent(current, it) }
        return "音轨 ${(index + 1).coerceAtLeast(1)}/${options.size}"
    }

    fun select(player: Player, option: AudioTrackOption) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(option.group, option.trackIndex))
            .build()
    }

    // 默认国语：优先带国语标签的；没有标签就按惯例取第一条，避开带粤语标签的轨
    fun defaultMandarin(options: List<AudioTrackOption>): AudioTrackOption? {
        options.firstOrNull { it.isMandarin }?.let { return it }
        val first = options.firstOrNull() ?: return null
        if (!first.isCantonese) return first
        return options.firstOrNull { !it.isCantonese } ?: first
    }
}
