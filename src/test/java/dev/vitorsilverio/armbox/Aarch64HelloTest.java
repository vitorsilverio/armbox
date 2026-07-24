package dev.vitorsilverio.armbox;

import dev.vitorsilverio.armbox.aarch64.Aarch64LinuxMachine;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Roda o binário REAL de testdata `hello-aarch64.elf` (montado à mão com
/// `aarch64-none-elf-as`/`-gcc`/`-ld` do devkitA64 via `build-testdata.ps1`, sem libc — só
/// `svc`s cruas) através do pipeline AArch64 novo (`--arch=aarch64`, B6.2). Espelha
/// {@link RealElfTest} (ARM 32-bit). Pulado se o `.elf` não estiver presente.
class Aarch64HelloTest {
    private static final Path HELLO_ELF = Path.of("testdata", "hello-aarch64.elf");

    @Test
    void realHelloAarch64ElfPrintsAndExits() throws IOException {
        assumeTrue(Files.exists(HELLO_ELF),
                "testdata/hello-aarch64.elf ausente — rode testdata/build-testdata.ps1");
        byte[] elf = Files.readAllBytes(HELLO_ELF);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode = Aarch64LinuxMachine.run(elf, stdout, stderr,
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        assertEquals("hello from a real AArch64 ELF\n", stdout.toString(StandardCharsets.UTF_8));
        assertEquals(42, exitCode);
    }
}
