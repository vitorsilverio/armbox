package dev.vitorsilverio.armbox.aarch64;

/// Constantes da ABI Linux arm64 (`arch/arm64/include/asm/unistd32.h` NÃO se aplica — arm64 usa a
/// tabela "genérica" de `include/uapi/asm-generic/unistd.h`; os NÚMEROS DE SYSCALL DIFEREM do ARM
/// 32-bit EABI em {@link dev.vitorsilverio.armbox.linux.LinuxAbi} — não é o mesmo enum reindexado,
/// é uma tabela genuinamente diferente do kernel).
///
/// Convenção: `svc #0` com o número da syscall em `x8`, argumentos em `x0`-`x5`, retorno em `x0`
/// (negativo = `-errno`) — não existe convenção OABI equivalente em arm64 (só uma ABI de syscall).
public final class Aarch64LinuxAbi {
    private Aarch64LinuxAbi() {
    }

    /// Registrador que carrega o número da syscall.
    public static final int SYSCALL_NUMBER_REGISTER = 8;

    // ── Números de syscall (genéricos, include/uapi/asm-generic/unistd.h) ───────────────────
    public static final int NR_IOCTL = 29;
    public static final int NR_UNLINKAT = 35;
    public static final int NR_FACCESSAT = 48;
    public static final int NR_CHDIR = 49;
    public static final int NR_OPENAT = 56;
    public static final int NR_CLOSE = 57;
    public static final int NR_LSEEK = 62;
    public static final int NR_READ = 63;
    public static final int NR_WRITE = 64;
    public static final int NR_WRITEV = 66;
    public static final int NR_READLINKAT = 78;
    public static final int NR_FSTATAT = 79;
    public static final int NR_FSTAT = 80;
    public static final int NR_EXIT = 93;
    public static final int NR_EXIT_GROUP = 94;
    public static final int NR_SET_TID_ADDRESS = 96;
    public static final int NR_CLOCK_GETTIME = 113;
    public static final int NR_RT_SIGACTION = 134;
    public static final int NR_RT_SIGPROCMASK = 135;
    public static final int NR_UNAME = 160;
    public static final int NR_GETPID = 172;
    public static final int NR_GETPPID = 173;
    public static final int NR_GETUID = 174;
    public static final int NR_GETEUID = 175;
    public static final int NR_GETGID = 176;
    public static final int NR_GETEGID = 177;
    public static final int NR_GETTID = 178;
    public static final int NR_BRK = 214;
    public static final int NR_MUNMAP = 215;
    public static final int NR_MMAP = 222;

    // ── errno ────────────────────────────────────────────────────────────────
    public static final int EBADF = 9;
    public static final int ENOMEM = 12;
    public static final int EACCES = 13;
    public static final int ENOENT = 2;
    public static final int EINVAL = 22;
    public static final int ENOTTY = 25;
    public static final int ENOSYS = 38;

    // ── Diversos ─────────────────────────────────────────────────────────────
    public static final int AT_FDCWD = -100;
    public static final int O_ACCMODE = 3;
    public static final int O_RDONLY = 0;
    public static final int MAP_ANONYMOUS = 0x20;
    public static final int STDIN_FD = 0;
    public static final int STDOUT_FD = 1;
    public static final int STDERR_FD = 2;
    public static final int TCGETS = 0x5401;
}
