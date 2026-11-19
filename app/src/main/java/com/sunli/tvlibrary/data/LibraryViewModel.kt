package com.sunli.tvlibrary.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

data class LibraryUiState(
    val loadingCache: Boolean = true,
    val scanning: Boolean = false,
    val progress: String = "",
    val library: Library? = null,
    val error: String? = null,
    val roots: List<String> = emptyList()
)

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val cache = LibraryCache(app)
    private val thumbnails = ThumbnailStore(app)
    private val scanner = MediaScanner(app)
    private val favoritesStore = FavoritesStore(app)
    private val hiddenStore = HiddenStore(app)
    private val encryptedStore = EncryptedStore(app)
    private val settingsStore = SettingsStore(app)
    private val playback = PlaybackPrefs(app)
    private val coverStore = CoverStore(app)
    private val renames = RenameStore(app)
    private val pinnedStore = PinnedStore(app)
    private val coverFetcher = CoverFetcher()
    private val uploads = UploadServer(app) { scheduleUploadRescan() }

    private val _ui = MutableStateFlow(LibraryUiState())
    val ui: StateFlow<LibraryUiState> = _ui.asStateFlow()

    private val _covers = MutableStateFlow(coverStore.readAll())
    val covers: StateFlow<Map<String, String>> = _covers.asStateFlow()

    val favorites: StateFlow<Favorites> = favoritesStore.favorites
    val pinned: StateFlow<Pinned> = pinnedStore.pinned
    val encrypted: StateFlow<Encrypted> = encryptedStore.items
    val pinSet: StateFlow<Boolean> = encryptedStore.pinSet
    val darkTheme: StateFlow<Boolean> = settingsStore.darkTheme
    val photosEnabled: StateFlow<Boolean> = settingsStore.photosEnabled
    val skipIntro: StateFlow<Boolean> = settingsStore.skipIntro
    val onlineCovers: StateFlow<Boolean> = settingsStore.onlineCovers
    val upload: StateFlow<UploadState> = uploads.state

    fun toggleTheme() = settingsStore.setDarkTheme(!settingsStore.darkTheme.value)

    fun toggleSkipIntro() = settingsStore.setSkipIntro(!settingsStore.skipIntro.value)

    fun toggleOnlineCovers() {
        val next = !settingsStore.onlineCovers.value
        settingsStore.setOnlineCovers(next)
        if (next) refreshCovers()
    }

    fun toggleUpload() {
        if (uploads.state.value.running) {
            uploads.stop()
            settingsStore.setUploadEnabled(false)
        } else {
            uploads.start()
            settingsStore.setUploadEnabled(uploads.state.value.running)
        }
    }

    fun setPhotosEnabled(enabled: Boolean) {
        settingsStore.setPhotosEnabled(enabled)
        if (enabled) {
            if (_ui.value.library?.photos.isNullOrEmpty()) refresh()
            return
        }
        val library = _ui.value.library ?: return
        if (library.photos.isEmpty()) return
        val next = library.copy(photos = emptyList())
        _ui.update { it.copy(library = next) }
        viewModelScope.launch(Dispatchers.IO) { cache.save(next) }
    }

    fun setPin(pin: String) = encryptedStore.setPin(pin)

    fun verifyPin(pin: String): Boolean = encryptedStore.verifyPin(pin)

    fun toggleGroupFavorite(id: String) = favoritesStore.toggleGroup(id)

    fun togglePhotoFavorite(path: String) = favoritesStore.togglePhoto(path)

    fun toggleGroupPinned(id: String) = pinnedStore.toggleGroup(id)

    fun togglePhotoPinned(path: String) = pinnedStore.togglePhoto(path)

    /**
     * 只改影库显示名，硬盘上的文件/文件夹不动。
     */
    fun renameGroup(id: String, title: String) {
        val name = title.trim().take(40)
        if (name.isEmpty()) return
        val library = _ui.value.library ?: return
        val group = library.groups.firstOrNull { it.id == id } ?: return
        renames.renameGroup(id, group.title, name)
        if (group.title == name) return
        val next = library.copy(
            groups = library.groups.map { if (it.id == id) it.copy(title = name) else it }
        )
        _ui.update { it.copy(library = next) }
        viewModelScope.launch(Dispatchers.IO) { cache.save(next) }
    }

    fun renamePhoto(path: String, name: String) {
        val title = name.trim().take(40)
        if (title.isEmpty()) return
        val library = _ui.value.library ?: return
        val photo = library.photos.firstOrNull { it.path == path } ?: return
        renames.renamePhoto(path, title)
        if (photo.name == title) return
        val next = library.copy(
            photos = library.photos.map { if (it.path == path) it.copy(name = title) else it }
        )
        _ui.update { it.copy(library = next) }
        viewModelScope.launch(Dispatchers.IO) { cache.save(next) }
    }

    /**
     * 只改影库里这一集的回目，硬盘上的文件不动。
     */
    fun renameEpisode(path: String, subtitle: String) {
        val text = subtitle.trim().take(60)
        if (text.isEmpty()) return
        val library = _ui.value.library ?: return
        var found = false
        val next = library.copy(
            groups = library.groups.map { group ->
                val episodes = group.episodes.map { episode ->
                    if (episode.path == path) {
                        found = true
                        episode.copy(subtitle = text)
                    } else {
                        episode
                    }
                }
                if (episodes == group.episodes) group else group.copy(episodes = episodes)
            }
        )
        if (!found) return
        renames.renameEpisode(path, text)
        _ui.update { it.copy(library = next) }
        viewModelScope.launch(Dispatchers.IO) { cache.save(next) }
    }

    fun toggleGroupEncrypted(id: String) = encryptedStore.toggleGroup(id)

    fun togglePhotoEncrypted(path: String) = encryptedStore.togglePhoto(path)

    fun encryptGroups(ids: Collection<String>) = encryptedStore.addGroups(ids)

    fun encryptPhotos(paths: Collection<String>) = encryptedStore.addPhotos(paths)

    fun deleteGroup(id: String) {
        hiddenStore.hideGroup(id)
        favoritesStore.removeGroup(id)
        encryptedStore.removeGroup(id)
        renames.clearGroup(id)
        pinnedStore.removeGroup(id)
        val library = _ui.value.library ?: return
        library.groups.firstOrNull { it.id == id }?.episodes?.forEach { renames.clearEpisode(it.path) }
        val next = library.copy(groups = library.groups.filter { it.id != id })
        _ui.update { it.copy(library = next) }
        viewModelScope.launch(Dispatchers.IO) { cache.save(next) }
    }

    fun deletePhoto(path: String) {
        hiddenStore.hidePhoto(path)
        favoritesStore.removePhoto(path)
        encryptedStore.removePhoto(path)
        renames.clearPhoto(path)
        pinnedStore.removePhoto(path)
        val library = _ui.value.library ?: return
        val next = library.copy(photos = library.photos.filter { it.path != path })
        _ui.update { it.copy(library = next) }
        viewModelScope.launch(Dispatchers.IO) { cache.save(next) }
    }

    /**
     * 彻底删除：直接把硬盘上的源文件删掉，再从影库移除。返回删不掉的文件路径。
     */
    fun purgeGroup(id: String, onDone: (List<String>) -> Unit) {
        val group = _ui.value.library?.groups?.firstOrNull { it.id == id }
        viewModelScope.launch {
            val failed = withContext(Dispatchers.IO) {
                group?.episodes?.mapNotNull { episode ->
                    deleteFile(episode.path)?.let { episode.path }
                }.orEmpty()
            }
            group?.posterPath?.let { runCatching { File(it).delete() } }
            if (group != null && failed.isEmpty()) {
                group.episodes.forEach { renames.clearEpisode(it.path) }
                removeGroupFromLibrary(group.id)
            } else if (group != null) {
                deleteGroup(group.id)
            }
            onDone(failed)
        }
    }

    fun purgePhoto(path: String, onDone: (List<String>) -> Unit) {
        viewModelScope.launch {
            val failed = withContext(Dispatchers.IO) {
                listOfNotNull(deleteFile(path))
            }
            if (failed.isEmpty()) {
                val library = _ui.value.library ?: return@launch onDone(failed)
                val next = library.copy(photos = library.photos.filter { it.path != path })
                _ui.update { it.copy(library = next) }
                favoritesStore.removePhoto(path)
                encryptedStore.removePhoto(path)
                renames.clearPhoto(path)
                pinnedStore.removePhoto(path)
                withContext(Dispatchers.IO) { cache.save(next) }
            } else {
                deletePhoto(path)
            }
            onDone(failed)
        }
    }

    /**
     * 批量彻底删除：逐个删除源文件并从影库移除。返回删不掉的文件路径。
     * 串行处理，状态更新后统一保存一次，避免多次异步落盘互相覆盖。
     */
    fun purgeItems(
        groupIds: Collection<String>,
        photoPaths: Collection<String>,
        onDone: (List<String>) -> Unit
    ) {
        viewModelScope.launch {
            val failed = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                groupIds.forEach { id ->
                    val group = _ui.value.library?.groups?.firstOrNull { it.id == id }
                        ?: return@forEach
                    val groupFailed = group.episodes.mapNotNull { episode ->
                        deleteFile(episode.path)?.let { episode.path }
                    }
                    group.posterPath?.let { runCatching { File(it).delete() } }
                    if (groupFailed.isNotEmpty()) hiddenStore.hideGroup(id)
                    favoritesStore.removeGroup(id)
                    encryptedStore.removeGroup(id)
                    renames.clearGroup(id)
                    group.episodes.forEach { renames.clearEpisode(it.path) }
                    pinnedStore.removeGroup(id)
                    val library = _ui.value.library
                    if (library != null) {
                        _ui.update {
                            it.copy(
                                library = library.copy(
                                    groups = library.groups.filter { item -> item.id != id }
                                )
                            )
                        }
                    }
                    failed += groupFailed
                }
                photoPaths.forEach { path ->
                    val photoFailed = listOfNotNull(deleteFile(path))
                    if (photoFailed.isNotEmpty()) hiddenStore.hidePhoto(path)
                    favoritesStore.removePhoto(path)
                    encryptedStore.removePhoto(path)
                    renames.clearPhoto(path)
                    pinnedStore.removePhoto(path)
                    val library = _ui.value.library
                    if (library != null) {
                        _ui.update {
                            it.copy(
                                library = library.copy(
                                    photos = library.photos.filter { item -> item.path != path }
                                )
                            )
                        }
                    }
                    failed += photoFailed
                }
                _ui.value.library?.let { cache.save(it) }
            }
            onDone(failed)
        }
    }

    private fun deleteFile(path: String): String? = runCatching {
        val file = File(path)
        if (!file.exists() || file.delete()) null else path
    }.getOrElse { path }

    private fun removeGroupFromLibrary(id: String) {
        favoritesStore.removeGroup(id)
        encryptedStore.removeGroup(id)
        renames.clearGroup(id)
        pinnedStore.removeGroup(id)
        val library = _ui.value.library ?: return
        val next = library.copy(groups = library.groups.filter { it.id != id })
        _ui.update { it.copy(library = next) }
        viewModelScope.launch(Dispatchers.IO) { cache.save(next) }
    }

    private var scanJob: Job? = null
    private var coverJob: Job? = null
    private var uploadRescanJob: Job? = null
    private var cachedLegacyPaths: Set<String> = emptySet()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val roots = runCatching { scanner.storageRoots().map { it.absolutePath } }
                .getOrDefault(emptyList())
            cachedLegacyPaths = runCatching {
                cache.loadAnyVersion()?.let { lib ->
                    buildSet {
                        lib.groups.forEach { group ->
                            add(group.folder)
                            group.episodes.forEach { add(it.path) }
                        }
                        lib.photos.forEach { add(it.path) }
                    }
                }
            }.getOrNull().orEmpty()
            val cached = runCatching {
                cache.load()?.let { raw ->
                    val stable = IdMigration.withStableIds(raw) { group ->
                        renames.originalGroupTitle(group.id) ?: group.title
                    }
                    migrateStoredIds(stable, roots)
                    val remapped = remapCachedPaths(stable, roots)
                    val visible = renames.applyTo(
                        remapped.repairPosters().let(hiddenStore::applyTo)
                    )
                    cache.save(visible)
                    visible
                }
            }.getOrNull()
            _ui.update {
                it.copy(loadingCache = false, library = cached, roots = roots)
            }
            if (cached != null) {
                generatePosters(cached)
                refreshCovers()
            }
            if (settingsStore.uploadEnabled.value) uploads.start()
        }
    }

    fun ensureScanned() {
        viewModelScope.launch {
            _ui.first { !it.loadingCache }
            val library = _ui.value.library
            if (library == null || library.isEmpty) refresh()
        }
    }

    /**
     * 存储挂载名变化后旧版按绝对路径生成的 id 会失效，这里把加密/收藏/隐藏里的 id
     * 迁移到新 id，避免加密过的资源重新出现在普通列表里。
     */
    private fun migrateStoredIds(library: Library, roots: Collection<String>) {
        val seeds = buildSet {
            addAll(roots)
            addAll(cachedLegacyPaths)
            addAll(playback.knownPaths())
            addAll(encryptedStore.items.value.photoPaths)
            addAll(favoritesStore.favorites.value.photoPaths)
            addAll(hiddenStore.hidden.value.photoPaths)
            addAll(pinnedStore.pinned.value.photoPaths)
            listOf(library, _ui.value.library).filterNotNull().forEach { lib ->
                lib.groups.forEach { group ->
                    add(group.folder)
                    group.episodes.forEach { add(it.path) }
                }
                lib.photos.forEach { add(it.path) }
            }
        }
        val legacyRoots = IdMigration.legacyRoots(seeds)
        val ids = IdMigration.idMap(library, legacyRoots) { group ->
            renames.originalGroupTitle(group.id) ?: group.title
        }
        val photos = IdMigration.remapPaths(
            legacyRoots,
            encryptedStore.items.value.photoPaths +
                favoritesStore.favorites.value.photoPaths +
                hiddenStore.hidden.value.photoPaths +
                pinnedStore.pinned.value.photoPaths
        )
        if (ids.isEmpty() && photos.isEmpty()) return
        encryptedStore.migrate(ids, photos)
        favoritesStore.migrate(ids, photos)
        hiddenStore.migrate(ids, photos)
        pinnedStore.migrate(ids, photos)
    }

    /**
     * 缓存里存的是扫描当时的绝对路径，U 盘重新挂载后可能已经失效（/storage/x6861-FED4 → /storage/a6861-FED4）。
     * 启动时把条目和照片路径换到当前挂载下，否则直接播放会报 ERROR_CODE_IO_UNSPECIFIED。
     */
    private fun remapCachedPaths(library: Library, roots: Collection<String>): Library {
        if (roots.isEmpty()) return library
        val paths = buildSet {
            library.groups.forEach { group ->
                add(group.folder)
                group.episodes.forEach { add(it.path) }
            }
            library.photos.forEach { add(it.path) }
        }
        val map = IdMigration.remapPaths(roots, paths)
        if (map.isEmpty()) return library
        fun fix(path: String) = map[path] ?: path
        playback.migrate(map)
        renames.migrate(map)
        return library.copy(
            groups = library.groups.map { group ->
                group.copy(
                    folder = fix(group.folder),
                    episodes = group.episodes.map { it.copy(path = fix(it.path)) }
                )
            },
            photos = library.photos.map { it.copy(path = fix(it.path)) }
        )
    }

    fun refresh() {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            _ui.update { it.copy(scanning = true, error = null, progress = "正在查找存储设备…") }
            try {
                val scanned = withContext(Dispatchers.IO) {
                    val fresh = scanner.scan(includePhotos = settingsStore.photosEnabled.value) { message ->
                        _ui.update { state -> state.copy(progress = message) }
                    }
                    val roots = scanner.storageRoots().map { root -> root.absolutePath }
                    migrateStoredIds(fresh, roots)
                    val visible = renames.applyTo(hiddenStore.applyTo(fresh))
                    _ui.update { it.copy(roots = roots) }
                    cache.save(visible)
                    visible
                }
                _ui.update { it.copy(scanning = false, progress = "", library = scanned) }
                generatePosters(scanned)
                refreshCovers()
            } catch (t: Throwable) {
                _ui.update {
                    it.copy(
                        scanning = false,
                        progress = "",
                        error = t.message?.takeIf { m -> m.isNotBlank() } ?: "扫描失败，请检查存储权限"
                    )
                }
            }
        }
    }

    /**
     * 扫描完成后在后台逐个抽取视频帧做封面，不阻塞资源库展示。
     */
    private suspend fun generatePosters(library: Library) {
        val missing = library.groups.filter { group ->
            group.posterPath == null || !File(group.posterPath).exists()
        }
        if (missing.isEmpty()) return
        val semaphore = Semaphore(3)
        coroutineScope {
            missing.map { group ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val episode = group.episodes.firstOrNull() ?: return@withPermit
                        val ok = runCatching {
                            thumbnails.extractPoster(episode.path, group.id)
                        }.getOrDefault(false)
                        if (!ok) return@withPermit
                        val poster = thumbnails.posterFile(group.id).absolutePath
                        _ui.update { state ->
                            val current = state.library ?: return@update state
                            state.copy(
                                library = current.copy(
                                    groups = current.groups.map { g ->
                                        if (g.id == group.id) g.copy(posterPath = poster) else g
                                    }
                                )
                            )
                        }
                    }
                }
            }.awaitAll()
        }
        withContext(Dispatchers.IO) {
            _ui.value.library?.let { cache.save(it) }
        }
    }

    /**
     * 按片名从网络匹配海报，存到本地后通过 covers 映射覆盖卡片与详情页背景。
     * force=true 时忽略之前的失败记录，重新尝试。
     */
    fun refreshCovers(force: Boolean = false) {
        if (force) coverJob?.cancel() else if (coverJob?.isActive == true) return
        if (!settingsStore.onlineCovers.value) return
        val library = _ui.value.library ?: return
        val targets = library.groups.filter { group ->
            force || (!_covers.value.containsKey(group.id) && !coverStore.missFile(group.id).exists())
        }
        if (targets.isEmpty()) return
        coverJob = viewModelScope.launch {
            val semaphore = Semaphore(3)
            coroutineScope {
                targets.map { group ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val bytes = runCatching { coverFetcher.fetchCover(group.title) }
                                .getOrNull()
                            if (bytes == null) {
                                coverStore.markMiss(group.id)
                                return@withPermit
                            }
                            val path = coverStore.save(group.id, bytes) ?: return@withPermit
                            _covers.update { it + (group.id to path) }
                            delay(300)
                        }
                    }
                }.awaitAll()
            }
        }
    }

    fun loadCoverCandidates(id: String, onResult: (List<CoverCandidate>) -> Unit) {
        viewModelScope.launch {
            val group = _ui.value.library?.groups?.firstOrNull { it.id == id }
            if (group == null) {
                onResult(emptyList())
                return@launch
            }
            val online = runCatching { coverFetcher.fetchCandidates(group.title) }
                .getOrDefault(emptyList())
            if (online.isNotEmpty()) {
                onResult(online)
                return@launch
            }
            // 网上没有结果时先给当前海报，再从影片里补几帧，用户不用干等
            val poster = withContext(Dispatchers.IO) {
                val file = thumbnails.posterFile(id)
                runCatching {
                    if (file.exists() && file.length() > 0) file.readBytes() else null
                }.getOrNull()
            }
            val local = poster?.let { listOf(CoverCandidate(it, it)) }.orEmpty()
            if (local.isNotEmpty()) onResult(local)
            val video = group.episodes.firstOrNull()?.path ?: return@launch
            val frames = withContext(Dispatchers.IO) {
                val path = MediaPaths.resolve(getApplication(), video)
                thumbnails.frameCandidates(path).map { CoverCandidate(it, it) }
            }
            if (frames.isNotEmpty()) onResult(local + frames)
        }
    }

    fun saveCoverBytes(id: String, bytes: ByteArray) {
        viewModelScope.launch(Dispatchers.IO) {
            val path = coverStore.save(id, bytes) ?: return@launch
            _covers.update { it + (id to path) }
        }
    }

    private fun scheduleUploadRescan() {
        uploadRescanJob?.cancel()
        uploadRescanJob = viewModelScope.launch {
            delay(3_000)
            refresh()
        }
    }

    private fun Library.repairPosters(): Library {
        val currentDir = thumbnails.directory
        return copy(
            groups = groups.map { group ->
                val poster = group.posterPath
                val file = thumbnails.posterFile(group.id)
                if (poster != null && File(poster).parentFile == currentDir && File(poster).exists()) {
                    group
                } else {
                    group.copy(posterPath = if (file.exists()) file.absolutePath else null)
                }
            }
        )
    }
}
