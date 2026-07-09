package dev.vitorsilverio.armbox.linux;

/// Constantes da ABI Linux ARM EABI (`arch/arm/tools/syscall.tbl` + `errno.h`).
///
/// EABI: `svc #0` com o número da syscall em r7, argumentos em r0–r5, retorno em r0
/// (negativo = `-errno`). OABI legado codifica o número no imediato do `svc`
/// (`0x900000 + n`) — também aceito pelo dispatcher.
public final class LinuxAbi {
    private LinuxAbi() {
    }

    /// Base do imediato de `swi` na ABI antiga (OABI).
    public static final int OABI_SYSCALL_BASE = 0x900000;
    /// Registrador que carrega o número da syscall na EABI.
    public static final int SYSCALL_NUMBER_REGISTER = 7;

    // ── Números de syscall (EABI) ────────────────────────────────────────────
    public static final int NR_EXIT = 1;
    public static final int NR_READ = 3;
    public static final int NR_WRITE = 4;
    public static final int NR_OPEN = 5;
    public static final int NR_CLOSE = 6;
    public static final int NR_LSEEK = 19;
    public static final int NR_GETPID = 20;
    public static final int NR_BRK = 45;
    public static final int NR_GETPPID = 64;
    public static final int NR_GETTIMEOFDAY = 78;
    public static final int NR_MUNMAP = 91;
    public static final int NR_UNAME = 122;
    public static final int NR_LLSEEK = 140;
    public static final int NR_WRITEV = 146;
    public static final int NR_RT_SIGACTION = 174;
    public static final int NR_RT_SIGPROCMASK = 175;
    public static final int NR_UGETRLIMIT = 191;
    public static final int NR_MMAP2 = 192;
    public static final int NR_FSTAT64 = 197;
    public static final int NR_GETUID32 = 199;
    public static final int NR_GETGID32 = 200;
    public static final int NR_GETEUID32 = 201;
    public static final int NR_GETEGID32 = 202;
    public static final int NR_GETTID = 224;
    public static final int NR_EXIT_GROUP = 248;
    public static final int NR_SET_TID_ADDRESS = 256;
    public static final int NR_CLOCK_GETTIME = 263;
    public static final int NR_OPENAT = 322;

    // Syscalls privadas do port ARM (base 0x0F0000).
    public static final int NR_ARM_CACHEFLUSH = 0x0F0002;
    public static final int NR_ARM_SET_TLS = 0x0F0005;

    // ── errno ────────────────────────────────────────────────────────────────
    public static final int EBADF = 9;
    public static final int ENOMEM = 12;
    public static final int EACCES = 13;
    public static final int ENOENT = 2;
    public static final int EINVAL = 22;
    public static final int ENOSYS = 38;

    // ── Diversos ─────────────────────────────────────────────────────────────
    public static final int AT_FDCWD = -100;
    public static final int O_ACCMODE = 3;
    public static final int O_RDONLY = 0;
    public static final int MAP_ANONYMOUS = 0x20;
    public static final int STDIN_FD = 0;
    public static final int STDOUT_FD = 1;
    public static final int STDERR_FD = 2;
    /// `st_mode`: dispositivo de caractere com permissão de tty (stdin/out/err).
    public static final int S_IFCHR_TTY = 0x2000 | 0620;
    /// `st_mode`: arquivo regular somente leitura.
    public static final int S_IFREG_RO = 0x8000 | 0444;
}
