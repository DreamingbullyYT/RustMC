package dev.horizon.launcher

import android.os.Bundle
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
import dev.horizon.launcher.ui.screens.PlaceholderScreen
import dev.horizon.launcher.ui.screens.UpdaterScreen
import dev.horizon.launcher.ui.theme.HorizonColors
import dev.horizon.launcher.ui.theme.HorizonTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HorizonApp() }
    }
}

@Composable
private fun HorizonApp() {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Home", "Instances", "Mods", "Files", "Settings")
    val icons = listOf("⌂", "▦", "◇", "▤", "⚙")
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
                0 -> HomeScreen(Modifier.padding(insets))
                1 -> InstancesScreen(Modifier.padding(insets))
                2 -> PlaceholderScreen("Mods & shaders", "HNMod, HMod and HShader will have separate compatibility and install flows.", Modifier.padding(insets))
                3 -> FileManagerScreen(Modifier.padding(insets))
                else -> UpdaterScreen(Modifier.padding(insets))
            }
        }
    }
}
