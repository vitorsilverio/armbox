package dev.vitorsilverio.armbox.support;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/// Monta um ELF32 ARM `ET_EXEC` mínimo em memória para os testes: um único `PT_LOAD`
/// mapeando o arquivo inteiro (cabeçalhos incluídos, como um kernel real faz), com o
/// código logo após os cabeçalhos.
public final class SyntheticElf {
    private static final int EHDR_SIZE = 52;
    private static final int PHDR_SIZE = 32;
    /// Offset do código no arquivo (e no segmento): logo após ehdr + 1 phdr.
    public static final int CODE_OFFSET = EHDR_SIZE + PHDR_SIZE;

    private static final int ET_EXEC = 2;
    private static final int EM_ARM = 40;
    private static final int PT_LOAD = 1;
    private static final int PF_RWX = 7;

    private SyntheticElf() {
    }

    /// Endereço virtual do início do código quando carregado em `base`.
    public static int entryAt(int base) {
        return base + CODE_OFFSET;
    }

    /// Constrói o ELF com `code` (words ARM little-endian) carregado em `base`.
    public static byte[] build(int base, int[] code) {
        int fileSize = CODE_OFFSET + code.length * Integer.BYTES;
        ByteBuffer elf = ByteBuffer.allocate(fileSize).order(ByteOrder.LITTLE_ENDIAN);
        // e_ident
        elf.putInt(0x464C457F);        // \x7FELF
        elf.put((byte) 1);             // EI_CLASS = ELFCLASS32
        elf.put((byte) 1);             // EI_DATA = little-endian
        elf.put((byte) 1);             // EI_VERSION
        elf.position(16);
        elf.putShort((short) ET_EXEC); // e_type
        elf.putShort((short) EM_ARM);  // e_machine
        elf.putInt(1);                 // e_version
        elf.putInt(entryAt(base));     // e_entry
        elf.putInt(EHDR_SIZE);         // e_phoff
        elf.putInt(0);                 // e_shoff
        elf.putInt(0);                 // e_flags
        elf.putShort((short) EHDR_SIZE);
        elf.putShort((short) PHDR_SIZE);
        elf.putShort((short) 1);       // e_phnum
        elf.putShort((short) 0);       // e_shentsize
        elf.putShort((short) 0);       // e_shnum
        elf.putShort((short) 0);       // e_shstrndx
        // program header (PT_LOAD do arquivo inteiro)
        elf.position(EHDR_SIZE);
        elf.putInt(PT_LOAD);
        elf.putInt(0);                 // p_offset
        elf.putInt(base);              // p_vaddr
        elf.putInt(base);              // p_paddr
        elf.putInt(fileSize);          // p_filesz
        elf.putInt(fileSize);          // p_memsz
        elf.putInt(PF_RWX);            // p_flags
        elf.putInt(0x1000);            // p_align
        // código
        elf.position(CODE_OFFSET);
        for (int word : code) {
            elf.putInt(word);
        }
        return elf.array();
    }
}
