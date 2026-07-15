package com.apexlions.aurorastudio

import java.text.Normalizer
import java.util.Locale
import kotlin.math.max

internal data class MatchCandidate(
    val targetIndex: Int,
    val fileIndex: Int?,
    val score: Double,
    val reason: String,
)

internal object MediaMatcher {
    private val removable = Regex(
        """\b(feat|ft|featuring|remaster(?:ed)?|version|edit|mix|explicit|clean|official|audio|video|lyrics?|instrumental|master)\b.*$""",
        RegexOption.IGNORE_CASE,
    )
    private val leadingNumber = Regex("""^\s*(?:cd\s*\d+\s*[-_. ]*)?(?:track\s*)?\d{1,3}\s*[-_. )]+""", RegexOption.IGNORE_CASE)
    private val bracket = Regex("""[\[(].*?[\])]""")
    private val separators = Regex("""[^a-z0-9]+""")

    fun normalize(value: String): String {
        val withoutExtension = value.substringBeforeLast('.', value)
        val folded = Normalizer.normalize(withoutExtension, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('ı', 'i')
            .lowercase(Locale.ROOT)
        return folded
            .replace(leadingNumber, "")
            .replace(bracket, " ")
            .replace(removable, " ")
            .replace(separators, " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    fun match(targetTitles: List<String>, fileNames: List<String>): List<MatchCandidate> {
        val targetKeys = targetTitles.map(::normalize)
        val fileKeys = fileNames.map(::normalize)
        val unused = fileNames.indices.toMutableSet()
        val result = MutableList(targetTitles.size) { MatchCandidate(it, null, 0.0, "Eşleşmedi") }

        targetKeys.forEachIndexed { targetIndex, target ->
            val exact = unused.firstOrNull { fileKeys[it] == target && target.isNotBlank() }
            if (exact != null) {
                result[targetIndex] = MatchCandidate(targetIndex, exact, 1.0, "İsim birebir")
                unused.remove(exact)
            }
        }

        targetKeys.forEachIndexed { targetIndex, target ->
            if (result[targetIndex].fileIndex != null) return@forEachIndexed
            val ranked = unused.map { fileIndex ->
                fileIndex to score(target, fileKeys[fileIndex])
            }.sortedByDescending { it.second }
            val best = ranked.firstOrNull()
            val second = ranked.getOrNull(1)?.second ?: 0.0
            if (best != null && best.second >= .64 && best.second - second >= .08) {
                result[targetIndex] = MatchCandidate(targetIndex, best.first, best.second, "İsim benzerliği")
                unused.remove(best.first)
            }
        }

        val remainingTargets = result.indices.filter { result[it].fileIndex == null }
        remainingTargets.zip(unused.sorted()).forEach { (targetIndex, fileIndex) ->
            result[targetIndex] = MatchCandidate(targetIndex, fileIndex, .35, "Albüm sırası")
        }
        return result
    }

    private fun score(left: String, right: String): Double {
        if (left.isBlank() || right.isBlank()) return 0.0
        if (left == right) return 1.0
        if (left in right || right in left) {
            val ratio = minOf(left.length, right.length).toDouble() / max(left.length, right.length)
            return .72 + ratio * .18
        }
        val leftTokens = left.split(' ').filter(String::isNotBlank).toSet()
        val rightTokens = right.split(' ').filter(String::isNotBlank).toSet()
        val union = leftTokens union rightTokens
        val tokenScore = if (union.isEmpty()) 0.0 else (leftTokens intersect rightTokens).size.toDouble() / union.size
        val editScore = 1.0 - levenshtein(left, right).toDouble() / max(left.length, right.length)
        return tokenScore * .62 + editScore.coerceIn(0.0, 1.0) * .38
    }

    private fun levenshtein(left: String, right: String): Int {
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length
        var previous = IntArray(right.length + 1) { it }
        left.forEachIndexed { i, leftChar ->
            val current = IntArray(right.length + 1)
            current[0] = i + 1
            right.forEachIndexed { j, rightChar ->
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + if (leftChar == rightChar) 0 else 1,
                )
            }
            previous = current
        }
        return previous[right.length]
    }
}
