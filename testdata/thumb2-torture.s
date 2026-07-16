@ thumb2-torture.s — binário ELF real Thumb-2 32-bit auto-verificável (tasks B4.0.2 + B2.6).
@
@ B4.0.2 cobriu o subconjunto Thumb-2 até B2.1(infra)/B2.2(data-processing):
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
@ B2.6 (checagens 11-20) estende para o preset `ARMV6K_THUMB2` FECHADO — as 4
@ extensões plugadas juntas (`Thumb2DataProcessingDecoder`/B2.2 acima,
@ `Thumb2LoadStoreDecoder`/B2.3, `Thumb2BranchDecoder`/B2.4, `Thumb2MiscDecoder`/B2.5)
@ e o fix do decode único de 32 bits para `BL`/`BLX` (o "fantasma" do sufixo deixa
@ de existir):
@   - LDR.W/STR.W (T3/T4) com writeback pré/pós-indexado
@   - LDRD/STRD com par de registradores NÃO-adjacente (Rt=r2, Rt2=r9)
@   - LDM.W/PUSH.W/POP.W (STMDB/LDMIA wide, confirmado via objdump)
@   - DMB/DSB/ISB (sem efeito observável além de ciclo/fetch)
@   - MRS/MSR de 32 bits (ida e volta do CPSR)
@   - TBB (tabela de bytes, índice 2)
@   - BL real entre funções, com um `.word 0xF890F890` logo depois de um `b` que
@     pula por cima dele (bytes que colidiriam com o "fantasma" pré-B2.6 se o par
@     BL/BLX fosse relido como dois halfwords independentes — o endereço de
@     retorno real de um `bl`/`bx lr` não pode conter dados crus, daí o `b` skip)
@
@ IMPORTANTE (armadilha do enunciado): a escolha 16 vs 32 bits é do assembler, não
@ do mnemônico. `.w` força a forma de 32 bits nos casos ambíguos; nos casos em que
@ Thumb-1 simplesmente NÃO tem a instrução (ANDS/MVN/ADD-3-registrador-com-shift
@ com imediato fora do range de 3 bits, MOVW/MOVT, RRX, LDRD/STRD, TBB, DMB/DSB/ISB,
@ MRS/MSR de 32 bits) o assembler já escolhe Thumb-2 sozinho, mas o `.w` foi mantido
@ mesmo assim por clareza/documentação onde aplicável. CONFIRME com objdump -d antes
@ de confiar neste arquivo (ver testdata/README ou o comentário no
@ build-testdata.ps1).
@
@ Cada checagem compara o resultado real contra o esperado e, se divergir, sai com
@ um código de saída ÚNICO (1..20) — identifica exatamente qual falhou. Sucesso =
@ exit 0 com a mensagem "thumb2 torture: ok".
@
@ Só usa branches Thumb-1 de 16 bits (B/Bcc curtos) para o CONTROLE do torture test
@ em si (macros CHECK32/CHECKREG, `fail`) — B.W de 32 bits condicional/incondicional
@ já é testado em separado por `Thumb2BranchesItTest` no arm-jitter (equivalência
@ ASM×interpretado). O arquivo é pequeno o bastante para caber dentro do alcance de
@ ±2KB (B) e ±256B (Bcc) do Thumb-1 sem esforço.
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
    @ não está no preset ARMV6K_THUMB2, que só registra Thumb2DataProcessingDecoder):
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

    @ ══════════════════════════════════════════════════════════════════════════════
    @ B2.6 — extensão do torture test: as 4 extensões Thumb-2 juntas no preset real
    @ (Thumb2LoadStoreDecoder/B2.3, Thumb2BranchDecoder/B2.4, Thumb2MiscDecoder/B2.5,
    @ além do Thumb2DataProcessingDecoder já exercitado acima) e o fix do "fantasma"
    @ BL/BLX (decode único de 32 bits). Usa r8-r10 como scratch extra (não conflita
    @ com r5/r6/r7 reservados acima).
    @ ══════════════════════════════════════════════════════════════════════════════

    .align 4
scratch:
    .word   0, 0, 0, 0

    @ ── 11: STR.W (T4) com writeback pré-indexado, depois LDR.W (T3) confirma ─────
    adr     r1, scratch
    movs    r0, #0x42
    str.w   r0, [r1, #4]!    @ escreve em scratch+4 e r1 vira scratch+4 (writeback)
    ldr.w   r2, [r1]
    CHECK32 r2, 0x42, 11

    @ ── 12: LDR.W (T4) pós-indexado (offset negativo, sem writeback no T3) ────────
    ldr.w   r3, [r1], #-4    @ lê de scratch+4 (mesmo endereço de 11), r1 volta a scratch
    CHECKREG r3, r2, 12

    @ ── 13: LDRD/STRD — par NÃO-adjacente (Rt=r2, Rt2=r9) ─────────────────────────
    movs    r2, #0x11
    movs    r9, #0x22
    adr     r4, scratch
    strd    r2, r9, [r4]
    movs    r2, #0          @ limpa para provar que o load reconstitui
    movs    r9, #0
    ldrd    r2, r9, [r4]
    CHECK32 r2, 0x11, 13
    CHECK32 r9, 0x22, 14

    @ ── 15: LDM.W / PUSH.W / POP.W (alias STMDB SP! / LDMIA SP!) ──────────────────
    movs    r0, #7
    movs    r1, #8
    push.w  {r0, r1}
    movs    r0, #0
    movs    r1, #0
    pop.w   {r0, r1}
    CHECK32 r0, 7, 15
    CHECK32 r1, 8, 16

    @ ── 17: DMB/DSB/ISB — sem efeito observável além de consumir ciclo (G4) ───────
    movs    r0, #0x99
    dmb     sy
    dsb     sy
    isb     sy
    CHECK32 r0, 0x99, 17

    @ ── 18: MRS/MSR de 32 bits — ida e volta do CPSR sem trocar de modo ───────────
    mrs     r0, cpsr
    msr     cpsr_f, r0        @ só o campo de flags, não mexe em modo/interrupt mask
    mrs     r1, cpsr
    CHECKREG r0, r1, 18

    @ ── 19: TBB — tabela de bytes, desvia para o índice usado (índice 2 -> +5*2) ──
    adr     r0, tbb_table
    movs    r1, #2
    tbb     [r0, r1]
tbb_table:
    .byte   (tbb_miss - tbb_table) / 2
    .byte   (tbb_miss - tbb_table) / 2
    .byte   (tbb_hit  - tbb_table) / 2
    .byte   0                          @ padding: mantém tbb_miss alinhado a halfword (Thumb)
    .align  1
tbb_miss:
    mov     r0, #19
    b       fail
tbb_hit:

    @ ── 20: BL real entre funções — e o "fantasma": um `.word` logo depois de um
    @ `bl` cujos bytes colidiriam (bug pré-B2.6) com o espaço de
    @ Thumb2LoadStoreDecoder (top8=0xF8) se o par prefixo/sufixo fosse relido como
    @ dois halfwords independentes. O endereço IMEDIATAMENTE após o `bl` é o
    @ endereço de retorno real da chamada — não pode conter dados crus (a CPU
    @ tentaria executá-los ao retornar) — então o `.word` fica logo depois de um
    @ `b` que pula por cima dele; o retorno do `bl` cai nesse `b`, não no `.word`.
    @ Isso já basta para exercitar o caso: com o decode único de 32 bits (B2.6), o
    @ `bl` consome exatamente 4 bytes atomicamente e nunca tenta decodificar o
    @ segundo halfword isoladamente — o "fantasma" nunca existe, então é seguro ter
    @ bytes com essa forma logo depois, ligados ou não.
    movs    r4, #0
    bl      helper
    b       after_ghost_word
    .word   0xF890F890        @ "dados" que colidiriam com o fantasma pré-B2.6
after_ghost_word:
    CHECK32 r4, 0x55, 20
    b       after_helper_body  @ pula por cima do corpo de `helper`, que segue inline

    .thumb_func
helper:
    movs    r4, #0x55
    bx      lr

after_helper_body:

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
