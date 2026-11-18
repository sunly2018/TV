package com.sunli.tvlibrary.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _darkTheme = MutableStateFlow(prefs.getBoolean(KEY_DARK, true))
    val darkTheme: StateFlow<Boolean> = _darkTheme.asStateFlow()

    private val _photosEnabled = MutableStateFlow(prefs.getBoolean(KEY_PHOTOS, true))
    val photosEnabled: StateFlow<Boolean> = _photosEnabled.asStateFlow()

    private val _skipIntro = MutableStateFlow(prefs.getBoolean(KEY_SKIP_INTRO, false))
    val skipIntro: StateFlow<Boolean> = _skipIntro.asStateFlow()

    private val _onlineCovers = MutableStateFlow(prefs.getBoolean(KEY_ONLINE_COVERS, true))
    val onlineCovers: StateFlow<Boolean> = _onlineCovers.asStateFlow()

    private val _uploadEnabled = MutableStateFlow(prefs.getBoolean(KEY_UPLOAD, false))
    val uploadEnabled: StateFlow<Boolean> = _uploadEnabled.asStateFlow()

    fun setDarkTheme(dark: Boolean) {
        prefs.edit().putBoolean(KEY_DARK, dark).apply()
        _darkTheme.value = dark
    }

    fun setPhotosEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PHOTOS, enabled).apply()
        _photosEnabled.value = enabled
    }

    fun setSkipIntro(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SKIP_INTRO, enabled).apply()
        _skipIntro.value = enabled
    }

    fun setOnlineCovers(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ONLINE_COVERS, enabled).apply()
        _onlineCovers.value = enabled
    }

    fun setUploadEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_UPLOAD, enabled).apply()
        _uploadEnabled.value = enabled
    }

    private companion object {
        const val KEY_DARK = "dark_theme"
        const val KEY_PHOTOS = "photos_enabled"
        const val KEY_SKIP_INTRO = "skip_intro"
        const val KEY_ONLINE_COVERS = "online_covers"
        const val KEY_UPLOAD = "upload_enabled"
    }
}
