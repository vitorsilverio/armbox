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

/// Aceite da fase 2 (task B4.0): busybox estático (musl, armv5l) de verdade.
/// Pulado se o binário não estiver em testdata.
class BusyboxTest {
    private static final Path BUSYBOX = Path.of("testdata", "busybox-armv5l");

    private String run(List<String> argv, int expectedExit) throws IOException {
        byte[] elf = Files.readAllBytes(BUSYBOX);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode = Armbox.run(elf, argv, List.of(), Armbox.Backend.JIT,
                new ByteArrayInputStream(new byte[0]), stdout, stderr,
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        assertEquals(expectedExit, exitCode,
                () -> "stderr: " + stderr.toString(StandardCharsets.UTF_8));
        return stdout.toString(StandardCharsets.UTF_8);
    }

    @Test
    void echoHello() throws IOException {
        assumeTrue(Files.exists(BUSYBOX));
        assertEquals("hello\n", run(List.of("busybox", "echo", "hello"), 0));
    }

    @Test
    void shellRunsSequentialBuiltins() throws IOException {
        assumeTrue(Files.exists(BUSYBOX));
        assertEquals("a\nb\n", run(List.of("busybox", "sh", "-c", "echo a; echo b"), 0));
    }

    @Test
    void shellExpandsArithmetic() throws IOException {
        assumeTrue(Files.exists(BUSYBOX));
        assertEquals("42\n", run(List.of("busybox", "sh", "-c", "echo $((6*7))"), 0));
    }

    @Test
    void unameReportsArmbox() throws IOException {
        assumeTrue(Files.exists(BUSYBOX));
        String out = run(List.of("busybox", "uname", "-sm"), 0);
        assertEquals("Linux armv5tejl\n", out);
    }
}
