package com.pod4me.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class FeedSearchTest {
    private fun doc(id: String, title: String, notes: String = "", date: Long = 0) =
        FeedSearch.document(id, date, title, notes)

    @Test fun ignoresCaseAccentsAndLigaturesRegardlessOfDeviceLocale() = runBlocking {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val docs = listOf(doc("a", "ÉCONOMIE de l’océan — Cœur et ŒUVRE, ISTANBUL"))
            assertEquals(listOf("a"), FeedSearch.rank(docs, "economie ocean coeur oeuvre istanbul"))
            assertEquals(listOf("a"), FeedSearch.rank(docs, "E\u0301CONOMIE"))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun findsWordsInEitherFieldInAnyOrderButRequiresEveryWord() = runBlocking {
        val docs = listOf(doc("both", "Ocean stories", "An expedition beneath the ice"), doc("one", "Ocean stories"))
        assertEquals(listOf("both"), FeedSearch.rank(docs, "ice ocean"))
        assertEquals(listOf("both"), FeedSearch.rank(docs, "ocean ice"))
        assertTrue(FeedSearch.rank(docs, "ocean volcano").isEmpty())
    }

    @Test fun ranksExactTitleThenExactDescriptionThenTypoAndUsesStableTies() = runBlocking {
        val docs = listOf(doc("typo", "sciense", date = 100), doc("notes", "Other", "science", 200),
            doc("b", "science", date = 10), doc("a", "science", date = 10), doc("new", "science", date = 20))
        assertEquals(listOf("new", "a", "b", "notes", "typo"), FeedSearch.rank(docs, "science"))
    }

    @Test fun supportsMissingExtraReplacedAndTransposedLetters() = runBlocking {
        val docs = listOf(doc("a", "science"))
        for (query in listOf("scince", "sciience", "sciense", "sceince")) {
            assertEquals(query, listOf("a"), FeedSearch.rank(docs, query))
        }
        assertTrue(FeedSearch.rank(docs, "sxienxe").isEmpty())
    }

    @Test fun ranksPrefixesAndSubstringsBelowExactWords() = runBlocking {
        val docs = listOf(doc("inside", "neuroscience"), doc("prefix", "scientific"), doc("exact", "sci"))
        assertEquals(listOf("exact", "prefix", "inside"), FeedSearch.rank(docs, "sci"))
    }

    @Test fun shortTermsAreExactAndEmptyOrPunctuationQueriesHaveNoMatches() = runBlocking {
        val docs = listOf(doc("a", "AI and art"), doc("b", "rain smart"))
        assertEquals(listOf("a"), FeedSearch.rank(docs, "ai"))
        assertTrue(FeedSearch.rank(docs, "at").isEmpty())
        assertTrue(FeedSearch.rank(docs, "").isEmpty())
        assertTrue(FeedSearch.rank(docs, "—%_'\"").isEmpty())
    }

    @Test fun repeatedWordsDoNotInflateRanking() = runBlocking {
        val docs = listOf(doc("a", "Ocean"), doc("b", "Other", "ocean ".repeat(2000)))
        assertEquals(listOf("a", "b"), FeedSearch.rank(docs, "ocean ocean"))
    }

    @Test fun searchesLargeFeedWithoutDroppingLateResults() = runBlocking {
        val docs = (1..10_000).map { doc(it.toString(), "Episode $it", if (it == 10_000) "astronomy" else "ordinary notes") }
        assertEquals(listOf("10000"), FeedSearch.rank(docs, "astronmy"))
    }
}
