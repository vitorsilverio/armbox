# armbox

Runner **Linux user-mode** para binários ARM 32-bit (estilo `qemu-arm`), construído
sobre o [arm-jitter](../arm-jitter). Carrega um ELF estático, monta a pilha ABI do
Linux, mapeia os kuser helpers do kernel ARM e traduz syscalls EABI para o host — com
o CPU rodando no JIT de bytecode JVM do arm-jitter (ARMv5TE).

É a task **B4.0** do roadmap do arm-jitter (`arm-jitter/tasks/trilha-b-arquiteturas/
b4.0-runner-user-mode.md`) e o veículo de validação das trilhas de arquitetura
(ARMv6K/Thumb-2/ARMv7 serão exercitadas aqui com binários reais de gcc).

## Uso

```bash
mvn package
java -jar target/armbox-1.0-SNAPSHOT.jar [--interp|--check] <elf> [args...]
```

| Flag | Backend |
|------|---------|
| (padrão) | JIT bytecode JVM (`JitRuntimeFactory.armThumb`, ARMv5TE) |
| `--interp` | Interpretador IR (debug/oráculo) |
| `--check` | JIT e interpretador em paralelo, aborta na primeira divergência |

O código de saída do processo é o `exit()` do guest.

## Estado (fases 1 e 2 concluídas)

**busybox estático (musl, armv5l) roda:** `echo`, `sh -c 'echo a; echo b'`,
aritmética do shell, `uname` — em JIT, interpretado e no modo `--check`
(zero divergências JIT×interpretador com código musl real).

- Loader ELF32 `ET_EXEC` estático (PIE e dinâmico são rejeitados com mensagem clara).
- Pilha inicial ABI: argc/argv/envp + auxv (AT_PHDR/PAGESZ/ENTRY/RANDOM/HWCAP/...).
- kuser helpers (`0xFFFF0Fxx`): memory barrier, cmpxchg (não-SMP) e get_tls.
- Syscalls: `exit, exit_group, read, write, writev, brk, mmap2 (anônimo), munmap,
  open/openat/close/lseek/_llseek (somente leitura), stat64/lstat64/fstat64, ioctl
  (TCGETS/TIOCGWINSZ), getcwd, access/faccessat, fcntl64, uname, clock_gettime,
  gettimeofday, getpid/tid/uid/gid, set*id (no-op), wait4 (-ECHILD),
  rt_sigaction/rt_sigprocmask (ignorados), readlink (-EINVAL), cacheflush, set_tls`.
  Desconhecidas: log + `-ENOSYS`.
- Segfault do guest = dump de registradores + exit 139 (como um shell reportaria).
- SMC coberto: a memória guest é envolvida por `InvalidationAwareAddressSpace`.

**Limites atuais (fase 3 se houver demanda):** sem `fork`/`vfork`/`execve` — o shell
roda builtins em processo, mas pipelines, subshells e comandos externos não; sem
escrita em arquivos; sem `getdents64` (`ls`); sem entrega de sinais.

**Armadilha documentada:** `TCGETS` escreve o termios do KERNEL (36 bytes), não o da
libc (60) — escrever 60 estoura o buffer de pilha do chamador e o processo "retorna"
para pc=0. Custou uma sessão de debug; ver `LinuxGuest.KERNEL_TERMIOS_SIZE`.

## Binários de teste

Não há toolchain glibc no Windows; os binários de teste usam **syscalls cruas**
(`-nostdlib`) compilados pelo devkitARM (`C:\devkitPro\devkitARM`):

```powershell
.\testdata\build-testdata.ps1   # gera testdata/hello.elf a partir de hello.s
java -jar target/armbox-*.jar testdata/hello.elf   # → "hello from a real ELF", exit 42
```

Os testes de integração também montam ELFs sintéticos em memória (instruções ARM
codificadas à mão em `ArmboxIntegrationTest`), então `mvn test` funciona sem toolchain.

`testdata/busybox-armv5l` é o build estático oficial de busybox.net (musl, ARMv5L):

```powershell
java -jar target/armbox-*.jar testdata/busybox-armv5l sh -c "echo a; echo b"
```

## Compilação

JDK do projeto = JBR 25 (`C:\Users\user\.jdks\jbr-25.0.3`). Requer
`dev.vitorsilverio:arm-jitter:1.0` no repositório Maven local (`mvn install` no
arm-jitter).
