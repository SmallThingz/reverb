package app.smallthingz.reverb

import org.junit.Assert.*
import org.junit.Test

class QaPreferenceDigestTest {
    @Test fun primitiveTypesNeverCollapseToTheirPrintedValues() {
        val values = listOf<Any>(1, 1L, 1f, "1", true, "true")
        val hashes = values.map { qaPreferenceDigest(mapOf("key" to it)) }
        assertEquals(values.size, hashes.toSet().size)
        hashes.forEach { assertTrue(it.matches(Regex("[0-9a-f]{64}"))) }
    }

    @Test fun keyAndStringSetOrderingDoesNotAffectTheDigest() {
        val first = linkedMapOf<String, Any>("z" to linkedSetOf("c", "a", "b"), "a" to 1)
        val second = linkedMapOf<String, Any>("a" to 1, "z" to linkedSetOf("b", "c", "a"))
        assertEquals(qaPreferenceDigest(first), qaPreferenceDigest(second))
    }

    @Test fun framedKeysAndValuesRejectMapToStringCollisions() {
        val first = mapOf("a" to "x, b=y")
        val second = mapOf("a" to "x", "b" to "y")
        assertEquals(first.toString(), second.toString())
        assertNotEquals(qaPreferenceDigest(first), qaPreferenceDigest(second))
        assertNotEquals(qaPreferenceDigest(mapOf("ab" to "c")), qaPreferenceDigest(mapOf("a" to "bc")))
        assertNotEquals(qaPreferenceDigest(mapOf("a\u0000" to "b")), qaPreferenceDigest(mapOf("a" to "\u0000b")))
    }

    @Test fun framedSetValuesRejectDelimiterCollisions() {
        val first = linkedSetOf("a, b")
        val second = linkedSetOf("a", "b")
        assertEquals(first.toString(), second.toString())
        assertNotEquals(qaPreferenceDigest(mapOf("set" to first)), qaPreferenceDigest(mapOf("set" to second)))
        assertNotEquals(qaPreferenceDigest(mapOf("set" to emptySet<String>())),
            qaPreferenceDigest(mapOf("set" to setOf(""))))
    }

    @Test fun nullIsDistinctFromAbsentAndTheStringNull() {
        val hashes = listOf(qaPreferenceDigest(emptyMap<String, Any?>()),
            qaPreferenceDigest(mapOf("key" to null)), qaPreferenceDigest(mapOf("key" to "null")))
        assertEquals(3, hashes.toSet().size)
    }

    @Test fun rawFloatBitsPreserveSignedZeroAndNanPayloads() {
        val values = listOf(0f, -0f, Float.fromBits(0x7fc00001), Float.fromBits(0x7fc00002),
            Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
        assertEquals(values.size, values.map { qaPreferenceDigest(mapOf("f" to it)) }.toSet().size)
    }

    @Test fun utf16EncodingPreservesUnpairedSurrogates() {
        val first = "\uD800"
        val second = "\uD801"
        assertArrayEquals(first.toByteArray(Charsets.UTF_8), second.toByteArray(Charsets.UTF_8))
        assertNotEquals(qaPreferenceDigest(mapOf("s" to first)), qaPreferenceDigest(mapOf("s" to second)))
    }

    @Test fun unsupportedValuesAndInvalidStringSetsFailExplicitly() {
        for (value in listOf<Any>(1.0, byteArrayOf(1), listOf("a"), setOf(1), setOf(null))) {
            try {
                qaPreferenceDigest(mapOf("key" to value))
                fail("Unsupported value must not produce a preservation digest")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun failedMapReadCannotBecomeAComparableDigest() {
        val expected = IllegalStateException("read failed")
        val values = object : Map<String, Any> by mapOf("key" to 1) {
            override fun get(key: String): Any = throw expected
        }
        try {
            qaPreferenceDigest(values)
            fail("Read failure must propagate")
        } catch (error: IllegalStateException) { assertSame(expected, error) }
    }
}
