package org.cascade.pipeline

import org.cascade.domain.Hazard
import java.util.PriorityQueue

data class HazardCell(val cell: String, val severity: Int, val corroborations: Int, val trusted: Boolean,
    val safeClaimTrusted: Boolean, val penalty: Int)

class HazardEngine(private val minimumReporters: Int = 2) {
    fun grid(reports: List<Hazard>, now: Long): Map<String, HazardCell> = reports
        .filter { it.expiresAt > now && it.observedAt <= now && it.expiresAt - it.observedAt <= 3_600_000 }
        .groupBy { it.cell }.mapValues { (cell, entries) ->
            val latest = entries.groupBy { it.reporter }.values.map { it.maxWith(compareBy<Hazard> { h -> h.observedAt }.thenBy { h -> h.id }) }
            val danger = latest.filter { it.type != "SAFE" && it.severity > 0 }
            val eligible = danger.filter { it.eligible }.map { it.independentGroup }.distinct().size
            val safe = latest.filter { it.type == "SAFE" && it.eligible }.map { it.independentGroup }.distinct().size
            val severity = danger.maxOfOrNull { it.severity.coerceIn(1, 5) } ?: 0
            // A fresh credible danger always vetoes SAFE, even before corroboration.
            val safeTrusted = safe >= minimumReporters && danger.isEmpty()
            val decay = danger.maxOfOrNull { ((it.expiresAt - now).toDouble() / (it.expiresAt - it.observedAt).coerceAtLeast(1)).coerceIn(0.1, 1.0) } ?: 1.0
            HazardCell(cell, severity, eligible, eligible >= minimumReporters, safeTrusted,
                if (safeTrusted) 0 else if (danger.isEmpty()) 400 else (severity * 1000 * decay).toInt())
        }

    /** Only corroborated safe cells form trusted routes. Absence of reports is UNKNOWN. */
    fun trustedPath(start: String, end: String, neighbors: Map<String, Set<String>>, grid: Map<String, HazardCell>): List<String>? {
        if (grid[start]?.safeClaimTrusted != true || grid[end]?.safeClaimTrusted != true) return null
        val queue = PriorityQueue<Pair<Int, String>>(compareBy<Pair<Int, String>> { it.first }.thenBy { it.second })
        val distances = mutableMapOf(start to 0); val previous = mutableMapOf<String, String>()
        queue += 0 to start
        while (queue.isNotEmpty()) {
            val (distance, node) = queue.remove()
            if (distance != distances[node]) continue
            if (node == end) {
                val path = mutableListOf(end)
                while (path.last() != start) path += previous.getValue(path.last())
                return path.reversed()
            }
            neighbors[node].orEmpty().sorted().filter { grid[it]?.safeClaimTrusted == true }.forEach { next ->
                val candidate = distance + 1
                if (candidate < (distances[next] ?: Int.MAX_VALUE)) {
                    distances[next] = candidate; previous[next] = node; queue += candidate to next
                }
            }
        }
        return null
    }
}
