package dev.vitorsilverio.armbox;

import dev.vitorsilverio.armbox.linux.ArmboxCp15;
import dev.vitorsilverio.armbox.linux.GuestExitException;
import dev.vitorsilverio.armbox.linux.InitialStack;
import dev.vitorsilverio.armbox.linux.KuserHelpers;
import dev.vitorsilverio.armbox.linux.LinuxGuest;
import dev.vitorsilverio.armbox.loader.Elf32Image;
import dev.vitorsilverio.armbox.loader.Elf32Loader;
import dev.vitorsilverio.armbox.memory.GuestMemory;
import dev.vitorsilverio.armbox.memory.GuestSegmentationFault;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.arch.ArmFeature;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.CpuMode;
import dev.vitorsilverio.armjitter.debug.GdbServer;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import dev.vitorsilverio.armjitter.jit.JitRuntime;
import dev.vitorsilverio.armjitter.jit.JitRuntimeFactory;
import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.memory.InvalidationAwareAddressSpace;
import dev.vitorsilverio.armjitter.truffle.TruffleJitRuntimeFactory;
import org.graalvm.nativeimage.ImageInfo;

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
        CHECK,
        /// JIT com o backend Truffle (task A5) em vez do backend ASM. Único backend que
        /// compila blocos em runtime dentro de um binário native-image — o ASM define
        /// classes em runtime via `defineClass`, o que native-image não suporta (ver
        /// {@link #run} para a rejeição explícita do backend ASM sob native-image).
        TRUFFLE
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

    /// Recusa o backend ASM (`Backend#JIT`/`Backend#CHECK`) cedo e com mensagem clara quando
    /// rodando dentro de um binário native-image (task A5, "Armadilhas"): `AsmCodeEmitter`
    /// define classes em runtime via `defineClass`, algo que native-image não suporta —
    /// sem esta checagem o binário quebraria mais tarde, no primeiro bloco quente, com um
    /// erro obscuro de classloading. `Backend#TRUFFLE` é a alternativa correta sob
    /// native-image (compila blocos em runtime via Graal/Truffle).
    private static void rejectAsmUnderNativeImage() {
        if (ImageInfo.inImageCode()) {
            throw new UnsupportedOperationException(
                    "backend ASM (JIT/CHECK) indisponivel sob native-image: AsmCodeEmitter "
                            + "define classes em runtime (defineClass), que native-image nao "
                            + "suporta. Use Armbox.Backend.TRUFFLE (--truffle na CLI) ou "
                            + "Backend.INTERPRETED.");
        }
    }

    /// Executa `elf` com os argumentos dados e devolve o código de saída do guest.
    ///
    /// `argv[0]` deve ser o nome do programa (como no execve real). Equivalente a
    /// {@link #run(byte[], List, List, Backend, ArmArchitecture, InputStream, OutputStream,
    /// OutputStream, PrintStream)} com {@link ArmArchitecture#ARMV5TE} (comportamento
    /// histórico do armbox, preservado sem mudança de assinatura).
    public static int run(byte[] elf, List<String> argv, List<String> envp, Backend backend,
                          InputStream stdin, OutputStream stdout, OutputStream stderr,
                          PrintStream hostLog) {
        return run(elf, argv, envp, backend, ArmArchitecture.ARMV5TE,
                stdin, stdout, stderr, hostLog);
    }

    /// Executa `elf` com os argumentos dados na arquitetura ARM informada e devolve o
    /// código de saída do guest.
    ///
    /// `argv[0]` deve ser o nome do programa (como no execve real).
    public static int run(byte[] elf, List<String> argv, List<String> envp, Backend backend,
                          ArmArchitecture architecture,
                          InputStream stdin, OutputStream stdout, OutputStream stderr,
                          PrintStream hostLog) {
        return run(elf, argv, envp, backend, architecture, stdin, stdout, stderr, hostLog, 0);
    }

    /// Mesmo que {@link #run(byte[], List, List, Backend, ArmArchitecture, InputStream,
    /// OutputStream, OutputStream, PrintStream)}, mas com um stub GDB remote serial opcional
    /// (`gdbPort > 0`): em vez do loop normal, bloqueia servindo um cliente GDB
    /// (`arm-none-eabi-gdb <elf> -ex "target remote :PORT"`) até ele se desconectar. O passo de
    /// depuração usa {@link ArmCore#step()} — o interpretador puro, não o `backend` escolhido —
    /// para fidelidade de instrução a instrução independente do backend (mesmo padrão do stub GDB
    /// do ndsemu, `NdsConsole#gdbStepArm9`); `backend` ainda decide o `JitRuntime` instalado, mas
    /// ele só é exercitado se o guest ficar rodando fora do controle do gdb depois do `D`etach.
    public static int run(byte[] elf, List<String> argv, List<String> envp, Backend backend,
                          ArmArchitecture architecture,
                          InputStream stdin, OutputStream stdout, OutputStream stderr,
                          PrintStream hostLog, int gdbPort) {
        GuestMemory memory = new GuestMemory();
        Elf32Image image = new Elf32Loader().load(elf, memory);
        KuserHelpers.mapInto(memory);

        LinuxGuest guest = new LinuxGuest(memory, stdin, stdout, stderr, hostLog);
        guest.setInitialBrk(image.initialBrk());

        JitRuntime runtime = switch (backend) {
            case JIT -> {
                rejectAsmUnderNativeImage();
                yield JitRuntimeFactory.armThumb(BLOCK_CACHE_ENTRIES, HOT_THRESHOLD, architecture);
            }
            case INTERPRETED -> JitRuntimeFactory.interpretedArmThumb(
                    BLOCK_CACHE_ENTRIES, HOT_THRESHOLD, architecture);
            case CHECK -> {
                // divergenceCheckingArmThumb roda o backend ASM em paralelo ao interpretador
                // (ver JitRuntimeFactory) — mesma restrição do caso JIT sob native-image.
                rejectAsmUnderNativeImage();
                yield JitRuntimeFactory.divergenceCheckingArmThumb(
                        BLOCK_CACHE_ENTRIES, HOT_THRESHOLD, architecture);
            }
            case TRUFFLE -> TruffleJitRuntimeFactory.truffleArmThumb(
                    BLOCK_CACHE_ENTRIES, HOT_THRESHOLD, architecture);
        };
        AddressSpace bus = new InvalidationAwareAddressSpace(memory, runtime);
        ArmCore core = new ArmCore(bus, guest.dispatcher(), architecture);
        // TPIDRURO/TPIDRURW (B4.0.4): só arquiteturas >= ARMv6K têm esses registradores de CP15
        // no hardware real. `EXTEND_ROTATE` é uma feature introduzida em ARMV6K (arbitrária entre
        // as suas, só usada aqui como marcador de "é v6k ou estende v6k") e ausente em ARMV5TE —
        // ARMV5TE fica sem barramento nenhum, comportamento inalterado (MRC/MCR de CP15 vira
        // Undefined como hoje).
        if (architecture.has(ArmFeature.EXTEND_ROTATE)) {
            core.setCoprocessorBus(new ArmboxCp15(memory));
        }
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

        Runnable stepOne = () -> {
            core.step();
            // Mesmo raciocínio de "WFI é um no-op observável em user-mode" do loop normal
            // abaixo, aplicado passo a passo.
            if (core.halted()) {
                core.wake();
            }
        };
        try {
            if (gdbPort > 0) {
                hostLog.printf("armbox: gdb stub on port %d. connect with:%n  arm-none-eabi-gdb <elf> -ex \"target remote :%d\"%n",
                        gdbPort, gdbPort);
                GdbServer.listenAndServe(gdbPort, core, bus, stepOne);
                return 0;
            }
            while (true) {
                core.runBlocks(runtime, RUN_SLICE_BLOCKS);
                // armbox é user-mode puro: não há temporizador nem controlador de IRQ (isso
                // é infraestrutura de sistema completo, fora de escopo — ver B4.1). No Linux
                // real, um WFI em modo usuário só estala o pipeline até o próximo tick do
                // timer do kernel (tipicamente <10ms) e a execução do processo continua
                // normalmente, sem qualquer exceção sendo entregue ao processo. `wake()` aqui
                // reproduz esse efeito: acorda o core sem passar por `setInterruptLine`/vetor
                // de exceção IRQ (que exigiriam uma tabela de vetores mapeada, que o guest
                // user-mode não tem) — WFI vira, na prática, um no-op observável.
                if (core.halted()) {
                    core.wake();
                }
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
