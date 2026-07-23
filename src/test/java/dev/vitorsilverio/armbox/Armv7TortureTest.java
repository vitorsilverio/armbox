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

/// B3.7 (fecha o épico B3): valida o preset `ArmArchitecture.ARMV7A` com binários ELF reais
/// (compilados pelo devkitARM, `testdata/build-testdata.ps1`), não só equivalência Java
/// (`ArmV7MediaDecoderTest`/`Thumb2MultiplyDivideDecoderTest`/`VfpDecoderTest`/
/// `VfpNativeEquivalenceTest` no arm-jitter). Pulado se os `.elf` não estiverem presentes.
///
/// `testdata/armv7a-torture.elf` (fonte: `testdata/armv7a-torture.s`) cobre o "inteiro v7" de
/// B3.1/B3.2 (MOVW/MOVT, bitfield, RBIT, MLS, SDIV/UDIV, DMB) mais a seção VFP de B3.3-B3.6
/// (VMOV imediato/transferência, VADD/VMUL/VDIV/VSQRT, VCMP+VMRS, VCVT, VLDR/VSTR, VPUSH/VPOP).
/// `testdata/hello-float.elf` (fonte: `testdata/hello-float.c`) é um binário `gcc` hard-float
/// REAL (`-march=armv7-a -mfpu=vfp -mfloat-abi=hard -O2`, sem CRT/libc — ver o comentário no
/// topo do `.c` para o motivo) que calcula uma série de Leibniz de 6 termos em `double` — prova
/// N3 (binário de compilador, não escrito por nós) do épico inteiro.
///
/// Esta task encontrou e corrigiu um bug REAL no arm-jitter (não no armbox): `VLDR`/`VSTR`/
/// `VLDM`/`VSTM` com `Rn=PC` (o idioma padrão de literal pool do `gcc` para constantes
/// `double`/`float`) não aplicava o viés arquitetural `PC+8` do ARM — `IrOp.VfpLoad`/`VfpStore`/
/// `VfpMultipleTransfer` não tinham o campo `baseValueOverride` que `IrOp.Load`/`Store` já
/// tinham para esse mesmo problema. Cada checagem se autoverifica e sai com um código de saída
/// único se divergir; sucesso = exit 0.
class Armv7TortureTest {
    private static final Path TORTURE_ELF = Path.of("testdata", "armv7a-torture.elf");
    private static final Path TORTURE_BROKEN_ELF = Path.of("testdata", "armv7a-torture-broken.elf");
    private static final Path HELLO_FLOAT_ELF = Path.of("testdata", "hello-float.elf");

    private record Result(int exitCode, String stdout) {
    }

    private static Result run(Path elfPath, Armbox.Backend backend) throws IOException {
        byte[] elf = Files.readAllBytes(elfPath);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode = Armbox.run(elf, List.of(elfPath.getFileName().toString()), List.of(), backend,
                ArmArchitecture.ARMV7A, new ByteArrayInputStream(new byte[0]), stdout, stderr,
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Result(exitCode, stdout.toString(StandardCharsets.UTF_8));
    }

    /// Aceite principal da task: os 3 backends concordam e saem 0 no torture test ARMv7-A.
    @org.junit.jupiter.api.Test
    void tortureBinaryExitsZeroOnAllThreeBackends() throws IOException {
        assumeTrue(Files.exists(TORTURE_ELF),
                "testdata/armv7a-torture.elf ausente — rode testdata/build-testdata.ps1");
        for (Armbox.Backend backend : Armbox.Backend.values()) {
            if (backend == Armbox.Backend.TRUFFLE) {
                continue; // A5/A6: backend Truffle não faz parte do escopo desta task (B3.7)
            }
            Result result = run(TORTURE_ELF, backend);
            assertEquals(0, result.exitCode(),
                    () -> backend + ": checagem " + result.exitCode() + " falhou (ver armv7a-torture.s)");
            assertEquals("armv7a torture: ok\n", result.stdout(), backend.name());
        }
    }

    /// "Teste do teste": prova que o padrão de verificação (CHECK32: compara, sai com código != 0
    /// se divergir) realmente detecta uma regressão — sem isso, um exit 0 no teste principal não
    /// provaria nada. `armv7a-torture-broken.s` é uma cópia mínima do mesmo padrão com um valor
    /// esperado deliberadamente errado (RBIT).
    @org.junit.jupiter.api.Test
    void brokenTortureBinaryDetectsTheIntentionalMismatch() throws IOException {
        assumeTrue(Files.exists(TORTURE_BROKEN_ELF),
                "testdata/armv7a-torture-broken.elf ausente — rode testdata/build-testdata.ps1");
        for (Armbox.Backend backend : new Armbox.Backend[]{Armbox.Backend.JIT, Armbox.Backend.INTERPRETED}) {
            Result result = run(TORTURE_BROKEN_ELF, backend);
            assertNotEquals(0, result.exitCode(), backend + ": deveria detectar o valor esperado errado");
            assertEquals(77, result.exitCode(), backend.name());
        }
    }

    /// Aceite N3 do épico: binário `gcc` hard-float REAL (não escrito por nós), série de Leibniz
    /// em `double`, stdout idêntico bit-a-bit nos 3 backends. Este teste foi o que primeiro
    /// revelou o bug do `baseValueOverride` de VFP documentado na javadoc da classe — antes do
    /// fix, JIT e interpretado divergiam entre si E do valor correto.
    @org.junit.jupiter.api.Test
    void helloFloatBinaryProducesIdenticalOutputOnAllThreeBackends() throws IOException {
        assumeTrue(Files.exists(HELLO_FLOAT_ELF),
                "testdata/hello-float.elf ausente — rode testdata/build-testdata.ps1");
        final String expected = "0.744012\n"; // Leibniz de 6 termos: 1 - 1/3 + 1/5 - 1/7 + 1/9 - 1/11
        for (Armbox.Backend backend : Armbox.Backend.values()) {
            if (backend == Armbox.Backend.TRUFFLE) {
                continue;
            }
            Result result = run(HELLO_FLOAT_ELF, backend);
            assertEquals(0, result.exitCode(), backend.name());
            assertEquals(expected, result.stdout(), backend.name());
        }
    }
}
