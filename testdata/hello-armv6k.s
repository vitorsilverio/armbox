@ hello-armv6k.s — mesmo programa de hello.s, mas montado com alvo ARMv6K (task
@ B4.0.1, item 2 da Especificação: "sinal complementar" de que o toolchain aceita
@ -march=armv6k para código NORMAL, não só o torture test escrito à mão). Não usa
@ nenhuma instrução nova do ARMv6K — GCC/as raramente emitem SIMD paralelo, UMAAL ou
@ LDREX/STREX sem intrínsecos, então esse papel fica com armv6k-torture.s; este
@ arquivo só confirma que o alvo de arquitetura em si é aceito e roda igual sob
@ --arch=armv6k. hello.s (ARMv5TE) permanece intocado — nenhuma regressão ali.
    .arch armv6k
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
