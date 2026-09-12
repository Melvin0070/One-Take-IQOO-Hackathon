package com.example.one_take

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingSetupTest {
    @Test fun wordsIgnoreWhitespaceAndBlankDrafts() {
        assertEquals(0, scriptWordCount("  \n\t"))
        assertEquals(4, scriptWordCount(" Welcome\nto\tOne   Take "))
    }
}
