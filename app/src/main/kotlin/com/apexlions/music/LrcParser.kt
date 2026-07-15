package com.apexlions.music

internal data class LrcLine(
    val timeMs: Long,
    val text: String,
)

internal object LrcParser {
    private val timestamp = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val offset = Regex("""\[offset:([+-]?\d+)]""", RegexOption.IGNORE_CASE)

    fun parse(raw: String): List<LrcLine> {
        if (raw.isBlank()) return emptyList()
        val input = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val matches = timestamp.findAll(input).toList()
        if (matches.isEmpty()) return emptyList()
        val offsetMs = offset.find(input)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        val pending = mutableListOf<Long>()
        val result = mutableListOf<LrcLine>()
        matches.forEachIndexed { index, match ->
            val start = match.range.last + 1
            val end = matches.getOrNull(index + 1)?.range?.first ?: input.length
            val text = input.substring(start, end)
                .replace(Regex("""\[[a-zA-Z]+:[^]]*]"""), " ")
                .replace('\n', ' ')
                .trim()
            val current = timestampToMs(match) + offsetMs
            if (text.isBlank()) {
                pending += current
            } else {
                (pending + current).forEach { time ->
                    result += LrcLine(time.coerceAtLeast(0L), text)
                }
                pending.clear()
            }
        }
        return result
            .filter { it.text.isNotBlank() }
            .distinctBy { it.timeMs to it.text }
            .sortedBy(LrcLine::timeMs)
    }

    fun activeIndex(lines: List<LrcLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var low = 0
        var high = lines.lastIndex
        var answer = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (lines[middle].timeMs <= positionMs + 80L) {
                answer = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return answer
    }

    fun normalize(raw: String): String = parse(raw).joinToString("\n") { line ->
        val totalSeconds = line.timeMs / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        val hundredths = (line.timeMs % 1000L) / 10L
        "[%02d:%02d.%02d]%s".format(minutes, seconds, hundredths, line.text)
    }

    private fun timestampToMs(match: MatchResult): Long {
        val minutes = match.groupValues[1].toLongOrNull() ?: 0L
        val seconds = match.groupValues[2].toLongOrNull() ?: 0L
        val fractionText = match.groupValues.getOrNull(3).orEmpty()
        val fractionMs = when (fractionText.length) {
            0 -> 0L
            1 -> fractionText.toLongOrNull()?.times(100L) ?: 0L
            2 -> fractionText.toLongOrNull()?.times(10L) ?: 0L
            else -> fractionText.take(3).padEnd(3, '0').toLongOrNull() ?: 0L
        }
        return (minutes * 60L + seconds) * 1000L + fractionMs
    }
}
