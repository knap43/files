package dev.knap43.files

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.knap43.files.data.FileItem
import dev.knap43.files.data.FileOps
import dev.knap43.files.data.FileOps.toItem
import dev.knap43.files.data.SortMode
import dev.knap43.files.data.StorageRoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class Clipboard(val files: List<File>, val cut: Boolean)

data class UiState(
    val current: File,
    val roots: List<StorageRoot>,
    val items: List<FileItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val selection: Set<File> = emptySet(),
    val sort: SortMode = SortMode.NAME,
    val ascending: Boolean = true,
    val showHidden: Boolean = false,
    val clipboard: Clipboard? = null,
    val searchQuery: String? = null,
    val searchResults: List<FileItem> = emptyList(),
    val searching: Boolean = false,
    val busy: String? = null,
)

class FilesViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("files", Context.MODE_PRIVATE)
    private val roots = FileOps.roots(app)

    private val _state = MutableStateFlow(
        UiState(
            current = roots.first().dir,
            roots = roots,
            sort = runCatching { SortMode.valueOf(prefs.getString("sort", null) ?: "NAME") }.getOrDefault(SortMode.NAME),
            ascending = prefs.getBoolean("ascending", true),
            showHidden = prefs.getBoolean("hidden", false),
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private val scrollPositions = mutableMapOf<String, Int>()
    private var loadJob: Job? = null
    private var searchJob: Job? = null

    init {
        load()
    }

    // ---- Navigation ---------------------------------------------------------------

    fun rootFor(dir: File): StorageRoot? =
        roots.filter { FileOps.isInside(dir, it.dir) }.maxByOrNull { it.dir.absolutePath.length }

    fun canGoUp(): Boolean {
        val cur = _state.value.current
        val root = rootFor(cur) ?: return cur.parentFile != null
        return cur.canonicalPath != root.dir.canonicalPath
    }

    fun open(dir: File) {
        _state.update { it.copy(current = dir, selection = emptySet()) }
        load()
    }

    fun up() {
        if (canGoUp()) _state.value.current.parentFile?.let(::open)
    }

    fun saveScroll(dir: File, index: Int) {
        scrollPositions[dir.absolutePath] = index
    }

    fun scrollFor(dir: File): Int = scrollPositions[dir.absolutePath] ?: 0

    fun refresh() = load()

    private fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val s = _state.value
            _state.update { it.copy(loading = true, error = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching { FileOps.sort(FileOps.list(s.current, s.showHidden), s.sort, s.ascending) }
            }
            _state.update {
                it.copy(
                    loading = false,
                    items = result.getOrDefault(emptyList()),
                    error = result.exceptionOrNull()?.message,
                )
            }
        }
    }

    // ---- View options -------------------------------------------------------------

    fun setSort(mode: SortMode) {
        val s = _state.value
        val ascending = if (s.sort == mode) !s.ascending else s.ascending
        prefs.edit().putString("sort", mode.name).putBoolean("ascending", ascending).apply()
        _state.update { it.copy(sort = mode, ascending = ascending, items = FileOps.sort(it.items, mode, ascending)) }
    }

    fun toggleHidden() {
        val hidden = !_state.value.showHidden
        prefs.edit().putBoolean("hidden", hidden).apply()
        _state.update { it.copy(showHidden = hidden) }
        load()
    }

    // ---- Selection ----------------------------------------------------------------

    fun toggleSelect(file: File) = _state.update {
        it.copy(selection = if (file in it.selection) it.selection - file else it.selection + file)
    }

    fun selectAll() = _state.update { it.copy(selection = it.items.map { i -> i.file }.toSet()) }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    // ---- Clipboard ----------------------------------------------------------------

    fun copySelection(cut: Boolean) {
        val files = _state.value.selection.toList()
        if (files.isEmpty()) return
        _state.update { it.copy(clipboard = Clipboard(files, cut), selection = emptySet()) }
        say("${files.size} ${plural(files.size)} ${if (cut) "cut" else "copied"} — open a folder and paste")
    }

    fun clearClipboard() = _state.update { it.copy(clipboard = null) }

    fun paste() {
        val clip = _state.value.clipboard ?: return
        val dest = _state.value.current
        runOp(if (clip.cut) "Moving…" else "Copying…") {
            var done = 0
            for (src in clip.files) {
                if (!src.exists()) continue
                if (src.isDirectory && FileOps.isInside(dest, src)) {
                    say("Can't put “${src.name}” inside itself")
                    continue
                }
                if (clip.cut && src.parentFile?.canonicalPath == dest.canonicalPath) continue
                if (clip.cut) FileOps.moveInto(src, dest) else FileOps.copyInto(src, dest)
                done++
            }
            _state.update { it.copy(clipboard = null) }
            "${if (clip.cut) "Moved" else "Copied"} $done ${plural(done)}"
        }
    }

    // ---- File operations ----------------------------------------------------------

    fun delete(files: Collection<File>) = runOp("Deleting…") {
        val failed = files.count { !it.deleteRecursively() }
        _state.update { it.copy(selection = emptySet()) }
        if (failed == 0) "Deleted ${files.size} ${plural(files.size)}" else "Couldn't delete $failed ${plural(failed)}"
    }

    fun rename(file: File, newName: String) = runOp(null) {
        val name = newName.trim()
        require(name.isNotEmpty() && '/' !in name) { "That name isn't allowed" }
        val target = File(file.parentFile, name)
        require(!target.exists()) { "“$name” already exists" }
        check(file.renameTo(target)) { "Rename failed" }
        _state.update { it.copy(selection = emptySet()) }
        null
    }

    fun createFolder(name: String) = runOp(null) {
        val n = name.trim()
        require(n.isNotEmpty() && '/' !in n) { "That name isn't allowed" }
        val dir = File(_state.value.current, n)
        require(!dir.exists()) { "“$n” already exists" }
        check(dir.mkdir()) { "Couldn't create the folder" }
        null
    }

    /** Runs [block] off the main thread, shows its message (or error), then reloads. */
    private fun runOp(busy: String?, block: suspend () -> String?) {
        viewModelScope.launch {
            _state.update { it.copy(busy = busy) }
            val result = withContext(Dispatchers.IO) { runCatching { block() } }
            _state.update { it.copy(busy = null) }
            result.onSuccess { msg -> msg?.let(::say) }
                .onFailure { e -> say(e.message ?: "Something went wrong") }
            load()
        }
    }

    // ---- Search -------------------------------------------------------------------

    fun startSearch() = _state.update { it.copy(searchQuery = "", searchResults = emptyList(), selection = emptySet()) }

    fun closeSearch() {
        searchJob?.cancel()
        _state.update { it.copy(searchQuery = null, searchResults = emptyList(), searching = false) }
    }

    fun search(query: String) {
        _state.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(searchResults = emptyList(), searching = false) }
            return
        }
        val base = _state.value.current
        val hidden = _state.value.showHidden
        searchJob = viewModelScope.launch {
            delay(250)
            _state.update { it.copy(searching = true) }
            val results = withContext(Dispatchers.IO) {
                val out = ArrayList<FileItem>()
                base.walkTopDown()
                    .onEnter { hidden || !it.name.startsWith(".") || it == base }
                    .forEach { f ->
                        ensureActive()
                        if (f != base && f.name.contains(query, ignoreCase = true) &&
                            (hidden || !f.name.startsWith("."))
                        ) {
                            out += f.toItem(hidden)
                            if (out.size >= MAX_RESULTS) return@withContext out
                        }
                    }
                out
            }
            _state.update {
                it.copy(searchResults = FileOps.sort(results, SortMode.NAME, true), searching = false)
            }
        }
    }

    private fun say(msg: String) {
        _messages.trySend(msg)
    }

    private fun plural(n: Int) = if (n == 1) "item" else "items"

    companion object {
        private const val MAX_RESULTS = 500
    }
}
