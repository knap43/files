package dev.knap43.files.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.knap43.files.FilesViewModel
import dev.knap43.files.UiState
import dev.knap43.files.data.ApkInstaller
import dev.knap43.files.data.FileItem
import dev.knap43.files.data.FileKind
import dev.knap43.files.data.FileOps
import dev.knap43.files.data.SortMode
import dev.knap43.files.data.isApk
import dev.knap43.files.ui.theme.AppearanceDialog
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

private sealed interface Dialog {
    data object NewFolder : Dialog
    data class Rename(val file: File) : Dialog
    data class Delete(val files: List<File>) : Dialog
    data class AllowInstalls(val apk: File) : Dialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(vm: FilesViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<Dialog?>(null) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { ApkInstaller.results.collect { snackbar.showSnackbar(it) } }

    // APK installs: the file waiting on "Install unknown apps", and whether one is being prepared.
    var awaitingPermission by remember { mutableStateOf<File?>(null) }
    var installing by remember { mutableStateOf(false) }
    val install: (File) -> Unit = { apk ->
        if (!ApkInstaller.canInstall(context)) {
            dialog = Dialog.AllowInstalls(apk)
        } else if (!installing) {
            installing = true
            scope.launch {
                try {
                    ApkInstaller.install(context, apk)
                } catch (e: Exception) {
                    snackbar.showSnackbar("Couldn't install “${apk.name}”: ${e.message ?: e.javaClass.simpleName}")
                } finally {
                    installing = false
                }
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val apk = awaitingPermission ?: return@LifecycleEventEffect
        awaitingPermission = null
        if (ApkInstaller.canInstall(context)) install(apk)
    }

    val listState = remember(state.current.absolutePath) { LazyListState(vm.scrollFor(state.current)) }
    val navigate: (File) -> Unit = { dir ->
        vm.saveScroll(state.current, listState.firstVisibleItemIndex)
        vm.open(dir)
    }
    val openItem: (FileItem) -> Unit = { item ->
        if (item.isDir) {
            if (state.searchQuery != null) vm.closeSearch()
            navigate(item.file)
        } else if (item.file.isApk()) {
            install(item.file)
        } else if (!context.openFile(item.file)) {
            scope.launch { snackbar.showSnackbar("No app can open “${item.name}”") }
        }
    }

    val selecting = state.selection.isNotEmpty()
    val searching = state.searchQuery != null
    BackHandler(enabled = selecting || searching || vm.canGoUp()) {
        when {
            selecting -> vm.clearSelection()
            searching -> vm.closeSearch()
            else -> state.current.parentFile?.let(navigate)
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        topBar = {
            Column {
                when {
                    selecting -> SelectionBar(
                        state = state,
                        onClose = vm::clearSelection,
                        onSelectAll = vm::selectAll,
                        onCopy = { vm.copySelection(cut = false) },
                        onCut = { vm.copySelection(cut = true) },
                        onShare = { context.shareFiles(state.selection.toList()) },
                        onRename = { dialog = Dialog.Rename(state.selection.first()) },
                        onDelete = { dialog = Dialog.Delete(state.selection.toList()) },
                    )
                    searching -> SearchBar(
                        query = state.searchQuery.orEmpty(),
                        folderName = folderTitle(state, vm),
                        onQuery = vm::search,
                        onClose = vm::closeSearch,
                    )
                    else -> BrowseBar(
                        state = state,
                        title = folderTitle(state, vm),
                        canGoUp = vm.canGoUp(),
                        onUp = { state.current.parentFile?.let(navigate) },
                        onSearch = vm::startSearch,
                        onSort = vm::setSort,
                        onToggleHidden = vm::toggleHidden,
                        onNewFolder = { dialog = Dialog.NewFolder },
                        onRoot = { navigate(it) },
                        scrollBehavior = scrollBehavior,
                    )
                }
                if (!searching) Breadcrumbs(state, vm, onNavigate = navigate)
                val showProgress = state.busy != null || state.loading || state.searching || installing
                Box(Modifier.fillMaxWidth().height(2.dp)) {
                    if (showProgress) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        floatingActionButton = {
            val clip = state.clipboard
            if (clip != null && !searching) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SmallFloatingActionButton(
                        onClick = vm::clearClipboard,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) { Icon(Icons.Rounded.Close, "Cancel paste") }
                    ExtendedFloatingActionButton(
                        onClick = vm::paste,
                        icon = { Icon(Icons.Rounded.ContentPaste, null) },
                        text = { Text(if (clip.cut) "Move ${clip.files.size} here" else "Paste ${clip.files.size} here") },
                    )
                }
            } else if (!selecting && !searching) {
                FloatingActionButton(onClick = { dialog = Dialog.NewFolder }) {
                    Icon(Icons.Rounded.CreateNewFolder, "New folder")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (searching) {
                SearchResults(state, vm, onOpen = openItem)
            } else {
                FileList(
                    state = state,
                    listState = listState,
                    onClick = { item -> if (selecting) vm.toggleSelect(item.file) else openItem(item) },
                    onLongClick = { item -> vm.toggleSelect(item.file) },
                )
            }
        }
    }

    when (val d = dialog) {
        Dialog.NewFolder -> NameDialog(
            title = "New folder",
            initial = "",
            confirm = "Create",
            onDismiss = { dialog = null },
            onConfirm = { vm.createFolder(it); dialog = null },
        )
        is Dialog.Rename -> NameDialog(
            title = "Rename",
            initial = d.file.name,
            confirm = "Rename",
            selectBaseName = !d.file.isDirectory,
            onDismiss = { dialog = null },
            onConfirm = { vm.rename(d.file, it); dialog = null },
        )
        is Dialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            icon = { Icon(Icons.Rounded.Delete, null) },
            title = { Text("Delete ${if (d.files.size == 1) "“${d.files[0].name}”" else "${d.files.size} items"}?") },
            text = { Text("This can't be undone. Folders are deleted with everything inside them.") },
            confirmButton = {
                TextButton(onClick = { vm.delete(d.files); dialog = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        is Dialog.AllowInstalls -> AlertDialog(
            onDismissRequest = { dialog = null },
            icon = { Icon(Icons.Rounded.Android, null) },
            title = { Text("Allow installing apps?") },
            text = {
                Text(
                    "To install “${d.apk.name}”, turn on “Allow from this source” for Files. " +
                        "The installation continues when you come back.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    awaitingPermission = d.apk
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
                        )
                    } catch (_: ActivityNotFoundException) {
                        awaitingPermission = null
                        scope.launch { snackbar.showSnackbar("This device has no setting for installing apps") }
                    }
                }) { Text("Open settings") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        null -> Unit
    }
}

private fun folderTitle(state: UiState, vm: FilesViewModel): String {
    val root = vm.rootFor(state.current)
    return if (root != null && root.dir.absolutePath == state.current.absolutePath) root.label else state.current.name.ifEmpty { "/" }
}

// ---- Top bars -------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowseBar(
    state: UiState,
    title: String,
    canGoUp: Boolean,
    onUp: () -> Unit,
    onSearch: () -> Unit,
    onSort: (SortMode) -> Unit,
    onToggleHidden: () -> Unit,
    onNewFolder: () -> Unit,
    onRoot: (File) -> Unit,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
) {
    var sortOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    var appearanceOpen by remember { mutableStateOf(false) }
    if (appearanceOpen) AppearanceDialog(onDismiss = { appearanceOpen = false })
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold) },
        navigationIcon = {
            if (canGoUp) IconButton(onClick = onUp) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Up") }
        },
        actions = {
            IconButton(onClick = onSearch) { Icon(Icons.Rounded.Search, "Search") }
            Box {
                IconButton(onClick = { sortOpen = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, "Sort") }
                DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                    SortMode.entries.forEach { mode ->
                        val active = state.sort == mode
                        DropdownMenuItem(
                            text = {
                                Text(
                                    mode.label,
                                    color = if (active) MaterialTheme.colorScheme.primary else Color.Unspecified,
                                )
                            },
                            trailingIcon = {
                                if (active) Icon(
                                    if (state.ascending) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            onClick = { onSort(mode); sortOpen = false },
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { moreOpen = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("New folder") },
                        leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, null) },
                        onClick = { moreOpen = false; onNewFolder() },
                    )
                    DropdownMenuItem(
                        text = { Text(if (state.showHidden) "Hide hidden files" else "Show hidden files") },
                        leadingIcon = {
                            Icon(if (state.showHidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null)
                        },
                        onClick = { moreOpen = false; onToggleHidden() },
                    )
                    DropdownMenuItem(
                        text = { Text("Appearance") },
                        leadingIcon = { Icon(Icons.Rounded.Palette, null) },
                        onClick = { moreOpen = false; appearanceOpen = true },
                    )
                    if (state.roots.size > 1) {
                        HorizontalDivider()
                        state.roots.forEach { root ->
                            DropdownMenuItem(
                                text = { Text(root.label) },
                                leadingIcon = {
                                    Icon(if (root.removable) Icons.Rounded.SdCard else Icons.Rounded.PhoneAndroid, null)
                                },
                                onClick = { moreOpen = false; onRoot(root.dir) },
                            )
                        }
                    }
                }
            }
        },
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(
    state: UiState,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var moreOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text("${state.selection.size} selected") },
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Clear selection") } },
        actions = {
            IconButton(onClick = onCopy) { Icon(Icons.Rounded.ContentCopy, "Copy") }
            IconButton(onClick = onCut) { Icon(Icons.Rounded.ContentCut, "Cut") }
            IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, "Delete") }
            Box {
                IconButton(onClick = { moreOpen = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                    if (state.selection.size == 1) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            leadingIcon = { Icon(Icons.Rounded.DriveFileRenameOutline, null) },
                            onClick = { moreOpen = false; onRename() },
                        )
                    }
                    if (state.selection.any { it.isFile }) {
                        DropdownMenuItem(
                            text = { Text("Share") },
                            leadingIcon = { Icon(Icons.Rounded.Share, null) },
                            onClick = { moreOpen = false; onShare() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Select all") },
                        leadingIcon = { Icon(Icons.Rounded.SelectAll, null) },
                        onClick = { moreOpen = false; onSelectAll() },
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(query: String, folderName: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close search") } },
        title = {
            TextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text("Search in $folderName", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
        },
        actions = {
            if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, "Clear") }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    )
}

@Composable
private fun Breadcrumbs(state: UiState, vm: FilesViewModel, onNavigate: (File) -> Unit) {
    val root = vm.rootFor(state.current)
    val chain = remember(state.current.absolutePath) {
        buildList {
            var f: File? = state.current
            while (f != null) {
                add(f)
                if (root != null && f.absolutePath == root.dir.absolutePath) break
                f = f.parentFile
            }
        }.reversed()
    }
    val rowState = rememberLazyListState()
    LaunchedEffect(chain.size) { if (chain.isNotEmpty()) rowState.animateScrollToItem(chain.lastIndex) }

    LazyRow(
        state = rowState,
        contentPadding = PaddingValues(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(40.dp),
    ) {
        itemsIndexed(chain) { i, dir ->
            val last = i == chain.lastIndex
            val label = if (i == 0 && root != null) root.label else dir.name.ifEmpty { "/" }
            TextButton(onClick = { if (!last) onNavigate(dir) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (last) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (!last) {
                Icon(
                    Icons.Rounded.ChevronRight,
                    null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

// ---- Lists ----------------------------------------------------------------------------

@Composable
private fun FileList(
    state: UiState,
    listState: LazyListState,
    onClick: (FileItem) -> Unit,
    onLongClick: (FileItem) -> Unit,
) {
    when {
        state.error != null -> EmptyState(Icons.Rounded.FolderOff, state.error)
        state.items.isEmpty() && !state.loading -> EmptyState(Icons.Rounded.Folder, "This folder is empty")
        else -> LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 96.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(state.items, key = { it.file.absolutePath }) { item ->
                FileRow(
                    item = item,
                    selected = item.file in state.selection,
                    subtitle = subtitleFor(item),
                    onClick = { onClick(item) },
                    onLongClick = { onLongClick(item) },
                )
            }
        }
    }
}

@Composable
private fun SearchResults(state: UiState, vm: FilesViewModel, onOpen: (FileItem) -> Unit) {
    val query = state.searchQuery.orEmpty()
    when {
        query.isBlank() -> EmptyState(Icons.Rounded.Search, "Type to search this folder and everything below it")
        state.searchResults.isEmpty() && state.searching -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            CircularProgressIndicator()
        }
        state.searchResults.isEmpty() -> EmptyState(Icons.Rounded.Search, "Nothing matches “$query”")
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(state.searchResults, key = { it.file.absolutePath }) { item ->
                val parent = item.file.parentFile
                val where = parent?.let { p ->
                    val root = vm.rootFor(p)
                    if (root != null) root.label + p.absolutePath.removePrefix(root.dir.absolutePath) else p.absolutePath
                }.orEmpty()
                FileRow(
                    item = item,
                    selected = false,
                    subtitle = where,
                    onClick = { onOpen(item) },
                    onLongClick = { onOpen(item) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    item: FileItem,
    selected: Boolean,
    subtitle: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = ListItemDefaults.colors(
            containerColor = if (selected) colors.primaryContainer.copy(alpha = 0.55f) else Color.Transparent,
        ),
        leadingContent = { FileIcon(item, selected) },
        headlineContent = {
            Text(
                item.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (item.name.startsWith(".")) colors.onSurfaceVariant else colors.onSurface,
            )
        },
        supportingContent = {
            Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant)
        },
    )
}

@Composable
private fun FileIcon(item: FileItem, selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    val kind = FileOps.kind(item)
    val (bg, fg) = when {
        selected -> colors.primary to colors.onPrimary
        kind == FileKind.DIR -> colors.primary.copy(alpha = 0.16f) to colors.primary
        else -> colors.surfaceContainerHigh to colors.onSurfaceVariant
    }
    Box(
        modifier = Modifier.size(42.dp).background(bg, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (selected) Icons.Rounded.Check else kind.icon(), null, tint = fg, modifier = Modifier.size(22.dp))
    }
}

private fun FileKind.icon(): ImageVector = when (this) {
    FileKind.DIR -> Icons.Rounded.Folder
    FileKind.IMAGE -> Icons.Rounded.Image
    FileKind.VIDEO -> Icons.Rounded.Movie
    FileKind.AUDIO -> Icons.Rounded.MusicNote
    FileKind.PDF -> Icons.Rounded.PictureAsPdf
    FileKind.ARCHIVE -> Icons.Rounded.FolderZip
    FileKind.APK -> Icons.Rounded.Android
    FileKind.TEXT -> Icons.Rounded.Description
    FileKind.OTHER -> Icons.Rounded.InsertDriveFile
}

@Composable
private fun subtitleFor(item: FileItem): String {
    val context = LocalContext.current
    val date = remember(item.modified) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.modified))
    }
    val left = if (item.isDir) {
        val n = item.childCount ?: 0
        if (n == 1) "1 item" else "$n items"
    } else {
        Formatter.formatShortFileSize(context, item.size)
    }
    return "$left  ·  $date"
}

@Composable
private fun EmptyState(icon: ImageVector, text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(56.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

// ---- Dialogs --------------------------------------------------------------------------

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    selectBaseName: Boolean = false,
) {
    val end = if (selectBaseName && initial.lastIndexOf('.') > 0) initial.lastIndexOf('.') else initial.length
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, end))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val valid = value.text.isNotBlank() && '/' !in value.text && value.text != initial

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text("Name") },
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.text) }, enabled = valid) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
