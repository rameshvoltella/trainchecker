package com.ramz.cross

import org.jsoup.Jsoup

data class Train(val number: String, val name: String)

/** Lists every train that runs Kottayam ↔ Ernakulam (both directions, ERN and ERS). */
class TrainRepository {

    private val urls = listOf(
        "https://www.railmitra.com/trains-between-stations/KTYM/ERN?doj=",
        "https://www.railmitra.com/trains-between-stations/KTYM/ERS?doj=",
        "https://www.railmitra.com/trains-between-stations/ERN/KTYM?doj=",
        "https://www.railmitra.com/trains-between-stations/ERS/KTYM?doj="
    )

    private val trainPattern = Regex("""(\d{5})\s*[-–]\s*([A-Za-z][A-Za-z0-9 /&'.()-]{2,60})""")

    fun fetchTrains(): List<Train> {
        val trains = linkedMapOf<String, Train>()
        var failures = 0
        for (url in urls) {
            try {
                val text = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
                    .timeout(20_000).maxBodySize(0).get().text()
                trainPattern.findAll(text).forEach {
                    val num = it.groupValues[1]
                    val name = it.groupValues[2].substringBefore(" Runs").trim()   // drop "Runs on M T W…"
                    trains.getOrPut(num) { Train(num, name) }
                }
            } catch (_: Exception) {
                failures++
            }
        }
        if (failures == urls.size) throw java.io.IOException("Could not load the train list")
        return trains.values.toList()
    }
}
