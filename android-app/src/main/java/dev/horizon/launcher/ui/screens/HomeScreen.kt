package dev.horizon.launcher.ui.screens

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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.horizon.launcher.GameSetup
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.horizon.launcher.ui.theme.HorizonColors

@Composable
fun HomeScreen(modifier: Modifier = Modifier, coreStatus: String = "Core not initialized") {
    val context = LocalContext.current
    var gameReady by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var installStatus by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(coreStatus) {
        gameReady = coreStatus.contains("connected") && runCatching { GameSetup.ready() }.getOrDefault(false)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(HorizonColors.Background)
            .padding(horizontal = 22.dp, vertical = 26.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "HORIZON",
                    color = HorizonColors.TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp
                )
                Text(
                    "Ready to play",
                    color = Color.White,
                    fontSize = 29.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Box(
                Modifier
                    .background(Color(0xFF19372F), CircleShape)
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Text("ANDROID DEV", color = HorizonColors.Success, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = HorizonColors.Surface),
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                Text("SELECTED INSTANCE", color = HorizonColors.TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text("Minecraft", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(if (gameReady) "Minecraft is installed and ready" else "Minecraft is not installed yet", color = HorizonColors.TextMuted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoPill("VANILLA")
                    InfoPill("ARM64")
                    InfoPill("VULKAN")
                }
                Button(
                    onClick = {
                        if (gameReady) {
                            GameSetup.play(context).onFailure { installStatus = "Could not open game: ${it.message}" }
                        } else {
                            scope.launch {
                                installing = true
                                installStatus = "Preparing Minecraft installation…"
                                try {
                                    GameSetup.install(context) { installStatus = it }
                                    gameReady = GameSetup.ready()
                                    installStatus = if (gameReady) "Minecraft is ready to play." else "Install finished, but Minecraft is not ready."
                                } catch (error: Throwable) {
                                    installStatus = "Install failed: ${error.message ?: error.javaClass.simpleName}"
                                } finally {
                                    installing = false
                                }
                            }
                        }
                    },
                    enabled = coreStatus.contains("connected") && !installing,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(disabledContainerColor = Color(0xFF343B4D))
                ) {
                    Text(
                        when {
                            installing -> "INSTALLING…"
                            gameReady -> "PLAY"
                            else -> "INSTALL MINECRAFT"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
                if (installStatus.isNotBlank()) {
                    Text(installStatus, color = HorizonColors.TextMuted, fontSize = 11.sp)
                }
            }
        }

        Text("SYSTEM STATUS", color = HorizonColors.TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        StatusCard("Launcher interface", "Jetpack Compose shell is built", "READY", HorizonColors.Success)
        StatusCard("Native game runtime", "Bundled engine build is being validated", "BUILDING", HorizonColors.Warning)
        StatusCard("Horizon launcher core", coreStatus, if (coreStatus.contains("connected")) "READY" else "PENDING", if (coreStatus.contains("connected")) HorizonColors.Success else HorizonColors.Warning)
        StatusCard("Mod compatibility", "Engine and HNMod API validation pending", "PENDING", HorizonColors.Warning)

        Spacer(Modifier.height(2.dp))
        Text(
            "Android features stay isolated from the upstream game engine.",
            color = HorizonColors.TextMuted,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
    }
}

@Composable
private fun InfoPill(label: String) {
    Surface(color = HorizonColors.SurfaceElevated, shape = RoundedCornerShape(50)) {
        Text(label, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = HorizonColors.TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatusCard(title: String, detail: String, status: String, tint: Color) {
    Surface(color = HorizonColors.Surface, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(detail, color = HorizonColors.TextMuted, fontSize = 11.sp)
            }
            Text(status, color = tint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}
