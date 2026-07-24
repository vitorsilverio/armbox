package dev.vitorsilverio.armbox;

import dev.vitorsilverio.armbox.baremetal.CortexMMachine;
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

/// B7.5: valida `--machine=cortex-m` — binários ELF reais bare-metal ARMv6-M/ARMv7-M
/// (compilados pelo devkitARM, `testdata/build-testdata.ps1`), não só equivalência Java. Espelha
/// o padrão de {@link Thumb2TortureTest}. Pulado se os `.elf` não estiverem presentes.
///
/// `cortexm-torture.elf`/`cortexm-torture-m0.elf` (fontes: `testdata/cortexm-torture*.s`)
/// cobrem: reset com MSP correto, `SVC` em MSP e PSP, `SysTick`, `PendSV` pendido de dentro de
/// outro handler (prioridade), `PRIMASK`, `MRS`/`MSR` de MSP/PSP/CONTROL/PRIMASK e (só a
/// variante v7-M) `MOVW`/`MOVT`/`SDIV`/`UDIV`/`UBFX`/`LDREX`+`STREX`. Cada firmware sai via
/// semihosting (`BKPT 0xAB`) com 0 (tudo passou) ou 1 (alguma checagem falhou).
class CortexMTortureTest {
    private static final Path TORTURE_M3_ELF = Path.of("testdata", "cortexm-torture.elf");
    private static final Path TORTURE_M3_BROKEN_ELF = Path.of("testdata", "cortexm-torture-broken.elf");
    private static final Path TORTURE_M0_ELF = Path.of("testdata", "cortexm-torture-m0.elf");
    private static final Path HELLO_ELF = Path.of("testdata", "hello-cortexm.elf");
    private static final Path LINUX_USER_HELLO_ELF = Path.of("testdata", "hello.elf");

    private record Result(int exitCode, String stdout) {
    }

    private static Result run(Path elfPath, Armbox.Backend backend, ArmArchitecture architecture)
            throws IOException {
        byte[] image = Files.readAllBytes(elfPath);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int exitCode = CortexMMachine.run(image, backend, architecture,
                CortexMMachine.DEFAULT_RAM_SIZE_BYTES, stdout, System.err);
        return new Result(exitCode, stdout.toString(StandardCharsets.UTF_8));
    }

    private static Armbox.Backend[] nonTruffleBackends() {
        return new Armbox.Backend[]{Armbox.Backend.JIT, Armbox.Backend.INTERPRETED, Armbox.Backend.CHECK};
    }

    /// Aceite principal (v7-M): os 3 backends concordam e saem 0.
    @org.junit.jupiter.api.Test
    void m3TortureExitsZeroOnAllThreeBackends() throws IOException {
        assumeTrue(Files.exists(TORTURE_M3_ELF),
                "testdata/cortexm-torture.elf ausente — rode testdata/build-testdata.ps1");
        for (Armbox.Backend backend : nonTruffleBackends()) {
            Result result = run(TORTURE_M3_ELF, backend, ArmArchitecture.ARMV7M);
            assertEquals(0, result.exitCode(), () -> backend + ": alguma checagem falhou (ver cortexm-torture.s)");
        }
    }

    /// Aceite principal (v6-M): mesmo torture test, subconjunto sem divide/bitfield/exclusivo.
    @org.junit.jupiter.api.Test
    void m0TortureExitsZeroOnAllThreeBackends() throws IOException {
        assumeTrue(Files.exists(TORTURE_M0_ELF),
                "testdata/cortexm-torture-m0.elf ausente — rode testdata/build-testdata.ps1");
        for (Armbox.Backend backend : nonTruffleBackends()) {
            Result result = run(TORTURE_M0_ELF, backend, ArmArchitecture.ARMV6M);
            assertEquals(0, result.exitCode(), () -> backend + ": alguma checagem falhou (ver cortexm-torture-m0.s)");
        }
    }

    /// "Teste do teste": prova que o gêmeo `-broken` (uma checagem deliberadamente errada, ver
    /// cortexm-torture-broken.s) realmente é detectado — sem isso, um exit 0 no teste principal
    /// não provaria nada.
    @org.junit.jupiter.api.Test
    void brokenTortureDetectsTheIntentionalMismatch() throws IOException {
        assumeTrue(Files.exists(TORTURE_M3_BROKEN_ELF),
                "testdata/cortexm-torture-broken.elf ausente — rode testdata/build-testdata.ps1");
        for (Armbox.Backend backend : nonTruffleBackends()) {
            Result result = run(TORTURE_M3_BROKEN_ELF, backend, ArmArchitecture.ARMV7M);
            assertNotEquals(0, result.exitCode(), backend + ": deveria detectar a checagem quebrada");
        }
    }

    /// Sinal de compilador real (gcc, sem CRT/libc): a tabela de vetores nasce de um array de
    /// ponteiros de função em C, não de `.word` cru em asm.
    @org.junit.jupiter.api.Test
    void helloCompiledByGccPrintsAndExitsZero() throws IOException {
        assumeTrue(Files.exists(HELLO_ELF), "testdata/hello-cortexm.elf ausente — rode testdata/build-testdata.ps1");
        for (Armbox.Backend backend : nonTruffleBackends()) {
            Result result = run(HELLO_ELF, backend, ArmArchitecture.ARMV7M);
            assertEquals(0, result.exitCode(), backend.name());
            assertEquals("hello cortex-m\n", result.stdout(), backend.name());
        }
    }

    /// `.bin` cru (sem cabeçalho ELF) carregado em `0x0` — mesmo firmware do teste principal,
    /// só a forma de empacotamento muda.
    @org.junit.jupiter.api.Test
    void rawBinaryImageBoots() throws IOException {
        assumeTrue(Files.exists(TORTURE_M3_ELF),
                "testdata/cortexm-torture.elf ausente — rode testdata/build-testdata.ps1");
        byte[] elf = Files.readAllBytes(TORTURE_M3_ELF);
        byte[] bin = extractFlashImage(elf);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int exitCode = CortexMMachine.run(bin, Armbox.Backend.INTERPRETED, ArmArchitecture.ARMV7M,
                CortexMMachine.DEFAULT_RAM_SIZE_BYTES, stdout, System.err);
        assertEquals(0, exitCode);
    }

    /// Extrai o único segmento `PT_LOAD` de um ELF32 (offset 28 = `e_phoff`, cabeçalho de
    /// program header de 32 bytes) como uma imagem `.bin` crua começando em `0x0` — evita
    /// depender de `arm-none-eabi-objcopy` estar no PATH só para este teste.
    private static byte[] extractFlashImage(byte[] elf) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(elf).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int phoff = buffer.getInt(28);
        int filesz = buffer.getInt(phoff + 16);
        int offset = buffer.getInt(phoff + 4);
        byte[] bin = new byte[filesz];
        System.arraycopy(elf, offset, bin, 0, filesz);
        return bin;
    }

    /// Regressão B4.0.x: `--machine=linux-user` (default) segue intacto — não usa
    /// {@link CortexMMachine} de forma alguma.
    @org.junit.jupiter.api.Test
    void linuxUserMachineUnaffectedByCortexMAddition() throws IOException {
        assumeTrue(Files.exists(LINUX_USER_HELLO_ELF), "testdata/hello.elf ausente — rode testdata/build-testdata.ps1");
        byte[] elf = Files.readAllBytes(LINUX_USER_HELLO_ELF);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode = Armbox.run(elf, List.of("hello.elf"), List.of(), Armbox.Backend.JIT,
                new ByteArrayInputStream(new byte[0]), stdout, stderr,
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        assertEquals(42, exitCode);
        assertEquals("hello from a real ELF\n", stdout.toString(StandardCharsets.UTF_8));
    }
}
