package com.evan.island

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private var tick by mutableIntStateOf(0)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = 0xFF14103A.toInt()
        window.navigationBarColor = 0xFF05030F.toInt()
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF9D8CFF),
                    onPrimary = Color.Black,
                    surface = Color(0xFF0B0820),
                    background = Color(0xFF0B0820)
                )
            ) { SettingsScreen(tick) }
        }
    }

    override fun onResume() {
        super.onResume()
        tick++
    }
}

@Composable
fun SettingsScreen(tick: Int) {
    val ctx = LocalContext.current
    val sp = remember { Prefs.sp(ctx) }
    var cfg by remember { mutableStateOf(Cfg.load(sp)) }
    var running by remember(tick) { mutableStateOf(sp.getBoolean("running", false)) }
    val overlayOk = remember(tick) { Settings.canDrawOverlays(ctx) }
    val listenerOk = remember(tick) {
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    }

    fun upd(n: Cfg) { cfg = n; n.save(sp) }
    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()
    fun setRunning(on: Boolean) {
        if (on) {
            if (!Settings.canDrawOverlays(ctx)) {
                toast("Autorise d'abord l'affichage par-dessus")
                return
            }
            ctx.startForegroundService(Intent(ctx, IslandService::class.java))
        } else {
            ctx.stopService(Intent(ctx, IslandService::class.java))
        }
        running = on
    }
    fun test(cmd: String) {
        val f = Bus.cmd
        if (f == null) toast("Démarre d'abord l'île") else f(cmd)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF14103A), Color(0xFF05030F))))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Island", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)

        Section("État") {
            SwitchRow("Île active", running, { setRunning(it) })
            PermRow(overlayOk, "Affichage par-dessus", "Autoriser") {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${ctx.packageName}"))
                )
            }
            PermRow(listenerOk, "Accès aux notifications", "Autoriser") {
                ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            if (!listenerOk) {
                Text(
                    "Si c'est grisé : Paramètres, Applications, Island, menu ⋮, « Autoriser les paramètres restreints ».",
                    color = Color(0x99FFFFFF), fontSize = 12.sp
                )
            }
        }

        Section("Position et taille") {
            SliderRow("Position verticale", cfg.posY, 0, 120, " dp") { upd(cfg.copy(posY = it)) }
            SliderRow("Position horizontale", cfg.posX, -160, 160, " dp") { upd(cfg.copy(posX = it)) }
            SliderRow("Largeur au repos", cfg.idleW, 60, 260, " dp") { upd(cfg.copy(idleW = it)) }
            SliderRow("Hauteur au repos", cfg.idleH, 20, 60, " dp") { upd(cfg.copy(idleH = it)) }
            SliderRow("Largeur ouverte", cfg.openW, 260, 400, " dp") { upd(cfg.copy(openW = it)) }
            SliderRow("Arrondi des cartes", cfg.radius, 12, 44, " dp") { upd(cfg.copy(radius = it)) }
        }

        Section("Verre") {
            SwitchRow("Mode verre", cfg.glass, { upd(cfg.copy(glass = it)) })
            SliderRow("Opacité", cfg.opacity, 20, 100, " %") { upd(cfg.copy(opacity = it)) }
            Text("Teinte", color = Color.White)
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TINTS.forEachIndexed { i, t ->
                    val sel = cfg.tint == i
                    Spacer(
                        Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Brush.verticalGradient(listOf(Color(t.top), Color(t.bottom))))
                            .border(
                                if (sel) 2.dp else 1.dp,
                                if (sel) Color.White else Color(0x44FFFFFF),
                                CircleShape
                            )
                            .clickable { upd(cfg.copy(tint = i)) }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            SwitchRow("Bordure lumineuse", cfg.border, { upd(cfg.copy(border = it)) })
            if (Build.VERSION.SDK_INT >= 31) {
                SliderRow("Flou réel du fond (expérimental)", cfg.blur, 0, 100, "") {
                    upd(cfg.copy(blur = it))
                }
                Text(
                    "Le flou réel floute tout le rectangle de la fenêtre : les coins peuvent paraître carrés. 0 = désactivé.",
                    color = Color(0x99FFFFFF), fontSize = 12.sp
                )
            }
        }

        Section("Animations") {
            SliderRow("Vitesse", cfg.speed, 50, 200, " %") { upd(cfg.copy(speed = it)) }
            SliderRow("Rebond", cfg.bounce, 0, 100, "") { upd(cfg.copy(bounce = it)) }
            SwitchRow("Flou vers net à l'ouverture", cfg.focusBlur, { upd(cfg.copy(focusBlur = it)) })
        }

        Section("Comportement") {
            SwitchRow("Notifications", cfg.notifOn, { upd(cfg.copy(notifOn = it)) })
            SliderRow("Durée des notifications", cfg.notifSec, 2, 10, " s") { upd(cfg.copy(notifSec = it)) }
            SwitchRow("Charge (⚡)", cfg.chargeOn, { upd(cfg.copy(chargeOn = it)) })
            SwitchRow("Musique", cfg.musicOn, { upd(cfg.copy(musicOn = it)) })
            SwitchRow("Lampe torche", cfg.torchOn, { upd(cfg.copy(torchOn = it)) })
            SwitchRow("Chronomètre", cfg.chronoOn, { upd(cfg.copy(chronoOn = it)) })
            SwitchRow("Démarrer avec le téléphone", cfg.boot, { upd(cfg.copy(boot = it)) })
        }

        Section("Tester") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val bc = ButtonDefaults.buttonColors(containerColor = Color(0x33FFFFFF), contentColor = Color.White)
                Button(onClick = { test("open") }, colors = bc, modifier = Modifier.weight(1f)) {
                    Text("Ouvrir", fontSize = 13.sp)
                }
                Button(onClick = { test("notif") }, colors = bc, modifier = Modifier.weight(1f)) {
                    Text("Notif", fontSize = 13.sp)
                }
                Button(onClick = { test("charge") }, colors = bc, modifier = Modifier.weight(1f)) {
                    Text("Charge", fontSize = 13.sp)
                }
            }
        }

        Button(
            onClick = { upd(Cfg()) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0x22FFFFFF), contentColor = Color.White)
        ) { Text("Réinitialiser les réglages") }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0x1AFFFFFF)),
        border = BorderStroke(1.dp, Color(0x2EFFFFFF)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun SliderRow(label: String, value: Int, min: Int, max: Int, unit: String, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.White)
            Text("$value$unit", color = Color(0xFFB9B3FF))
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = min.toFloat()..max.toFloat()
        )
    }
}

@Composable
fun PermRow(ok: Boolean, label: String, action: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (ok) "✅" else "⚠️", fontSize = 18.sp)
        Text(label, color = Color.White, modifier = Modifier.weight(1f).padding(start = 10.dp))
        if (!ok) TextButton(onClick = onClick) { Text(action) }
    }
}
