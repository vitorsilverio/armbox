package dev.vitorsilverio.armbox.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuestMemoryTest {
    @Test
    void readsAndWritesLittleEndian() {
        GuestMemory memory = new GuestMemory();
        memory.map(0x10000, GuestMemory.PAGE_SIZE);
        memory.write32(0x10000, 0x12345678);
        assertEquals(0x78, memory.read8(0x10000));
        assertEquals(0x12, memory.read8(0x10003));
        assertEquals(0x5678, memory.read16(0x10000));
        assertEquals(0x12345678, memory.read32(0x10000));
    }

    @Test
    void unmappedAccessFaults() {
        GuestMemory memory = new GuestMemory();
        GuestSegmentationFault fault =
                assertThrows(GuestSegmentationFault.class, () -> memory.read32(0xDEAD0000));
        assertEquals(0xDEAD0000, fault.address());
        assertThrows(GuestSegmentationFault.class, () -> memory.write8(0x500, 1));
    }

    @Test
    void wordAccessCrossesPageBoundary() {
        GuestMemory memory = new GuestMemory();
        memory.map(0x10000, 2 * GuestMemory.PAGE_SIZE);
        int boundary = 0x10000 + GuestMemory.PAGE_SIZE - 2;
        memory.write32(boundary, 0xCAFEBABE);
        assertEquals(0xCAFEBABE, memory.read32(boundary));
    }

    @Test
    void unmapMakesPagesInaccessible() {
        GuestMemory memory = new GuestMemory();
        memory.map(0x20000, GuestMemory.PAGE_SIZE);
        assertTrue(memory.isMapped(0x20000));
        memory.unmap(0x20000, GuestMemory.PAGE_SIZE);
        assertFalse(memory.isMapped(0x20000));
        assertThrows(GuestSegmentationFault.class, () -> memory.read8(0x20000));
    }

    @Test
    void highAddressesUseUnsignedPaging() {
        GuestMemory memory = new GuestMemory();
        memory.map(0xFFFF0000, GuestMemory.PAGE_SIZE);
        memory.write32(0xFFFF0FF0, 0x42);
        assertEquals(0x42, memory.read32(0xFFFF0FF0));
    }
}
