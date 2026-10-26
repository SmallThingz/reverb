package app.smallthingz.reverb.nativeui

import app.smallthingz.reverb.QuickSaveTileOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class QuickTileRowCacheTest {
    private class Row(val option: QuickSaveTileOption)
    private fun option(slot: Int, revision: Char) = QuickSaveTileOption(slot, revision.toString().repeat(32))

    @Test
    fun reorderAndSameRevisionEditsReuseRowsWhileReplacementRetiresOldRevision() {
        val created = mutableListOf<Row>()
        val retired = mutableListOf<Row>()
        val cache = QuickTileRowCache(create = { Row(it).also(created::add) }, retire = retired::add)
        val a = option(7, 'a')
        val b = option(2, 'b')
        cache.retain(listOf(a, b))
        val firstA = cache.getOrCreate(a)
        val firstB = cache.getOrCreate(b)
        cache.retain(listOf(b, a))
        assertEquals(listOf(firstB, firstA), listOf(b, a).map(cache::getOrCreate))
        assertEquals(emptyList<Row>(), retired)
        val presentationOnly = a.copy(amount = 3)
        cache.retain(listOf(b, presentationOnly))
        assertSame(firstA, cache.getOrCreate(presentationOnly))
        assertEquals(2, created.size)

        val replacement = option(2, 'c')
        cache.retain(listOf(replacement, a))
        assertEquals(listOf(firstB), retired)
        val newB = cache.getOrCreate(replacement)
        assertNotSame(firstB, newB)
        assertSame(firstA, cache.getOrCreate(a))
        cache.close()
        assertEquals(listOf(firstB, firstA, newB), retired)
        cache.close()
        assertEquals(3, retired.size)
    }

    @Test
    fun removalsKeepOriginalCreationOrderAcrossDisplayedReordering() {
        val retired = mutableListOf<Row>()
        val cache = QuickTileRowCache(create = ::Row, retire = retired::add)
        val options = listOf(option(7, 'a'), option(0, 'b'), option(4, 'c'))
        cache.retain(options)
        val rows = options.map(cache::getOrCreate)
        cache.retain(options.reversed())
        assertEquals(rows.reversed(), options.reversed().map(cache::getOrCreate))
        cache.retain(emptyList())
        assertEquals(rows, retired)
        cache.close()
        assertEquals(rows, retired)
    }

    @Test
    fun invalidDuplicateSlotsCannotPartiallyRetireTheCurrentRows() {
        val retired = mutableListOf<Row>()
        val cache = QuickTileRowCache(create = ::Row, retire = retired::add)
        val original = option(3, 'a')
        cache.retain(listOf(original))
        val row = cache.getOrCreate(original)
        assertThrows(IllegalArgumentException::class.java) {
            cache.retain(listOf(original, option(3, 'b')))
        }
        assertEquals(emptyList<Row>(), retired)
        assertSame(row, cache.getOrCreate(original))
        cache.close()
        assertEquals(listOf(row), retired)
    }
}
