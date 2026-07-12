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

/// B4.0.2: valida Thumb-2 de verdade — binário ELF real (compilado pelo devkitARM,
/// `testdata/build-testdata.ps1`), não só equivalência Java (`Thumb2DataProcessingDecoderTest`
/// no arm-jitter). Pulado se os `.elf` não estiverem presentes.
///
/// `testdata/thumb2-torture.elf` (fonte: `testdata/thumb2-torture.s`) exercita o
/// subconjunto Thumb-2 implementado até B2.1 (infra)/B2.2 (data-processing) — exatamente
/// o que `ArmArchitecture.ARMV6K_THUMB2_PARTIAL` habilita: modified immediate com
/// carry-out, MOVW/MOVT, ADD/SUB Rd,SP/PC,#imm (ADR), forma registrador com shift
/// imediato incl. RRX. Cada grupo se autoverifica e sai com um código de saída único
/// por checagem se divergir; sucesso = exit 0.
class Thumb2TortureTest {
    private static final Path TORTURE_ELF = Path.of("testdata", "thumb2-torture.elf");
    private static final Path TORTURE_BROKEN_ELF = Path.of("testdata", "thumb2-torture-broken.elf");

    private record Result(int exitCode, String stdout) {
    }

    private static Result run(Path elfPath, Armbox.Backend backend) throws IOException {
        byte[] elf = Files.readAllBytes(elfPath);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode = Armbox.run(elf, List.of(elfPath.getFileName().toString()), List.of(), backend,
                ArmArchitecture.ARMV6K_THUMB2_PARTIAL, new ByteArrayInputStream(new byte[0]), stdout, stderr,
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Result(exitCode, stdout.toString(StandardCharsets.UTF_8));
    }

    /// Aceite principal da task: os 3 backends concordam e saem 0.
    @org.junit.jupiter.api.Test
    void tortureBinaryExitsZeroOnAllThreeBackends() throws IOException {
        assumeTrue(Files.exists(TORTURE_ELF),
                "testdata/thumb2-torture.elf ausente — rode testdata/build-testdata.ps1");
        for (Armbox.Backend backend : Armbox.Backend.values()) {
            if (backend == Armbox.Backend.TRUFFLE) {
                continue; // A5: backend Truffle não faz parte do escopo desta task (B4.0.2)
            }
            Result result = run(TORTURE_ELF, backend);
            assertEquals(0, result.exitCode(),
                    () -> backend + ": checagem " + result.exitCode() + " falhou (ver thumb2-torture.s)");
            assertEquals("thumb2 torture: ok\n", result.stdout(), backend.name());
        }
    }

    /// "Teste do teste": prova que o padrão de verificação (CHECK32: compara, sai com
    /// código != 0 se divergir) realmente detecta uma regressão — sem isso, um exit 0
    /// no teste principal não provaria nada. `thumb2-torture-broken.s` é uma cópia
    /// mínima do mesmo padrão com um valor esperado deliberadamente errado.
    @org.junit.jupiter.api.Test
    void brokenTortureBinaryDetectsTheIntentionalMismatch() throws IOException {
        assumeTrue(Files.exists(TORTURE_BROKEN_ELF),
                "testdata/thumb2-torture-broken.elf ausente — rode testdata/build-testdata.ps1");
        for (Armbox.Backend backend : new Armbox.Backend[]{Armbox.Backend.JIT, Armbox.Backend.INTERPRETED}) {
            Result result = run(TORTURE_BROKEN_ELF, backend);
            assertNotEquals(0, result.exitCode(), backend + ": deveria detectar o valor esperado errado");
            assertEquals(77, result.exitCode(), backend.name());
        }
    }
}
