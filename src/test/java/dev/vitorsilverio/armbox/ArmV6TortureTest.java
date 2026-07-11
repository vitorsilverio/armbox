package dev.vitorsilverio.armbox;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// B4.0.1: valida ARMv6K de verdade — binários ELF reais (compilados pelo devkitARM,
/// `testdata/build-testdata.ps1`), não só equivalência Java. Pulado se os `.elf` não
/// estiverem presentes.
///
/// `testdata/armv6k-torture.elf` (fonte: `testdata/armv6k-torture.s`) exercita pelo
/// menos um representante de cada grupo de instrução novo de B1.1-B1.6: extensão com
/// rotação (SXTB/UXTAH), REV/REV16/UMAAL, SIMD paralelo (SADD16/UQSUB8/UADD8+SEL),
/// PKHBT/SSAT/USAD8, o monitor de exclusividade LDREX/STREX/CLREX (sucesso E as duas
/// formas de falha — sem LDREX prévio e via CLREX) e as instruções de sistema
/// CPS/SETEND/WFI. Cada grupo se autoverifica e sai com um código de saída único por
/// checagem se divergir; sucesso = exit 0.
class ArmV6TortureTest {
    private static final Path TORTURE_ELF = Path.of("testdata", "armv6k-torture.elf");
    private static final Path TORTURE_BROKEN_ELF = Path.of("testdata", "armv6k-torture-broken.elf");
    private static final Path HELLO_ARMV6K_ELF = Path.of("testdata", "hello-armv6k.elf");

    private record Result(int exitCode, String stdout) {
    }

    private static Result run(Path elfPath, Armbox.Backend backend, ArmArchitecture architecture)
            throws IOException {
        byte[] elf = Files.readAllBytes(elfPath);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode = Armbox.run(elf, List.of(elfPath.getFileName().toString()), List.of(), backend, architecture,
                new ByteArrayInputStream(new byte[0]), stdout, stderr,
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Result(exitCode, stdout.toString(StandardCharsets.UTF_8));
    }

    /// Aceite principal da task: os 3 backends concordam e saem 0.
    @org.junit.jupiter.api.Test
    void tortureBinaryExitsZeroOnAllThreeBackends() throws IOException {
        assumeTrue(Files.exists(TORTURE_ELF),
                "testdata/armv6k-torture.elf ausente — rode testdata/build-testdata.ps1");
        for (Armbox.Backend backend : Armbox.Backend.values()) {
            Result result = run(TORTURE_ELF, backend, ArmArchitecture.ARMV6K);
            assertEquals(0, result.exitCode(),
                    () -> backend + ": checagem " + result.exitCode() + " falhou (ver armv6k-torture.s)");
            assertEquals("armv6k torture: ok\n", result.stdout(), backend.name());
        }
    }

    /// "Teste do teste": prova que o padrão de verificação (CHECK32: compara, sai com
    /// código != 0 se divergir) realmente detecta uma regressão — sem isso, um exit 0
    /// no teste principal não provaria nada. `armv6k-torture-broken.s` é uma cópia
    /// mínima do mesmo padrão com um valor esperado deliberadamente errado.
    @org.junit.jupiter.api.Test
    void brokenTortureBinaryDetectsTheIntentionalMismatch() throws IOException {
        assumeTrue(Files.exists(TORTURE_BROKEN_ELF),
                "testdata/armv6k-torture-broken.elf ausente — rode testdata/build-testdata.ps1");
        for (Armbox.Backend backend : new Armbox.Backend[]{Armbox.Backend.JIT, Armbox.Backend.INTERPRETED}) {
            Result result = run(TORTURE_BROKEN_ELF, backend, ArmArchitecture.ARMV6K);
            assertNotEquals(0, result.exitCode(), backend + ": deveria detectar o valor esperado errado");
            assertEquals(77, result.exitCode(), backend.name());
        }
    }

    /// Sinal complementar (item 2 da Especificação da task): código GERADO pelo
    /// compilador (não só o `.s` escrito à mão) também roda sob `--arch=armv6k`.
    @org.junit.jupiter.api.Test
    void compilerGeneratedArmv6kBinaryRunsUnderTheNewArchFlag() throws IOException {
        assumeTrue(Files.exists(HELLO_ARMV6K_ELF),
                "testdata/hello-armv6k.elf ausente — rode testdata/build-testdata.ps1");
        for (Armbox.Backend backend : new Armbox.Backend[]{Armbox.Backend.JIT, Armbox.Backend.INTERPRETED}) {
            Result result = run(HELLO_ARMV6K_ELF, backend, ArmArchitecture.ARMV6K);
            assertEquals("hello from a real ELF\n", result.stdout(), backend.name());
            assertEquals(42, result.exitCode(), backend.name());
        }
    }

    /// Regressão zero (G3 / aceite): `--arch=armv5te` (via o overload antigo de
    /// `Armbox.run`, sem `ArmArchitecture`) continua idêntico a antes — mesmo binário
    /// ARMv5TE de sempre, sem passar a nova arquitetura.
    @org.junit.jupiter.api.Test
    void defaultArchOverloadStillBehavesLikeArmv5te() throws IOException {
        Path helloElf = Path.of("testdata", "hello.elf");
        assumeTrue(Files.exists(helloElf), "testdata/hello.elf ausente — rode testdata/build-testdata.ps1");
        byte[] elf = Files.readAllBytes(helloElf);
        for (Armbox.Backend backend : new Armbox.Backend[]{Armbox.Backend.JIT, Armbox.Backend.INTERPRETED}) {
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            int exitCode = Armbox.run(elf, List.of("hello.elf"), List.of(), backend,
                    new ByteArrayInputStream(new byte[0]), stdout, stderr,
                    new PrintStream(stderr, true, StandardCharsets.UTF_8));
            assertEquals("hello from a real ELF\n", stdout.toString(StandardCharsets.UTF_8), backend.name());
            assertEquals(42, exitCode, backend.name());
        }
    }
}
