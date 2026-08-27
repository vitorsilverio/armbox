package dev.vitorsilverio.armbox.linux;

import dev.vitorsilverio.armbox.memory.GuestMemory;
import dev.vitorsilverio.armjitter.core.ArmCore;
import dev.vitorsilverio.armjitter.swi.CpuState;
import dev.vitorsilverio.armjitter.swi.SwiDispatcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/// Estado do "kernel" user-mode: tradução de syscalls Linux ARM EABI para o host.
///
/// O handler de SWI lê os argumentos direto do [ArmCore] (r0–r5; número em r7) e devolve
/// o resultado via `state.withR0(...)`. Retornos de erro seguem a convenção do kernel:
/// `-errno` em r0.
public final class LinuxGuest {
    private static final int PID = 1000;
    private static final int PPID = 1;
    private static final int TID = PID;
    private static final int UID = 1000;
    /// Região de mmap anônimo: alocador bump a partir daqui (fora do heap e da pilha).
    private static final int MMAP_BASE = 0x40000000;
    // Offsets no `struct stat64` do ARM (arch/arm/include/uapi/asm/stat.h).
    private static final int STAT64_SIZE = 104;
    private static final int STAT64_ST_MODE_OFFSET = 16;
    private static final int STAT64_ST_NLINK_OFFSET = 20;
    private static final int STAT64_ST_UID_OFFSET = 24;
    private static final int STAT64_ST_GID_OFFSET = 28;
    private static final int STAT64_ST_SIZE_OFFSET = 48;
    private static final int STAT64_ST_BLKSIZE_OFFSET = 56;
    private static final int UTSNAME_FIELD_SIZE = 65;
    /// `struct termios` do KERNEL ARM (4 flags de 32 bits + c_line + NCCS=19 c_cc).
    private static final int KERNEL_TERMIOS_SIZE = 36;

    private final GuestMemory memory;
    private final InputStream stdin;
    private final OutputStream stdout;
    private final OutputStream stderr;
    private final PrintStream hostLog;
    private final Map<Integer, SeekableByteChannel> openFiles = new HashMap<>();
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    private ArmCore core;
    private int currentBrk;
    private int initialBrk;
    private int mmapCursor = MMAP_BASE;
    private int nextFd = 3;

    public LinuxGuest(GuestMemory memory, InputStream stdin, OutputStream stdout,
                      OutputStream stderr, PrintStream hostLog) {
        this.memory = memory;
        this.stdin = stdin;
        this.stdout = stdout;
        this.stderr = stderr;
        this.hostLog = hostLog;
    }

    /// Define o início do heap (fim da imagem ELF carregada).
    public void setInitialBrk(int brk) {
        this.initialBrk = brk;
        this.currentBrk = brk;
    }

    /// Liga o guest ao core (necessário para ler r4/r5/r7, que o CpuState não expõe).
    public void attach(ArmCore core) {
        this.core = core;
    }

    /// Cria o dispatcher que traduz `svc` em syscalls.
    public SwiDispatcher dispatcher() {
        SwiDispatcher dispatcher = SwiDispatcher.empty();
        dispatcher.fallbackWithNumber(this::handleSwi);
        return dispatcher;
    }

    private CpuState handleSwi(int swiImmediate, CpuState state) {
        int number;
        if (swiImmediate == 0) {
            number = core.register(LinuxAbi.SYSCALL_NUMBER_REGISTER); // EABI: número em r7
        } else if ((swiImmediate & 0xFFF00000) == (LinuxAbi.OABI_SYSCALL_BASE & 0xFFF00000)) {
            number = swiImmediate - LinuxAbi.OABI_SYSCALL_BASE;      // OABI: número no imediato
        } else {
            hostLog.printf("armbox: swi 0x%X desconhecido em pc=0x%08X%n", swiImmediate, state.pc());
            return state.withR0(-LinuxAbi.ENOSYS);
        }
        int result = syscall(number,
                state.r0(), state.r1(), state.r2(), state.r3(),
                core.register(4), core.register(5));
        return state.withR0(result);
    }

    private int syscall(int number, int a0, int a1, int a2, int a3, int a4, int a5) {
        return switch (number) {
            case LinuxAbi.NR_EXIT, LinuxAbi.NR_EXIT_GROUP -> throw new GuestExitException(a0 & 0xFF);
            case LinuxAbi.NR_WRITE -> write(a0, a1, a2);
            case LinuxAbi.NR_WRITEV -> writev(a0, a1, a2);
            case LinuxAbi.NR_READ -> read(a0, a1, a2);
            case LinuxAbi.NR_BRK -> brk(a0);
            case LinuxAbi.NR_MMAP2 -> mmap2(a0, a1, a2, a3, a4, a5);
            case LinuxAbi.NR_MUNMAP -> munmap(a0, a1);
            case LinuxAbi.NR_OPEN -> openFile(a0, a1);
            case LinuxAbi.NR_OPENAT -> a0 == LinuxAbi.AT_FDCWD ? openFile(a1, a2) : -LinuxAbi.ENOSYS;
            case LinuxAbi.NR_CLOSE -> close(a0);
            case LinuxAbi.NR_LSEEK -> lseek(a0, a1, a2);
            case LinuxAbi.NR_LLSEEK -> llseek(a0, a1, a2, a3, a4);
            case LinuxAbi.NR_FSTAT64 -> fstat64(a0, a1);
            case LinuxAbi.NR_STAT64, LinuxAbi.NR_LSTAT64 -> stat64(a0, a1);
            case LinuxAbi.NR_IOCTL -> ioctl(a0, a1, a2);
            case LinuxAbi.NR_GETCWD -> getcwd(a0, a1);
            case LinuxAbi.NR_ACCESS -> access(a0);
            case LinuxAbi.NR_FACCESSAT -> a0 == LinuxAbi.AT_FDCWD ? access(a1) : -LinuxAbi.ENOSYS;
            case LinuxAbi.NR_READLINK, LinuxAbi.NR_READLINKAT -> -LinuxAbi.EINVAL; // nada é symlink
            // fcntl64: F_GETFD/F_SETFD/F_GETFL/F_SETFL — flags não importam na fase 2.
            case LinuxAbi.NR_FCNTL64 -> 0;
            // Processo single-user: set*id para o próprio id é um no-op bem-sucedido.
            case LinuxAbi.NR_SETUID16, LinuxAbi.NR_SETGID16,
                 LinuxAbi.NR_SETUID32, LinuxAbi.NR_SETGID32 -> 0;
            // Não há fork na fase 2 — nunca há filho para colher.
            case LinuxAbi.NR_WAIT4 -> -LinuxAbi.ECHILD;
            case LinuxAbi.NR_UNAME -> uname(a0);
            case LinuxAbi.NR_CLOCK_GETTIME -> clockGettime(a1);
            case LinuxAbi.NR_GETTIMEOFDAY -> gettimeofday(a0);
            case LinuxAbi.NR_GETPID -> PID;
            case LinuxAbi.NR_GETPPID -> PPID;
            case LinuxAbi.NR_GETTID -> TID;
            case LinuxAbi.NR_GETUID32, LinuxAbi.NR_GETEUID32,
                 LinuxAbi.NR_GETGID32, LinuxAbi.NR_GETEGID32 -> UID;
            case LinuxAbi.NR_SET_TID_ADDRESS -> TID;
            // Sinais: registrados e ignorados (não há entrega de sinais na fase 1).
            case LinuxAbi.NR_RT_SIGACTION, LinuxAbi.NR_RT_SIGPROCMASK -> 0;
            case LinuxAbi.NR_UGETRLIMIT -> 0;
            // O JIT já invalida via InvalidationAwareAddressSpace; nada a fazer.
            case LinuxAbi.NR_ARM_CACHEFLUSH -> 0;
            case LinuxAbi.NR_ARM_SET_TLS -> setTls(a0);
            // Nenhuma proteção de página é modelada (fase 2) — sempre bem-sucedido, como o
            // restante do bloco "no-op" acima.
            case LinuxAbi.NR_MPROTECT -> 0;
            // Sem threads/futex robusto na fase 2 (mesmo raciocínio de NR_WAIT4: não há para
            // onde a lista apontar) — glibc só REGISTRA o ponteiro, não valida o retorno.
            case LinuxAbi.NR_SET_ROBUST_LIST -> 0;
            case LinuxAbi.NR_GETRANDOM -> getrandom(a0, a1);
            // Opcional (glibc/musl tratam ENOSYS como "kernel sem rseq"/"kernel sem statx" e
            // caem para o caminho antigo silenciosamente, mesmo comportamento de um kernel real
            // mais velho) — não implementados de propósito, não é o mesmo "não implementada"
            // genérico do default (musl chama `statx` primeiro e refaz via `fstatat`+`stat64`
            // internamente quando dá ENOSYS, sem expor isso ao chamador).
            case LinuxAbi.NR_RSEQ, LinuxAbi.NR_STATX -> -LinuxAbi.ENOSYS;
            case LinuxAbi.NR_CLOCK_GETTIME64 -> clockGettime64(a1);
            default -> {
                hostLog.printf("armbox: syscall %d não implementada (ENOSYS)%n", number);
                yield -LinuxAbi.ENOSYS;
            }
        };
    }

    // ── I/O ──────────────────────────────────────────────────────────────────

    private int write(int fd, int buffer, int count) {
        try {
            byte[] data = memory.readBytes(buffer, count);
            OutputStream target = switch (fd) {
                case LinuxAbi.STDOUT_FD -> stdout;
                case LinuxAbi.STDERR_FD -> stderr;
                default -> null;
            };
            if (target == null) {
                return -LinuxAbi.EBADF;
            }
            target.write(data);
            target.flush();
            return count;
        } catch (IOException e) {
            return -LinuxAbi.EBADF;
        }
    }

    private int writev(int fd, int iov, int iovcnt) {
        int total = 0;
        for (int i = 0; i < iovcnt; i++) {
            int base = memory.read32(iov + i * 8);
            int length = memory.read32(iov + i * 8 + 4);
            if (length == 0) {
                continue;
            }
            int written = write(fd, base, length);
            if (written < 0) {
                return total > 0 ? total : written;
            }
            total += written;
        }
        return total;
    }

    private int read(int fd, int buffer, int count) {
        try {
            if (fd == LinuxAbi.STDIN_FD) {
                byte[] data = new byte[count];
                int n = stdin.read(data, 0, count);
                if (n <= 0) {
                    return 0; // EOF
                }
                memory.writeBytes(buffer, data, 0, n);
                return n;
            }
            SeekableByteChannel channel = openFiles.get(fd);
            if (channel == null) {
                return -LinuxAbi.EBADF;
            }
            ByteBuffer chunk = ByteBuffer.allocate(count);
            int n = channel.read(chunk);
            if (n <= 0) {
                return 0;
            }
            memory.writeBytes(buffer, chunk.array(), 0, n);
            return n;
        } catch (IOException e) {
            return -LinuxAbi.EBADF;
        }
    }

    private int openFile(int pathAddress, int flags) {
        if ((flags & LinuxAbi.O_ACCMODE) != LinuxAbi.O_RDONLY) {
            return -LinuxAbi.EACCES; // fase 1: somente leitura
        }
        String path = memory.readCString(pathAddress);
        try {
            SeekableByteChannel channel = Files.newByteChannel(Path.of(path), StandardOpenOption.READ);
            int fd = nextFd++;
            openFiles.put(fd, channel);
            return fd;
        } catch (IOException e) {
            return -LinuxAbi.ENOENT;
        }
    }

    private int close(int fd) {
        SeekableByteChannel channel = openFiles.remove(fd);
        if (channel == null) {
            return fd <= LinuxAbi.STDERR_FD ? 0 : -LinuxAbi.EBADF;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // fechar já removeu o fd; erro de close não interessa ao guest
        }
        return 0;
    }

    private int lseek(int fd, int offset, int whence) {
        long result = seekChannel(fd, offset, whence);
        return (int) result;
    }

    private int llseek(int fd, int offsetHigh, int offsetLow, int resultAddress, int whence) {
        long offset = ((long) offsetHigh << 32) | Integer.toUnsignedLong(offsetLow);
        long result = seekChannel(fd, offset, whence);
        if (result < 0) {
            return (int) result;
        }
        memory.write32(resultAddress, (int) result);
        memory.write32(resultAddress + 4, (int) (result >>> 32));
        return 0;
    }

    private long seekChannel(int fd, long offset, int whence) {
        SeekableByteChannel channel = openFiles.get(fd);
        if (channel == null) {
            return -LinuxAbi.EBADF;
        }
        try {
            long base = switch (whence) {
                case 0 -> 0;                    // SEEK_SET
                case 1 -> channel.position();   // SEEK_CUR
                case 2 -> channel.size();       // SEEK_END
                default -> -1;
            };
            if (base < 0) {
                return -LinuxAbi.EINVAL;
            }
            channel.position(base + offset);
            return channel.position();
        } catch (IOException e) {
            return -LinuxAbi.EINVAL;
        }
    }

    /// Preenche o essencial do `struct stat64` do ARM: modo, nlink, dono, tamanho e
    /// blksize. O resto (tempos, inode real, rdev) fica zerado — suficiente para libc.
    private void writeStat64(int statAddress, int mode, long size) {
        byte[] zeroed = new byte[STAT64_SIZE];
        memory.writeBytes(statAddress, zeroed, 0, zeroed.length);
        memory.write32(statAddress + STAT64_ST_MODE_OFFSET, mode);
        memory.write32(statAddress + STAT64_ST_NLINK_OFFSET, 1);
        memory.write32(statAddress + STAT64_ST_UID_OFFSET, UID);
        memory.write32(statAddress + STAT64_ST_GID_OFFSET, UID);
        memory.write32(statAddress + STAT64_ST_SIZE_OFFSET, (int) size);
        memory.write32(statAddress + STAT64_ST_SIZE_OFFSET + 4, (int) (size >>> 32));
        memory.write32(statAddress + STAT64_ST_BLKSIZE_OFFSET, GuestMemory.PAGE_SIZE);
    }

    private int fstat64(int fd, int statAddress) {
        if (fd >= LinuxAbi.STDIN_FD && fd <= LinuxAbi.STDERR_FD) {
            writeStat64(statAddress, LinuxAbi.S_IFCHR_TTY, 0);
            return 0;
        }
        SeekableByteChannel channel = openFiles.get(fd);
        if (channel == null) {
            return -LinuxAbi.EBADF;
        }
        try {
            writeStat64(statAddress, LinuxAbi.S_IFREG_RO, channel.size());
        } catch (IOException e) {
            writeStat64(statAddress, LinuxAbi.S_IFREG_RO, 0);
        }
        return 0;
    }

    private int stat64(int pathAddress, int statAddress) {
        Path path = Path.of(memory.readCString(pathAddress));
        if (!Files.exists(path)) {
            return -LinuxAbi.ENOENT;
        }
        try {
            if (Files.isDirectory(path)) {
                writeStat64(statAddress, LinuxAbi.S_IFDIR_RX, 0);
            } else {
                writeStat64(statAddress, LinuxAbi.S_IFREG_RO, Files.size(path));
            }
            return 0;
        } catch (IOException e) {
            return -LinuxAbi.EACCES;
        }
    }

    private int ioctl(int fd, int request, int argAddress) {
        if (fd > LinuxAbi.STDERR_FD && !openFiles.containsKey(fd)) {
            return -LinuxAbi.EBADF;
        }
        return switch (request) {
            // struct termios zerado: a libc só quer saber que É um tty. ATENÇÃO: o
            // kernel escreve o termios DELE (36 bytes) — não os 60 do struct da libc;
            // escrever mais estoura o buffer de pilha do chamador (pc=0).
            case LinuxAbi.TCGETS -> {
                byte[] termios = new byte[KERNEL_TERMIOS_SIZE];
                memory.writeBytes(argAddress, termios, 0, termios.length);
                yield fd <= LinuxAbi.STDERR_FD ? 0 : -LinuxAbi.ENOTTY;
            }
            case LinuxAbi.TIOCGWINSZ -> {
                memory.write16(argAddress, LinuxAbi.TTY_ROWS);
                memory.write16(argAddress + 2, LinuxAbi.TTY_COLS);
                memory.write32(argAddress + 4, 0);
                yield fd <= LinuxAbi.STDERR_FD ? 0 : -LinuxAbi.ENOTTY;
            }
            default -> {
                hostLog.printf("armbox: ioctl 0x%X não implementado (ENOTTY)%n", request);
                yield -LinuxAbi.ENOTTY;
            }
        };
    }

    private int getcwd(int buffer, int size) {
        byte[] cwd = "/\0".getBytes(StandardCharsets.US_ASCII);
        if (size < cwd.length) {
            return -LinuxAbi.ERANGE;
        }
        memory.writeBytes(buffer, cwd, 0, cwd.length);
        return cwd.length;
    }

    private int access(int pathAddress) {
        return Files.exists(Path.of(memory.readCString(pathAddress))) ? 0 : -LinuxAbi.ENOENT;
    }

    // ── Memória ──────────────────────────────────────────────────────────────

    private int brk(int requested) {
        if (requested == 0 || Integer.compareUnsigned(requested, initialBrk) < 0) {
            return currentBrk;
        }
        if (Integer.compareUnsigned(requested, currentBrk) > 0) {
            memory.map(currentBrk, requested - currentBrk);
        }
        currentBrk = requested;
        return currentBrk;
    }

    private int mmap2(int hint, int length, int prot, int flags, int fd, int pageOffset) {
        if ((flags & LinuxAbi.MAP_ANONYMOUS) == 0) {
            hostLog.printf("armbox: mmap2 com arquivo (fd=%d) não suportado na fase 1%n", fd);
            return -LinuxAbi.ENOSYS;
        }
        if (length <= 0) {
            return -LinuxAbi.EINVAL;
        }
        int aligned = (length + GuestMemory.PAGE_SIZE - 1) & -GuestMemory.PAGE_SIZE;
        int address = mmapCursor;
        mmapCursor += aligned;
        memory.map(address, aligned);
        return address;
    }

    private int munmap(int address, int length) {
        if (length <= 0) {
            return -LinuxAbi.EINVAL;
        }
        memory.unmap(address, length);
        return 0;
    }

    private int setTls(int value) {
        memory.write32(KuserHelpers.TLS_VALUE_ADDRESS, value);
        return 0;
    }

    // ── Sistema ──────────────────────────────────────────────────────────────

    private int uname(int utsAddress) {
        writeUtsField(utsAddress, 0, "Linux");
        writeUtsField(utsAddress, 1, "armbox");
        writeUtsField(utsAddress, 2, "6.1.0-armbox");
        writeUtsField(utsAddress, 3, "#1 armbox user-mode");
        writeUtsField(utsAddress, 4, "armv5tejl");
        writeUtsField(utsAddress, 5, "");
        return 0;
    }

    private void writeUtsField(int base, int index, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        int address = base + index * UTSNAME_FIELD_SIZE;
        byte[] field = new byte[UTSNAME_FIELD_SIZE];
        System.arraycopy(bytes, 0, field, 0, Math.min(bytes.length, UTSNAME_FIELD_SIZE - 1));
        memory.writeBytes(address, field, 0, field.length);
    }

    private int clockGettime(int timespecAddress) {
        long nanos = System.nanoTime();
        memory.write32(timespecAddress, (int) (nanos / 1_000_000_000L));
        memory.write32(timespecAddress + 4, (int) (nanos % 1_000_000_000L));
        return 0;
    }

    private int gettimeofday(int timevalAddress) {
        long millis = System.currentTimeMillis();
        memory.write32(timevalAddress, (int) (millis / 1000));
        memory.write32(timevalAddress + 4, (int) (millis % 1000) * 1000);
        return 0;
    }

    /// `clock_gettime64` (y2038): mesmo campo de `clock_gettime`, mas `struct __kernel_timespec`
    /// usa `tv_sec`/`tv_nsec` de 64 bits cada (16 bytes no total), não 32 bits.
    private int clockGettime64(int timespecAddress) {
        long nanos = System.nanoTime();
        writeInt64(timespecAddress, nanos / 1_000_000_000L);
        writeInt64(timespecAddress + 8, nanos % 1_000_000_000L);
        return 0;
    }

    private void writeInt64(int address, long value) {
        memory.write32(address, (int) value);
        memory.write32(address + 4, (int) (value >> 32));
    }

    /// `getrandom`: preenche `count` bytes pseudo-aleatórios em `buffer` — suficiente para o uso
    /// real (sementes de hash/stack canary), nenhum guest depende de entropia criptográfica.
    private int getrandom(int buffer, int count) {
        byte[] bytes = new byte[count];
        RANDOM.nextBytes(bytes);
        memory.writeBytes(buffer, bytes, 0, bytes.length);
        return count;
    }
}
