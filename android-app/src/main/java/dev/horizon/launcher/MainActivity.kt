package dev.horizon.launcher

import android.os.Bundle
import java.io.File
import dev.horizon.launcher.data.Updater
import java.io.File
import dev.horizon.launcher.data.Core
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import dev.horizon.launcher.ui.screens.FileManagerScreen
import dev.horizon.launcher.ui.screens.HomeScreen
import dev.horizon.launcher.ui.screens.InstancesScreen
import dev.horizon.launcher.ui.screens.ModsScreen
import dev.horizon.launcher.ui.screens.TouchControlsScreen
import dev.horizon.launcher.ui.screens.UpdaterScreen
import dev.horizon.launcher.ui.theme.HorizonColors
import dev.horizon.launcher.ui.theme.HorizonTheme

class MainActivity : ComponentActivity() {
    private var coreStatus: String = "Core not initialized"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val home = File(filesDir, "launcher").apply { mkdirs() }
        val setupError = runCatching {
            Updater.prepare(filesDir, this)
            val gameHome = File(home, "game").apply { mkdirs() }
            val resourceError = runCatching { Updater.syncEngineData(this, gameHome) }.exceptionOrNull()?.message
            Core.init(home.absolutePath) ?: resourceError
        }.getOrElse { it.message ?: it.javaClass.simpleName }
        Updater.armGuard(filesDir, "launcher")
        coreStatus = when {
            Core.loadError != null -> "Core unavailable: ${Core.loadError}"
            setupError != null -> "Core setup error: $setupError"
            else -> "Core ${Core.coreVersion()} connected"
        }
        setContent { HorizonApp(coreStatus) }
    }
}

@Composable
private fun HorizonApp(coreStatus: String) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Home", "Instances", "Mods", "Controls", "Files", "Settings")
    val icons = listOf("⌂", "▦", "◇", "◎", "▤", "⚙")
    HorizonTheme {
        Scaffold(
            containerColor = HorizonColors.Background,
            bottomBar = {
                NavigationBar(containerColor = HorizonColors.Surface) {
                    tabs.forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = {
                                Text(
                                    icons[index],
                                    fontSize = 19.sp,
                                    color = if (selectedTab == index) HorizonColors.Accent else Color.Gray
                                )
                            },
                            label = { Text(label) }
                        )
                    }
                }
            }
        ) { insets ->
            when (selectedTab) {
                0 -> HomeScreen(Modifier.padding(insets), coreStatus)
                1 -> InstancesScreen(Modifier.padding(insets))
                2 -> ModsScreen(Modifier.padding(insets))
                3 -> TouchControlsScreen(Modifier.padding(insets))
                4 -> FileManagerScreen(Modifier.padding(insets))
                else -> UpdaterScreen(Modifier.padding(insets))
            }
        }
    }
}
