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
binário ELF real. Diferente de B4.0.1, a arquitetura `ARMV6K_THUMB2` (com
`ArmFeature.THUMB2` + `Thumb2DataProcessingDecoder`) não existia como preset público
antes desta task — só como `ArmArchitecture.extending(...)` construído manualmente
dentro de cada teste; agora vive em `ArmArchitecture.ARMV6K_THUMB2` no
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

### Binário de compilador Thumb-2 completo (task B4.0.3)

Nível N3 da matriz (`docs/VALIDACAO-ARQUITETURAS.md` do arm-jitter) para Thumb-2: código
que nós não escrevemos à mão, com a malícia real de um compilador — load/store de 32
bits, tabela `TBB` e `IT` blocks à vontade.

`testdata/hello-thumb2.c` é `gcc` real (`-march=armv7-a -mthumb -Os -nostdlib -static`,
ver o comentário no topo do `.c`): um struct com bitfields (`UBFX`/`SBFX`/`STRH`), `STRD`
explícito, um switch denso que o gcc compila para `TBB`, e um qsort pequeno e recursivo
(`IT` blocks + cmp/branches curtos). Roda com `--arch=armv7a`, **não** `--arch=thumb2` —
o struct com bitfields obriga `UBFX`/`SBFX`, que só o preset `ARMV7A` decodifica em
Thumb-2 (`ArmFeature.BIT_FIELD`; `ARMV6K_THUMB2` não tem essa feature, decisão do épico
B3), mesmo fallback previsto pela própria task quando o binário usa algo v7-only.
`objdump -d` confirma `ldr.w`/`strd`/`ldmia.w`/`tbb`/`it`(`e`)/`bl` no binário, e a
ausência de `sdiv`/`udiv`/`movw`/`movt`/`bfi`/`dmb` (só `ubfx`/`sbfx`, daí o fallback).

```powershell
java -jar target/armbox-*.jar --arch=armv7a testdata/hello-thumb2.elf         # checksum hex + exit 0
java -jar target/armbox-*.jar --arch=armv7a --interp testdata/hello-thumb2.elf
java -jar target/armbox-*.jar --arch=armv7a --check testdata/hello-thumb2.elf
```

**Achado real desta task**: o preset público `ArmArchitecture.ARMV7A` (arm-jitter) tinha
um bug de fiação — `Thumb2DataProcessingDecoder`/`Thumb2RegisterDataProcessingDecoder`/
`Thumb2MultiplyDecoder` recebiam `ARMV6K_THUMB2_FEATURES` (sem `BIT_FIELD`/
`BIT_REVERSE`/`MLS_MULTIPLY`/`DIVIDE`) no construtor em vez de `ARMV7A_FEATURES`, então
`UBFX`/`SBFX`/`RBIT`/`SDIV`/`UDIV`/`MLS` em encoding **Thumb-2** viravam `UNDEFINED`
mesmo com o preset `ARMV7A` tendo as features certas — o encoding ARM clássico nunca
teve esse bug (usa a `architecture` real, não uma cópia guardada no construtor).
Corrigido no arm-jitter; ver o javadoc de `ArmArchitecture.ARMV7A` e o teste de
regressão `ArmV7aThumb2PresetIntegrationTest` (decodifica contra o preset PÚBLICO, não
um preset sintético de teste — é o que teria pego esse bug antes).

**Busybox Thumb-2 (item 3 da task, NÃO fechado nesta sessão)**: a task pede um busybox
estático compilado em Thumb-2, baixado ou buildado. Não há binário pré-compilado em
Thumb-2 nos releases oficiais de busybox.net (só ARM mode, uclibc-static, ver
`testdata/busybox-armv5l`). Buildar da fonte exige um toolchain `arm-linux-*` (musl/
glibc) real — os cross-toolchains Linux do musl.cc (`armv7l-linux-musleabihf-cross`,
por exemplo) são binários ELF Linux, não rodam neste ambiente Windows/MSYS2 (sem WSL
com uma distro completa configurada); o devkitARM instalado é bare-metal
(`arm-none-eabi`, sem libc de userspace Linux), não serve para linkar busybox. Fica
pendente para uma sessão com um toolchain `arm-linux-*` real disponível (ex.: WSL com
uma distro Linux configurada, ou um cross-toolchain Windows-hosted).

### Modo bare-metal Cortex-M (task B7.5)

`--machine=cortex-m` é um segundo modo de máquina, ao lado do `linux-user` de sempre:
sem SO, boot pela tabela de vetores (ARMv7-M ARM §B1.5.5) em vez de ELF+syscalls.
Reusa o loader ELF (`.elf`) — ou aceita `.bin` cru carregado direto em `0x0` — e os
mesmos 3 backends (JIT/interpretado/`--check`).

Mapa de memória fixo: flash em `0x00000000` (imagem carregada), RAM em `0x20000000`
(tamanho por `--ram-size`, default 1 MiB), SCS (System Control Space — `NVIC`/`SysTick`/
`SHPR`/`VTOR`/...) em `0xE000E000`, implementado por
`dev.vitorsilverio.armjitter.core.MProfileSystemControl`. Todo o mapa vive num
`PagedAddressSpace` (task C3) — primeiro consumidor real do utilitário fora dos
próprios testes/benchmark dele.

Saída via **semihosting** (`BKPT 0xAB`, convenção ARM padrão: R0 = operação, R1 =
ponteiro/valor de argumento) — implementado como um `BkptDispatcher` novo no
arm-jitter (mesmo padrão do `SwiDispatcher` de SWI), instalado via
`ArmCore#setBkptDispatcher`:

- `SYS_WRITE0` (`0x04`): string NUL-terminada em `[R1]` → stdout (limite de 64 KiB
  contra firmware quebrado sem NUL).
- `SYS_WRITEC` (`0x03`): um caractere em `[R1]` → stdout.
- `SYS_EXIT` (`0x18`): `R1` = código de saída do processo.

```powershell
java -jar target/armbox-*.jar --arch=armv7m --machine=cortex-m testdata/cortexm-torture.elf   # exit 0
java -jar target/armbox-*.jar --arch=armv6m --machine=cortex-m testdata/cortexm-torture-m0.elf
java -jar target/armbox-*.jar --arch=armv7m --machine=cortex-m testdata/hello-cortexm.elf      # "hello cortex-m"
```

`testdata/cortexm-torture.s` (ARMv7-M, `-mcpu=cortex-m3`) e `testdata/cortexm-torture-m0.s`
(subconjunto ARMv6-M, `-mcpu=cortex-m0`, sem MOVW/MOVT/SDIV/UDIV/UBFX/LDREX/STREX/blocos
IT) cobrem: reset com MSP correto; `SVC` respondido em MSP e depois em PSP (troca via
`CONTROL.SPSEL`); `SysTick` (RVR curto, `TICKINT`, contador incrementado por handler);
`PendSV` pendido de DENTRO do handler de `SysTick` e só entrando depois (prioridade
igual não preempta); `PRIMASK` segurando a entrega e liberando na sequência;
`MRS`/`MSR` de `MSP`/`PSP`/`CONTROL`/`PRIMASK` ida-e-volta; e (só a variante m3)
`MOVW`/`MOVT`, `SDIV`/`UDIV`, `UBFX`, `LDREX`+`STREX`. Sai com 0 (tudo passou) ou 1
(alguma checagem falhou) — `cortexm-torture-broken.s` é o "teste do teste" (uma
checagem deliberadamente errada, prova que o harness detecta regressão).
`hello-cortexm.c` é o sinal de compilador real: gcc puro (`-nostdlib`, sem CRT), tabela
de vetores como array de ponteiros de função em C (o compilador já emite o bit Thumb
certo em cada entrada, sem `.word` cru).

Ver `CortexMTortureTest` (JUnit) e `dev.vitorsilverio.armbox.baremetal.CortexMMachine`.

## Compilação

JDK do projeto = JBR 25 (`C:\Users\user\.jdks\jbr-25.0.3`). `dev.vitorsilverio:arm-jitter:1.1.0`
(+ `arm-jitter-truffle`) resolvem do **Maven Central**, sem `mvn install` local. Só é preciso
instalar uma versão local (`-SNAPSHOT`, sem commitar) quando se está desenvolvendo a lib junto
com o armbox — ver `arm-jitter/README.md`.

### Otimizações do binário nativo (task A8, 2026-07-31)

5 variantes do perfil Maven `native` medidas na mesma máquina/sessão (GraalVM
25.0.3+9.1 Oracle, MSVC 19.44.35226, `armbox.exe`). Protocolo: best-of-5 para
startup/throughput; RSS = `PeakWorkingSet64` amostrado por polling a cada 20ms
durante a execução única do workload de throughput; corretude = `hello.elf`
(exit 42) + `busybox-armv5l sh -c "echo hi"` (stdout `hi`, exit 0) nos dois
backends (`--truffle`/`--interp`).

Workloads: startup = `armbox.exe --truffle hello.elf`; throughput = o loop de
referência das tasks A5/A7 (`busybox sh -c 'i=0; while [ $i -lt 2000 ]; do
i=$((i+1)); done; echo done $i'`) medido em `--truffle` e `--interp`; RSS
amostrado durante o throughput `--truffle`.

| # | Variante | Startup (ms) | Throughput truffle (ms) | Throughput interp (ms) | RSS pico (MB) | Corretude |
|---|----------|-------------:|-------------------------:|------------------------:|--------------:|:---------:|
| 1 | Baseline (config anterior à A8) | 31,25 | 2563,80 | 1720,41 | 101,84 | ✅ |
| 2 | `-O3` | 43,22 | 2318,97 | 1625,09 | 103,56 | ✅ |
| 3 | `-O3 -march=native` | 33,50 | 2174,94 | 1535,99 | 103,84 | ✅ |
| 4 | `--gc=G1` | — | — | — | — | 🔴 build falhou: `Error: The G1 garbage collector ('--gc=G1') is currently only supported on Linux AMD64 and AArch64.` (esperado pela própria task — registrado, não é bloqueio) |
| **5** | **`--pgo=<profile> -O3`** ✅ **vencedora** | **33,01** | **2101,03** | **1466,21** | **97,29** | ✅ |

A variante **5 (PGO+O3)** venceu (ou empatou) as 4 métricas simultaneamente —
melhor throughput nos dois backends, menor RSS (única variante ABAIXO do
baseline) e startup no mesmo patamar da `-march=native` — e foi **promovida a
default do perfil `native`** (`pom.xml`). `-march=native` (variante 3) gera
binário não-portável (amarrado ao microarch da máquina de build) e não venceu
PGO em nenhuma métrica, por isso não foi escolhida apesar de próxima.

O perfil PGO usado (`native-profile/default.iprof`, comitado no repo) foi
gerado com o workload de throughput `--truffle` + `hello.elf` (cobre boot e
loop quente), conforme a task pede. Para regenerar após mudanças relevantes de
código (arm-jitter/truffle ou armbox):

```bat
call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat"
set JAVA_HOME=E:\graalvm-jdk-25.0.3+9.1
set PATH=%JAVA_HOME%\bin;%PATH%
cd armbox

:: 1. build instrumentado (edite temporariamente o buildArg para --pgo-instrument)
mvn -Pnative -DskipTests package
target\armbox.exe --truffle testdata\hello.elf
target\armbox.exe --truffle testdata\busybox-armv5l sh -c "i=0; while [ $i -lt 2000 ]; do i=$((i+1)); done; echo done $i"
:: gera .\default.iprof (CWD do processo, não target\)
move default.iprof native-profile\default.iprof

:: 2. build final (buildArg de volta a --pgo=native-profile/default.iprof -O3)
mvn -Pnative -DskipTests package
```

**Armadilha confirmada** (já citada na task): o build instrumentado é
deliberadamente lento — não comparar tempos dele, só existe para gerar o
`.iprof`. G1 falhou por ser Linux/AArch64-only nesta versão do native-image,
não por erro de configuração.

## Licença

BSD 3-Clause — ver [LICENSE](LICENSE).

Os binários de terceiros usados em testes e execução (BIOS, firmware, ROMs, kernels,
`busybox`) **não** são cobertos por esta licença e não são redistribuídos por este projeto
salvo quando a licença original permitir; ver o `README.md` do diretório correspondente.
