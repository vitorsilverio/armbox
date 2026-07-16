package dev.vitorsilverio.armbox;

import dev.vitorsilverio.armbox.support.SyntheticElf;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// B4.0.4: TPIDRURO/TPIDRURW de CP15 via {@link dev.vitorsilverio.armbox.linux.ArmboxCp15},
/// instalado só em arquiteturas >= ARMv6K (ver {@link Armbox#run}). Espelha os testes `KUSER_TLS`
/// de {@link ArmboxIntegrationTest}, mas lendo o TLS pelo caminho de coprocessador que musl/glibc
/// ARMv7 usam (`MRC p15, 0, Rt, c13, c0, 3`) em vez do kuser helper `get_tls`.
class ArmboxCp15Test {
    private static final int BASE = 0x10000;
    /// 128 + SIGSEGV(11) — mesmo código de saída que {@code Armbox#dumpRegisters} usa quando o
    /// core tenta buscar um vetor de exceção que o armbox (user-mode puro) não mapeia.
    private static final int SEGFAULT_EXIT_CODE = 139;

    /// set_tls(42) via syscall privada, depois lido de volta por
    /// `mrc p15, 0, r0, c13, c0, 3` (TPIDRURO) — prova que o CP15 e o kuser helper leem o
    /// MESMO valor de TLS (a word única em `KuserHelpers.TLS_VALUE_ADDRESS`).
    private static final int[] TPIDRURO_ROUND_TRIP = {
            0xE59F7010, // ldr r7, [pc, #16] (r7 = ARM_NR_set_tls)
            0xE3A0002A, // mov r0, #42
            0xEF000000, // svc #0            (set_tls)
            0xEE1D0F70, // mrc p15, 0, r0, c13, c0, 3  (r0 = TPIDRURO = TLS)
            0xE3A07001, // mov r7, #1        (NR_exit)
            0xEF000000, // svc #0
            0x000F0005, // literal: ARM_NR_set_tls
    };

    /// Escreve 99 em TPIDRURW via `mcr`, lê de volta via `mrc`, sai com o valor lido.
    private static final int[] TPIDRURW_ROUND_TRIP = {
            0xE3A01063, // mov r1, #99
            0xEE0D1F50, // mcr p15, 0, r1, c13, c0, 2  (TPIDRURW = 99)
            0xEE1D2F50, // mrc p15, 0, r2, c13, c0, 2  (r2 = TPIDRURW)
            0xE1A00002, // mov r0, r2
            0xE3A07001, // mov r7, #1        (NR_exit)
            0xEF000000, // svc #0
    };

    /// `mrc p15, 0, r0, c0, c0, 0` (MIDR/CPUID, fora do bloco de ID de thread) —
    /// {@link dev.vitorsilverio.armbox.linux.ArmboxCp15} não atende esse registrador (pinado,
    /// continua "não implementado"): ver a limitação documentada no javadoc de `ArmboxCp15` —
    /// como `handles(15)` é `true`, o core não intercepta o acesso antes de chamar
    /// `read`/`write`, então a única forma de não "engolir" o acesso é lançar, o que aparece
    /// aqui como uma exceção Java não capturada saindo de {@link Armbox#run}.
    private static final int[] OTHER_CP15_REGISTER_STAYS_UNDEFINED = {
            0xEE100F10, // mrc p15, 0, r0, c0, c0, 0
            0xE3A07001, // mov r7, #1        (NR_exit, nunca alcançado)
            0xEF000000, // svc #0
    };

    private record Result(int exitCode, String stdout) {
    }

    private static Result run(int[] program, Armbox.Backend backend, ArmArchitecture architecture) {
        byte[] elf = SyntheticElf.build(BASE, program);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode = Armbox.run(elf, List.of("test"), List.of(), backend, architecture,
                new ByteArrayInputStream(new byte[0]), stdout, stderr,
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Result(exitCode, stdout.toString(StandardCharsets.UTF_8));
    }

    @Test
    void tpidruroRoundTripsThroughSetTlsSyscall() {
        for (Armbox.Backend backend : new Armbox.Backend[]{
                Armbox.Backend.INTERPRETED, Armbox.Backend.JIT, Armbox.Backend.TRUFFLE}) {
            assertEquals(42, run(TPIDRURO_ROUND_TRIP, backend, ArmArchitecture.ARMV6K).exitCode(),
                    backend.name());
        }
    }

    @Test
    void tpidrurwWriteThenReadRoundTrips() {
        for (Armbox.Backend backend : new Armbox.Backend[]{
                Armbox.Backend.INTERPRETED, Armbox.Backend.JIT, Armbox.Backend.TRUFFLE}) {
            assertEquals(99, run(TPIDRURW_ROUND_TRIP, backend, ArmArchitecture.ARMV6K).exitCode(),
                    backend.name());
        }
    }

    @Test
    void unsupportedCp15RegisterStaysUndefined() {
        for (Armbox.Backend backend : new Armbox.Backend[]{
                Armbox.Backend.INTERPRETED, Armbox.Backend.JIT, Armbox.Backend.TRUFFLE}) {
            assertThrows(IllegalStateException.class,
                    () -> run(OTHER_CP15_REGISTER_STAYS_UNDEFINED, backend, ArmArchitecture.ARMV6K),
                    backend.name());
        }
    }

    /// ARMv5TE (sem `EXTEND_ROTATE`) não ganha o barramento CP15 novo — mesmo `mrc` de
    /// TPIDRURO que funciona sob ARMv6K continua Undefined, comportamento inalterado (G3).
    @Test
    void armv5teHasNoCoprocessorBusInstalled() {
        assertEquals(SEGFAULT_EXIT_CODE,
                run(TPIDRURO_ROUND_TRIP, Armbox.Backend.INTERPRETED, ArmArchitecture.ARMV5TE).exitCode());
    }
}
