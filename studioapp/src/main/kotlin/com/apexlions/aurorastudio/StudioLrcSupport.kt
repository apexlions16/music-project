package com.apexlions.aurorastudio

internal data class StudioLrcLine(val timeMs: Long, val text: String)

internal object StudioLrcSupport {
    private val timestamp = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

    fun parse(raw: String): List<StudioLrcLine> {
        val input = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val matches = timestamp.findAll(input).toList()
        if (matches.isEmpty()) return emptyList()
        val pending = mutableListOf<Long>()
        val result = mutableListOf<StudioLrcLine>()
        matches.forEachIndexed { index, match ->
            val start = match.range.last + 1
            val end = matches.getOrNull(index + 1)?.range?.first ?: input.length
            val lyric = input.substring(start, end).replace('\n', ' ').trim()
            val time = toMs(match)
            if (lyric.isBlank()) pending += time else {
                (pending + time).forEach { result += StudioLrcLine(it, lyric) }
                pending.clear()
            }
        }
        return result.filter { it.text.isNotBlank() }.distinctBy { it.timeMs to it.text }.sortedBy { it.timeMs }
    }

    fun normalize(raw: String): String {
        val lines = parse(raw)
        require(lines.isNotEmpty()) { "LRC dosyasında [dakika:saniye.salise] biçiminde zaman kodu bulunamadı." }
        return lines.joinToString("\n") { line ->
            val total = line.timeMs / 1000L
            val minute = total / 60L
            val second = total % 60L
            val hundredth = (line.timeMs % 1000L) / 10L
            "[%02d:%02d.%02d]%s".format(minute, second, hundredth, line.text)
        }
    }

    private fun toMs(match: MatchResult): Long {
        val minute = match.groupValues[1].toLongOrNull() ?: 0L
        val second = match.groupValues[2].toLongOrNull() ?: 0L
        val fraction = match.groupValues.getOrNull(3).orEmpty()
        val fractionMs = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLongOrNull()?.times(100L) ?: 0L
            2 -> fraction.toLongOrNull()?.times(10L) ?: 0L
            else -> fraction.take(3).padEnd(3, '0').toLongOrNull() ?: 0L
        }
        return (minute * 60L + second) * 1000L + fractionMs
    }
}
