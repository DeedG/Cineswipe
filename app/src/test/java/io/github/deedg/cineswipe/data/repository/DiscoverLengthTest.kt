package io.github.deedg.cineswipe.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverLengthTest {

    @Test
    fun lengthsFilterRuntimesAndBuildTmdbParams() {
        assertTrue(DiscoverLength.ANY.accepts(null))
        assertTrue(DiscoverLength.SHORT.accepts(100))
        assertFalse(DiscoverLength.SHORT.accepts(101))
        assertFalse(DiscoverLength.SHORT.accepts(null))
        assertTrue(DiscoverLength.MEDIUM.accepts(130))
        assertTrue(DiscoverLength.LONG.accepts(131))
        assertFalse(DiscoverLength.LONG.accepts(130))

        val short = DiscoverFilters(length = DiscoverLength.SHORT).toQuery().toParams("fr-FR", 1)
        assertEquals("100", short["with_runtime.lte"])
        assertNull(short["with_runtime.gte"])
        val long = DiscoverFilters(length = DiscoverLength.LONG).toQuery().toParams("fr-FR", 1)
        assertEquals("131", long["with_runtime.gte"])
        assertNull(DiscoverFilters().toQuery().toParams("fr-FR", 1)["with_runtime.lte"])
    }
}
