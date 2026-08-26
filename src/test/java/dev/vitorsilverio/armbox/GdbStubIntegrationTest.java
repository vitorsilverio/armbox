package dev.vitorsilverio.armbox;

import dev.vitorsilverio.armbox.aarch64.Aarch64LinuxMachine;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Prova ponta a ponta que `--gdb=PORT` (task nova: "GDB para 32 e 64 bits no armbox", pedida
/// para uso didático) conecta de verdade os binários REAIS de `testdata/` ao stub do
/// arm-jitter — {@code GdbServer} (ARM32) e {@code Gdb64Server} (AArch64, novo nesta task).
/// Não usa um cliente `gdb` de verdade (nenhum toolchain disponível em CI); fala o protocolo de
/// série remota diretamente por socket, como um cliente mínimo faria. Pulado se o `.elf` da
/// arquitetura não estiver presente.
class GdbStubIntegrationTest {
    private static final Path HELLO_ELF_32 = Path.of("testdata", "hello.elf");
    private static final Path HELLO_ELF_64 = Path.of("testdata", "hello-aarch64.elf");

    @Test
    void gdbStub32BitReadsRegistersAndLetsTheGuestRunToCompletion() throws Exception {
        assumeTrue(Files.exists(HELLO_ELF_32),
                "testdata/hello.elf ausente — rode testdata/build-testdata.ps1");
        byte[] elf = Files.readAllBytes(HELLO_ELF_32);
        int port = freePort();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        CompletableFuture<Integer> exitCode = CompletableFuture.supplyAsync(() ->
                Armbox.run(elf, List.of("hello.elf"), List.of(), Armbox.Backend.JIT,
                        dev.vitorsilverio.armjitter.arch.ArmArchitecture.ARMV5TE,
                        new ByteArrayInputStream(new byte[0]), stdout, stderr,
                        new PrintStream(stderr, true, StandardCharsets.UTF_8), port));

        try (Socket client = connect(port)) {
            String registers = exchange(client, "g");
            assertEquals(336, registers.length(), "r0-15 + f0-7 + fps + cpsr, mesmo layout do GdbServer");
            String badAddress = exchange(client, "m8000000,4");
            assertEquals("E01", badAddress, "endereço não mapeado reporta erro, não derruba a sessão");
            exchangeNoReply(client, "c"); // deixa o guest rodar até sair sozinho (não há mais breakpoint)
        }

        assertEquals(42, exitCode.get());
        assertEquals("hello from a real ELF\n", stdout.toString(StandardCharsets.UTF_8));
    }

    @Test
    void gdbStub64BitReadsRegistersAndLetsTheGuestRunToCompletion() throws Exception {
        assumeTrue(Files.exists(HELLO_ELF_64),
                "testdata/hello-aarch64.elf ausente — rode testdata/build-testdata.ps1");
        byte[] elf = Files.readAllBytes(HELLO_ELF_64);
        int port = freePort();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        CompletableFuture<Integer> exitCode = CompletableFuture.supplyAsync(() ->
                Aarch64LinuxMachine.run(elf, stdout, stderr,
                        new PrintStream(stderr, true, StandardCharsets.UTF_8), port));

        try (Socket client = connect(port)) {
            String registers = exchange(client, "g");
            assertEquals(33 * 16 + 8, registers.length(), "x0-x30 + sp + pc + cpsr, layout AArch64 real");
            String badAddress = exchange(client, "mffffffffff,4");
            assertEquals("E01", badAddress, "endereço não mapeado reporta erro, não derruba a sessão");
            exchangeNoReply(client, "c"); // deixa o guest rodar até sair sozinho
        }

        assertEquals(42, exitCode.get());
        assertEquals("hello from a real AArch64 ELF\n", stdout.toString(StandardCharsets.UTF_8));
    }

    // ---- helpers: cliente mínimo do protocolo de série remota GDB ----

    private static int freePort() throws IOException {
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private static Socket connect(int port) throws IOException, InterruptedException {
        IOException lastFailure = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            try {
                return new Socket("127.0.0.1", port);
            } catch (IOException notListeningYet) {
                lastFailure = notListeningYet;
                Thread.sleep(20);
            }
        }
        throw new IOException("gdb stub never started listening on port " + port, lastFailure);
    }

    private static String exchange(Socket socket, String command) throws IOException {
        sendPacket(socket.getOutputStream(), command);
        return readReply(socket.getInputStream(), socket.getOutputStream());
    }

    /// Envia um comando (ex. `c`ontinue) sem esperar uma resposta — o guest roda até sair
    /// sozinho, o que fecha a conexão em vez de responder um pacote.
    private static void exchangeNoReply(Socket socket, String command) throws IOException {
        sendPacket(socket.getOutputStream(), command);
    }

    private static void sendPacket(OutputStream out, String data) throws IOException {
        int checksum = 0;
        for (int i = 0; i < data.length(); i++) {
            checksum = (checksum + data.charAt(i)) & 0xFF;
        }
        out.write(("$" + data + "#" + String.format("%02x", checksum)).getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static String readReply(InputStream in, OutputStream out) throws IOException {
        int c;
        do {
            c = in.read();
        } while (c != '$');
        StringBuilder sb = new StringBuilder();
        while ((c = in.read()) != '#') {
            sb.append((char) c);
        }
        in.read();
        in.read(); // checksum
        out.write('+');
        out.flush();
        return sb.toString();
    }
}
