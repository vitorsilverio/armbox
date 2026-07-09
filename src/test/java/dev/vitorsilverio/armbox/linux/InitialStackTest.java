package dev.vitorsilverio.armbox.linux;

import dev.vitorsilverio.armbox.loader.Elf32Image;
import dev.vitorsilverio.armbox.memory.GuestMemory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InitialStackTest {
    private static final int STACK_TOP = 0xBF000000;
    private static final int STACK_SIZE = 1024 * 1024;
    private static final int AT_NULL = 0;
    private static final int AT_PAGESZ = 6;
    private static final int AT_RANDOM = 25;

    @Test
    void layoutFollowsLinuxAbi() {
        GuestMemory memory = new GuestMemory();
        Elf32Image image = new Elf32Image(0x10054, 0x20000, 0x10034, 32, 1);

        int sp = InitialStack.build(memory, STACK_TOP, STACK_SIZE,
                List.of("hello", "arg1"), List.of("PATH=/bin"), image);

        assertEquals(0, sp & 7, "SP deve estar alinhado a 8 (EABI)");
        assertEquals(2, memory.read32(sp), "[sp] = argc");
        int argv0 = memory.read32(sp + 4);
        int argv1 = memory.read32(sp + 8);
        assertEquals("hello", memory.readCString(argv0));
        assertEquals("arg1", memory.readCString(argv1));
        assertEquals(0, memory.read32(sp + 12), "argv termina em NULL");
        int envp0 = memory.read32(sp + 16);
        assertEquals("PATH=/bin", memory.readCString(envp0));
        assertEquals(0, memory.read32(sp + 20), "envp termina em NULL");

        // auxv: varre pares até AT_NULL conferindo entradas conhecidas
        int cursor = sp + 24;
        boolean sawPagesz = false;
        boolean sawRandom = false;
        for (int i = 0; i < 32; i++) {
            int type = memory.read32(cursor);
            int value = memory.read32(cursor + 4);
            cursor += 8;
            if (type == AT_NULL) {
                break;
            }
            if (type == AT_PAGESZ) {
                assertEquals(GuestMemory.PAGE_SIZE, value);
                sawPagesz = true;
            }
            if (type == AT_RANDOM) {
                assertNotEquals(0, value);
                memory.read8(value + 15); // os 16 bytes devem estar mapeados
                sawRandom = true;
            }
        }
        assertTrue(sawPagesz, "auxv deve conter AT_PAGESZ");
        assertTrue(sawRandom, "auxv deve conter AT_RANDOM");
    }
}
