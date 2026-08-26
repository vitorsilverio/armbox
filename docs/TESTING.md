# Binários de teste e cobertura por arquitetura

Detalhe de como cada arquitetura suportada pelo `armbox` foi validada com binários reais
(não só testes de equivalência Java). Visão geral do projeto: [README](../README.md).

## ARMv5TE (baseline)

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

## Binários ARMv6K

`testdata/armv6k-torture.s` é um torture test escrito à mão (`-nostdlib`, devkitARM
`-march=armv6k`), auto-verificável, cobrindo pelo menos um representante de cada grupo de
instrução novo:

- `SXTB` com rotação, `UXTAH` com acumulador **e** rotação;
- `REV`/`REV16`, `UMAAL`;
- `SADD16` (com GE), `UQSUB8`, `UADD8`+`SEL` (SEL consumindo o GE produzido);
- `PKHBT`, `SSAT` (com o Q sticky), `USAD8`;
- `LDREX`/`STREX`/`CLREX` — sucesso E as **duas** formas de falha do monitor de
  exclusividade, forçadas explicitamente: `STREX` sem `LDREX` prévio, e
  `LDREX`→`CLREX`→`STREX`;
- `CPS` (`CPSID`/`CPSIE if`), `SETEND` (round-trip BE→LE sem acesso a dado no meio), `WFI`
  (ver seção sobre a política de auto-wake no README).

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
harness realmente detectaria uma regressão (exit 77, não 0).

`testdata/hello-armv6k.s` é o sinal complementar: o mesmo `hello.s` recompilado com
`-march=armv6k`, provando que o toolchain aceita o alvo para código "normal" também.

## Binário Thumb-2

`B2.1` (infra) e `B2.2` (data-processing de 32 bits) foram validados por equivalência
Java antes de existir um binário ELF real. `ArmArchitecture.ARMV6K_THUMB2` (ARMv6K + o
subconjunto Thumb-2 de 32 bits de B2.1-B2.2 — **não** é o ARMv7-A completo, sem
VFP/SDIV/UDIV, nem sequer o Thumb-2 completo, faltam load/store 32-bit/branches+IT/misc).

`testdata/thumb2-torture.s` cobre pelo menos um representante de cada grupo pedido:

- modified immediate com carry-out (`ANDS`/`ADDS` com imediato rotacionado; `MVN`
  via o alias `ORN`+`Rn=PC` de graça);
- `MOVW`+`MOVT` compondo uma constante de 32 bits;
- `ADD Rd,SP,#imm` (Rn=SP genérico) e `ADR` nas duas direções (Rn=PC, soma e
  subtração);
- forma registrador com shift imediato (`ADD ...,LSL#n`) e `RRX` (só existe em
  Thumb-2, sem equivalente Thumb-1).

Escrito à mão em encoding Thumb-2 de 32 bits genuíno (`.syntax unified`) — só usa
branches Thumb-1 de 16 bits (`B.W`/`BL.W` de 32 bits ainda não são decodificados por
este preset).

```powershell
java -jar target/armbox-*.jar --arch=thumb2 testdata/thumb2-torture.elf   # exit 0, "thumb2 torture: ok"
java -jar target/armbox-*.jar --arch=thumb2 --interp testdata/thumb2-torture.elf
java -jar target/armbox-*.jar --arch=thumb2 --check testdata/thumb2-torture.elf
```

## Binário de compilador Thumb-2 completo (ARMv7-A)

`testdata/hello-thumb2.c` é `gcc` real (`-march=armv7-a -mthumb -Os -nostdlib -static`):
um struct com bitfields (`UBFX`/`SBFX`/`STRH`), `STRD` explícito, um switch denso que o
gcc compila para `TBB`, e um qsort pequeno e recursivo (`IT` blocks + cmp/branches
curtos). Roda com `--arch=armv7a` (não `--arch=thumb2` — o struct com bitfields exige
`UBFX`/`SBFX`, que só o preset `ARMV7A` decodifica em Thumb-2).

```powershell
java -jar target/armbox-*.jar --arch=armv7a testdata/hello-thumb2.elf         # checksum hex + exit 0
java -jar target/armbox-*.jar --arch=armv7a --interp testdata/hello-thumb2.elf
java -jar target/armbox-*.jar --arch=armv7a --check testdata/hello-thumb2.elf
```

**Achado real desta task**: o preset público `ArmArchitecture.ARMV7A` (arm-jitter) tinha
um bug de fiação — os decoders Thumb-2 recebiam as features de `ARMV6K_THUMB2` em vez de
`ARMV7A` no construtor, então `UBFX`/`SBFX`/`RBIT`/`SDIV`/`UDIV`/`MLS` em encoding
Thumb-2 viravam `UNDEFINED` mesmo com o preset `ARMV7A` correto (o encoding ARM clássico
nunca teve esse bug). Corrigido no arm-jitter; ver o javadoc de `ArmArchitecture.ARMV7A`
e o teste de regressão `ArmV7aThumb2PresetIntegrationTest`.

**Pendência conhecida**: não há binário pré-compilado ou toolchain disponível nesta
máquina (Windows/MSYS2) para gerar um busybox estático em Thumb-2 — os cross-toolchains
Linux do musl.cc são ELFs Linux que não rodam aqui, e o devkitARM instalado é bare-metal
(`arm-none-eabi`, sem libc de userspace Linux). Fica pendente para uma sessão com um
toolchain `arm-linux-*` real (WSL com distro configurada, ou cross-toolchain
Windows-hosted).

## Modo bare-metal Cortex-M

`--machine=cortex-m` é um segundo modo de máquina, ao lado do `linux-user` de sempre:
sem SO, boot pela tabela de vetores (ARMv7-M ARM §B1.5.5) em vez de ELF+syscalls. Reusa
o loader ELF (`.elf`) — ou aceita `.bin` cru carregado direto em `0x0` — e os mesmos 3
backends (JIT/interpretado/`--check`).

Mapa de memória fixo: flash em `0x00000000` (imagem carregada), RAM em `0x20000000`
(tamanho por `--ram-size`, default 1 MiB), SCS (`NVIC`/`SysTick`/`SHPR`/`VTOR`/...) em
`0xE000E000`, implementado por `dev.vitorsilverio.armjitter.core.MProfileSystemControl`.
Todo o mapa vive num `PagedAddressSpace`.

Saída via **semihosting** (`BKPT 0xAB`, convenção ARM padrão: R0 = operação, R1 =
ponteiro/valor de argumento) — implementado como um `BkptDispatcher` no arm-jitter
(mesmo padrão do `SwiDispatcher` de SWI):

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
(alguma checagem falhou) — `cortexm-torture-broken.s` é o "teste do teste".
`hello-cortexm.c` é o sinal de compilador real: gcc puro (`-nostdlib`, sem CRT), tabela
de vetores como array de ponteiros de função em C.

Ver `CortexMTortureTest` (JUnit) e `dev.vitorsilverio.armbox.baremetal.CortexMMachine`.
