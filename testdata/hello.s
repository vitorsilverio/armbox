@ hello.s — menor programa Linux ARM user-mode possível (syscalls cruas, sem libc).
@ Compilar com o script build-testdata.ps1 (devkitARM arm-none-eabi, -nostdlib).
    .arch armv5te
    .arm
    .text
    .global _start
_start:
    mov     r0, #1              @ fd = stdout
    adr     r1, msg
    mov     r2, #(msg_end - msg)
    mov     r7, #4              @ NR_write
    svc     #0
    mov     r0, #42             @ código de saída (prova o plumbing do exit)
    mov     r7, #1              @ NR_exit
    svc     #0
msg:
    .ascii  "hello from a real ELF\n"
msg_end:
