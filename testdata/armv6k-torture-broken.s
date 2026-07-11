@ armv6k-torture-broken.s — "teste do teste" da B4.0.1: uma cópia mínima do padrão de
@ verificação do armv6k-torture.s com um valor esperado DELIBERADAMENTE errado.
@
@ Existe só para provar que o harness de verificação (CHECK: compara, sai com
@ código != 0 se divergir) realmente detectaria uma regressão — não é cobertura de
@ instrução nova. ArmV6TortureTest roda este binário e afirma exit-code != 0 nos
@ backends JIT e interpretado; se algum dia passar a sair 0, o "teste do teste"
@ deixou de testar algo, e é sinal de bug no harness em si.
    .arch armv6k
    .arm
    .text
    .global _start

_start:
    mov     r1, #0xFF
    rev     r0, r1              @ REV de 0x000000FF = 0xFF000000 (resultado real)
    mov     r2, #0              @ esperado DELIBERADAMENTE errado (deveria ser 0xFF000000)
    cmp     r0, r2
    beq     ok
    mov     r0, #77             @ código de saída identifica esta checagem
    mov     r7, #1
    svc     #0
ok:
    mov     r0, #0
    mov     r7, #1
    svc     #0
