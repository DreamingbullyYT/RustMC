package dev.horizon.launcher.ui.screens

import android.app.DownloadManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.horizon.launcher.ui.theme.HorizonColors
import dev.horizon.updater.ChecksumResult
import dev.horizon.updater.DownloadProgress
import dev.horizon.updater.HorizonUpdater
import dev.horizon.updater.ReleaseInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun UpdaterScreen(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val updater = remember(context) { HorizonUpdater(context) }
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf<ReleaseInfo?>(null) }
    var progress by remember { mutableStateOf<DownloadProgress?>(null) }
    val restoredDownload = remember(updater) { updater.restorePendingDownload() }
    var downloadId by remember { mutableStateOf(restoredDownload?.id) }
    var pendingAsset by remember { mutableStateOf(restoredDownload?.asset) }
    var verification by remember { mutableStateOf<ChecksumResult?>(null) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun checkForUpdates() {
        scope.launch {
            checking = true
            error = null
            message = null
            try {
                release = withContext(Dispatchers.IO) { updater.checkLatestRelease() }
                message = "Release găsit: ${release?.tag}"
            } catch (e: Exception) {
                error = e.message ?: "Nu s-a putut verifica updater-ul."
                release = null
            } finally {
                checking = false
            }
        }
    }

    LaunchedEffect(Unit) { checkForUpdates() }

    LaunchedEffect(downloadId, pendingAsset) {
        val id = downloadId ?: return@LaunchedEffect
        val asset = pendingAsset ?: return@LaunchedEffect
        while (true) {
            val state = withContext(Dispatchers.IO) { updater.queryDownload(id) }
            progress = state
            if (state == null || state.status == DownloadManager.STATUS_SUCCESSFUL || state.status == DownloadManager.STATUS_FAILED) {
                if (state?.status == DownloadManager.STATUS_SUCCESSFUL) {
                    try {
                        verification = withContext(Dispatchers.IO) { updater.verifyDownloadedApk(asset) }
                        message = if (verification?.verified == true) {
                            "APK descărcat și verificat SHA-256."
                        } else {
                            "APK descărcat, dar checksum-ul public lipsește sau nu corespunde. Nu îl instala."
                        }
                    } catch (e: Exception) {
                        error = e.message ?: "Verificarea APK-ului a eșuat."
                    }
                } else if (state?.status == DownloadManager.STATUS_FAILED) {
                    error = "Descărcarea a eșuat (cod ${state.reason})."
                } else if (state == null) {
                    error = "Descărcarea salvată nu mai există în Android Download Manager."
                }
                withContext(Dispatchers.IO) { updater.clearPendingDownload() }
                downloadId = null
                pendingAsset = null
                break
            }
            delay(500)
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Updates", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Launcher updates are independent from the Horizon game engine.", color = HorizonColors.TextMuted, fontSize = 13.sp)
        Card(colors = CardDefaults.cardColors(containerColor = HorizonColors.Surface), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Horizon Android", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("Installed version: 0.1.0-dev", color = HorizonColors.TextMuted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { checkForUpdates() }, enabled = !checking) {
                        Text(if (checking) "Checking…" else "Check for updates")
                    }
                    if (release != null && downloadId == null) {
                        OutlinedButton(onClick = {
                            val asset = release!!.apk
                            scope.launch {
                                try {
                                    verification = null
                                    progress = null
                                    error = null
                                    message = "Descărcarea a fost pornită în Android Download Manager."
                                    pendingAsset = asset
                                    downloadId = withContext(Dispatchers.IO) { updater.enqueueApkDownload(asset) }
                                } catch (e: Exception) {
                                    error = e.message ?: "Nu s-a putut porni descărcarea."
                                }
                            }
                        }) { Text("Download APK") }
                    }
                }
                if (release != null) {
                    Text(release!!.title, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Version ${release!!.tag}${if (release!!.isPrerelease) " · prerelease" else ""}", color = HorizonColors.AccentSecondary, fontSize = 12.sp)
                    Text(release!!.changelog, color = HorizonColors.TextMuted, fontSize = 13.sp)
                    Text("APK: ${release!!.apk.name} · ${formatBytes(release!!.apk.sizeBytes)}", color = HorizonColors.TextMuted, fontSize = 11.sp)
                    Text(
                        if (release!!.apk.sha256 != null) "SHA-256 checksum available" else "No published SHA-256 checksum — install will remain blocked",
                        color = if (release!!.apk.sha256 != null) HorizonColors.Success else HorizonColors.Warning,
                        fontSize = 11.sp
                    )
                }
            }
        }
        if (progress != null) {
            Card(colors = CardDefaults.cardColors(containerColor = HorizonColors.Surface), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Download", color = Color.White, fontWeight = FontWeight.SemiBold)
                    if (progress!!.totalBytes > 0) {
                        LinearProgressIndicator(progress = { progress!!.percent / 100f }, modifier = Modifier.fillMaxWidth())
                        Text("${progress!!.percent}% · ${formatBytes(progress!!.downloadedBytes)} / ${formatBytes(progress!!.totalBytes)}", color = HorizonColors.TextMuted, fontSize = 12.sp)
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("Waiting for download size…", color = HorizonColors.TextMuted, fontSize = 12.sp)
                    }
                    if (progress!!.status == DownloadManager.STATUS_PAUSED) Text("Paused by Android; it may resume automatically.", color = HorizonColors.Warning, fontSize = 12.sp)
                    if (progress!!.status == DownloadManager.STATUS_SUCCESSFUL) Text("Download finished.", color = HorizonColors.Success, fontSize = 12.sp)
                }
            }
        }
        if (verification != null) {
            Card(colors = CardDefaults.cardColors(containerColor = HorizonColors.Surface), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (verification!!.verified) "SHA-256 verified" else "Checksum not verified", color = if (verification!!.verified) HorizonColors.Success else HorizonColors.Warning, fontWeight = FontWeight.SemiBold)
                    Text("Actual: ${verification!!.actualSha256}", color = HorizonColors.TextMuted, fontSize = 10.sp)
                    if (verification!!.expectedSha256 != null) Text("Expected: ${verification!!.expectedSha256}", color = HorizonColors.TextMuted, fontSize = 10.sp)
                }
            }
        }
        if (message != null) Text(message!!, color = HorizonColors.Success, fontSize = 12.sp)
        if (error != null) Text(error!!, color = HorizonColors.Warning, fontSize = 12.sp)
        Text("The updater never installs an APK silently. Installation confirmation and package-signature checks will be added after the release download flow is validated.", color = HorizonColors.TextMuted, fontSize = 12.sp)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024f * 1024f))
}
