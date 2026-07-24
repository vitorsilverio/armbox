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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// B4.0.3 — nível N3 (`docs/VALIDACAO-ARQUITETURAS.md`) para Thumb-2: código gerado por um
/// compilador de verdade, não escrito à mão, exercitando load/store de 32 bits, tabelas
/// `TBB`/`TBH` e `IT` blocks à vontade — nenhum teste sintético (`Thumb2TortureTest`) cobre isso
/// com a mesma malícia. Pulado se o `.elf` não estiver presente.
///
/// `testdata/hello-thumb2.elf` (fonte: `testdata/hello-thumb2.c`) é `gcc` real
/// (`-march=armv7-a -mthumb -Os -nostdlib -static`, ver o comentário no topo do `.c` e
/// `testdata/build-testdata.ps1`): struct com bitfields (UBFX/SBFX/STRH), `STRD` explícito,
/// switch denso que o gcc compila para `TBB`, e um qsort pequeno e recursivo (`IT` blocks +
/// cmp/branches curtos). Roda com `--arch=armv7a`, não `--arch=thumb2` — ver o comentário do
/// `.c` para o motivo (bitfields exigem `ArmFeature.BIT_FIELD`, que só `ARMV7A` tem).
///
/// **Achado real desta task**: o preset público `ArmArchitecture.ARMV7A` tinha um bug de fiação
/// (`UBFX`/`SBFX`/`RBIT`/`SDIV`/`UDIV`/`MLS` em encoding Thumb-2 viravam `UNDEFINED` mesmo com
/// as features certas no preset) — corrigido no arm-jitter, ver o javadoc de
/// `ArmArchitecture.ARMV7A` e `ArmV7aThumb2PresetIntegrationTest` (regressão contra o preset
/// público, não um preset sintético) para os detalhes.
class Thumb2CompilerBinaryTest {
    private static final Path HELLO_THUMB2_ELF = Path.of("testdata", "hello-thumb2.elf");

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

    /// Aceite principal da task: os 3 backends concordam (mesmo checksum hexadecimal, mesmo
    /// exit 0) rodando um binário que nenhum de nós escreveu manualmente.
    @org.junit.jupiter.api.Test
    void helloThumb2BinaryProducesIdenticalOutputOnAllThreeBackends() throws IOException {
        assumeTrue(Files.exists(HELLO_THUMB2_ELF),
                "testdata/hello-thumb2.elf ausente — rode testdata/build-testdata.ps1");
        final String expected = "83f38984\n";
        for (Armbox.Backend backend : Armbox.Backend.values()) {
            if (backend == Armbox.Backend.TRUFFLE) {
                continue; // fora do escopo desta task, mesmo padrão de Thumb2TortureTest/Armv7TortureTest
            }
            Result result = run(HELLO_THUMB2_ELF, backend);
            assertEquals(0, result.exitCode(), backend.name());
            assertEquals(expected, result.stdout(), backend.name());
        }
    }
}
