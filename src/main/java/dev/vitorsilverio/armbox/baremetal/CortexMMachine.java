package dev.vitorsilverio.armbox.baremetal;

import dev.vitorsilverio.armbox.loader.BadElfException;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.core.BkptDispatcher;
import dev.vitorsilverio.armjitter.core.MProfileExceptionModel;
import dev.vitorsilverio.armjitter.core.MProfileSystemControl;
import dev.vitorsilverio.armjitter.decoder.InstructionSet;
import dev.vitorsilverio.armjitter.jit.JitRuntime;
import dev.vitorsilverio.armjitter.jit.JitRuntimeFactory;
import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.memory.InvalidationAwareAddressSpace;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;
import dev.vitorsilverio.armjitter.memory.PagedAddressSpace;
import dev.vitorsilverio.armjitter.swi.CpuState;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/// Runner bare-metal Cortex-M do armbox (B7.5, `--machine=cortex-m`): sem SO, boot pela tabela
/// de vetores (ARMv7-M ARM B1.5.5) e saída via semihosting (`BKPT 0xAB`) — em vez de ELF+syscalls
/// como {@link dev.vitorsilverio.armbox.Armbox} (`linux-user`). Reusa os mesmos 3 backends
/// (JIT/interpretado/divergência) e o padrão de torture test de B4.0.1/B4.0.2.
///
/// Mapa de memória fixo: flash em `0x00000000` (imagem carregada), RAM em `0x20000000`
/// (tamanho configurável, default {@link #DEFAULT_RAM_SIZE_BYTES}), SCS em `0xE000E000`
/// ({@link MProfileSystemControl}). Usa {@link PagedAddressSpace} (C3) — primeiro consumidor
/// real do utilitário fora dos próprios testes/benchmark dele.
public final class CortexMMachine {
    private static final int FLASH_BASE = 0x0000_0000;
    private static final int FLASH_SIZE = 1 * 1024 * 1024;
    private static final int RAM_BASE = 0x2000_0000;
    /// Default do tamanho de RAM (spec B7.5, item 1): 1 MiB.
    public static final int DEFAULT_RAM_SIZE_BYTES = 1 * 1024 * 1024;
    private static final int SCS_BASE = 0xE000_E000;
    private static final int SCS_SIZE = 4096;
    /// Páginas de 4 KiB (`PagedAddressSpace`) — flash/RAM/SCS já nascem alinhados a isso.
    private static final int PAGE_SHIFT = 12;

    private static final int BLOCK_CACHE_ENTRIES = 8192;
    private static final int HOT_THRESHOLD = 3;
    /// Fatia pequena de propósito (ao contrário do `RUN_SLICE_BLOCKS` de 4096 do modo
    /// `linux-user`): o `SysTick`/NVIC precisam reagir dentro de poucos blocos — uma fatia
    /// grande atrasaria `MProfileSystemControl#tick`/a entrega de exceção pendente até o fim
    /// do lote inteiro (Armadilha da task: alimentar o `tick` com os MESMOS ciclos do runtime,
    /// mas isso não ajuda se o lote for tão grande que a resposta chegue tarde demais para o
    /// firmware de teste perceber).
    private static final int RUN_SLICE_BLOCKS = 16;
    private static final int SP_REGISTER = 13;
    /// Máximo de IRQs externas suportadas nesta fase (limite do próprio
    /// {@code MProfileExceptionModel}, um word de ISER/ICER/ISPR/ICPR/IABR).
    private static final int MAX_EXTERNAL_IRQS = 32;
    private static final int VTOR_MSP_OFFSET = 0;
    private static final int VTOR_RESET_OFFSET = 4;
    private static final int VECTOR_THUMB_BIT = 1;

    // ── Semihosting (ARM "Semihosting for AArch32/AArch64", `BKPT 0xAB`) ──
    private static final int SEMIHOSTING_BKPT_IMMEDIATE = 0xAB;
    private static final int SYS_WRITEC = 0x03;
    private static final int SYS_WRITE0 = 0x04;
    private static final int SYS_EXIT = 0x18;
    /// Limite de segurança para `SYS_WRITE0` (Armadilha da task): um firmware quebrado que nunca
    /// grava o NUL terminador não pode travar o runner lendo memória para sempre.
    private static final int SYS_WRITE0_MAX_BYTES = 64 * 1024;

    private CortexMMachine() {
    }

    /// Executa uma imagem bare-metal Cortex-M (`.elf` ou `.bin` cru em `0x0`) e devolve o código
    /// de saída reportado por `SYS_EXIT` (ou {@link CortexMExitException} nunca lançada — loop
    /// infinito — não é possível nesta assinatura; o chamador deve limitar tempo de execução no
    /// próprio firmware/teste).
    ///
    /// @param image       bytes da imagem (`.elf` ou `.bin`, ver `isElf`)
    /// @param backend     backend de execução (JIT/INTERPRETED/CHECK — TRUFFLE não é suportado
    ///                     aqui, mesma restrição de {@link dev.vitorsilverio.armbox.Armbox.Backend})
    /// @param architecture {@code ArmArchitecture.ARMV6M} ou {@code ArmArchitecture.ARMV7M}
    /// @param ramSizeBytes tamanho da RAM em `0x20000000`, múltiplo de 4 KiB
    /// @param stdout      destino de `SYS_WRITEC`/`SYS_WRITE0`
    public static int run(byte[] image, dev.vitorsilverio.armbox.Armbox.Backend backend,
            ArmArchitecture architecture, int ramSizeBytes, OutputStream stdout, PrintStream hostLog) {
        if (backend == dev.vitorsilverio.armbox.Armbox.Backend.TRUFFLE) {
            throw new UnsupportedOperationException(
                    "--machine=cortex-m não suporta o backend TRUFFLE (mesma restrição do linux-user)");
        }
        if (ramSizeBytes <= 0 || ramSizeBytes % (1 << PAGE_SHIFT) != 0) {
            throw new IllegalArgumentException(
                    "ramSizeBytes deve ser um múltiplo positivo de " + (1 << PAGE_SHIFT) + ": " + ramSizeBytes);
        }

        JitRuntime runtime = switch (backend) {
            case JIT -> JitRuntimeFactory.armThumb(BLOCK_CACHE_ENTRIES, HOT_THRESHOLD, architecture);
            case INTERPRETED -> JitRuntimeFactory.interpretedArmThumb(BLOCK_CACHE_ENTRIES, HOT_THRESHOLD, architecture);
            case CHECK -> JitRuntimeFactory.divergenceCheckingArmThumb(BLOCK_CACHE_ENTRIES, HOT_THRESHOLD, architecture);
            case TRUFFLE -> throw new AssertionError("rejeitado acima");
        };

        PagedAddressSpace pagedBus = new PagedAddressSpace(PAGE_SHIFT, OPEN_BUS);
        pagedBus.mapRam(FLASH_BASE, new byte[FLASH_SIZE]);
        pagedBus.mapRam(RAM_BASE, new byte[ramSizeBytes]);

        MProfileExceptionModel exceptionModel = new MProfileExceptionModel();
        MProfileSystemControl systemControl = new MProfileSystemControl(
                exceptionModel, MAX_EXTERNAL_IRQS, () -> { /* SYSRESETREQ: sem suporte a reset, ignorado */ });
        pagedBus.mapHandler(SCS_BASE, SCS_SIZE, new SystemControlAddressSpace(systemControl));

        AddressSpace bus = new InvalidationAwareAddressSpace(pagedBus, runtime);

        loadImage(image, pagedBus);

        ArmCore core = new ArmCore(bus, dev.vitorsilverio.armjitter.swi.SwiDispatcher.empty(), architecture);
        core.setExceptionModel(exceptionModel);
        core.setBkptDispatcher(newSemihostingDispatcher(bus, stdout));

        int msp = bus.read32(exceptionModel.vectorTableOffset() + VTOR_MSP_OFFSET);
        int resetVector = bus.read32(exceptionModel.vectorTableOffset() + VTOR_RESET_OFFSET);
        if ((resetVector & VECTOR_THUMB_BIT) == 0) {
            throw new IllegalStateException(
                    "vetor de reset com bit 0 = 0 (perfil M não tem estado ARM): 0x"
                            + Integer.toHexString(resetVector));
        }
        core.setRegister(SP_REGISTER, msp);
        core.setInstructionSet(InstructionSet.THUMB);
        core.setProgramCounter(resetVector & ~VECTOR_THUMB_BIT);

        try {
            while (true) {
                long cycles = core.runBlocks(runtime, RUN_SLICE_BLOCKS);
                systemControl.tick((int) cycles);
                if (core.halted() && core.exceptionModel().hasPendingException()) {
                    core.wake();
                }
            }
        } catch (CortexMExitException exit) {
            return exit.exitCode();
        }
    }

    /// Barramento aberto (fora de flash/RAM/SCS): lê 0, ignora escritas — mesmo comportamento de
    /// um endereço não mapeado num microcontrolador real sem bus fault modelado (fora de escopo).
    private static final AddressSpace OPEN_BUS = new AddressSpace() {
        @Override public int read8(int address) { return 0; }
        @Override public int read16(int address) { return 0; }
        @Override public int read32(int address) { return 0; }
        @Override public void write8(int address, int value) { }
        @Override public void write16(int address, int value) { }
        @Override public void write32(int address, int value) { }
        @Override public int accessCycles(int address, int sizeBytes, MemoryAccessType type) { return 0; }
        @Override public boolean providesAccessCycles() { return false; }
    };

    private static BkptDispatcher newSemihostingDispatcher(AddressSpace bus, OutputStream stdout) {
        BkptDispatcher dispatcher = BkptDispatcher.empty();
        dispatcher.register(SEMIHOSTING_BKPT_IMMEDIATE, state -> handleSemihosting(state, bus, stdout));
        return dispatcher;
    }

    private static CpuState handleSemihosting(CpuState state, AddressSpace bus, OutputStream stdout) {
        int operation = state.r0();
        int argument = state.r1();
        try {
            switch (operation) {
                case SYS_WRITEC -> stdout.write(bus.read8(argument));
                case SYS_WRITE0 -> writeString(bus, argument, stdout);
                case SYS_EXIT -> throw new CortexMExitException(argument);
                default -> { /* operação de semihosting não implementada: NOP observável */ }
            }
            stdout.flush();
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
        return state;
    }

    private static void writeString(AddressSpace bus, int address, OutputStream stdout) throws java.io.IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int i = 0; i < SYS_WRITE0_MAX_BYTES; i++) {
            int b = bus.read8(address + i) & 0xFF;
            if (b == 0) {
                break;
            }
            line.write(b);
        }
        line.writeTo(stdout);
    }

    /// Carrega `.elf` (ET_EXEC, EM_ARM, `PT_LOAD` pelos endereços físicos) ou `.bin` cru em
    /// `0x0` — detectado pelo magic ELF (spec B7.5, item 1).
    private static void loadImage(byte[] image, PagedAddressSpace bus) {
        if (isElf(image)) {
            loadElf(image, bus);
        } else {
            for (int i = 0; i < image.length; i++) {
                bus.write8(FLASH_BASE + i, image[i]);
            }
        }
    }

    private static final int ELF_MAGIC = 0x464C457F;
    private static final int ELFCLASS32 = 1;
    private static final int ELFDATA2LSB = 1;
    private static final int ET_EXEC = 2;
    private static final int EM_ARM = 40;
    private static final int PT_LOAD = 1;
    private static final int EHDR_SIZE = 52;

    private static boolean isElf(byte[] image) {
        return image.length >= 4 && ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN).getInt(0) == ELF_MAGIC;
    }

    /// Mapeia os segmentos `PT_LOAD` pelos endereços FÍSICOS do program header — sem relocação,
    /// sem pilha ABI Linux (aqui é bare-metal: quem monta MSP/PC é a tabela de vetores, não este
    /// loader). Deliberadamente não reusa {@link dev.vitorsilverio.armbox.loader.Elf32Loader}: ele
    /// mapeia sob demanda numa {@code GuestMemory} dinâmica (ABI Linux), enquanto aqui o mapa de
    /// memória já é fixo (flash/RAM pré-alocadas) — só a leitura do program header é compartilhável,
    /// e é pequena o bastante para não justificar uma abstração nova (G: sem abstração prematura).
    private static void loadElf(byte[] elf, PagedAddressSpace bus) {
        if (elf.length < EHDR_SIZE) {
            throw new BadElfException("arquivo menor que um cabeçalho ELF32 (" + elf.length + " bytes)");
        }
        ByteBuffer buffer = ByteBuffer.wrap(elf).order(ByteOrder.LITTLE_ENDIAN);
        if ((elf[4] & 0xFF) != ELFCLASS32 || (elf[5] & 0xFF) != ELFDATA2LSB) {
            throw new BadElfException("apenas ELF32 little-endian é suportado");
        }
        int type = buffer.getShort(16) & 0xFFFF;
        if (type != ET_EXEC) {
            throw new BadElfException("tipo ELF não executável (esperado ET_EXEC): e_type=" + type);
        }
        int machine = buffer.getShort(18) & 0xFFFF;
        if (machine != EM_ARM) {
            throw new BadElfException("apenas EM_ARM (40) é suportado: e_machine=" + machine);
        }
        int phoff = buffer.getInt(28);
        int phentsize = buffer.getShort(42) & 0xFFFF;
        int phnum = buffer.getShort(44) & 0xFFFF;
        for (int i = 0; i < phnum; i++) {
            int ph = phoff + i * phentsize;
            if (buffer.getInt(ph) != PT_LOAD) {
                continue;
            }
            int pOffset = buffer.getInt(ph + 4);
            int pVaddr = buffer.getInt(ph + 8);
            int pFilesz = buffer.getInt(ph + 16);
            for (int b = 0; b < pFilesz; b++) {
                bus.write8(pVaddr + b, elf[pOffset + b]);
            }
        }
    }

    /// Adapta {@link MProfileSystemControl} (offset relativo a `0xE000E000`) para
    /// {@link AddressSpace} (endereço absoluto) — par natural de `PagedAddressSpace#mapHandler`
    /// citado no javadoc de {@link MProfileSystemControl}. Registradores do SCS são sempre
    /// acessados em word; 8/16 bits fazem leitura/escrita-modificação sobre a word alinhada.
    private static final class SystemControlAddressSpace implements AddressSpace {
        private final MProfileSystemControl control;

        SystemControlAddressSpace(MProfileSystemControl control) {
            this.control = control;
        }

        private int offset(int address) {
            return address - SCS_BASE;
        }

        @Override
        public int read32(int address) {
            return control.read32(offset(address) & ~3);
        }

        @Override
        public int read16(int address) {
            int word = read32(address & ~3);
            return (word >>> ((address & 2) * 8)) & 0xFFFF;
        }

        @Override
        public int read8(int address) {
            int word = read32(address & ~3);
            return (word >>> ((address & 3) * 8)) & 0xFF;
        }

        @Override
        public void write32(int address, int value) {
            control.write32(offset(address) & ~3, value);
        }

        @Override
        public void write16(int address, int value) {
            int aligned = address & ~3;
            int shift = (address & 2) * 8;
            int word = read32(aligned);
            word = (word & ~(0xFFFF << shift)) | ((value & 0xFFFF) << shift);
            write32(aligned, word);
        }

        @Override
        public void write8(int address, int value) {
            int aligned = address & ~3;
            int shift = (address & 3) * 8;
            int word = read32(aligned);
            word = (word & ~(0xFF << shift)) | ((value & 0xFF) << shift);
            write32(aligned, word);
        }

        @Override
        public boolean providesAccessCycles() {
            return false;
        }
    }
}
