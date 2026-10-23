package com.ramz.cross

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ramz.cross.ui.theme.LevelcrossappTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The screen stays on while the app is in front, so the gate status stays visible.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            LevelcrossappTheme {
                GateScreen()
            }
        }
    }
}

private val ClosedRed = Color(0xFFC62828)
private val PossibleYellow = Color(0xFFF9A825)
private val OnYellow = Color(0xFF3A2A00)          // dark text: white is unreadable on yellow
private val PossibleText = Color(0xFFB26A00)      // yellow is too light for text on a light card
private val OpenGreen = Color(0xFF2E7D32)

private const val UPCOMING_SHOWN = 4

private fun hhmm(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.ENGLISH).apply { timeZone = IST }.format(Date(millis))

@Composable
fun GateScreen() {
    val context = LocalContext.current
    val monitor = remember { GateMonitor.get(context) }
    val state by monitor.state.collectAsStateWithLifecycle()

    // Android 13+ asks before showing notifications; the check starts either way.
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        afterPermission?.invoke()
        afterPermission = null
    }
    fun withNotifications(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            afterPermission = action
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else action()
    }

    var tab by rememberSaveable { mutableStateOf(Tab.STATUS) }
    val actions = Actions(
        quickCheck = { withNotifications { GateMonitorService.checkOnce(context) } },
        checkAll = { withNotifications { GateMonitorService.checkAll(context) } },
        monitor = { withNotifications { GateMonitorService.start(context) } },
        stop = { GateMonitorService.stop(context) }
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.STATUS, onClick = { tab = Tab.STATUS },
                    icon = { Text("▣") }, label = { Text("Status") }
                )
                NavigationBarItem(
                    selected = tab == Tab.MAP, onClick = { tab = Tab.MAP },
                    icon = { Text("🗺") }, label = { Text("Map") }
                )
            }
        }
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            StatusBanner(state)
            when (tab) {
                Tab.STATUS -> StatusTab(state, actions, Modifier.weight(1f))
                Tab.MAP -> {
                    GateMapTab(state, Modifier.weight(1f))
                    ActionButtons(state, actions, compact = true, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        }
    }
}

private enum class Tab { STATUS, MAP }

private class Actions(val quickCheck: () -> Unit, val checkAll: () -> Unit, val monitor: () -> Unit, val stop: () -> Unit)

/** Quick check / Check all trains / Start monitoring, or Stop while something runs. */
@Composable
private fun ActionButtons(state: GateUiState, actions: Actions, compact: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.running) {
            OutlinedButton(onClick = actions.stop, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.monitoring) "Stop monitoring" else "Stop quick check")
            }
            if (!state.monitoring && !compact) {
                Text("Quick check running — it carries on with the screen off.", style = MaterialTheme.typography.bodySmall)
            }
        } else if (compact) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = actions.quickCheck, modifier = Modifier.weight(1f)) { Text("Quick check", maxLines = 1) }
                OutlinedButton(onClick = actions.checkAll, modifier = Modifier.weight(1f)) { Text("Check all", maxLines = 1) }
                OutlinedButton(onClick = actions.monitor, modifier = Modifier.weight(1f)) { Text("Monitor", maxLines = 1) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions.quickCheck, modifier = Modifier.weight(1f)) { Text("Quick check") }
                OutlinedButton(onClick = actions.checkAll, modifier = Modifier.weight(1f)) { Text("Check all trains") }
            }
            OutlinedButton(onClick = actions.monitor, modifier = Modifier.fillMaxWidth()) { Text("Start monitoring") }
            Text(
                "Quick check looks at the trains near KDTY once. Check all trains looks up every " +
                    "train again (about a minute). Monitoring checks every minute until you stop it. " +
                    "All carry on with the screen off and notify you when a train is in the stretch.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun StatusTab(state: GateUiState, actions: Actions, modifier: Modifier = Modifier) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ActionButtons(state, actions, compact = false)

        SideCard("From Kottayam", "🟡 at ETM  ·  🔴 KRPP, KDTY", "ETM", state.fromKottayam)
        SideCard("From Ernakulam", "🟡 arrived PVRD  ·  🔴 left PVRD, VARD, KDTY", "PVRD", state.fromErnakulam)

        if (state.checking) {
            state.phase?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (state.total > 0) {
                LinearProgressIndicator(
                    progress = { state.done.toFloat() / state.total },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        val footer = buildString {
            state.lastChecked?.let { append("Last checked ${hhmm(it)}") }
            if (state.failedTrains.isNotEmpty()) append("  •  ${state.failedTrains.size} trains could not be checked")
        }
        if (footer.isNotEmpty()) Text(footer, style = MaterialTheme.typography.bodySmall)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        val upcoming = state.upcoming.take(UPCOMING_SHOWN)
        if (upcoming.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Due at KDTY next (rough estimate)", style = MaterialTheme.typography.titleSmall)
            upcoming.forEach { UpcomingRow(it) }
        }
    }
}

@Composable
private fun StatusBanner(state: GateUiState) {
    data class Look(val bg: Color, val fg: Color, val title: String, val subtitle: String)
    val look = when {
        state.closed -> Look(ClosedRed, Color.White, "GATE CLOSED", "Train past ETM or PVRD — gate is down")
        state.possible -> Look(PossibleYellow, OnYellow, "POSSIBLE CROSS", "Train at ETM or PVRD — gate may close soon")
        state.open -> Look(OpenGreen, Color.White, "GATE OPEN", "No train between ETM–KDTY or PVRD–KDTY")
        state.running -> Look(Color.Gray, Color.White, "Checking…", "Asking RailYatri (and NTES as backup) where the trains are")
        else -> Look(Color.Gray, Color.White, "Not checked", "Press Quick check or Start monitoring")
    }
    val dim = look.fg.copy(alpha = 0.85f)
    Column(
        Modifier.fillMaxWidth().background(look.bg).padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("KDTY level crossing", color = dim)
        Text(look.title, color = look.fg, fontSize = 36.sp, fontWeight = FontWeight.ExtraBold)
        Text(look.subtitle, color = look.fg, fontSize = 16.sp)
        if ((state.closed || state.possible) && SideStatus.CHECKING in listOf(state.fromKottayam.status, state.fromErnakulam.status)) {
            Text("Still checking the other side…", color = dim, style = MaterialTheme.typography.bodySmall)
        }
        if (!state.running && (state.closed || state.possible || state.open)) {
            state.lastChecked?.let {
                Text("Result of the check at ${hhmm(it)} — not updating", color = dim, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SideCard(title: String, rule: String, possibleAt: String, side: SideState) {
    val hit = side.hit
    val accent = when (side.status) {
        SideStatus.CLOSED -> ClosedRed
        SideStatus.POSSIBLE -> PossibleText
        else -> null
    }
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = accent?.copy(alpha = 0.14f) ?: MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(rule, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                when (side.status) {
                    SideStatus.CLOSED -> "🔴 Gate closed — ${hit!!.train.number} ${hit.train.name}"
                    SideStatus.POSSIBLE -> "🟡 Possible cross — ${hit!!.train.number} ${hit.train.name}"
                    SideStatus.CLEAR -> "No train"
                    SideStatus.CHECKING -> "Checking…"
                    SideStatus.NOT_CHECKED -> "Not checked"
                },
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                color = accent ?: MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 4.dp)
            )
            hit?.let { h ->
                val r = h.report
                Text(
                    "${r.event} ${r.name} (${r.code}) at ${hhmm(r.eventMillis)} · ${r.source.label}" +
                        if (h.estimated) "\nShould have reached $possibleAt by now (estimate)" else "",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun UpcomingRow(pass: TrainPass) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("~${hhmm(pass.estimate.etaMillis)}", fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp))
        Column(Modifier.weight(1f)) {
            Text("${pass.train.number} ${pass.train.name}", maxLines = 1, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (pass.estimate.approach == Approach.FROM_KOTTAYAM_SIDE) "from Kottayam" else "from Ernakulam",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
