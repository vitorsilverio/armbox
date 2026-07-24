package dev.vitorsilverio.armbox.aarch64;

import dev.vitorsilverio.armbox.linux.GuestExitException;
import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.executor64.Ir64BlockExecutor;

import java.io.OutputStream;
import java.io.PrintStream;

/// Runner `linux-user` AArch64 do armbox (`--arch=aarch64`): carrega um ELF64 estático, monta uma
/// pilha mínima e roda via {@link Ir64BlockExecutor} (interpretador puro — B6.2 não tem JIT/tiered
/// para A64, isso é B6.4) com syscalls traduzidas por {@link Aarch64LinuxGuest}. Irmão de
/// {@link dev.vitorsilverio.armbox.Armbox} (ARM 32-bit) — deliberadamente uma classe SEPARADA, não
/// uma sobrecarga: o core, a memória, o loader e a ABI de syscall são todos tipos de 64 bits
/// diferentes (G2/G3 do arm-jitter: os dois mundos não se misturam).
public final class Aarch64LinuxMachine {
    /// Topo da pilha inicial — bem abaixo do topo teórico do espaço de endereço de 64 bits,
    /// mesma ideia de {@code Armbox#STACK_TOP} (deixa margem folgada abaixo dele para o restante
    /// do processo, mesmo sem mmap real implementado ainda).
    private static final long STACK_TOP = 0x0000_7FFF_FFFF_0000L;
    private static final long STACK_SIZE = 8L * 1024 * 1024;
    /// Blocos (instruções) por fatia do loop principal — não há hardware a atender entre fatias
    /// (mesmo raciocínio do `RUN_SLICE_BLOCKS` do runner 32-bit).
    private static final int RUN_SLICE_INSTRUCTIONS = 4096;
    /// `[sp]=argc(0) [sp+8]=argv-NULL [sp+16]=envp-NULL [sp+24]=auxv AT_NULL.type` — layout ABI
    /// mínimo válido (o `AT_NULL.value` em `sp+32` já vem zerado pela página recém-mapeada, sem
    /// precisar de um `write64` explícito). `hello-aarch64.s` não lê nada disso (sem libc), mas
    /// um SP com o formato correto custa nada e evita surpresas caso um binário futuro (busybox)
    /// o leia.
    private static final int MINIMAL_ABI_STACK_WORDS = 4;
    private static final long STACK_ALIGNMENT_MASK = ~0xFL; // EABI64: SP alinhado a 16 bytes

    private Aarch64LinuxMachine() {
    }

    /// Executa `elf` e devolve o código de saída do guest.
    public static int run(byte[] elf, OutputStream stdout, OutputStream stderr, PrintStream hostLog) {
        Aarch64GuestMemory memory = new Aarch64GuestMemory();
        Elf64Image image = new Elf64Loader().load(elf, memory);

        Aarch64LinuxGuest guest = new Aarch64LinuxGuest(memory, stdout, stderr, hostLog);
        guest.setInitialBrk(image.initialBrk());

        Aarch64Core core = new Aarch64Core(memory);
        core.setSvcHandler(guest);

        long stackBase = STACK_TOP - STACK_SIZE;
        memory.map(stackBase, STACK_SIZE);
        long sp = (STACK_TOP - (long) MINIMAL_ABI_STACK_WORDS * Long.BYTES) & STACK_ALIGNMENT_MASK;
        memory.write64(sp, 0);              // argc = 0
        memory.write64(sp + 8, 0);          // argv NULL
        memory.write64(sp + 16, 0);         // envp NULL
        memory.write64(sp + 24, 0);         // auxv AT_NULL.type (value em sp+32 já é 0)
        core.setSp(sp);
        core.setProgramCounter(image.entry());

        Ir64BlockExecutor executor = new Ir64BlockExecutor();
        try {
            while (true) {
                executor.run(core, RUN_SLICE_INSTRUCTIONS);
            }
        } catch (GuestExitException exit) {
            return exit.exitCode();
        }
    }
}
