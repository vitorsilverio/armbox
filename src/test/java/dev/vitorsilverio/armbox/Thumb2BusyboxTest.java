package dev.vitorsilverio.armbox;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;
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

/// Item 3 da task B4.0.3: busybox estático compilado em Thumb-2 de verdade (`-mthumb
/// -march=armv7-a`, musl, `Tag_THUMB_ISA_use: Thumb-2` confirmado via `readelf -A`) rodando sob
/// `--arch=armv7a`. Espelha {@link BusyboxTest} (armv5l/ARM mode); não repete `unameReportsArmbox`
/// porque {@code LinuxGuest#uname} sempre reporta `armv5tejl` (achado pré-existente, fora do
/// escopo desta task — o campo `machine` do `uname` não varia por `ArmArchitecture`).
/// Pulado se o binário não estiver em testdata.
class Thumb2BusyboxTest {
    private static final Path BUSYBOX = Path.of("testdata", "busybox-thumb2");

    private String run(List<String> argv, Armbox.Backend backend, int expectedExit) throws IOException {
        byte[] elf = Files.readAllBytes(BUSYBOX);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode = Armbox.run(elf, argv, List.of(), backend, ArmArchitecture.ARMV7A,
                new ByteArrayInputStream(new byte[0]), stdout, stderr,
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        assertEquals(expectedExit, exitCode,
                () -> "stderr: " + stderr.toString(StandardCharsets.UTF_8));
        return stdout.toString(StandardCharsets.UTF_8);
    }

    @Test
    void echoHelloInterpreted() throws IOException {
        assumeTrue(Files.exists(BUSYBOX));
        assertEquals("hello\n", run(List.of("busybox", "echo", "hello"), Armbox.Backend.INTERPRETED, 0));
    }

    @Test
    void echoHelloJit() throws IOException {
        assumeTrue(Files.exists(BUSYBOX));
        assertEquals("hello\n", run(List.of("busybox", "echo", "hello"), Armbox.Backend.JIT, 0));
    }

    @Test
    void shellRunsSequentialBuiltins() throws IOException {
        assumeTrue(Files.exists(BUSYBOX));
        assertEquals("a\nb\n", run(List.of("busybox", "sh", "-c", "echo a; echo b"), Armbox.Backend.JIT, 0));
    }

    @Test
    void shellExpandsArithmetic() throws IOException {
        assumeTrue(Files.exists(BUSYBOX));
        assertEquals("42\n", run(List.of("busybox", "sh", "-c", "echo $((6*7))"), Armbox.Backend.JIT, 0));
    }
}
