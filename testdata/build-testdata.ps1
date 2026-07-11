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
