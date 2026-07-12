@ thumb2-torture.s — binário ELF real Thumb-2 32-bit auto-verificável (task B4.0.2).
@
@ Cobre o subconjunto Thumb-2 implementado até B2.1 (infra)/B2.2 (data-processing),
@ que é exatamente o que o preset `ArmArchitecture.ARMV6K_THUMB2_PARTIAL` habilita
@ (ver arm-jitter `ArmArchitecture` — B2.3/B2.4/B2.5 NÃO estão nesta lista ainda,
@ mesmo já ✅ no índice de tasks, porque o Objetivo desta task escopa só B2.1-B2.2):
@   - modified immediate com carry-out (ANDS com imediato rotacionado; MVN via
@     alias ORN+Rn=PC também exercitado de graça)
@   - carry aritmético normal (ADDS com imediato, sem relação com o carry-out do
@     shifter — incluído porque o enunciado da task pede "ANDS/ADDS")
@   - MOVW+MOVT compondo uma constante de 32 bits
@   - ADD Rd,SP,#imm (Rn=SP genérico, sem alias dedicado no decoder) e ADR nas duas
@     direções (Rn=PC: soma vira MOV direto, subtração vira o mesmo caminho ADD/SUB
@     plain-binary com sinal invertido — cobre PLAIN_OP_ADD e PLAIN_OP_SUB)
@   - forma registrador com shift imediato (ADD com LSL) e RRX (MOV via alias
@     ORR+Rn=PC com shift RRX, que só existe em Thumb-2 — sem equivalente Thumb-1)
@
@ IMPORTANTE (armadilha do enunciado): a escolha 16 vs 32 bits é do assembler, não
@ do mnemônico. `.w` força a forma de 32 bits nos casos ambíguos; nos casos em que
@ Thumb-1 simplesmente NÃO tem a instrução (ANDS/MVN/ADD-3-registrador-com-shift
@ com imediato fora do range de 3 bits, MOVW/MOVT, RRX) o assembler já escolhe
@ Thumb-2 sozinho, mas o `.w` foi mantido mesmo assim por clareza/documentação.
@ CONFIRME com objdump -d antes de confiar neste arquivo (ver testdata/README ou o
@ comentário no build-testdata.ps1) — thumb2-torture-encoding-check.txt documenta a
@ verificação feita nesta task.
@
@ Cada checagem compara o resultado real contra o esperado e, se divergir, sai com
@ um código de saída ÚNICO (1..10) — identifica exatamente qual falhou. Sucesso =
@ exit 0 com a mensagem "thumb2 torture: ok".
@
@ Só usa branches Thumb-1 de 16 bits (B/Bcc curtos) — B.W/BL.W de 32 bits são grupo
@ de B2.4 (branches + IT), que este preset ainda NÃO decodifica; um branch de 32
@ bits aqui viraria UNDEFINED. O arquivo é pequeno o bastante para caber dentro do
@ alcance de ±2KB (B) e ±256B (Bcc) do Thumb-1 sem esforço.
    .syntax unified
    .arch armv7-a
    .thumb
    .text
    .global _start

    @ Dado ANTES de _start (referência backward para o teste 8 de ADR/SUB) — não é
    @ executado: a entrada real do processo é o endereço de _start (via e_entry),
    @ então dados crus aqui não "vazam" para o fluxo de execução.
    .align 4
backmarker:
    .word   0xB16B00B5

    .thumb_func
_start:
    @ r6 é o scratch interno das macros CHECK32/CHECKREG — nunca usar para dados de
    @ teste. r5 é scratch de extração (CPSR mascarado etc.). r7 é o número de
    @ syscall (convenção EABI) — só tocado nos dois pontos de saída (svc).

    .macro CHECK32 reg, value, code
    ldr     r6, =\value
    cmp     \reg, r6
    beq     90f
    mov     r0, #\code
    b       fail
90:
    .endm

    .macro CHECKREG ra, rb, code
    cmp     \ra, \rb
    beq     91f
    mov     r0, #\code
    b       fail
91:
    .endm

    @ ── 1/2: ANDS com imediato rotacionado — ThumbExpandImm_C, carry-out=1 ────────
    @ MVN.W r1,#0 usa o alias ORN+Rn=PC (decodeModifiedImmediate) de graça.
    @ Extração do carry SEM MRS (fora de escopo desta task — MRS é B2.5/Thumb2MiscDecoder,
    @ não está no preset ARMV6K_THUMB2_PARTIAL, que só registra Thumb2DataProcessingDecoder):
    @ ADC (modified-immediate, S=0, mesma tabela op4 do decoder) lê o carry sem tocar flags —
    @ `adc r5, r6, #0` com r6=0 dá r5=C diretamente.
    mvn.w   r1, #0
    ands.w  r0, r1, #0x80000000
    CHECK32 r0, 0x80000000, 1
    mov.w   r6, #0
    adc.w   r5, r6, #0
    CHECK32 r5, 1, 2

    @ ── 3/4: ADDS com imediato — carry aritmético normal (overflow sem sinal) ─────
    adds.w  r0, r1, #1
    CHECK32 r0, 0, 3
    mov.w   r6, #0
    adc.w   r5, r6, #0
    CHECK32 r5, 1, 4

    @ ── 5: MOVW + MOVT compondo uma constante de 32 bits ──────────────────────────
    movw    r0, #0x1234
    movt    r0, #0x5678
    CHECK32 r0, 0x56781234, 5

    @ ── 6: ADD Rd,SP,#imm — Rn=SP genérico (sem alias dedicado no decoder) ────────
    mov     r2, sp
    add.w   r0, sp, #5
    add     r3, r2, #5
    CHECKREG r0, r3, 6

    @ ── 7: ADR (Rn=PC, offset positivo) — vira MOV direto no decode ───────────────
    adr.w   r1, adrtarget
    ldr     r0, [r1]
    CHECK32 r0, 0xCAFEBABE, 7

    @ ── 8: ADR (Rn=PC, offset negativo) — mesmo caminho, sinal invertido (SUB) ────
    adr.w   r1, backmarker
    ldr     r0, [r1]
    CHECK32 r0, 0xB16B00B5, 8

    @ ── 9: forma registrador com shift imediato (LSL) ─────────────────────────────
    movs    r1, #1
    movs    r2, #1
    add.w   r0, r1, r2, lsl #3
    CHECK32 r0, 9, 9

    @ ── 10: forma registrador com RRX (só existe em Thumb-2, sem equivalente T1) ──
    movs    r2, #1
    lsrs    r2, r2, #1      @ desloca 1 bit para 0, carry-out = bit0 original = 1
    movs    r1, #2
    mov.w   r0, r1, rrx     @ (2>>>1) | (carry<<31) = 1 | 0x80000000
    CHECK32 r0, 0x80000001, 10

    @ Tudo passou.
    mov     r0, #1
    adr     r1, msg
    mov     r2, #(msg_end - msg)
    mov     r7, #4
    svc     #0
    mov     r0, #0
    mov     r7, #1
    svc     #0

fail:
    mov     r7, #1
    svc     #0

    @ Pool de literais das CHECK32 (ldr r6,=valor): depois dos dois svc de saída —
    @ nenhum dos dois caminhos cai na sequência seguinte (svc exit termina o
    @ processo guest), então é seguro ter dados crus logo em seguida.
    .ltorg

    .align  4
adrtarget:
    .word   0xCAFEBABE
msg:
    .ascii  "thumb2 torture: ok\n"
msg_end:
