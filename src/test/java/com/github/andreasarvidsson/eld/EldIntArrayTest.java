package com.github.andreasarvidsson.eld;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.github.andreasarvidsson.eld.runtime.EldIntArray;

class EldIntArrayTest {
    @Test
    void growsWithoutLosingValuesAndUsesLogicalSize() {
        final EldIntArray array = new EldIntArray();
        assertEquals(0, array.size());
        assertEquals("[]", array.toString());
        assertThrows(IndexOutOfBoundsException.class, () -> array.get(-1));
        for (int i = 0; i < 101; i++) {
            array.add(i * 3);
        }
        assertEquals(101, array.size());
        for (int i = 0; i < 101; i++) {
            assertEquals(i * 3, array.get(i));
            assertEquals(i * 3, array.get(i - 101));
        }
        array.set(-1, 42);
        assertEquals(42, array.get(100));
        assertEquals("[297, 42]", array.sliceFrom(-2).toString());
        assertEquals(array.toString(), array.copy().toString());
        for (final int index : new int[] {101, -102, Integer.MIN_VALUE,
                Integer.MAX_VALUE}) {
            assertThrows(
                IndexOutOfBoundsException.class,
                () -> array.get(index)
            );
            assertThrows(
                IndexOutOfBoundsException.class,
                () -> array.set(index, 1)
            );
        }
    }

    @Test
    void sliceBoundariesAllowSizeButRejectInvalidIndices() {
        final EldIntArray array = new EldIntArray();
        array.add(1);
        array.add(2);
        array.add(3);
        assertEquals("[1, 2, 3]", array.sliceTo(3).toString());
        assertEquals("[1, 2, 3]", array.slice(0, 3).toString());
        assertEquals("[]", array.sliceFrom(3).toString());
        assertEquals("[]", array.slice(3, 3).toString());
        assertEquals("[1, 2, 3]", array.sliceFrom(-3).toString());
        assertEquals("[2]", array.slice(-2, -1).toString());
        assertEquals("[]", array.sliceTo(-3).toString());
        final EldIntArray empty = new EldIntArray();
        assertEquals("[]", empty.slice(0, 0).toString());
        assertEquals("[]", empty.sliceFrom(0).toString());
        assertEquals("[]", empty.sliceTo(0).toString());
        for (final int index : new int[] {4, -4, Integer.MIN_VALUE,
                Integer.MAX_VALUE}) {
            assertThrows(
                IndexOutOfBoundsException.class,
                () -> array.sliceFrom(index)
            );
            assertThrows(
                IndexOutOfBoundsException.class,
                () -> array.sliceTo(index)
            );
            assertThrows(
                IndexOutOfBoundsException.class,
                () -> array.slice(index, 3)
            );
            assertThrows(
                IndexOutOfBoundsException.class,
                () -> array.slice(0, index)
            );
        }
        assertThrows(IndexOutOfBoundsException.class, () -> array.slice(2, 1));
    }
}
