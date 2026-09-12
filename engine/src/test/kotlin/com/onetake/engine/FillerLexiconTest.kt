package com.onetake.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FillerLexiconTest {
    @Test
    fun hesitationsAndStretchedSoundsMatchTheirLexiconEntry() {
        val fillers = FillerLexicon.find(spoken("Um,", "uhh", "Ummm...", "hmm", "erm", "uhm", gap = TIGHT))

        assertEquals(listOf("um", "uh", "um", "hmm", "erm", "um"), fillers.map { it.text })
        assertEquals(0L, fillers.first().startSample)
        assertEquals(WORD, fillers.first().endSample)
    }

    @Test
    fun fillerInsideAWordDoesNotMatch() {
        val words = spoken(
            "umbrella", "hum", "summer", "album", "Uh-huh", "likely", "unlike", "also", "soap",
            "basic", "you", "knowledge", "done",
            gap = PAUSE,
        )

        assertTrue(FillerLexicon.find(words).isEmpty())
    }

    @Test
    fun markersCountOnlyWhenSetOffOnBothSides() {
        assertEquals(emptyList<String>(), texts(spoken("I", "like", "this", gap = TIGHT)))
        assertEquals(emptyList<String>(), texts(spoken("so", "that", "we", gap = TIGHT)))
        assertEquals(emptyList<String>(), texts(spoken("you", "know", "what", "I", "mean", gap = TIGHT)))
        assertEquals(emptyList<String>(), texts(timed("it" to 0L, "was" to TIGHT, "like" to PAUSE, "the" to TIGHT, "end" to TIGHT)))
        assertEquals(listOf("like"), texts(spoken("and", "like", "five", gap = PAUSE)))
        assertEquals(listOf("you know"), texts(timed("right" to 0L, "you" to PAUSE, "know" to TIGHT, "next" to PAUSE)))
        assertEquals(listOf("so"), texts(timed("So" to 0L, "today" to PAUSE)))
        assertEquals(listOf("um", "basically", "uh"), texts(spoken("um", "basically", "uh", gap = TIGHT)))
    }

    @Test
    fun youKnowNeedsBothWordsSpokenTogether() {
        assertTrue(FillerLexicon.find(spoken("okay", "you", "know", "next", gap = PAUSE)).isEmpty())
    }

    @Test
    fun theEndOfTheWordsIsNotTreatedAsAPause() {
        assertTrue(FillerLexicon.find(spoken("that", "is", "so", gap = PAUSE)).isEmpty())
        assertTrue(FillerLexicon.find(spoken("okay", "you", gap = PAUSE)).isEmpty())
        assertEquals(listOf("so"), texts(spoken("that", "is", "so", "ok", gap = PAUSE)))
    }

    @Test
    fun lowConfidenceWordsAreNeitherFillersNorEvidenceForANeighbor() {
        assertTrue(FillerLexicon.find(listOf(TranscriptWord(0, WORD, "um", .64f))).isEmpty())

        val words = spoken("right", "um", "so", "uh", "okay", gap = TIGHT)
            .map { if (it.text == "so" || it.text == "right" || it.text == "okay") it else it.copy(confidence = .5f) }
        assertTrue(FillerLexicon.find(words).isEmpty())

        val quietNeighbor = spoken("okay", "so", "next", gap = PAUSE)
            .mapIndexed { index, word -> if (index == 0) word.copy(confidence = .3f) else word }
        assertTrue(FillerLexicon.find(quietNeighbor).isEmpty())
    }

    @Test
    fun wordsAreMatchedInSourceOrderRegardlessOfInputOrder() {
        val words = spoken("and", "like", "five", "um", gap = PAUSE)

        assertEquals(FillerLexicon.find(words), FillerLexicon.find(words.reversed()))
    }

    private fun texts(words: List<TranscriptWord>) = FillerLexicon.find(words).map { it.text }

    private fun spoken(vararg texts: String, gap: Long) =
        timed(*texts.mapIndexed { index, text -> text to if (index == 0) 0L else gap }.toTypedArray())

    private fun timed(vararg words: Pair<String, Long>): List<TranscriptWord> {
        var cursor = 0L
        return words.map { (text, gapBefore) ->
            val start = cursor + gapBefore
            cursor = start + WORD
            TranscriptWord(start, cursor, text, .95f)
        }
    }

    private companion object {
        const val WORD = 4_000L
        const val TIGHT = 800L
        const val PAUSE = 8_000L
    }
}
