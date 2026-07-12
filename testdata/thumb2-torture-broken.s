@ thumb2-torture-broken.s — "teste do teste" da B4.0.2: cópia mínima do padrão de
@ verificação do thumb2-torture.s com um valor esperado DELIBERADAMENTE errado.
@
@ Existe só para provar que o harness de verificação (CHECK32: compara, sai com
@ código != 0 se divergir) realmente detectaria uma regressão — não é cobertura de
@ instrução nova. Thumb2TortureTest roda este binário e afirma exit-code != 0 nos
@ backends JIT e interpretado; se algum dia passar a sair 0, o "teste do teste"
@ deixou de testar algo, e é sinal de bug no harness em si.
    .syntax unified
    .arch armv7-a
    .thumb
    .text
    .global _start

    .thumb_func
_start:
    movw    r0, #0x1234
    movt    r0, #0x5678          @ resultado real: r0 = 0x56781234
    ldr     r6, =0x00000000      @ esperado DELIBERADAMENTE errado
    cmp     r0, r6
    beq     ok
    mov     r0, #77              @ código de saída identifica esta checagem
    mov     r7, #1
    svc     #0
ok:
    mov     r0, #0
    mov     r7, #1
    svc     #0
