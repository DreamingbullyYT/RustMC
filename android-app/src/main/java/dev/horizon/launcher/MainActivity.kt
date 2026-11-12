package dev.horizon.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF0B0D12)
private val Panel = Color(0xFF151923)
private val Muted = Color(0xFF9AA4B5)
private val Accent = Color(0xFF8B9CFF)
private val Good = Color(0xFF67D6A3)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HorizonApp() }
    }
}

@Composable
private fun HorizonApp() {
    var selectedTab by remember { mutableIntStateOf(0) }
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Accent,
        background = Ink,
        surface = Panel,
        onBackground = Color.White,
        onSurface = Color.White
    )) {
        Scaffold(
            containerColor = Ink,
            bottomBar = {
                NavigationBar(containerColor = Panel) {
                    listOf("Home", "Instances", "Mods", "Settings").forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = { Text(listOf("⌂", "▦", "◇", "⚙")[index], fontSize = 19.sp) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        ) { insets ->
            when (selectedTab) {
                0 -> HomeScreen(Modifier.padding(insets))
                1 -> PlaceholderScreen("Instances", "Instance management will live here.", Modifier.padding(insets))
                2 -> PlaceholderScreen("Mods & shaders", "HNMod, HMod and HShader management is not connected yet.", Modifier.padding(insets))
                else -> PlaceholderScreen("Settings", "Renderer, controls, storage and toolchain settings will be added here.", Modifier.padding(insets))
            }
        }
    }
}

@Composable
private fun HomeScreen(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 26.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("HORIZON", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                Text("Launcher", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
            Box(
                Modifier.background(Color(0xFF19372F), CircleShape).padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Text("ANDROID · DEV", color = Good, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Panel),
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("YOUR GAME", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text("Minecraft", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Text("The Android runtime is not connected yet.", color = Muted, fontSize = 13.sp)
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(disabledContainerColor = Color(0xFF343B4D))
                ) {
                    Text("PLAY  ·  NOT READY", fontWeight = FontWeight.Bold)
                }
            }
        }

        Text("PROJECT STATUS", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        StatusCard("Android app shell", "Compose interface is active", "READY", Good)
        StatusCard("Native engine bridge", "JNI and game runtime are not linked", "PENDING", Accent)
        StatusCard("Mod compatibility", "Waiting for the upstream integration layer", "PENDING", Accent)
        Spacer(Modifier.height(2.dp))
        Text(
            "Built to keep Android code separate from the upstream Horizon engine.",
            color = Muted,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
    }
}

@Composable
private fun StatusCard(title: String, detail: String, status: String, tint: Color) {
    Surface(
        color = Panel,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(detail, color = Muted, fontSize = 11.sp)
            }
            Text(status, color = tint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PlaceholderScreen(title: String, description: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(description, color = Muted, fontSize = 14.sp)
        Text("This section is intentionally a placeholder until its service contract is implemented.", color = Muted, fontSize = 12.sp)
    }
}
