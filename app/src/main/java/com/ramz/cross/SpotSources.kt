package com.ramz.cross

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The ticks on screen: [all] = existing behaviour; otherwise only the ticked sites. */
data class SpotChoice(val all: Boolean = false, val ntes: Boolean = false, val wimt: Boolean = true) {
    /** Sites used to spot nearby trains. Never empty: with nothing ticked, Where is my Train. */
    val sources: Set<Source>
        get() = when {
            all -> Source.entries.toSet()
            ntes && wimt -> setOf(Source.NTES, Source.WIMT)
            ntes -> setOf(Source.NTES)
            else -> setOf(Source.WIMT)
        }
}

/**
 * Which sites are asked where each nearby train is. Only affects spotting trains during a check;
 * the full lookup of every train (Check all trains) still uses RailYatri and NTES. Saved on the phone.
 */
class SpotSources private constructor(context: Context) {

    companion object {
        @Volatile private var instance: SpotSources? = null

        fun get(context: Context): SpotSources =
            instance ?: synchronized(this) { instance ?: SpotSources(context.applicationContext).also { instance = it } }
    }

    private val prefs = context.getSharedPreferences("spot_sources", Context.MODE_PRIVATE)

    private val _choice = MutableStateFlow(
        SpotChoice(prefs.getBoolean("all", false), prefs.getBoolean("ntes", false), prefs.getBoolean("wimt", true))
    )
    val choice: StateFlow<SpotChoice> = _choice

    fun set(choice: SpotChoice) {
        _choice.value = choice
        prefs.edit { putBoolean("all", choice.all).putBoolean("ntes", choice.ntes).putBoolean("wimt", choice.wimt) }
    }
}
