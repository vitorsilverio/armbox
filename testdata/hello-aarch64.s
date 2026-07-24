// hello-aarch64.s — menor programa Linux arm64 user-mode possível (syscalls cruas, sem libc).
// Montado com aarch64-none-elf-as/-ld (devkitA64, bare-metal) — ver build-testdata.ps1.
// Convenção de syscall arm64: número em x8, args em x0-x5, svc #0, retorno em x0.
    .text
    .global _start
_start:
    mov     x0, #1              // fd = stdout
    adr     x1, msg
    mov     x2, #(msg_end - msg)
    mov     x8, #64             // NR_write (arm64: numeros DIFEREM do ARM 32-bit EABI)
    svc     #0
    mov     x0, #42             // código de saída (prova o plumbing do exit)
    mov     x8, #93             // NR_exit
    svc     #0
msg:
    .ascii  "hello from a real AArch64 ELF\n"
msg_end:
