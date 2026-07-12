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
java -jar target/armbox-1.0-SNAPSHOT.jar [--arch=armv5te|armv6k|thumb2] [--interp|--check] <elf> [args...]
```

| Flag | Efeito |
|------|--------|
| `--arch=armv5te` (padrão) | `ArmArchitecture.ARMV5TE` — comportamento histórico do armbox, sem mudança |
| `--arch=armv6k` | `ArmArchitecture.ARMV6K` — habilita extend/reverse/UMAAL, SIMD paralelo, PKH/SAT/USAD8, LDREX/STREX/CLREX, CPS/SETEND/WFI (B1.1-B1.6) |
| `--arch=thumb2` | `ArmArchitecture.ARMV6K_THUMB2_PARTIAL` — ARMv6K mais o subconjunto Thumb-2 de 32 bits já implementado (infra de B2.1 + data-processing de B2.2: modified immediate com carry-out, MOVW/MOVT, ADD/SUB/ADR, forma registrador com shift incl. RRX). **Não** é o ARMv7-A completo — sem load/store 32-bit, branches/IT ou misc de 32 bits ainda (task B4.0.2) |
| (padrão) | JIT bytecode JVM (`JitRuntimeFactory.armThumb`) |
| `--interp` | Interpretador IR (debug/oráculo) |
| `--check` | JIT e interpretador em paralelo, aborta na primeira divergência |

`--arch` é processado antes de `--interp`/`--check` e antes do caminho do ELF; os dois
podem ser combinados: `--arch=armv6k --check testdata/armv6k-torture.elf`.

O código de saída do processo é o `exit()` do guest.

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

### Binários ARMv6K (task B4.0.1)

`B1.1-B1.6` implementaram e testaram ARMv6K inteiramente por equivalência Java — nunca
por um binário ELF real. `testdata/armv6k-torture.s` fecha essa lacuna: um torture
test escrito à mão (`-nostdlib`, devkitARM `-march=armv6k`), auto-verificável, cobrindo
pelo menos um representante de cada grupo de instrução novo:

- `SXTB` com rotação, `UXTAH` com acumulador **e** rotação;
- `REV`/`REV16`, `UMAAL`;
- `SADD16` (com GE), `UQSUB8`, `UADD8`+`SEL` (SEL consumindo o GE produzido);
- `PKHBT`, `SSAT` (com o Q sticky), `USAD8`;
- `LDREX`/`STREX`/`CLREX` — sucesso E as **duas** formas de falha do monitor de
  exclusividade, forçadas explicitamente: `STREX` sem `LDREX` prévio, e
  `LDREX`→`CLREX`→`STREX`;
- `CPS` (`CPSID`/`CPSIE if`), `SETEND` (round-trip BE→LE sem acesso a dado no meio —
  ver Armadilhas), `WFI` (ver seção acima sobre a política de auto-wake).

Cada grupo compara o resultado contra o vetor esperado (os mesmos valores dos testes
Java `ArmV6*Test`) e sai com um **código de saída único por checagem** (1-26) se
divergir; sucesso = exit 0.

```powershell
java -jar target/armbox-*.jar --arch=armv6k testdata/armv6k-torture.elf   # exit 0, "armv6k torture: ok"
java -jar target/armbox-*.jar --arch=armv6k --interp testdata/armv6k-torture.elf
java -jar target/armbox-*.jar --arch=armv6k --check testdata/armv6k-torture.elf
```

`testdata/armv6k-torture-broken.s` é o "teste do teste": uma cópia mínima do mesmo
padrão de verificação com um valor esperado **deliberadamente errado** — prova que o
harness realmente detectaria uma regressão (exit 77, não 0). Não é cobertura de
instrução nova, só uma sentinela do harness em si (`ArmV6TortureTest`).

`testdata/hello-armv6k.s` é o sinal complementar: o mesmo `hello.s` (sem nenhuma
instrução ARMv6K nova — GCC raramente emite SIMD paralelo/UMAAL/LDREX sem intrínsecos)
recompilado com `-march=armv6k`, provando que o toolchain aceita o alvo para código
"normal" também. `hello.elf`/`hello.s` (ARMv5TE) continuam intocados.

### Binário Thumb-2 (task B4.0.2)

`B2.1` (infra) e `B2.2` (data-processing de 32 bits) foram implementados e testados
inteiramente por equivalência Java (`Thumb2DataProcessingDecoderTest`) — nunca por um
binário ELF real. Diferente de B4.0.1, a arquitetura `ARMV6K_THUMB2_PARTIAL` (com
`ArmFeature.THUMB2` + `Thumb2DataProcessingDecoder`) não existia como preset público
antes desta task — só como `ArmArchitecture.extending(...)` construído manualmente
dentro de cada teste; agora vive em `ArmArchitecture.ARMV6K_THUMB2_PARTIAL` no
arm-jitter, ao lado de `ARMV6K`.

O nome deliberadamente **não** diz "THUMB2" sozinho nem "ARMv7": não é o ARMv7-A
completo da task B3 (sem VFP/SDIV/UDIV), nem sequer o Thumb-2 completo do épico B2
(faltam load/store 32-bit de B2.3, branches+IT de B2.4 e misc de B2.5 — mesmo já ✅ no
índice de tasks, o Objetivo desta task B4.0.2 escopa só B2.1-B2.2; a convenção
documentada no arm-jitter é acrescentar o `DecoderExtension` de cada B2.x nova ao
preset conforme fecham).

`testdata/thumb2-torture.s` cobre pelo menos um representante de cada grupo pedido:

- modified immediate com carry-out (`ANDS`/`ADDS` com imediato rotacionado; `MVN`
  via o alias `ORN`+`Rn=PC` de graça);
- `MOVW`+`MOVT` compondo uma constante de 32 bits;
- `ADD Rd,SP,#imm` (Rn=SP genérico) e `ADR` nas duas direções (Rn=PC, soma e
  subtração — cobre os dois ramos `PLAIN_OP_ADD`/`PLAIN_OP_SUB`);
- forma registrador com shift imediato (`ADD ...,LSL#n`) e `RRX` (só existe em
  Thumb-2, sem equivalente Thumb-1).

Escrito à mão em encoding Thumb-2 de 32 bits genuíno (`.syntax unified`, sufixo `.w`
explícito nos casos ambíguos) — **só usa branches Thumb-1 de 16 bits** (`B`/`Bcc`
curtos): `B.W`/`BL.W` de 32 bits são o grupo de branches/IT de B2.4, que este preset
ainda não decodifica, e um branch de 32 bits no teste viraria `UNDEFINED`.

```powershell
java -jar target/armbox-*.jar --arch=thumb2 testdata/thumb2-torture.elf   # exit 0, "thumb2 torture: ok"
java -jar target/armbox-*.jar --arch=thumb2 --interp testdata/thumb2-torture.elf
java -jar target/armbox-*.jar --arch=thumb2 --check testdata/thumb2-torture.elf
```

## Compilação

JDK do projeto = JBR 25 (`C:\Users\user\.jdks\jbr-25.0.3`). Requer
`dev.vitorsilverio:arm-jitter:1.0` no repositório Maven local (`mvn install` no
arm-jitter).
