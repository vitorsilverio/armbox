# Gera os binários de teste com o devkitARM (arm-none-eabi, bare-metal).
# Não há toolchain glibc no Windows; os programas usam syscalls cruas (-nostdlib),
# que é exatamente o que a fase 1 do armbox suporta.
$gcc = "C:\devkitPro\devkitARM\bin\arm-none-eabi-gcc.exe"
& $gcc -nostdlib -static -march=armv5te "-Wl,-Ttext=0x10000" -o "$PSScriptRoot\hello.elf" "$PSScriptRoot\hello.s"
if ($LASTEXITCODE -ne 0) { throw "build do hello.elf falhou" }
Write-Host "hello.elf gerado."
