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

## Estado (fase 1)

- Loader ELF32 `ET_EXEC` estático (PIE e dinâmico são rejeitados com mensagem clara).
- Pilha inicial ABI: argc/argv/envp + auxv (AT_PHDR/PAGESZ/ENTRY/RANDOM/HWCAP/...).
- kuser helpers (`0xFFFF0Fxx`): memory barrier, cmpxchg (não-SMP) e get_tls.
- Syscalls: `exit, exit_group, read, write, writev, brk, mmap2 (anônimo), munmap,
  open/openat/close/lseek/_llseek (somente leitura), fstat64 (stub), uname,
  clock_gettime, gettimeofday, getpid/tid/uid/gid, rt_sigaction/rt_sigprocmask
  (ignorados), cacheflush, set_tls`. Desconhecidas: log + `-ENOSYS`.
- SMC coberto: a memória guest é envolvida por `InvalidationAwareAddressSpace`.

Fase 2 (busybox): mmap de arquivo, ioctl(TCGETS), stubs de sinal restantes — ver a
spec da task B4.0.

## Binários de teste

Não há toolchain glibc no Windows; os binários de teste usam **syscalls cruas**
(`-nostdlib`) compilados pelo devkitARM (`C:\devkitPro\devkitARM`):

```powershell
.\testdata\build-testdata.ps1   # gera testdata/hello.elf a partir de hello.s
java -jar target/armbox-*.jar testdata/hello.elf   # → "hello from a real ELF", exit 42
```

Os testes de integração também montam ELFs sintéticos em memória (instruções ARM
codificadas à mão em `ArmboxIntegrationTest`), então `mvn test` funciona sem toolchain.

## Compilação

JDK do projeto = JBR 25 (`C:\Users\user\.jdks\jbr-25.0.3`). Requer
`dev.vitorsilverio:arm-jitter:1.0` no repositório Maven local (`mvn install` no
arm-jitter).
