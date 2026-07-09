package dev.vitorsilverio.armbox;

import dev.vitorsilverio.armbox.linux.GuestExitException;
import dev.vitorsilverio.armbox.linux.InitialStack;
import dev.vitorsilverio.armbox.linux.KuserHelpers;
import dev.vitorsilverio.armbox.linux.LinuxGuest;
import dev.vitorsilverio.armbox.loader.Elf32Image;
import dev.vitorsilverio.armbox.loader.Elf32Loader;
import dev.vitorsilverio.armbox.memory.GuestMemory;
import dev.vitorsilverio.armbox.memory.GuestSegmentationFault;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import dev.vitorsilverio.armjitter.jit.JitRuntime;
import dev.vitorsilverio.armjitter.jit.JitRuntimeFactory;
import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.memory.InvalidationAwareAddressSpace;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.List;

/// Executa um ELF ARM Linux user-mode: carrega, monta a pilha ABI, mapeia os kuser
/// helpers e roda o core com syscalls traduzidas — estilo qemu-user.
public final class Armbox {
    /// Backend de execução do CPU core.
    public enum Backend {
        /// JIT bytecode JVM (produção).
        JIT,
        /// Interpretador IR (debug/oráculo).
        INTERPRETED,
        /// Executa JIT e interpretador em paralelo, abortando na primeira divergência.
        CHECK
    }

    private static final int BLOCK_CACHE_ENTRIES = 8192;
    private static final int HOT_THRESHOLD = 3;
    /// Blocos por fatia do loop principal (não há hardware a atender entre fatias).
    private static final int RUN_SLICE_BLOCKS = 4096;
    /// Topo da pilha inicial (abaixo da faixa 0xC0000000 do kernel real).
    private static final int STACK_TOP = 0xBF000000;
    private static final int STACK_SIZE = 8 * 1024 * 1024;
    /// Registrador SP na convenção ARM.
    private static final int SP_REGISTER = 13;
    private static final int THUMB_ENTRY_BIT = 1;

    private Armbox() {
    }

    /// Executa `elf` com os argumentos dados e devolve o código de saída do guest.
    ///
    /// `argv[0]` deve ser o nome do programa (como no execve real).
    public static int run(byte[] elf, List<String> argv, List<String> envp, Backend backend,
                          InputStream stdin, OutputStream stdout, OutputStream stderr,
                          PrintStream hostLog) {
        GuestMemory memory = new GuestMemory();
        Elf32Image image = new Elf32Loader().load(elf, memory);
        KuserHelpers.mapInto(memory);

        LinuxGuest guest = new LinuxGuest(memory, stdin, stdout, stderr, hostLog);
        guest.setInitialBrk(image.initialBrk());

        JitRuntime runtime = switch (backend) {
            case JIT -> JitRuntimeFactory.armThumb(
                    BLOCK_CACHE_ENTRIES, HOT_THRESHOLD, ArmArchitecture.ARMV5TE);
            case INTERPRETED -> JitRuntimeFactory.interpretedArmThumb(
                    BLOCK_CACHE_ENTRIES, HOT_THRESHOLD, ArmArchitecture.ARMV5TE);
            case CHECK -> JitRuntimeFactory.divergenceCheckingArmThumb(
                    BLOCK_CACHE_ENTRIES, HOT_THRESHOLD, ArmArchitecture.ARMV5TE);
        };
        AddressSpace bus = new InvalidationAwareAddressSpace(memory, runtime);
        ArmCore core = new ArmCore(bus, guest.dispatcher(), ArmArchitecture.ARMV5TE);
        guest.attach(core);

        int stackPointer = InitialStack.build(memory, STACK_TOP, STACK_SIZE, argv, envp, image);
        boolean thumbEntry = (image.entry() & THUMB_ENTRY_BIT) != 0;
        core.configureExecutionState(
                image.entry() & ~THUMB_ENTRY_BIT,
                CpuMode.SYSTEM,
                thumbEntry ? InstructionSet.THUMB : InstructionSet.ARM,
                true,
                true);
        core.setRegister(SP_REGISTER, stackPointer);

        try {
            while (true) {
                core.runBlocks(runtime, RUN_SLICE_BLOCKS);
            }
        } catch (GuestExitException exit) {
            return exit.exitCode();
        } catch (GuestSegmentationFault fault) {
            dumpRegisters(core, fault, hostLog);
            return SEGFAULT_EXIT_CODE;
        }
    }

    /// 128 + SIGSEGV(11), como um shell reportaria.
    private static final int SEGFAULT_EXIT_CODE = 139;
    private static final int LR_REGISTER = 14;

    private static void dumpRegisters(ArmCore core, GuestSegmentationFault fault, PrintStream log) {
        log.printf("armbox: %s%n", fault.getMessage());
        log.printf("  pc=%08X lr=%08X sp=%08X cpsr=%08X%n",
                core.programCounter(), core.register(LR_REGISTER),
                core.register(SP_REGISTER), core.cpsr().get());
        for (int i = 0; i < 13; i += 4) {
            StringBuilder line = new StringBuilder("  ");
            for (int r = i; r < Math.min(i + 4, 13); r++) {
                line.append("r%-2d=%08X ".formatted(r, core.register(r)));
            }
            log.println(line.toString().stripTrailing());
        }
    }
}
