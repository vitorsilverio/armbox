package dev.vitorsilverio.armbox;

import org.junit.jupiter.api.Test;

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

/// Roda o binário REAL de testdata (compilado pelo devkitARM via build-testdata.ps1).
/// Pulado se o .elf não estiver presente.
class RealElfTest {
    private static final Path HELLO_ELF = Path.of("testdata", "hello.elf");

    @Test
    void realHelloElfPrintsAndExits() throws IOException {
        assumeTrue(Files.exists(HELLO_ELF), "testdata/hello.elf ausente — rode testdata/build-testdata.ps1");
        byte[] elf = Files.readAllBytes(HELLO_ELF);
        for (Armbox.Backend backend : new Armbox.Backend[]{Armbox.Backend.INTERPRETED, Armbox.Backend.JIT}) {
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
