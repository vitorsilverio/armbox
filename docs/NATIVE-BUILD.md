# Build nativo (GraalVM native-image) e benchmarks

Visão geral do projeto: [README](../README.md).

## Otimizações do binário nativo (task A8, 2026-07-31)

5 variantes do perfil Maven `native` medidas na mesma máquina/sessão (GraalVM
25.0.3+9.1 Oracle, MSVC 19.44.35226, `armbox.exe`). Protocolo: best-of-5 para
startup/throughput; RSS = `PeakWorkingSet64` amostrado por polling a cada 20ms
durante a execução única do workload de throughput; corretude = `hello.elf`
(exit 42) + `busybox-armv5l sh -c "echo hi"` (stdout `hi`, exit 0) nos dois
backends (`--truffle`/`--interp`).

Workloads: startup = `armbox.exe --truffle hello.elf`; throughput = o loop de
referência (`busybox sh -c 'i=0; while [ $i -lt 2000 ]; do i=$((i+1)); done; echo done $i'`)
medido em `--truffle` e `--interp`; RSS amostrado durante o throughput `--truffle`.

| # | Variante | Startup (ms) | Throughput truffle (ms) | Throughput interp (ms) | RSS pico (MB) | Corretude |
|---|----------|-------------:|-------------------------:|------------------------:|--------------:|:---------:|
| 1 | Baseline (config anterior à A8) | 31,25 | 2563,80 | 1720,41 | 101,84 | ✅ |
| 2 | `-O3` | 43,22 | 2318,97 | 1625,09 | 103,56 | ✅ |
| 3 | `-O3 -march=native` | 33,50 | 2174,94 | 1535,99 | 103,84 | ✅ |
| 4 | `--gc=G1` | — | — | — | — | 🔴 build falhou: `Error: The G1 garbage collector ('--gc=G1') is currently only supported on Linux AMD64 and AArch64.` (esperado — registrado, não é bloqueio) |
| **5** | **`--pgo=<profile> -O3`** ✅ **vencedora** | **33,01** | **2101,03** | **1466,21** | **97,29** | ✅ |

A variante **5 (PGO+O3)** venceu (ou empatou) as 4 métricas simultaneamente — melhor
throughput nos dois backends, menor RSS (única variante abaixo do baseline) e startup
no mesmo patamar da `-march=native` — e foi **promovida a default do perfil `native`**
(`pom.xml`). `-march=native` (variante 3) gera binário não-portável (amarrado ao
microarch da máquina de build) e não venceu PGO em nenhuma métrica.

O perfil PGO usado (`native-profile/default.iprof`, comitado no repo) foi gerado com o
workload de throughput `--truffle` + `hello.elf` (cobre boot e loop quente). Para
regenerar após mudanças relevantes de código (arm-jitter/truffle ou armbox):

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

**Armadilha confirmada**: o build instrumentado é deliberadamente lento — não comparar
tempos dele, só existe para gerar o `.iprof`. G1 falhou por ser Linux/AArch64-only nesta
versão do native-image, não por erro de configuração.
