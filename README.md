# armbox

[![CI](https://github.com/vitorsilverio/armbox/actions/workflows/ci.yml/badge.svg)](https://github.com/vitorsilverio/armbox/actions/workflows/ci.yml)

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
java -jar target/armbox-1.0-SNAPSHOT.jar [--arch=...] [--machine=linux-user|cortex-m] [--interp|--check] [--ram-size=N] <elf|bin> [args...]
```

| Flag | Efeito |
|------|--------|
| `--arch=armv5te` (padrão) | `ArmArchitecture.ARMV5TE` — comportamento histórico do armbox, sem mudança |
| `--arch=armv6k` | `ArmArchitecture.ARMV6K` — habilita extend/reverse/UMAAL, SIMD paralelo, PKH/SAT/USAD8, LDREX/STREX/CLREX, CPS/SETEND/WFI (B1.1-B1.6) |
| `--arch=thumb2` | `ArmArchitecture.ARMV6K_THUMB2` — ARMv6K mais o subconjunto Thumb-2 de 32 bits já implementado (infra de B2.1 + data-processing de B2.2: modified immediate com carry-out, MOVW/MOVT, ADD/SUB/ADR, forma registrador com shift incl. RRX). **Não** é o ARMv7-A completo — sem load/store 32-bit, branches/IT ou misc de 32 bits ainda (task B4.0.2) |
| `--arch=armv7a` | `ArmArchitecture.ARMV7A` — inteiro v7 completo + VFPv2 (épico B3) |
| `--arch=armv6m` / `--arch=armv7m` | `ArmArchitecture.ARMV6M`/`ARMV7M` — perfil Cortex-M (épico B7); exige `--machine=cortex-m` |
| `--machine=linux-user` (padrão) | modo de sempre: ELF Linux + syscalls (ver seções abaixo) |
| `--machine=cortex-m` | bare-metal Cortex-M (task B7.5, ver seção própria) — exige `--arch=armv6m` ou `armv7m` |
| `--ram-size=N` | só `--machine=cortex-m`: tamanho da RAM em `0x20000000` em bytes (default 1 MiB) |
| (padrão) | JIT bytecode JVM (`JitRuntimeFactory.armThumb`) |
| `--interp` | Interpretador IR (debug/oráculo) |
| `--check` | JIT e interpretador em paralelo, aborta na primeira divergência |

`--arch`/`--machine`/`--ram-size` são processados antes de `--interp`/`--check` e antes
do caminho do arquivo; podem ser combinados: `--arch=armv6k --check testdata/armv6k-torture.elf`.

O código de saída do processo é o `exit()` do guest (`--machine=linux-user`) ou o
`SYS_EXIT` do semihosting (`--machine=cortex-m`).

### `WFI` em user-mode: por que não trava (task B4.0.1)

`armbox` é um runner **user-mode puro** — não há temporizador nem controlador de IRQ
(isso é infraestrutura de sistema completo, fora de escopo; ver B4.1). Um `WFI`
(ARMv6K, hint "wait for interrupt") de verdade colocaria o core em HALT esperando uma
interrupção que nunca chega, travando o processo para sempre.

A escolha explícita: o laço principal de `Armbox.run` chama `ArmCore#wake()` sempre
que observa o core em HALT após uma fatia de blocos — sem passar por
`setInterruptLine`/vetor de exceção IRQ (que exigiriam uma tabela de vetores mapeada,
que um guest user-mode não tem). Isso espelha o Linux real: um `WFI` em modo usuário
só estala o pipeline até o próximo tick do timer do kernel (tipicamente <10ms) e a
execução do processo continua normalmente, sem nenhum sinal sendo entregue. Sob essa
política, `WFI` vira um no-op observável para o guest — os registradores não mudam e a
instrução seguinte executa normalmente (validado pelos checks 25/26 de
`armv6k-torture.s`).

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

Cada arquitetura suportada (ARMv5TE, ARMv6K, Thumb-2, ARMv7-A, Cortex-M) é validada com
um binário real além dos testes de equivalência Java — torture tests escritos à mão e
compilados com devkitARM, e binários gerados por `gcc` real. Detalhe completo, incluindo
o `busybox` estático usado como corpus e as armadilhas encontradas:
**[docs/TESTING.md](docs/TESTING.md)**.

```powershell
.\testdata\build-testdata.ps1   # gera testdata/hello.elf a partir de hello.s
java -jar target/armbox-*.jar testdata/hello.elf                        # exit 42
java -jar target/armbox-*.jar testdata/busybox-armv5l sh -c "echo a; echo b"
java -jar target/armbox-*.jar --arch=armv6k testdata/armv6k-torture.elf # exit 0
```

## Compilação

JDK do projeto = JBR 25 (`C:\Users\user\.jdks\jbr-25.0.3`). `dev.vitorsilverio:arm-jitter:1.1.0`
(+ `arm-jitter-truffle`) resolvem do **Maven Central**, sem `mvn install` local. Só é preciso
instalar uma versão local (`-SNAPSHOT`, sem commitar) quando se está desenvolvendo a lib junto
com o armbox — ver `arm-jitter/README.md`.

### Build nativo (GraalVM native-image)

O perfil Maven `native` gera um executável standalone com GraalVM (perfil PGO+`-O3` como
default, medido contra 4 outras variantes). Detalhe dos benchmarks e como regenerar o
perfil PGO: **[docs/NATIVE-BUILD.md](docs/NATIVE-BUILD.md)**.

## Como contribuir

Issues e pull requests são bem-vindos — ver [CONTRIBUTING.md](CONTRIBUTING.md).

## Autor e contato

Feito por [Vitor Silvério Rodrigues](https://vitorsilverio.dev/) — blog/currículo com mais
detalhes sobre este e outros projetos. Contato: vitor.silverio.rodrigues@gmail.com ou uma
[issue](https://github.com/vitorsilverio/armbox/issues) neste repositório.

## Licença

BSD 3-Clause — ver [LICENSE](LICENSE).

Os binários de terceiros usados em testes e execução (BIOS, firmware, ROMs, kernels,
`busybox`) **não** são cobertos por esta licença e não são redistribuídos por este projeto
salvo quando a licença original permitir; ver o `README.md` do diretório correspondente.
