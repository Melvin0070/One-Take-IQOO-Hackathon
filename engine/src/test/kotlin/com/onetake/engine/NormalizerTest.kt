package com.onetake.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class NormalizerTest {

    private val normalizer = Normalizer(
        RuntimeConfig(
            configVersion = "test",
            brandAliases = mapOf(
                "iQOO" to listOf("i qoo", "i-qoo"),
                "Gen" to listOf("generation"),
            ),
        ),
    )

    @Test
    fun `normalizes the R2 product names and numbers to the same tokens`() {
        assertEquals(
            normalizer.normalize("iQOO 15"),
            normalizer.normalize("i-qoo fifteen"),
        )
        assertEquals(
            normalizer.normalize("Snapdragon 8 Elite Gen 5"),
            normalizer.normalize("Snapdragon eight Elite Generation five"),
        )
        assertEquals(
            normalizer.normalize("₹70k"),
            normalizer.normalize("seventy thousand rupees"),
        )
        assertEquals(
            normalizer.normalize("2026"),
            normalizer.normalize("twenty twenty six"),
        )
        assertEquals(
            normalizer.normalize("2026"),
            normalizer.normalize("two thousand twenty six"),
        )
        assertEquals(
            normalizer.normalize("4K at 60 fps"),
            normalizer.normalize("four K at sixty frames per second"),
        )
        assertEquals(
            Normalizer(RuntimeConfig(configVersion = "test")).normalize("iQOO 15"),
            Normalizer(RuntimeConfig(configVersion = "test")).normalize("i QOO fifteen"),
        )
    }

    @Test
    fun `strict mode keeps token order and caption mode removes filler punctuation`() {
        assertEquals(
            listOf("hello", "world"),
            normalizer.normalize("Hello, world!", Normalizer.Mode.MUST_SAY_STRICT),
        )
        assertEquals(
            listOf("hello", "world"),
            normalizer.normalize("  hello... world  ", Normalizer.Mode.CAPTION),
        )
    }
}
