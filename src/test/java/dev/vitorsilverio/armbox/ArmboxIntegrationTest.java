package dev.vitorsilverio.armbox;

import dev.vitorsilverio.armbox.support.SyntheticElf;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Roda programas ARM completos (ELF sintético) pelo pipeline inteiro:
/// loader → pilha ABI → kuser helpers → core (interp E JIT) → syscalls.
class ArmboxIntegrationTest {
    private static final int BASE = 0x10000;

    /// write(1, "hello from armbox\n", 18); exit(42) — em ARM cru:
    private static final int[] HELLO = {
            0xE3A00001, // mov r0, #1        (fd stdout)
            0xE28F1014, // add r1, pc, #20   (r1 = msg)
            0xE3A02012, // mov r2, #18       (strlen)
            0xE3A07004, // mov r7, #4        (NR_write)
            0xEF000000, // svc #0
            0xE3A0002A, // mov r0, #42
            0xE3A07001, // mov r7, #1        (NR_exit)
            0xEF000000, // svc #0
            0x6C6C6568, // "hell"
            0x7266206F, // "o fr"
            0x61206D6F, // "om a"
            0x6F626D72, // "rmbo"
            0x00000A78, // "x\n"
    };

    /// cmpxchg(old=5, new=7, ptr=var inicial 5) via kuser helper 0xFFFF0FC0 (BLX),
    /// depois exit(*ptr) — código de saída 7 prova que o helper armazenou.
    private static final int[] KUSER_CMPXCHG = {
            0xE28F201C, // add r2, pc, #28   (r2 = &var)
            0xE3A00005, // mov r0, #5        (valor esperado)
            0xE3A01007, // mov r1, #7        (valor novo)
            0xE59F300C, // ldr r3, [pc, #12] (r3 = 0xFFFF0FC0)
            0xE12FFF33, // blx r3
            0xE5920000, // ldr r0, [r2]      (r0 = var pós-troca)
            0xE3A07001, // mov r7, #1        (NR_exit)
            0xEF000000, // svc #0
            0xFFFF0FC0, // literal: kuser_cmpxchg
            0x00000005, // var = 5
    };

    /// set_tls(42) via syscall ARM privada, depois lê de volta pelo kuser get_tls
    /// (0xFFFF0FE0) e sai com o valor — código de saída 42 fecha o ciclo.
    private static final int[] KUSER_TLS = {
            0xE59F7014, // ldr r7, [pc, #20] (r7 = ARM_NR_set_tls)
            0xE3A0002A, // mov r0, #42
            0xEF000000, // svc #0            (set_tls)
            0xE59F300C, // ldr r3, [pc, #12] (r3 = 0xFFFF0FE0)
            0xE12FFF33, // blx r3            (r0 = tls)
            0xE3A07001, // mov r7, #1        (NR_exit)
            0xEF000000, // svc #0
            0x000F0005, // literal: ARM_NR_set_tls
            0xFFFF0FE0, // literal: kuser_get_tls
    };

    private record Result(int exitCode, String stdout) {
    }

    private Result run(int[] program, Armbox.Backend backend) {
        byte[] elf = SyntheticElf.build(BASE, program);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode = Armbox.run(elf, List.of("test"), List.of(), backend,
                new ByteArrayInputStream(new byte[0]), stdout, stderr,
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Result(exitCode, stdout.toString(StandardCharsets.UTF_8));
    }

    @Test
    void helloWorldInterpreted() {
        Result result = run(HELLO, Armbox.Backend.INTERPRETED);
        assertEquals("hello from armbox\n", result.stdout());
        assertEquals(42, result.exitCode());
    }

    @Test
    void helloWorldJit() {
        Result result = run(HELLO, Armbox.Backend.JIT);
        assertEquals("hello from armbox\n", result.stdout());
        assertEquals(42, result.exitCode());
    }

    /// Backend Truffle (task A5): mesmo padrão de {@link #helloWorldJit()}, mas com o
    /// emissor {@code TruffleCodeEmitter} em vez do bytecode ASM — o backend que precisa
    /// funcionar dentro de native-image (ASM não pode, ver `Armbox#rejectAsmUnderNativeImage`).
    @Test
    void helloWorldTruffle() {
        Result result = run(HELLO, Armbox.Backend.TRUFFLE);
        assertEquals("hello from armbox\n", result.stdout());
        assertEquals(42, result.exitCode());
    }

    @Test
    void kuserCmpxchgStoresOnMatch() {
        assertEquals(7, run(KUSER_CMPXCHG, Armbox.Backend.INTERPRETED).exitCode());
        assertEquals(7, run(KUSER_CMPXCHG, Armbox.Backend.JIT).exitCode());
        assertEquals(7, run(KUSER_CMPXCHG, Armbox.Backend.TRUFFLE).exitCode());
    }

    @Test
    void setTlsRoundTripsThroughKuserGetTls() {
        assertEquals(42, run(KUSER_TLS, Armbox.Backend.INTERPRETED).exitCode());
        assertEquals(42, run(KUSER_TLS, Armbox.Backend.JIT).exitCode());
        assertEquals(42, run(KUSER_TLS, Armbox.Backend.TRUFFLE).exitCode());
    }
}
