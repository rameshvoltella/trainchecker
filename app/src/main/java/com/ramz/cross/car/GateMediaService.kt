package com.ramz.cross.car

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import com.ramz.cross.GateLevel
import com.ramz.cross.GateMonitor
import com.ramz.cross.GateMonitorService
import com.ramz.cross.GateUiState
import com.ramz.cross.IST
import com.ramz.cross.R
import com.ramz.cross.SideState
import com.ramz.cross.SideStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Android Auto shows the app as a media app (the only kind it lists from outside the Play Store
 * on our phone). Nothing is played; the "track" is the gate status.
 *
 *  • Browse list: a coloured status row, one row per side, and Quick check / Start or Stop
 *    monitoring / Check all trains rows that run when tapped.
 *  • Now Playing: the status as the title, the sides as the artist line. Play = start monitoring,
 *    Pause = stop; Quick check and Check all are extra buttons.
 *  • Voice: "Hey Google, play KDTY gate" runs a quick check; saying "monitor" starts monitoring.
 */
class GateMediaService : MediaBrowserServiceCompat() {

    companion object {
        private const val ROOT = "root"
        private const val ID_STATUS = "status"
        private const val ID_KOTTAYAM = "side_kottayam"
        private const val ID_ERNAKULAM = "side_ernakulam"
        private const val ID_QUICK = "quick_check"
        private const val ID_MONITOR = "monitor"
        private const val ID_ALL = "check_all"

        private val RED = Color.rgb(0xC6, 0x28, 0x28)
        private val YELLOW = Color.rgb(0xF9, 0xA8, 0x25)
        private val GREEN = Color.rgb(0x2E, 0x7D, 0x32)
        private val GREY = Color.rgb(0x75, 0x75, 0x75)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var monitor: GateMonitor
    private lateinit var session: MediaSessionCompat
    private var localRun: Job? = null

    override fun onCreate() {
        super.onCreate()
        monitor = GateMonitor.get(this)
        session = MediaSessionCompat(this, "KDTY gate").apply {
            setCallback(Controls())
            isActive = true
        }
        sessionToken = session.sessionToken
        // Android Auto treats every metadata or playback-state change as the player changing, so
        // each is only sent when what it shows changes: the "track" when the gate status or a
        // side changes (not the check time), the play state when checking starts or stops.
        // The browse list, which also shows the check time, is refreshed on its own.
        val shown = monitor.state.map { Shown.of(it) }
        scope.launch {
            shown.map { it.copy(footer = "") }.distinctUntilChanged().collect { session.setMetadata(metadata(it)) }
        }
        scope.launch {
            shown.map { it.running }.distinctUntilChanged().collect { session.setPlaybackState(playback(it)) }
        }
        scope.launch {
            shown.distinctUntilChanged().collect { notifyChildrenChanged(ROOT) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        session.release()
        super.onDestroy()
    }

    // Anyone may browse: it only shows the gate status, built from public train data.
    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?) = BrowserRoot(ROOT, null)

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        if (parentId != ROOT) return result.sendResult(mutableListOf())
        val s = Shown.of(monitor.state.value)
        result.sendResult(
            mutableListOf(
                item(ID_STATUS, s.title, s.subtitle, s.color),
                item(ID_KOTTAYAM, "From Kottayam: ${s.kottayam.first}", s.kottayam.second, s.kottayamColor),
                item(ID_ERNAKULAM, "From Ernakulam: ${s.ernakulam.first}", s.ernakulam.second, s.ernakulamColor),
                if (s.running) item(ID_MONITOR, "■ Stop", if (s.monitoring) "Stop monitoring" else "Stop the check", GREY)
                else item(ID_QUICK, "▶ Quick check", "Check the trains near KDTY once", GREY),
                if (s.running) null else item(ID_MONITOR, "▶ Start monitoring", "Check every minute until stopped", GREY),
                if (s.running) null else item(ID_ALL, "▶ Check all trains", "Look up every train again (about a minute)", GREY)
            ).filterNotNull().toMutableList()
        )
    }

    // ── What is shown ───────────────────────────────────────────────────────────

    private data class Shown(
        val title: String, val subtitle: String, val color: Int,
        val kottayam: Pair<String, String?>, val kottayamColor: Int,
        val ernakulam: Pair<String, String?>, val ernakulamColor: Int,
        val footer: String, val running: Boolean, val monitoring: Boolean
    ) {
        companion object {
            fun of(s: GateUiState): Shown {
                val (title, subtitle, color) = when {
                    s.closed -> Triple("🔴 GATE CLOSED", "Train past ETM or PVRD — gate is down", RED)
                    s.possible -> Triple("🟡 POSSIBLE CROSS", "Train at ETM or PVRD — may close soon", YELLOW)
                    s.open -> Triple("🟢 GATE OPEN", "No train between ETM–KDTY or PVRD–KDTY", GREEN)
                    s.running -> Triple("Checking…", "Asking where the trains are", GREY)
                    else -> Triple("KDTY gate: not checked", "Tap Quick check or Start monitoring", GREY)
                }
                val footer = listOfNotNull(
                    if (s.running) (if (s.monitoring) "Monitoring every minute" else "Checking once") else null,
                    s.lastChecked?.let { "checked ${hhmm(it)}" }
                ).joinToString(" · ")
                return Shown(
                    title, subtitle, color,
                    side(s.fromKottayam), sideColor(s.fromKottayam),
                    side(s.fromErnakulam), sideColor(s.fromErnakulam),
                    footer, s.running, s.monitoring
                )
            }

            private fun side(side: SideState): Pair<String, String?> {
                val hit = side.hit
                return when (side.status) {
                    SideStatus.CLOSED, SideStatus.POSSIBLE -> {
                        val r = hit!!.report
                        val level = if (hit.level == GateLevel.CLOSED) "Closed" else "Possible"
                        "$level — ${hit.train.number} ${hit.train.name}" to
                            "${r.event} ${r.code} at ${hhmm(r.eventMillis)}" + if (hit.estimated) " (estimate)" else ""
                    }
                    SideStatus.CLEAR -> "No train" to null
                    SideStatus.CHECKING -> "Checking…" to null
                    SideStatus.NOT_CHECKED -> "Not checked" to null
                }
            }

            private fun sideColor(side: SideState) = when (side.status) {
                SideStatus.CLOSED -> RED
                SideStatus.POSSIBLE -> YELLOW
                SideStatus.CLEAR -> GREEN
                else -> GREY
            }

            private fun hhmm(millis: Long) =
                SimpleDateFormat("HH:mm", Locale.ENGLISH).apply { timeZone = IST }.format(Date(millis))
        }
    }

    private fun item(id: String, title: String, subtitle: String?, color: Int) = MediaItem(
        MediaDescriptionCompat.Builder()
            .setMediaId(id).setTitle(title).setSubtitle(subtitle)
            .setIconBitmap(dot(color, 96))
            .build(),
        MediaItem.FLAG_PLAYABLE
    )

    private fun metadata(s: Shown): MediaMetadataCompat {
        val sides = "Kottayam side: ${s.kottayam.first} · Ernakulam side: ${s.ernakulam.first}"
        return MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, ID_STATUS)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, s.title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, s.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, sides)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, sides)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "KDTY level crossing")
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, dot(s.color, 512))
            .build()
    }

    private fun playback(running: Boolean): PlaybackStateCompat = PlaybackStateCompat.Builder()
        .setState(
            if (running) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
            PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 0f
        )
        .setActions(
            PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
        )
        .addCustomAction(ID_QUICK, "Quick check", R.drawable.ic_stat_gate)
        .addCustomAction(ID_ALL, "Check all trains", R.drawable.ic_stat_gate)
        .build()

    /** A filled circle in [color], used as the coloured status icon. */
    private val dots = HashMap<Pair<Int, Int>, Bitmap>()

    // Drawn once per colour and size: a new bitmap each time makes Android Auto reload the artwork.
    private fun dot(color: Int, size: Int): Bitmap = dots.getOrPut(color to size) {
        createBitmap(size, size).also {
            Canvas(it).drawCircle(size / 2f, size / 2f, size * 0.45f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        }
    }

    // ── Controls from the car ───────────────────────────────────────────────────

    private inner class Controls : MediaSessionCompat.Callback() {
        override fun onPlay() { if (!monitor.state.value.running) start(continuous = true) }
        override fun onPause() = stop()
        override fun onStop() = stop()

        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            if (monitor.state.value.running) return
            start(continuous = query?.contains("monitor", ignoreCase = true) == true)
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            when (mediaId) {
                ID_QUICK -> start(continuous = false)
                ID_ALL -> start(continuous = false, checkAll = true)
                ID_MONITOR -> if (monitor.state.value.running) stop() else start(continuous = true)
                // Status rows: nothing to do; Android Auto opens Now Playing with the status.
            }
        }

        override fun onCustomAction(action: String?, extras: Bundle?) {
            if (monitor.state.value.running) return
            when (action) {
                ID_QUICK -> start(continuous = false)
                ID_ALL -> start(continuous = false, checkAll = true)
            }
        }
    }

    private fun start(continuous: Boolean, checkAll: Boolean = false) {
        try {
            when {
                continuous -> GateMonitorService.start(this)
                checkAll -> GateMonitorService.checkAll(this)
                else -> GateMonitorService.checkOnce(this)
            }
        } catch (e: IllegalStateException) {
            // Android 12+ can refuse to start a foreground service from here. Check in this
            // service instead; it stops when Android Auto disconnects, with no phone notification.
            localRun?.cancel()
            localRun = scope.launch { monitor.run(continuous, checkAll) }
        }
    }

    private fun stop() {
        GateMonitorService.stop(this)
        localRun?.cancel()
        localRun = null
    }
}
