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
