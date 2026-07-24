package dev.vitorsilverio.armbox.aarch64;

import dev.vitorsilverio.armjitter.core64.Aarch64Core;
import dev.vitorsilverio.armjitter.core64.Aarch64SvcHandler;
import dev.vitorsilverio.armbox.linux.GuestExitException;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/// Estado do "kernel" user-mode para o runtime AArch64 — irmão de
/// {@link dev.vitorsilverio.armbox.linux.LinuxGuest} (mesmo papel: tradução de `svc` em
/// syscalls), mas para a ABI arm64 (números de syscall diferentes — ver {@link Aarch64LinuxAbi} —
/// e argumentos em `x0`-`x5`/número em `x8`, não r0-r5/r7). Implementa {@link Aarch64SvcHandler}
/// diretamente (não existe um `SwiDispatcher` genérico no lado A64, ver o javadoc de
/// {@code Aarch64SvcHandler}).
///
/// Cobre o subconjunto de ~10 syscalls que o `hello-aarch64.s`/um futuro busybox estático arm64
/// exigem (mesmo espírito de cobertura mínima do `LinuxGuest` original) — write/exit/exit_group
/// primeiro (únicas exercidas por `hello-aarch64.s`, que não usa libc), mais leitura/arquivo/heap
/// básicos para quando um binário com libc estática for testado no futuro (B6.3+).
public final class Aarch64LinuxGuest implements Aarch64SvcHandler {
    private static final int PID = 1000;
    private static final int UID = 1000;
    private static final int UTSNAME_FIELD_SIZE = 65;

    private final Aarch64GuestMemory memory;
    private final OutputStream stdout;
    private final OutputStream stderr;
    private final PrintStream hostLog;

    private long currentBrk;
    private long initialBrk;

    public Aarch64LinuxGuest(Aarch64GuestMemory memory, OutputStream stdout, OutputStream stderr,
            PrintStream hostLog) {
        this.memory = memory;
        this.stdout = stdout;
        this.stderr = stderr;
        this.hostLog = hostLog;
    }

    /// Define o início do heap (fim da imagem ELF carregada).
    public void setInitialBrk(long brk) {
        this.initialBrk = brk;
        this.currentBrk = brk;
    }

    @Override
    public void handle(Aarch64Core core, int immediate) {
        int number = (int) core.x(Aarch64LinuxAbi.SYSCALL_NUMBER_REGISTER);
        long a0 = core.x(0);
        long a1 = core.x(1);
        long a2 = core.x(2);
        long result = syscall(number, a0, a1, a2, core.x(3), core.x(4), core.x(5));
        core.setX(0, result);
    }

    private long syscall(int number, long a0, long a1, long a2, long a3, long a4, long a5) {
        return switch (number) {
            case Aarch64LinuxAbi.NR_EXIT, Aarch64LinuxAbi.NR_EXIT_GROUP ->
                    throw new GuestExitException((int) (a0 & 0xFF));
            case Aarch64LinuxAbi.NR_WRITE -> write((int) a0, a1, (int) a2);
            case Aarch64LinuxAbi.NR_READ -> 0; // fase 1: stdin não suportado, EOF imediato
            case Aarch64LinuxAbi.NR_CLOSE -> 0;
            case Aarch64LinuxAbi.NR_BRK -> brk(a0);
            case Aarch64LinuxAbi.NR_MUNMAP -> 0;
            case Aarch64LinuxAbi.NR_IOCTL -> -Aarch64LinuxAbi.ENOTTY;
            case Aarch64LinuxAbi.NR_SET_TID_ADDRESS -> PID;
            case Aarch64LinuxAbi.NR_GETPID, Aarch64LinuxAbi.NR_GETTID -> PID;
            case Aarch64LinuxAbi.NR_GETPPID -> 1;
            case Aarch64LinuxAbi.NR_GETUID, Aarch64LinuxAbi.NR_GETEUID,
                 Aarch64LinuxAbi.NR_GETGID, Aarch64LinuxAbi.NR_GETEGID -> UID;
            case Aarch64LinuxAbi.NR_UNAME -> uname(a0);
            case Aarch64LinuxAbi.NR_RT_SIGACTION, Aarch64LinuxAbi.NR_RT_SIGPROCMASK -> 0;
            default -> {
                hostLog.printf("armbox: syscall arm64 %d não implementada (ENOSYS)%n", number);
                yield -Aarch64LinuxAbi.ENOSYS;
            }
        };
    }

    private long write(int fd, long bufferAddress, int count) {
        try {
            byte[] data = memory.readBytes(bufferAddress, count);
            OutputStream target = switch (fd) {
                case Aarch64LinuxAbi.STDOUT_FD -> stdout;
                case Aarch64LinuxAbi.STDERR_FD -> stderr;
                default -> null;
            };
            if (target == null) {
                return -Aarch64LinuxAbi.EBADF;
            }
            target.write(data);
            target.flush();
            return count;
        } catch (IOException e) {
            return -Aarch64LinuxAbi.EBADF;
        }
    }

    private long brk(long requested) {
        if (requested == 0 || Long.compareUnsigned(requested, initialBrk) < 0) {
            return currentBrk;
        }
        if (Long.compareUnsigned(requested, currentBrk) > 0) {
            memory.map(currentBrk, requested - currentBrk);
        }
        currentBrk = requested;
        return currentBrk;
    }

    private long uname(long utsAddress) {
        writeUtsField(utsAddress, 0, "Linux");
        writeUtsField(utsAddress, 1, "armbox");
        writeUtsField(utsAddress, 2, "6.1.0-armbox");
        writeUtsField(utsAddress, 3, "#1 armbox user-mode");
        writeUtsField(utsAddress, 4, "aarch64");
        writeUtsField(utsAddress, 5, "");
        return 0;
    }

    private void writeUtsField(long base, int index, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        long address = base + (long) index * UTSNAME_FIELD_SIZE;
        byte[] field = new byte[UTSNAME_FIELD_SIZE];
        System.arraycopy(bytes, 0, field, 0, Math.min(bytes.length, UTSNAME_FIELD_SIZE - 1));
        memory.writeBytes(address, field, 0, field.length);
    }
}
