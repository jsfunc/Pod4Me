package com.pod4me.domain

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** A temporary index for one feed. Keeps distinct words, not raw show notes or audio. */
object FeedSearch {
    data class Document internal constructor(
        val id: String, val published: Long,
        internal val titleWords: Set<String>, internal val descriptionWords: Set<String>,
    )

    fun document(id: String, published: Long, title: String, plainDescription: String) =
        Document(id, published, words(title), words(plainDescription))

    private fun words(text: String): Set<String> {
        val normalized = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
            .replace("œ", "oe").replace("æ", "ae").replace("ß", "ss")
        val result = mutableSetOf<String>()
        val word = StringBuilder()
        for (char in normalized) {
            when {
                char.isLetterOrDigit() -> word.append(char)
                isMark(char) -> Unit
                else -> if (word.isNotEmpty()) { result += word.toString(); word.setLength(0) }
            }
        }
        if (word.isNotEmpty()) result += word.toString()
        return result
    }

    private fun isMark(char: Char) = when (Character.getType(char)) {
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }

    /** Every query word must match, in either field and any order. Repetition does not inflate scores. */
    suspend fun rank(documents: List<Document>, query: String): List<String> {
        val terms = words(query)
        if (terms.isEmpty()) return emptyList()
        val context = currentCoroutineContext()
        val matches = mutableListOf<Pair<Document, Int>>()
        for (document in documents) {
            context.ensureActive()
            var score = 0
            for (term in terms) {
                val title = match(term, document.titleWords)
                val description = if (title == 100) 0 else match(term, document.descriptionWords) * 3 / 5
                val best = maxOf(title, description)
                if (best == 0) { score = 0; break }
                score += best
            }
            if (score > 0) matches += document to score
        }
        return matches.sortedWith(compareByDescending<Pair<Document, Int>> { it.second }
            .thenByDescending { it.first.published }.thenBy { it.first.id }).map { it.first.id }
    }

    private suspend fun match(term: String, words: Set<String>): Int {
        if (term in words) return 100
        // Short terms require exact words: fuzzy matching "a" or "in" would match almost everything.
        if (term.length < 3) return 0
        val context = currentCoroutineContext()
        var best = 0
        for ((index, word) in words.withIndex()) {
            if (index % 256 == 0) context.ensureActive()
            val score = when {
                word.startsWith(term) -> 65
                word.contains(term) -> 45
                term.length >= 4 && oneEditApart(term, word) -> 35
                else -> 0
            }
            best = maxOf(best, score)
            if (best == 65) break
        }
        return best
    }

    /** One insertion, deletion, replacement or adjacent transposition; linear time, no edit matrix. */
    private fun oneEditApart(a: String, b: String): Boolean {
        if (abs(a.length - b.length) > 1) return false
        var i = 0
        while (i < minOf(a.length, b.length) && a[i] == b[i]) i++
        if (i == minOf(a.length, b.length)) return true
        return when {
            a.length < b.length -> a.regionMatches(i, b, i + 1, a.length - i)
            a.length > b.length -> a.regionMatches(i + 1, b, i, b.length - i)
            a.regionMatches(i + 1, b, i + 1, a.length - i - 1) -> true
            i + 1 < a.length && a[i] == b[i + 1] && a[i + 1] == b[i] ->
                a.regionMatches(i + 2, b, i + 2, a.length - i - 2)
            else -> false
        }
    }
}
