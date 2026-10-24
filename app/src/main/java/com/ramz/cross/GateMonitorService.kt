package com.ramz.cross

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs [GateMonitor] with the screen off, behind an ongoing notification that shows the gate
 * status and has a Stop button. A partial wake lock keeps the CPU awake between checks.
 *
 * Started either to monitor (check every minute until stopped) or for one quick check, after
 * which it stops by itself.
 *
 * The ongoing notification is silent, so phones don't forward it to a smartwatch. The result
 * goes out as a separate, alerting notification — "Gate CLOSED / Possible close / Gate OPEN" —
 * after a quick check, and while monitoring whenever the status changes.
 */
class GateMonitorService : Service() {

    companion object {
        private const val CHANNEL = "gate_status"
        private const val ALERT_CHANNEL = "gate_alerts"
        private const val NOTIFICATION_ID = 1
        private const val RESULT_ID = 20             // one result notification, replaced by the next
        private const val ACTION_STOP = "com.ramz.cross.action.STOP"
        private const val ACTION_ONCE = "com.ramz.cross.action.CHECK_ONCE"
        private const val ACTION_ALL = "com.ramz.cross.action.CHECK_ALL"
        private const val MAX_RUN = 12 * 60 * 60 * 1000L
        private const val RESULT_TIMEOUT = 30 * 60 * 1000L
        private const val RED = 0xFFC62828.toInt()
        private const val YELLOW = 0xFFF9A825.toInt()
        private const val GREEN = 0xFF2E7D32.toInt()

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, GateMonitorService::class.java))

        fun checkOnce(context: Context) =
            ContextCompat.startForegroundService(
                context, Intent(context, GateMonitorService::class.java).setAction(ACTION_ONCE)
            )

        /** Like [checkOnce], but looks up every train again first. */
        fun checkAll(context: Context) =
            ContextCompat.startForegroundService(
                context, Intent(context, GateMonitorService::class.java).setAction(ACTION_ALL)
            )

        /** Stops any check or monitoring that is running. */
        fun stop(context: Context) {
            GateMonitor.get(context).stop()
            context.stopService(Intent(context, GateMonitorService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            GateMonitor.get(this).stop()
            stopSelf()
            return START_NOT_STICKY
        }
        createChannels()
        val monitor = GateMonitor.get(this)
        val checkAll = intent?.action == ACTION_ALL
        val once = checkAll || intent?.action == ACTION_ONCE
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(monitor.state.value),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
        if (job == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LevelcrossCheck:monitor")
                .apply { acquire(MAX_RUN) }
            job = scope.launch {
                val nm = getSystemService(NotificationManager::class.java)
                launch {
                    monitor.state.map { notificationText(it) }.distinctUntilChanged().collect {
                        nm.notify(NOTIFICATION_ID, buildNotification(monitor.state.value))
                    }
                }
                if (!once) launch {
                    // Monitoring: tell the watch whenever open / possible / closed changes.
                    monitor.state.map { resultOf(it) }.filterNotNull().distinctUntilChanged().collect {
                        postResult(nm, monitor.state.value)
                    }
                }
                try {
                    monitor.run(continuous = !once, checkAll = checkAll)
                    postResult(nm, monitor.state.value)   // quick check / check all finished
                } finally {
                    // Also when the run was stopped from elsewhere (e.g. GateMonitor.stop), so the
                    // notification doesn't stay behind.
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Gate status", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Live KDTY level-crossing status while checking" }
        )
        nm.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL, "Gate result", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Gate closed, possible close or open — also shown on a smartwatch" }
        )
    }

    private enum class GateResult { OPEN, POSSIBLE, CLOSED }

    private fun resultOf(s: GateUiState) = when {
        s.closed -> GateResult.CLOSED
        s.possible -> GateResult.POSSIBLE
        s.open -> GateResult.OPEN
        else -> null
    }

    /** Short, alerting notification with the result, kept brief so it reads well on a watch. */
    private fun postResult(nm: NotificationManager, s: GateUiState) {
        val result = resultOf(s)
        val title = when (result) {
            GateResult.CLOSED -> "🔴 Gate CLOSED"
            GateResult.POSSIBLE -> "🟡 Possible close"
            GateResult.OPEN -> "🟢 Gate OPEN"
            null -> "KDTY gate: couldn't check"
        }
        val trains = listOfNotNull(s.fromKottayam.hit, s.fromErnakulam.hit).joinToString(" · ") { hit ->
            val side = if (GateRule.approach(hit.report.routeKm) == Approach.FROM_KOTTAYAM_SIDE) "from Kottayam" else "from Ernakulam"
            "${hit.train.number} ${hit.train.name} at ${hit.report.code} ($side)" + if (hit.estimated) " est." else ""
        }
        val checked = s.lastChecked?.let { hhmm(it) }
        val text = when (result) {
            GateResult.OPEN -> "No train near KDTY" + (checked?.let { " · $it" } ?: "")
            null -> s.error ?: "No answer from NTES or RailYatri"
            else -> trains + (checked?.let { " · $it" } ?: "")
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_gate)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setColor(when (result) { GateResult.CLOSED -> RED; GateResult.POSSIBLE -> YELLOW; else -> GREEN })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(false)                 // every new result buzzes (and reaches the watch)
            .setAutoCancel(true)
            .setTimeoutAfter(RESULT_TIMEOUT)
            .setContentIntent(open)
            .build()
        nm.notify(RESULT_ID, notification)
    }

    private fun hhmm(millis: Long) =
        SimpleDateFormat("HH:mm", Locale.ENGLISH).apply { timeZone = IST }.format(Date(millis))

    private fun notificationText(s: GateUiState): Pair<String, String> {
        val title = when {
            s.closed -> "🔴 Gate CLOSED"
            s.possible -> "🟡 Possible cross soon"
            s.open -> "🟢 Gate OPEN"
            !s.monitoring -> "Quick check of KDTY gate…"
            else -> "Checking KDTY gate…"
        }
        val lines = listOfNotNull(s.fromKottayam.hit, s.fromErnakulam.hit).map { hit ->
            (if (hit.level == GateLevel.CLOSED) "Closed: " else "Possible: ") +
                "${hit.train.number} ${hit.train.name} at ${hit.report.code}" + if (hit.estimated) " (est.)" else ""
        }
        val checked = s.lastChecked?.let { "checked " + hhmm(it) }
        val text = when {
            lines.isNotEmpty() -> lines.joinToString(" · ")
            s.open -> "No train between ETM–KDTY or PVRD–KDTY"
            else -> s.phase ?: "Starting…"
        }
        return title to listOfNotNull(text, checked).joinToString(" · ")
    }

    private fun buildNotification(s: GateUiState): Notification {
        val (title, text) = notificationText(s)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, GateMonitorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_gate)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setColor(if (s.closed) RED else if (s.possible) YELLOW else GREEN)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
