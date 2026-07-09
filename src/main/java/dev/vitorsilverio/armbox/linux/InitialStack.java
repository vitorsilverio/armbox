package dev.vitorsilverio.armbox.linux;

import dev.vitorsilverio.armbox.loader.Elf32Image;
import dev.vitorsilverio.armbox.memory.GuestMemory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/// Monta a pilha inicial de um processo Linux ARM 32-bit:
/// `[sp] = argc`, depois `argv[]`, `NULL`, `envp[]`, `NULL`, auxv (pares tipo/valor)
/// terminado em `AT_NULL`, com as strings e os bytes de `AT_RANDOM` acima dos vetores.
///
/// Referência de layout: "About ELF Auxiliary Vectors" / `fs/binfmt_elf.c` do kernel.
public final class InitialStack {
    // Tipos de entrada do auxv (include/uapi/linux/auxvec.h).
    private static final int AT_NULL = 0;
    private static final int AT_PHDR = 3;
    private static final int AT_PHENT = 4;
    private static final int AT_PHNUM = 5;
    private static final int AT_PAGESZ = 6;
    private static final int AT_ENTRY = 9;
    private static final int AT_UID = 11;
    private static final int AT_EUID = 12;
    private static final int AT_GID = 13;
    private static final int AT_EGID = 14;
    private static final int AT_HWCAP = 16;
    private static final int AT_CLKTCK = 17;
    private static final int AT_SECURE = 23;
    private static final int AT_RANDOM = 25;

    // HWCAP ARM (arch/arm/include/uapi/asm/hwcap.h): SWP|HALF|THUMB|FAST_MULT — o que
    // um ARMv5TE user-mode oferece hoje. Cresce junto com as trilhas B1/B3.
    private static final int HWCAP_SWP = 1;
    private static final int HWCAP_HALF = 2;
    private static final int HWCAP_THUMB = 4;
    private static final int HWCAP_FAST_MULT = 16;
    private static final int HWCAP_ARMV5TE = HWCAP_SWP | HWCAP_HALF | HWCAP_THUMB | HWCAP_FAST_MULT;

    private static final int UID = 1000;
    private static final int GID = 1000;
    private static final int CLOCK_TICKS_PER_SECOND = 100;
    private static final int AT_RANDOM_BYTES = 16;
    /// EABI exige SP alinhado a 8 bytes em interfaces públicas.
    private static final int STACK_ALIGNMENT = 8;

    private InitialStack() {
    }

    /// Mapeia a região de pilha e escreve o layout inicial; devolve o SP para o entry point.
    public static int build(GuestMemory memory, int stackTop, int stackSize,
                            List<String> argv, List<String> envp, Elf32Image image) {
        memory.map(stackTop - stackSize, stackSize);

        // 1. Strings e bytes de AT_RANDOM, do topo para baixo.
        int cursor = stackTop;
        cursor -= AT_RANDOM_BYTES;
        int randomAddress = cursor;
        byte[] random = new byte[AT_RANDOM_BYTES];
        new Random().nextBytes(random);
        memory.writeBytes(randomAddress, random, 0, random.length);

        List<Integer> argvAddresses = new ArrayList<>();
        for (String arg : argv) {
            cursor = pushString(memory, cursor, arg);
            argvAddresses.add(cursor);
        }
        List<Integer> envpAddresses = new ArrayList<>();
        for (String env : envp) {
            cursor = pushString(memory, cursor, env);
            envpAddresses.add(cursor);
        }

        // 2. Vetores abaixo das strings: argc + argv + NULL + envp + NULL + auxv.
        int[][] auxv = {
                {AT_PHDR, image.programHeaderAddress()},
                {AT_PHENT, image.programHeaderEntrySize()},
                {AT_PHNUM, image.programHeaderCount()},
                {AT_PAGESZ, GuestMemory.PAGE_SIZE},
                {AT_ENTRY, image.entry()},
                {AT_UID, UID},
                {AT_EUID, UID},
                {AT_GID, GID},
                {AT_EGID, GID},
                {AT_HWCAP, HWCAP_ARMV5TE},
                {AT_CLKTCK, CLOCK_TICKS_PER_SECOND},
                {AT_SECURE, 0},
                {AT_RANDOM, randomAddress},
                {AT_NULL, 0},
        };
        int vectorWords = 1 + (argvAddresses.size() + 1) + (envpAddresses.size() + 1) + auxv.length * 2;
        int sp = (cursor - vectorWords * Integer.BYTES) & -STACK_ALIGNMENT;

        int write = sp;
        write = pushWord(memory, write, argv.size());
        for (int address : argvAddresses) {
            write = pushWord(memory, write, address);
        }
        write = pushWord(memory, write, 0);
        for (int address : envpAddresses) {
            write = pushWord(memory, write, address);
        }
        write = pushWord(memory, write, 0);
        for (int[] entry : auxv) {
            write = pushWord(memory, write, entry[0]);
            write = pushWord(memory, write, entry[1]);
        }
        return sp;
    }

    private static int pushString(GuestMemory memory, int cursor, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        cursor -= bytes.length + 1;
        memory.writeBytes(cursor, bytes, 0, bytes.length);
        memory.write8(cursor + bytes.length, 0);
        return cursor;
    }

    private static int pushWord(GuestMemory memory, int address, int value) {
        memory.write32(address, value);
        return address + Integer.BYTES;
    }
}
