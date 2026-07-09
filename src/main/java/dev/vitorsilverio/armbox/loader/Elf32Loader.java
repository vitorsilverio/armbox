package dev.vitorsilverio.armbox.loader;

import dev.vitorsilverio.armbox.memory.GuestMemory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/// Carrega um executável ELF32 ARM estático (`ET_EXEC`) em uma [GuestMemory].
///
/// Escopo da fase 1 do runner: little-endian, estático, sem interpretador (`PT_INTERP`)
/// e sem PIE (`ET_DYN`) — casos fora disso falham com mensagem clara.
public final class Elf32Loader {
    private static final int ELF_MAGIC = 0x464C457F; // "\x7FELF" little-endian
    private static final int ELFCLASS32 = 1;
    private static final int ELFDATA2LSB = 1;
    private static final int ET_EXEC = 2;
    private static final int ET_DYN = 3;
    private static final int EM_ARM = 40;
    private static final int PT_LOAD = 1;
    private static final int PT_INTERP = 3;

    private static final int EHDR_SIZE = 52;

    /// Mapeia todos os `PT_LOAD` na memória e devolve os metadados do processo.
    public Elf32Image load(byte[] elf, GuestMemory memory) {
        if (elf.length < EHDR_SIZE) {
            throw new BadElfException("arquivo menor que um cabeçalho ELF32 (" + elf.length + " bytes)");
        }
        ByteBuffer buffer = ByteBuffer.wrap(elf).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.getInt(0) != ELF_MAGIC) {
            throw new BadElfException("magic ELF ausente");
        }
        if ((elf[4] & 0xFF) != ELFCLASS32) {
            throw new BadElfException("apenas ELF de 32 bits é suportado (EI_CLASS=" + elf[4] + ")");
        }
        if ((elf[5] & 0xFF) != ELFDATA2LSB) {
            throw new BadElfException("apenas little-endian é suportado (EI_DATA=" + elf[5] + ")");
        }
        int type = buffer.getShort(16) & 0xFFFF;
        if (type == ET_DYN) {
            throw new BadElfException("executável PIE (ET_DYN) não suportado na fase 1 — "
                    + "recompile com -static e sem -pie");
        }
        if (type != ET_EXEC) {
            throw new BadElfException("tipo ELF não executável: e_type=" + type);
        }
        int machine = buffer.getShort(18) & 0xFFFF;
        if (machine != EM_ARM) {
            throw new BadElfException("apenas EM_ARM (40) é suportado: e_machine=" + machine);
        }
        int entry = buffer.getInt(24);
        int phoff = buffer.getInt(28);
        int phentsize = buffer.getShort(42) & 0xFFFF;
        int phnum = buffer.getShort(44) & 0xFFFF;

        long brk = 0;
        int phdrAddress = 0;
        for (int i = 0; i < phnum; i++) {
            int ph = phoff + i * phentsize;
            int pType = buffer.getInt(ph);
            if (pType == PT_INTERP) {
                throw new BadElfException("executável dinâmico (PT_INTERP) não suportado — "
                        + "recompile com -static");
            }
            if (pType != PT_LOAD) {
                continue;
            }
            int pOffset = buffer.getInt(ph + 4);
            int pVaddr = buffer.getInt(ph + 8);
            int pFilesz = buffer.getInt(ph + 16);
            int pMemsz = buffer.getInt(ph + 20);
            if (pMemsz == 0) {
                continue;
            }
            memory.map(pVaddr, pMemsz);
            memory.writeBytes(pVaddr, elf, pOffset, pFilesz);
            long end = Integer.toUnsignedLong(pVaddr) + Integer.toUnsignedLong(pMemsz);
            brk = Math.max(brk, end);
            if (phdrAddress == 0
                    && Integer.toUnsignedLong(phoff) >= Integer.toUnsignedLong(pOffset)
                    && Integer.toUnsignedLong(phoff) + (long) phnum * phentsize
                            <= Integer.toUnsignedLong(pOffset) + Integer.toUnsignedLong(pFilesz)) {
                phdrAddress = pVaddr + (phoff - pOffset);
            }
        }
        if (brk == 0) {
            throw new BadElfException("nenhum segmento PT_LOAD com conteúdo");
        }
        int initialBrk = (int) ((brk + GuestMemory.PAGE_SIZE - 1) & ~(GuestMemory.PAGE_SIZE - 1L));
        return new Elf32Image(entry, initialBrk, phdrAddress, phentsize, phnum);
    }
}
