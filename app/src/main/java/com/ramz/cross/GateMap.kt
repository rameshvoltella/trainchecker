package com.ramz.cross

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.drawable.toDrawable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val START_ZOOM = 12.5
private const val RED = 0xFFC62828.toInt()
private const val YELLOW = 0xFFF9A825.toInt()
private const val GREEN = 0xFF2E7D32.toInt()
private const val GREY = 0xFF616161.toInt()
private const val BLUE = 0xFF1565C0.toInt()

private fun TrainMap.point(p: LatLng) = GeoPoint(p.lat, p.lng)

/**
 * OpenStreetMap of Kottayam – Piravam Road, centred on KDTY: the gate (coloured by its status),
 * the stations of both closing stretches, and every nearby train from the last check. Pinch or
 * use + / − to zoom, drag to move, ◎ to come back to KDTY; tap a train for its details.
 */
@Composable
fun GateMapTab(state: GateUiState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    var selected by remember { mutableStateOf<String?>(null) }
    val map = remember { createMap(context) }

    // osmdroid needs to know when the screen is shown or hidden (tile loading, sensors).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        map.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            map.onPause()
        }
    }

    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { map },
                update = { drawMarkers(it, state, density) { number -> selected = number } },
                modifier = Modifier.fillMaxSize()
            )
            Column(
                Modifier.align(Alignment.BottomEnd).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SmallFloatingActionButton(onClick = { map.controller.animateTo(TrainMap.point(TrainMap.KDTY), START_ZOOM, 400L) }) { Text("◎") }
                SmallFloatingActionButton(onClick = { map.controller.zoomIn() }) { Text("＋") }
                SmallFloatingActionButton(onClick = { map.controller.zoomOut() }) { Text("−") }
            }
            if (state.mapTrains.isEmpty()) {
                Text(
                    if (state.lastChecked == null) "Run a check to see trains on the map" else "No trains on this stretch right now",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp)
                )
            }
        }
        state.mapTrains.firstOrNull { it.train.number == selected }?.let { TrainDetails(it) }
    }
}

@Composable
private fun TrainDetails(t: MapTrain) {
    val r = t.report
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text("${t.train.number} ${t.train.name}  ·  ${direction(t.approach)}", fontWeight = FontWeight.SemiBold)
            Text(
                "${r.event} ${r.name} (${r.code}) at ${hhmm(r.eventMillis)} · ${r.source.label}" +
                    (if (t.estimated) " · position estimated" else ""),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                when (t.level) {
                    GateLevel.CLOSED -> "🔴 Gate closed by this train"
                    GateLevel.POSSIBLE -> "🟡 Possible cross soon"
                    null -> "Not near the gate yet"
                },
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun direction(a: Approach) = if (a == Approach.FROM_KOTTAYAM_SIDE) "→ Ernakulam" else "→ Kottayam"

private fun hhmm(millis: Long) =
    SimpleDateFormat("HH:mm", Locale.ENGLISH).apply { timeZone = IST }.format(Date(millis))

private fun createMap(context: Context): MapView {
    // OpenStreetMap's tile servers require an identifying user agent.
    Configuration.getInstance().userAgentValue = context.packageName
    return MapView(context).apply {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)                                   // pinch to zoom
        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)   // our own + / −
        minZoomLevel = 9.0
        maxZoomLevel = 18.0
        controller.setZoom(START_ZOOM)
        controller.setCenter(TrainMap.point(TrainMap.KDTY))
    }
}

/** Replaces all markers: stations, the gate, then the trains on top. */
private fun drawMarkers(map: MapView, state: GateUiState, density: Float, onTrainTap: (String) -> Unit) {
    map.overlays.clear()

    val zoneStations = setOf("ETM", "KRPP", "VARD", "PVRD")
    TrainMap.stations.forEach { (code, pos) ->
        if (code == "KDTY") return@forEach
        map.overlays += Marker(map).apply {
            position = TrainMap.point(pos)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = label(code, if (code in zoneStations) BLUE else GREY, density, small = true).toDrawable(map.resources)
            title = TrainMap.stationNames[code]
            infoWindow = null
        }
    }

    val gateColor = when {
        state.closed -> RED
        state.possible -> YELLOW
        state.open -> GREEN
        else -> GREY
    }
    map.overlays += Marker(map).apply {
        position = TrainMap.point(TrainMap.KDTY)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        icon = label("🚧 KDTY", gateColor, density, small = false).toDrawable(map.resources)
        infoWindow = null
    }

    state.mapTrains.forEach { t ->
        val color = when (t.level) {
            GateLevel.CLOSED -> RED
            GateLevel.POSSIBLE -> YELLOW
            null -> GREY
        }
        val arrow = if (t.approach == Approach.FROM_KOTTAYAM_SIDE) "↖" else "↘"   // the line runs SE (KTYM) → NW (ERN)
        map.overlays += Marker(map).apply {
            position = TrainMap.point(t.position)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = label("🚆 ${t.train.number} $arrow", color, density, small = false).toDrawable(map.resources)
            infoWindow = null
            setOnMarkerClickListener { _, _ -> onTrainTap(t.train.number); true }
        }
    }
    map.invalidate()
}

/** A rounded label ("pill") with white text on [color], used for every marker. */
private fun label(text: String, color: Int, density: Float, small: Boolean): Bitmap {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.WHITE
        textSize = (if (small) 11f else 14f) * density
        typeface = Typeface.DEFAULT_BOLD
    }
    val padX = 6f * density
    val padY = 3f * density
    val w = (paint.measureText(text) + padX * 2).toInt()
    val h = (paint.fontMetrics.let { it.descent - it.ascent } + padY * 2).toInt()
    return createBitmap(w, h).also {
        val c = Canvas(it)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), h / 2f, h / 2f, bg)
        c.drawText(text, padX, padY - paint.fontMetrics.ascent, paint)
    }
}
