package dev.vitorsilverio.armbox.loader;

import dev.vitorsilverio.armbox.memory.GuestMemory;
import dev.vitorsilverio.armbox.support.SyntheticElf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Elf32LoaderTest {
    private static final int BASE = 0x10000;

    @Test
    void loadsSegmentsAndReportsMetadata() {
        int[] code = {0xE3A00001, 0xE3A07001, 0xEF000000};
        byte[] elf = SyntheticElf.build(BASE, code);
        GuestMemory memory = new GuestMemory();

        Elf32Image image = new Elf32Loader().load(elf, memory);

        assertEquals(SyntheticElf.entryAt(BASE), image.entry());
        assertEquals(0xE3A00001, memory.read32(SyntheticElf.entryAt(BASE)));
        // brk arredondado para a próxima página após o fim do segmento
        assertEquals(BASE + GuestMemory.PAGE_SIZE, image.initialBrk());
        // phdrs estão dentro do PT_LOAD (offset 52 do arquivo mapeado em BASE)
        assertEquals(BASE + 52, image.programHeaderAddress());
        assertEquals(1, image.programHeaderCount());
        assertEquals(32, image.programHeaderEntrySize());
    }

    @Test
    void rejectsNonElf() {
        BadElfException e = assertThrows(BadElfException.class,
                () -> new Elf32Loader().load(new byte[64], new GuestMemory()));
        assertTrue(e.getMessage().contains("magic"));
    }

    @Test
    void rejectsPie() {
        byte[] elf = SyntheticElf.build(BASE, new int[]{0});
        elf[16] = 3; // e_type = ET_DYN
        BadElfException e = assertThrows(BadElfException.class,
                () -> new Elf32Loader().load(elf, new GuestMemory()));
        assertTrue(e.getMessage().contains("PIE"));
    }

    @Test
    void rejectsWrongMachine() {
        byte[] elf = SyntheticElf.build(BASE, new int[]{0});
        elf[18] = 62; // e_machine = EM_X86_64
        assertThrows(BadElfException.class, () -> new Elf32Loader().load(elf, new GuestMemory()));
    }
}
