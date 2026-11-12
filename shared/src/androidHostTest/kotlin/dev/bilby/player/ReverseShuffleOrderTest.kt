package dev.bilby.player

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

class ReverseShuffleOrderTest {

    @Test
    fun `empty order returns INDEX_UNSET everywhere`() {
        val empty = ReverseShuffleOrder(0)
        assertEquals(0, empty.length)
        assertEquals(C.INDEX_UNSET, empty.firstIndex)
        assertEquals(C.INDEX_UNSET, empty.lastIndex)
        assertEquals(C.INDEX_UNSET, empty.getNextIndex(0))
        assertEquals(C.INDEX_UNSET, empty.getPreviousIndex(0))
    }

    @Test
    fun `non-empty order traverses from lastIndex down to zero`() {
        val order = ReverseShuffleOrder(4)
        assertEquals(4, order.length)
        assertEquals(3, order.firstIndex)
        assertEquals(0, order.lastIndex)

        assertEquals(2, order.getNextIndex(3))
        assertEquals(1, order.getNextIndex(2))
        assertEquals(0, order.getNextIndex(1))
        assertEquals(C.INDEX_UNSET, order.getNextIndex(0))

        assertEquals(C.INDEX_UNSET, order.getPreviousIndex(3))
        assertEquals(3, order.getPreviousIndex(2))
        assertEquals(2, order.getPreviousIndex(1))
        assertEquals(1, order.getPreviousIndex(0))
    }

    @Test
    fun `clone mutations adjust length while keeping reverse semantics`() {
        val grown = ReverseShuffleOrder(3).cloneAndInsert(0, 2)
        assertEquals(5, grown.length)
        assertEquals(4, grown.firstIndex)
        assertEquals(0, grown.lastIndex)

        val shrunk = grown.cloneAndRemove(1, 3)
        assertEquals(3, shrunk.length)
        assertEquals(2, shrunk.firstIndex)

        val cleared = shrunk.cloneAndClear()
        assertEquals(0, cleared.length)
        assertEquals(C.INDEX_UNSET, cleared.firstIndex)
    }
}
