package com.ramz.cross

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface DuePosition {
    data object Loading : DuePosition
    data class Shown(val text: String, val at: Long) : DuePosition
    data class Failed(val reason: String) : DuePosition
}

/**
 * Current positions, from Where is my Train, of the trains in "Due at KDTY next".
 *
 * Lookups go through one queue, one train at a time; [WhereIsMyTrainClient] spaces requests out
 * so the site never sees a burst from us. A result stays good for [FRESH]; [retry] asks again anyway.
 */
object DuePositions {

    private const val TAG = "DuePositions"
    private const val FRESH = 2 * MIN

    private val client = WhereIsMyTrainClient()
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val _positions = MutableStateFlow<Map<String, DuePosition>>(emptyMap())
    val positions: StateFlow<Map<String, DuePosition>> = _positions

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for (number in queue) {
                try {
                    val text = client.summary(client.stops(number), System.currentTimeMillis())
                    _positions.update { it + (number to DuePosition.Shown(text, System.currentTimeMillis())) }
                } catch (e: Exception) {
                    Log.w(TAG, "[$number] ${e.javaClass.simpleName}: ${e.message}")
                    val reason = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                    _positions.update { it + (number to DuePosition.Failed(reason)) }
                }
            }
        }
    }

    /** Queues the trains whose position is missing or older than [FRESH]. */
    fun request(numbers: List<String>) {
        val now = System.currentTimeMillis()
        numbers.filter { n ->
            when (val p = _positions.value[n]) {
                null, is DuePosition.Failed -> true
                is DuePosition.Loading -> false
                is DuePosition.Shown -> now - p.at > FRESH
            }
        }.forEach(::enqueue)
    }

    fun retry(number: String) = enqueue(number)

    private fun enqueue(number: String) {
        if (_positions.value[number] is DuePosition.Loading) return
        _positions.update { it + (number to DuePosition.Loading) }
        queue.trySend(number)
    }
}
