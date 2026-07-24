package dev.vitorsilverio.armbox.aarch64;

import dev.vitorsilverio.armbox.loader.BadElfException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/// Carrega um executável ELF64 AArch64 estático (`ET_EXEC`) em uma {@link Aarch64GuestMemory} —
/// irmão de {@link dev.vitorsilverio.armbox.loader.Elf32Loader} (mesmo escopo: little-endian,
/// estático, sem `PT_INTERP`/PIE), mas para o layout de 64 bits do cabeçalho ELF e dos program
/// headers (`Elf64_Ehdr`/`Elf64_Phdr` têm offsets e tamanhos de campo diferentes do ELF32 — não dá
/// para reaproveitar `Elf32Loader` trocando só o tipo do endereço).
public final class Elf64Loader {
    private static final int ELF_MAGIC = 0x464C457F; // "\x7FELF" little-endian
    private static final int ELFCLASS64 = 2;
    private static final int ELFDATA2LSB = 1;
    private static final int ET_EXEC = 2;
    private static final int ET_DYN = 3;
    private static final int EM_AARCH64 = 183;
    private static final int PT_LOAD = 1;
    private static final int PT_INTERP = 3;

    /// Tamanho de `Elf64_Ehdr`.
    private static final int EHDR_SIZE = 64;

    /// Mapeia todos os `PT_LOAD` na memória e devolve os metadados do processo.
    public Elf64Image load(byte[] elf, Aarch64GuestMemory memory) {
        if (elf.length < EHDR_SIZE) {
            throw new BadElfException("arquivo menor que um cabeçalho ELF64 (" + elf.length + " bytes)");
        }
        ByteBuffer buffer = ByteBuffer.wrap(elf).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.getInt(0) != ELF_MAGIC) {
            throw new BadElfException("magic ELF ausente");
        }
        if ((elf[4] & 0xFF) != ELFCLASS64) {
            throw new BadElfException("apenas ELF de 64 bits é suportado (EI_CLASS=" + elf[4] + ")");
        }
        if ((elf[5] & 0xFF) != ELFDATA2LSB) {
            throw new BadElfException("apenas little-endian é suportado (EI_DATA=" + elf[5] + ")");
        }
        int type = buffer.getShort(16) & 0xFFFF;
        if (type == ET_DYN) {
            throw new BadElfException("executável PIE (ET_DYN) não suportado — "
                    + "recompile/linke estático e sem -pie");
        }
        if (type != ET_EXEC) {
            throw new BadElfException("tipo ELF não executável: e_type=" + type);
        }
        int machine = buffer.getShort(18) & 0xFFFF;
        if (machine != EM_AARCH64) {
            throw new BadElfException("apenas EM_AARCH64 (183) é suportado: e_machine=" + machine);
        }
        long entry = buffer.getLong(24);
        long phoff = buffer.getLong(32);
        int phentsize = buffer.getShort(54) & 0xFFFF;
        int phnum = buffer.getShort(56) & 0xFFFF;

        long brk = 0;
        for (int i = 0; i < phnum; i++) {
            long ph = phoff + (long) i * phentsize;
            int pType = buffer.getInt((int) ph);
            if (pType == PT_INTERP) {
                throw new BadElfException("executável dinâmico (PT_INTERP) não suportado — "
                        + "recompile/linke estático");
            }
            if (pType != PT_LOAD) {
                continue;
            }
            long pOffset = buffer.getLong((int) ph + 8);
            long pVaddr = buffer.getLong((int) ph + 16);
            long pFilesz = buffer.getLong((int) ph + 32);
            long pMemsz = buffer.getLong((int) ph + 40);
            if (pMemsz == 0) {
                continue;
            }
            memory.map(pVaddr, pMemsz);
            memory.writeBytes(pVaddr, elf, (int) pOffset, (int) pFilesz);
            long end = pVaddr + pMemsz;
            brk = Math.max(brk, end);
        }
        if (brk == 0) {
            throw new BadElfException("nenhum segmento PT_LOAD com conteúdo");
        }
        long initialBrk = (brk + Aarch64GuestMemory.PAGE_SIZE - 1) & ~(Aarch64GuestMemory.PAGE_SIZE - 1L);
        return new Elf64Image(entry, initialBrk);
    }
}
