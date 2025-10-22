package com.rifsxd.ksunext.ui.screen

import android.app.Activity.RESULT_OK
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.ramcosta.composedestinations.navigation.EmptyDestinationsNavigator
import com.rifsxd.ksunext.Natives
import com.rifsxd.ksunext.R
import com.rifsxd.ksunext.ksuApp
import com.rifsxd.ksunext.ui.component.ConfirmResult
import com.rifsxd.ksunext.ui.component.SearchAppBar
import com.rifsxd.ksunext.ui.component.rememberConfirmDialog
import com.rifsxd.ksunext.ui.component.rememberLoadingDialog
import com.rifsxd.ksunext.ui.util.*
import com.rifsxd.ksunext.ui.viewmodel.KPMViewModel
import com.rifsxd.ksunext.ui.theme.getCardElevation
import androidx.compose.material3.ElevatedCard

@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun KPMScreen(navigator: DestinationsNavigator) {
    val viewModel: KPMViewModel = viewModel()
    val context = LocalContext.current
    val snackBarHost = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (viewModel.kmpList.isEmpty() || viewModel.isNeedRefresh) {
            viewModel.fetchKMPList()
        }
    }

    val isSafeMode = Natives.isSafeMode
    val isManager = Natives.becomeManager(context.packageName)
    val fullFeatured = isManager && !Natives.requireNewKernel() && rootAvailable()

    val installKMPLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                viewModel.installKMP(uri, context)
            }
        }
    }

    val loadingDialog = rememberLoadingDialog()
    val confirmDialog = rememberConfirmDialog()

    LaunchedEffect(viewModel.isLoading) {
        if (viewModel.isLoading) {
            loadingDialog.show()
        } else {
            loadingDialog.hide()
        }
    }

    LaunchedEffect(viewModel.errorMessage) {
        viewModel.errorMessage?.let { message ->
            snackBarHost.showSnackbar(message)
            viewModel.clearError()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        if (!fullFeatured) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = if (isSafeMode) {
                        "KMP functionality is disabled in safe mode"
                    } else {
                        "KMP requires root access and proper kernel support"
                    },
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        KMPList(
            navigator = navigator,
            viewModel = viewModel,
            modifier = Modifier.fillMaxSize(),
            onInstallKMP = { installKMPLauncher.launch("*/*") },
            onUnloadKMP = { kmp ->
                scope.launch {
                    val result = confirmDialog.awaitConfirm(
                        title = "Unload KMP Module",
                        content = "Are you sure you want to unload ${kmp.name}?",
                        confirmText = "Unload",
                        dismissText = "Cancel"
                    )
                    if (result == ConfirmResult.Confirmed) {
                        viewModel.unloadKMP(kmp.id)
                    }
                }
            },
            context = context,
            snackBarHost = snackBarHost,
            enabled = fullFeatured
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KMPList(
    navigator: DestinationsNavigator,
    viewModel: KPMViewModel,
    modifier: Modifier = Modifier,
    onInstallKMP: () -> Unit,
    onUnloadKMP: (KMPViewModel.KMPInfo) -> Unit,
    context: Context,
    snackBarHost: SnackbarHostState,
    enabled: Boolean = true
) {
    val listState = rememberLazyListState()
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearching by rememberSaveable { mutableStateOf(false) }

    val filteredKMPList = remember(viewModel.kmpList, searchQuery) {
        if (searchQuery.isBlank()) {
            viewModel.kmpList
        } else {
            viewModel.kmpList.filter { kmp ->
                kmp.name.contains(searchQuery, ignoreCase = true) ||
                kmp.description.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    PullToRefreshBox(
        isRefreshing = viewModel.isRefreshing,
        onRefresh = { viewModel.fetchKPMList() },
        modifier = modifier
    ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header with install button
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "KMP Modules (${viewModel.kmpList.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    
                    if (enabled) {
                        FloatingActionButton(
                            onClick = onInstallKMP,
                            modifier = Modifier.size(48.dp),
                            containerColor = MaterialTheme.colorScheme.primary
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Install KMP")
                        }
                    }
                }
            }

            // Search bar
            if (viewModel.kmpList.isNotEmpty()) {
                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Search KMP modules") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }

            // KPM modules list
            if (filteredKMPList.isEmpty() && !viewModel.isLoading) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Memory,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = if (searchQuery.isBlank()) {
                                    "No KMP modules installed"
                                } else {
                                    "No modules match your search"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = if (searchQuery.isBlank()) {
                                    "Install KMP modules to extend kernel functionality"
                                } else {
                                    "Try a different search term"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(filteredKMPList) { kmp ->
                    KMPItem(
                        kmp = kmp,
                        onUnload = { onUnloadKMP(kmp) },
                        enabled = enabled
                    )
                }
            }
        }
    }
}

@Composable
fun KMPItem(
    kmp: KMPViewModel.KMPInfo,
    onUnload: () -> Unit,
    enabled: Boolean = true
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var showDropdown by remember { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        elevation = getCardElevation()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = kmp.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Version: ${kmp.version}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Status indicator
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = if (kmp.state == "loaded") {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        }
                    ) {
                        Text(
                            text = kmp.state.uppercase(),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (kmp.state == "loaded") {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            }
                        )
                    }

                    if (enabled) {
                        Box {
                            IconButton(onClick = { showDropdown = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More options")
                            }
                            
                            DropdownMenu(
                                expanded = showDropdown,
                                onDismissRequest = { showDropdown = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Module Info") },
                                    onClick = {
                                        showDropdown = false
                                        expanded = !expanded
                                    },
                                    leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) }
                                )
                                
                                if (kmp.state == "loaded") {
                                    DropdownMenuItem(
                                        text = { Text("Unload") },
                                        onClick = {
                                            showDropdown = false
                                            onUnload()
                                        },
                                        leadingIcon = { Icon(Icons.Default.Stop, contentDescription = null) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (kmp.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = kmp.description,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    Divider()
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    InfoRow("Module ID", kmp.id.toString())
                    InfoRow("Size", formatSize(kmp.size))
                    InfoRow("Reference Count", kmp.refCount.toString())
                    
                    if (kmp.flags.isNotEmpty()) {
                        InfoRow("Flags", kmp.flags.joinToString(", "))
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

fun formatSize(size: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB")
    var sizeFloat = size.toFloat()
    var unitIndex = 0
    
    while (sizeFloat >= 1024 && unitIndex < units.size - 1) {
        sizeFloat /= 1024
        unitIndex++
    }
    
    return "%.1f %s".format(sizeFloat, units[unitIndex])
}

@Preview
@Composable
fun KMPItemPreview() {
    MaterialTheme {
        KMPItem(
            kmp = KMPViewModel.KMPInfo(
                id = 1,
                name = "Sample KMP Module",
                version = "1.0.0",
                description = "This is a sample KMP module for demonstration purposes",
                state = "loaded",
                size = 1024 * 1024,
                refCount = 2,
                flags = listOf("LIVE", "UNLOAD_OK")
            ),
            onUnload = {},
            enabled = true
        )
    }
}