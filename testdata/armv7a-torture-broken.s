@ armv7a-torture-broken.s — "teste do teste" da B3.7: cópia mínima do padrão de
@ verificação do armv7a-torture.s com um valor esperado DELIBERADAMENTE errado.
@
@ Existe só para provar que o harness de verificação (CHECK: compara, sai com código
@ != 0 se divergir) realmente detectaria uma regressão — não é cobertura de instrução
@ nova. Armv7TortureTest roda este binário e afirma exit-code != 0 nos backends JIT e
@ interpretado; se algum dia passar a sair 0, o "teste do teste" deixou de testar algo,
@ e é sinal de bug no harness em si (mesmo padrão de armv6k-torture-broken.s/
@ thumb2-torture-broken.s).
    .syntax unified
    .arch armv7ve
    .fpu vfpv3-d16
    .arm
    .text
    .global _start

_start:
    ldr     r1, =0x80000001
    rbit    r0, r1              @ RBIT de 0x80000001 = 0x80000001 (resultado real)
    mov     r2, #0              @ esperado DELIBERADAMENTE errado (deveria ser 0x80000001)
    cmp     r0, r2
    beq     ok
    mov     r0, #77             @ código de saída identifica esta checagem
    mov     r7, #1
    svc     #0
ok:
    mov     r0, #0
    mov     r7, #1
    svc     #0
