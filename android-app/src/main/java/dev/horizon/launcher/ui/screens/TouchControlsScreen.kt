package dev.horizon.launcher.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.horizon.launcher.game.TouchControlEditorActivity
import dev.horizon.launcher.ui.theme.HorizonColors

@Composable
fun TouchControlsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier = modifier.fillMaxSize().padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Touch controls", color = MaterialTheme.colorScheme.onBackground, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text(
            "Editorul original Zalith folosit de Horizon Android Edition. Layout-urile sunt salvate local și sunt folosite de overlay-ul jocului.",
            color = HorizonColors.TextMuted,
            fontSize = 13.sp
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = HorizonColors.Surface)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Control layout editor", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Text(
                    "Mută și redimensionează joystick-ul și butoanele, configurează acțiunile, stilurile și straturile.",
                    color = HorizonColors.TextMuted,
                    fontSize = 12.sp
                )
                Button(
                    onClick = { context.startActivity(Intent(context, TouchControlEditorActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open touch editor")
                }
            }
        }
        Text(
            "Overlay-ul din joc este furnizat de același modul. Pornirea jocului rămâne dezactivată până conectăm fluxul de instalare/instanță la runtime.",
            color = HorizonColors.TextMuted,
            fontSize = 12.sp
        )
    }
}
