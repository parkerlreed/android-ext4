package io.github.parkerlreed.usbext4mount

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Eject
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.UsbOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MountViewModel) {
    val snackbar = remember { SnackbarHostState() }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    vm.message?.let { message ->
        LaunchedEffect(message) {
            val result = snackbar.showSnackbar(
                message = message.text,
                actionLabel = if (message.offerForce) "Force" else null,
                withDismissAction = !message.offerForce,
                duration = if (message.offerForce) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) vm.unmount(force = true)
            vm.messageShown()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            Column {
                LargeTopAppBar(
                    title = { Text("USB ext4 Mount") },
                    actions = {
                        IconButton(onClick = vm::refresh, enabled = vm.busy == null) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
                        }
                    },
                    scrollBehavior = scroll,
                )
                // Keep the height constant so content doesn't jump when work starts.
                Box(Modifier.height(4.dp)) {
                    if (vm.busy != null) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        bottomBar = { ActionBar(vm) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StatusCard(vm) }
            item { SectionHeader("Drives") }
            val error = vm.rootError
            when {
                error != null -> item { RootErrorCard(error) }
                vm.partitions.isEmpty() -> item { EmptyCard(scanning = vm.busy != null) }
                else -> items(vm.partitions, key = { it.device }) { partition ->
                    PartitionCard(
                        partition = partition,
                        selected = partition.device == vm.selectedDevice,
                        onClick = { vm.selectedDevice = partition.device },
                    )
                }
            }
            item { SectionHeader("Settings") }
            item { SettingsCard(vm) }
            item { LogCard(vm.log) }
        }
    }
}

@Composable
private fun StatusCard(vm: MountViewModel) {
    val mounted = vm.mounted
    val colors = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (mounted != null) colors.primaryContainer else colors.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = if (mounted != null) colors.primary else colors.surfaceContainerHighest,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (mounted != null) Icons.Rounded.CheckCircle else Icons.Rounded.UsbOff,
                            contentDescription = null,
                            tint = if (mounted != null) colors.onPrimary else colors.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        if (mounted != null) "Mounted" else "Not mounted",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        if (mounted != null) "${mounted.displayName} at ${vm.mountPoint.trim()}"
                        else vm.mountPoint.trim(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (mounted != null) colors.onPrimaryContainer else colors.onSurfaceVariant,
                    )
                }
            }
            val usage = vm.usage
            if (mounted != null && usage != null) {
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { usage.percent / 100f },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    drawStopIndicator = {},
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "${usage.used} used of ${usage.size}  •  ${usage.available} free",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun PartitionCard(partition: Partition, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) colors.secondaryContainer else colors.surfaceContainer,
        ),
        border = if (selected) BorderStroke(2.dp, colors.secondary) else null,
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { RadioButton(selected = selected, onClick = onClick) },
            headlineContent = { Text(partition.displayName) },
            supportingContent = {
                val parts = listOfNotNull(
                    partition.name.takeIf { partition.label.isNotEmpty() },
                    formatSize(partition.bytes),
                    partition.type.takeIf { it != "-" } ?: "unknown",
                )
                Text(parts.joinToString("  •  "))
            },
            trailingContent = {
                if (partition.mountPoint.isNotEmpty()) {
                    Text(
                        partition.mountPoint,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.primary,
                    )
                } else if (!partition.isExt) {
                    Text("Not ext", style = MaterialTheme.typography.labelMedium, color = colors.error)
                }
            },
        )
    }
}

@Composable
private fun EmptyCard(scanning: Boolean) {
    MessageCard(
        icon = Icons.Rounded.Usb,
        title = if (scanning) "Looking for drives…" else "No USB drive detected",
        body = "Connect an ext4 drive with a USB OTG adapter, then tap refresh.",
    )
}

@Composable
private fun RootErrorCard(error: String) {
    MessageCard(
        icon = Icons.Rounded.ErrorOutline,
        title = "Root access needed",
        body = "Allow superuser for this app in KernelSU or Magisk, then tap refresh.\n\n$error",
        error = true,
    )
}

@Composable
private fun MessageCard(icon: ImageVector, title: String, body: String, error: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (error) colors.errorContainer else colors.surfaceContainer,
            contentColor = if (error) colors.onErrorContainer else colors.onSurface,
        ),
    ) {
        Row(Modifier.padding(20.dp)) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun SettingsCard(vm: MountViewModel) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(vertical = 8.dp)) {
            OutlinedTextField(
                value = vm.mountPoint,
                onValueChange = vm::updateMountPoint,
                label = { Text("Mount point") },
                leadingIcon = { Icon(Icons.Rounded.Folder, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            OutlinedTextField(
                value = vm.options,
                onValueChange = vm::updateOptions,
                label = { Text("Mount options") },
                leadingIcon = { Icon(Icons.Rounded.Tune, contentDescription = null) },
                supportingText = { Text("Passed to mount -o") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SwitchRow(
                title = "Check filesystem first",
                subtitle = "Runs e2fsck -p if the ROM has it",
                checked = vm.fsck,
                onCheckedChange = vm::updateFsck,
            )
            SwitchRow(
                title = "Show in /sdcard",
                subtitle = "Media apps can read the drive at /sdcard/${MountViewModel.EXPOSE_NAME}",
                checked = vm.expose,
                onCheckedChange = vm::updateExpose,
            )
            if (vm.expose) {
                SwitchRow(
                    title = "Index for media apps",
                    subtitle = "Rescan after mounting so galleries and pickers list the drive. Slow on drives with many files",
                    checked = vm.indexMedia,
                    onCheckedChange = vm::updateIndexMedia,
                )
            }
            SwitchRow(
                title = "Auto-mount on attach",
                subtitle = "Mounts the first ext partition when a drive is plugged in",
                checked = vm.autoMount,
                onCheckedChange = vm::updateAutoMount,
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun LogCard(log: List<String>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(
        onClick = { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text("Log") },
            supportingContent = { Text(if (log.isEmpty()) "No commands run yet" else "${log.size} lines") },
            trailingContent = {
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
            },
        )
        AnimatedVisibility(expanded && log.isNotEmpty()) {
            SelectionContainer {
                Text(
                    log.joinToString("\n"),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    softWrap = false,
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@Composable
private fun ActionBar(vm: MountViewModel) {
    val idle = vm.busy == null
    val mounted = vm.mounted
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .padding(16.dp),
        ) {
            OutlinedButton(
                onClick = { vm.unmount() },
                enabled = idle && mounted != null,
                modifier = Modifier.weight(1f).height(52.dp),
            ) {
                Icon(Icons.Rounded.Eject, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Unmount")
            }
            val selected = vm.selected
            Button(
                onClick = vm::mountSelected,
                enabled = idle && selected != null && selected.mountPoint.isEmpty() && mounted == null,
                modifier = Modifier.weight(1f).height(52.dp),
            ) {
                Icon(Icons.Rounded.Save, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Mount")
            }
        }
    }
}

private val Partition.displayName get() = label.ifEmpty { name }

private fun formatSize(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) {
        value /= 1000
        unit++
    }
    return String.format(Locale.US, if (unit == 0) "%.0f %s" else "%.1f %s", value, units[unit])
}
