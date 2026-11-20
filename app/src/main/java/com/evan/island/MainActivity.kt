package com.evan.island

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.core.view.WindowCompat
import kotlin.math.roundToInt

// ---------- palette façon iOS ----------
class Ios(
    val bg: Color, val group: Color, val label: Color, val sec: Color,
    val sep: Color, val fill: Color, val segSel: Color
)

private val DarkIos = Ios(
    Color(0xFF000000), Color(0xFF1C1C1E), Color.White, Color(0xFF8E8E93),
    Color(0xFF38383A), Color(0xFF2C2C2E), Color(0xFF636366)
)
private val LightIos = Ios(
    Color(0xFFF2F2F7), Color.White, Color.Black, Color(0xFF8E8E93),
    Color(0xFFC6C6C8), Color(0xFFE9E9EB), Color.White
)
private val IBlue = Color(0xFF0A84FF)
private val IGreen = Color(0xFF34C759)
private val IRed = Color(0xFFFF453A)

@Composable
private fun ios(): Ios = if (isSystemInDarkTheme()) DarkIos else LightIos

class MainActivity : ComponentActivity() {
    private var tick by mutableIntStateOf(0)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val bar = if (dark) 0xFF000000.toInt() else 0xFFF2F2F7.toInt()
        window.statusBarColor = bar
        window.navigationBarColor = bar
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
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
    val c = ios()
    val ctx = LocalContext.current
    val sp = remember { Prefs.sp(ctx) }
    var cfg by remember { mutableStateOf(Cfg.load(sp)) }
    var running by remember(tick) { mutableStateOf(sp.getBoolean("running", false)) }
    val overlayOk = remember(tick) { Settings.canDrawOverlays(ctx) }
    val listenerOk = remember(tick) {
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    }
    var page by remember { mutableStateOf("home") }
    BackHandler(enabled = page != "home") { page = "home" }

    fun live(n: Cfg) { Bus.preview?.invoke(n) }
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

    Box(Modifier.fillMaxSize().background(c.bg)) {
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                if (targetState != "home") {
                    (slideInHorizontally { it / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally { -it / 3 } + fadeOut())
                } else {
                    (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally { it / 3 } + fadeOut())
                }
            },
            label = "page"
        ) { p ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                if (p == "home") {
                    Text(
                        "Island", color = c.label, fontSize = 34.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                    )
                    Group {
                        ToggleRow("Île active", running) { setRunning(it) }
                        Sep()
                        StatusRow("Affichage par-dessus", overlayOk, "Autoriser") {
                            ctx.startActivity(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${ctx.packageName}"))
                            )
                        }
                        Sep()
                        StatusRow("Accès aux notifications", listenerOk, "Autoriser") {
                            ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                    }
                    if (!listenerOk) {
                        Text(
                            "Si c'est grisé : Paramètres, Applications, Island, menu ⋮, « Autoriser les paramètres restreints ».",
                            color = c.sec, fontSize = 13.sp,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp)
                        )
                    }
                    Group {
                        NavRow("📍", Color(0xFF0A84FF), "Position et caméra") { page = "pos" }
                        Sep()
                        NavRow("🎨", Color(0xFFBF5AF2), "Apparence") { page = "look" }
                        Sep()
                        NavRow("✨", Color(0xFFFF9F0A), "Animations") { page = "anim" }
                        Sep()
                        NavRow("🔔", Color(0xFFFF453A), "Comportement") { page = "behav" }
                        Sep()
                        NavRow("▶", Color(0xFF8E8E93), "Tester") { page = "test" }
                    }
                    Spacer(Modifier.height(40.dp))
                } else {
                    Text(
                        "‹ Island", color = IBlue, fontSize = 17.sp,
                        modifier = Modifier.clickable { page = "home" }.padding(vertical = 8.dp)
                    )
                    val title = when (p) {
                        "pos" -> "Position et caméra"
                        "look" -> "Apparence"
                        "anim" -> "Animations"
                        "behav" -> "Comportement"
                        else -> "Tester"
                    }
                    Text(
                        title, color = c.label, fontSize = 34.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                    )
                    when (p) {
                        "pos" -> {
                            Group(
                                "Position",
                                "Descends la position verticale vers 0, ou en négatif, pour coller la pilule à la caméra."
                            ) {
                                SliderRow("Verticale", cfg.posY, -30, 200, " dp",
                                    { live(cfg.copy(posY = it)) }) { upd(cfg.copy(posY = it)) }
                                Sep()
                                SliderRow("Horizontale", cfg.posX, -200, 200, " dp",
                                    { live(cfg.copy(posX = it)) }) { upd(cfg.copy(posX = it)) }
                            }
                            Group(
                                "Trou de la caméra",
                                "Règle la largeur du trou pour que les infos s'affichent de chaque côté sans être cachées."
                            ) {
                                SliderRow("Largeur du trou", cfg.holeW, 20, 90, " dp",
                                    { live(cfg.copy(holeW = it)) }) { upd(cfg.copy(holeW = it)) }
                                Sep()
                                SliderRow("Hauteur de la pilule", cfg.idleH, 20, 60, " dp",
                                    { live(cfg.copy(idleH = it)) }) { upd(cfg.copy(idleH = it)) }
                                Sep()
                                SliderRow("Largeur au repos", cfg.idleW, 60, 260, " dp",
                                    { live(cfg.copy(idleW = it)) }) { upd(cfg.copy(idleW = it)) }
                            }
                            Group("Cartes ouvertes") {
                                SliderRow("Largeur", cfg.openW, 260, 400, " dp",
                                    { live(cfg.copy(openW = it)) }) { upd(cfg.copy(openW = it)) }
                                Sep()
                                SliderRow("Arrondi", cfg.radius, 12, 44, " dp",
                                    { live(cfg.copy(radius = it)) }) { upd(cfg.copy(radius = it)) }
                            }
                        }
                        "look" -> {
                            Group("Style") {
                                Segmented(listOf("Noir", "Dégradé", "Verre"), cfg.style) {
                                    upd(cfg.copy(style = it))
                                }
                            }
                            Group("Teinte", "Couleur des cartes ouvertes.") {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                        .padding(14.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    TINTS.forEachIndexed { i, t ->
                                        val sel = cfg.tint == i
                                        Spacer(
                                            Modifier
                                                .size(40.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    Brush.verticalGradient(
                                                        listOf(Color(t.top), Color(t.bottom))
                                                    )
                                                )
                                                .border(
                                                    if (sel) 3.dp else 1.dp,
                                                    if (sel) IBlue else c.sep,
                                                    CircleShape
                                                )
                                                .clickable { upd(cfg.copy(tint = i)) }
                                        )
                                    }
                                }
                            }
                            Group {
                                ToggleRow("Bordure lumineuse", cfg.border) { upd(cfg.copy(border = it)) }
                                if (cfg.style == 2) {
                                    Sep()
                                    SliderRow("Opacité du verre", cfg.opacity, 50, 100, " %",
                                        { live(cfg.copy(opacity = it)) }) { upd(cfg.copy(opacity = it)) }
                                }
                            }
                        }
                        "anim" -> {
                            Group(
                                "Ressort",
                                "Vitesse haute et rebond bas : sec et rapide. Rebond haut : effet gélatine."
                            ) {
                                SliderRow("Vitesse", cfg.speed, 50, 200, " %",
                                    { }) { upd(cfg.copy(speed = it)) }
                                Sep()
                                SliderRow("Rebond", cfg.bounce, 0, 100, "",
                                    { }) { upd(cfg.copy(bounce = it)) }
                            }
                            Group {
                                ToggleRow("Flou vers net à l'ouverture", cfg.focusBlur) {
                                    upd(cfg.copy(focusBlur = it))
                                }
                            }
                        }
                        "behav" -> {
                            Group("Notifications") {
                                ToggleRow("Afficher les notifications", cfg.notifOn) {
                                    upd(cfg.copy(notifOn = it))
                                }
                                Sep()
                                SliderRow("Durée d'affichage", cfg.notifSec, 2, 10, " s",
                                    { }) { upd(cfg.copy(notifSec = it)) }
                            }
                            Group("Activités") {
                                ToggleRow("Charge", cfg.chargeOn) { upd(cfg.copy(chargeOn = it)) }
                                Sep()
                                ToggleRow("Musique", cfg.musicOn) { upd(cfg.copy(musicOn = it)) }
                                Sep()
                                ToggleRow("Lampe torche", cfg.torchOn) { upd(cfg.copy(torchOn = it)) }
                                Sep()
                                ToggleRow("Minuteurs et chrono", cfg.chronoOn) {
                                    upd(cfg.copy(chronoOn = it))
                                }
                            }
                            Group {
                                ToggleRow("Démarrer avec le téléphone", cfg.boot) {
                                    upd(cfg.copy(boot = it))
                                }
                            }
                        }
                        else -> {
                            Group("Aperçu en direct") {
                                ActionRow("Ouvrir l'île") { test("open") }
                                Sep()
                                ActionRow("Simuler une notification") { test("notif") }
                                Sep()
                                ActionRow("Simuler un branchement") { test("charge") }
                                Sep()
                                ActionRow("Lancer ou arrêter un chrono") { test("chrono") }
                            }
                            Group {
                                ActionRow("Réinitialiser les réglages", IRed) { upd(Cfg()) }
                            }
                        }
                    }
                    Spacer(Modifier.height(40.dp))
                }
            }
        }
    }
}

// ---------- composants ----------
@Composable
fun Group(
    title: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val c = ios()
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        if (title != null) {
            Text(
                title.uppercase(), color = c.sec, fontSize = 13.sp,
                modifier = Modifier.padding(start = 16.dp, bottom = 7.dp)
            )
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.group)
        ) { content() }
        if (footer != null) {
            Text(
                footer, color = c.sec, fontSize = 13.sp,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp)
            )
        }
    }
}

@Composable
fun Sep() {
    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(ios().sep))
}

@Composable
fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = ios()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = c.label, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = IGreen,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = c.segSel,
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

@Composable
fun SliderRow(
    label: String, value: Int, min: Int, max: Int, unit: String,
    onLive: (Int) -> Unit, onDone: (Int) -> Unit
) {
    val c = ios()
    var v by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = c.label, fontSize = 17.sp)
            Text("${v.roundToInt()}$unit", color = c.sec, fontSize = 17.sp)
        }
        Slider(
            value = v,
            onValueChange = { v = it; onLive(it.roundToInt()) },
            onValueChangeFinished = { onDone(v.roundToInt()) },
            valueRange = min.toFloat()..max.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = IBlue,
                inactiveTrackColor = c.fill
            )
        )
    }
}

@Composable
fun NavRow(emoji: String, tint: Color, label: String, onClick: () -> Unit) {
    val c = ios()
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)).background(tint),
            contentAlignment = Alignment.Center
        ) { Text(emoji, fontSize = 16.sp) }
        Text(
            label, color = c.label, fontSize = 17.sp,
            modifier = Modifier.weight(1f).padding(start = 14.dp)
        )
        Text("›", color = c.sec, fontSize = 24.sp)
    }
}

@Composable
fun StatusRow(label: String, ok: Boolean, action: String, onClick: () -> Unit) {
    val c = ios()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = c.label, fontSize = 17.sp, modifier = Modifier.weight(1f))
        if (ok) {
            Text("Activé", color = IGreen, fontSize = 17.sp)
        } else {
            Text(action, color = IBlue, fontSize = 17.sp, modifier = Modifier.clickable { onClick() })
        }
    }
}

@Composable
fun ActionRow(text: String, color: Color = IBlue, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = color, fontSize = 17.sp) }
}

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = ios()
    Row(
        Modifier.fillMaxWidth().padding(12.dp)
            .clip(RoundedCornerShape(9.dp)).background(c.fill).padding(2.dp)
    ) {
        options.forEachIndexed { i, o ->
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(7.dp))
                    .background(if (i == selected) c.segSel else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    o, color = c.label, fontSize = 13.sp,
                    fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}
