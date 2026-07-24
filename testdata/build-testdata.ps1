# Gera os binários de teste com o devkitARM (arm-none-eabi, bare-metal).
# Não há toolchain glibc no Windows; os programas usam syscalls cruas (-nostdlib),
# que é exatamente o que a fase 1 do armbox suporta.
$gcc = "C:\devkitPro\devkitARM\bin\arm-none-eabi-gcc.exe"
& $gcc -nostdlib -static -march=armv5te "-Wl,-Ttext=0x10000" -o "$PSScriptRoot\hello.elf" "$PSScriptRoot\hello.s"
if ($LASTEXITCODE -ne 0) { throw "build do hello.elf falhou" }
Write-Host "hello.elf gerado."

# B4.0.1 — binários reais ARMv6K (--arch=armv6k), veja armv6k-torture.s para a lista
# de instrucoes cobertas e hello-armv6k.s para o sinal complementar de compilador.
& $gcc -nostdlib -static -march=armv6k "-Wl,-Ttext=0x10000" -o "$PSScriptRoot\armv6k-torture.elf" "$PSScriptRoot\armv6k-torture.s"
if ($LASTEXITCODE -ne 0) { throw "build do armv6k-torture.elf falhou" }
Write-Host "armv6k-torture.elf gerado."

& $gcc -nostdlib -static -march=armv6k "-Wl,-Ttext=0x10000" -o "$PSScriptRoot\armv6k-torture-broken.elf" "$PSScriptRoot\armv6k-torture-broken.s"
if ($LASTEXITCODE -ne 0) { throw "build do armv6k-torture-broken.elf falhou" }
Write-Host "armv6k-torture-broken.elf gerado."

& $gcc -nostdlib -static -march=armv6k "-Wl,-Ttext=0x10000" -o "$PSScriptRoot\hello-armv6k.elf" "$PSScriptRoot\hello-armv6k.s"
if ($LASTEXITCODE -ne 0) { throw "build do hello-armv6k.elf falhou" }
Write-Host "hello-armv6k.elf gerado."

# B4.0.2 — binário real Thumb-2 (--arch=thumb2), veja thumb2-torture.s para a lista de
# grupos cobertos (subconjunto B2.1-B2.2: modified immediate com carry, MOVW/MOVT,
# ADD/ADR com SP/PC, forma registrador com shift incl. RRX). -mthumb força o assembler
# a começar em estado Thumb; -march=armv7-a é o menor alvo do devkitARM com Thumb-2
# completo (ARMv6T2 introduziu Thumb-2, mas o devkitARM não expõe esse -march
# diretamente — armv7-a é um superconjunto seguro para o subconjunto testado aqui).
& $gcc -nostdlib -static -march=armv7-a -mthumb "-Wl,-Ttext=0x10000" -o "$PSScriptRoot\thumb2-torture.elf" "$PSScriptRoot\thumb2-torture.s"
if ($LASTEXITCODE -ne 0) { throw "build do thumb2-torture.elf falhou" }
Write-Host "thumb2-torture.elf gerado."

& $gcc -nostdlib -static -march=armv7-a -mthumb "-Wl,-Ttext=0x10000" -o "$PSScriptRoot\thumb2-torture-broken.elf" "$PSScriptRoot\thumb2-torture-broken.s"
if ($LASTEXITCODE -ne 0) { throw "build do thumb2-torture-broken.elf falhou" }
Write-Host "thumb2-torture-broken.elf gerado."

# B3.7 — preset ARMv7-A (--arch=armv7a): armv7a-torture.s cobre inteiro v7 (B3.1/B3.2:
# MOVW/MOVT, bitfield, RBIT, MLS, SDIV/UDIV, DMB) + VFP (B3.3-B3.6: VMOV imediato,
# transferencia core<->S/D, VADD/VMUL/VDIV/VSQRT, VCMP+VMRS, VCVT, VLDR/VSTR,
# VPUSH/VPOP). hello-float.c e o unico testdata em C do repo, mas segue o MESMO padrao
# bare-metal dos demais (-nostdlib, sem CRT/libc/libgcc, syscalls cruas via svc) --
# ver o comentario no topo do .c para o motivo (evita de proposito o risco de NEON de
# uma libc pre-compilada, citado na armadilha da task).
& $gcc -nostdlib -static -march=armv7ve -mfpu=vfpv3-d16 "-Wl,-Ttext=0x10000" -o "$PSScriptRoot\armv7a-torture.elf" "$PSScriptRoot\armv7a-torture.s"
if ($LASTEXITCODE -ne 0) { throw "build do armv7a-torture.elf falhou" }
Write-Host "armv7a-torture.elf gerado."

& $gcc -nostdlib -static -march=armv7ve -mfpu=vfpv3-d16 "-Wl,-Ttext=0x10000" -o "$PSScriptRoot\armv7a-torture-broken.elf" "$PSScriptRoot\armv7a-torture-broken.s"
if ($LASTEXITCODE -ne 0) { throw "build do armv7a-torture-broken.elf falhou" }
Write-Host "armv7a-torture-broken.elf gerado."

& $gcc -nostdlib -static -march=armv7-a -mfpu=vfp -mfloat-abi=hard -O2 "-Wl,-Ttext=0x10000" -o "$PSScriptRoot\hello-float.elf" "$PSScriptRoot\hello-float.c"
if ($LASTEXITCODE -ne 0) { throw "build do hello-float.elf falhou" }
Write-Host "hello-float.elf gerado."

# B7.5 — runner bare-metal Cortex-M (--machine=cortex-m --arch=armv6m|armv7m): firmware
# bare-metal de verdade (tabela de vetores própria, sem CRT/libc, saída via semihosting
# BKPT 0xAB), ligado com o linker script flash.ld (flash em 0x0, RAM em 0x20000000).
& $gcc -nostdlib -static -mcpu=cortex-m3 -mthumb -T "$PSScriptRoot\flash.ld" -o "$PSScriptRoot\cortexm-torture.elf" "$PSScriptRoot\cortexm-torture.s"
if ($LASTEXITCODE -ne 0) { throw "build do cortexm-torture.elf falhou" }
Write-Host "cortexm-torture.elf gerado."

& $gcc -nostdlib -static -mcpu=cortex-m3 -mthumb -T "$PSScriptRoot\flash.ld" -o "$PSScriptRoot\cortexm-torture-broken.elf" "$PSScriptRoot\cortexm-torture-broken.s"
if ($LASTEXITCODE -ne 0) { throw "build do cortexm-torture-broken.elf falhou" }
Write-Host "cortexm-torture-broken.elf gerado."

& $gcc -nostdlib -static -mcpu=cortex-m0 -mthumb -T "$PSScriptRoot\flash.ld" -o "$PSScriptRoot\cortexm-torture-m0.elf" "$PSScriptRoot\cortexm-torture-m0.s"
if ($LASTEXITCODE -ne 0) { throw "build do cortexm-torture-m0.elf falhou" }
Write-Host "cortexm-torture-m0.elf gerado."

& $gcc -nostdlib -static -mcpu=cortex-m3 -mthumb -T "$PSScriptRoot\flash.ld" -O2 -o "$PSScriptRoot\hello-cortexm.elf" "$PSScriptRoot\hello-cortexm.c"
if ($LASTEXITCODE -ne 0) { throw "build do hello-cortexm.elf falhou" }
Write-Host "hello-cortexm.elf gerado."
