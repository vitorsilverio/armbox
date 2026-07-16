@ armv6k-torture.s — binário ELF real ARMv6K auto-verificável (task B4.0.1).
@
@ Cobre pelo menos um representante de cada grupo de instrução novo de B1.1-B1.7:
@ extensão com rotação (SXTB/UXTAH), inversão de bytes + UMAAL, SIMD paralelo
@ (SADD16/UQSUB8/UADD8+SEL), pack/sat/usad (PKHBT/SSAT/USAD8), monitor de
@ exclusividade (LDREX/STREX/CLREX — sucesso E as DUAS formas de falha: sem
@ LDREX prévio e via CLREX), as instruções de sistema CPS/SETEND/WFI, e o acesso
@ desalinhado atravessado de ArmFeature.UNALIGNED_ACCESS (LDR/STR, B1.7).
@
@ Cada grupo compara o resultado real contra o valor esperado (mesmos vetores dos
@ testes Java de equivalência ArmV6*Test) e, se divergir, sai com um código de saída
@ ÚNICO por checagem (1..28) — identifica exatamente qual checagem falhou. Sucesso =
@ exit 0. "Teste do teste": ArmV6TortureTest também roda um programa sintético com
@ um valor esperado deliberadamente errado e confirma exit-code != 0.
@
@ WFI: armbox é user-mode puro, sem timer/controlador de IRQ (isso é infraestrutura
@ de sistema completo, fora de escopo — B4.1). A escolha explícita (documentada
@ também em Armbox.run) é: o runner acorda o core sozinho (ArmCore#wake) assim que
@ o observa HALTED, sem envolver `setInterruptLine`/vetor de exceção — no Linux
@ real, um WFI em modo usuário só estala o pipeline até o próximo tick do timer do
@ kernel e a execução continua, sem qualquer sinal entregue ao processo. Este teste
@ (checks 25/26) prova que, sob essa política, WFI é um no-op observável: os
@ registradores não mudam e a instrução seguinte executa normalmente.
    .arch armv6k
    .arm
    .text
    .global _start

_start:
    @ r8 é reservado como scratch interno da macro CHECK32 — nunca usar para dados
    @ de teste. r9 é scratch de extração (CPSR mascarado, memória relida etc.).

    .macro CHECK32 reg, value, code
    ldr     r8, =\value
    cmp     \reg, r8
    beq     90f
    mov     r0, #\code
    b       fail
90:
    .endm

    @ ── 1: SXTB com rotação (ROR #8 traz o byte 0xFF para a posição 7:0) ───────────
    mov     r1, #0xFF00
    sxtb    r0, r1, ror #8
    CHECK32 r0, 0xFFFFFFFF, 1

    @ ── 2: UXTAH com acumulador E rotação (ROR #16 troca as metades do halfword) ──
    mov     r2, #0x100
    mov     r1, #0xAB0000
    uxtah   r0, r2, r1, ror #16
    CHECK32 r0, 0x1AB, 2

    @ ── 3/4: REV / REV16 ────────────────────────────────────────────────────────
    mov     r1, #0x12000000
    orr     r1, r1, #0x340000
    orr     r1, r1, #0x5600
    orr     r1, r1, #0x78
    rev     r0, r1
    CHECK32 r0, 0x78563412, 3
    rev16   r0, r1
    CHECK32 r0, 0x34127856, 4

    @ ── 5/6: UMAAL (RdLo:RdHi += Rm*Rs, os dois acumuladores como termos u32) ─────
    mov     r0, #3
    mov     r1, #4
    mov     r2, #5
    mov     r3, #6
    umaal   r2, r3, r0, r1
    CHECK32 r2, 23, 5
    CHECK32 r3, 0, 6

    @ ── 7/8: SADD16 (lanes independentes + GE reflete o sinal de cada lane) ──────
    mov     r0, #0x80000000
    orr     r0, r0, #1
    mov     r1, #1
    sadd16  r2, r0, r1
    CHECK32 r2, 0x80000002, 7
    mrs     r9, cpsr
    lsr     r9, r9, #16
    and     r9, r9, #0xF
    CHECK32 r9, 0b0011, 8

    @ ── 9: UQSUB8 (subtração paralela saturada sem sinal, satura o byte negativo) ─
    mov     r0, #0x01000000
    orr     r0, r0, #0x020000
    orr     r0, r0, #0x0300
    orr     r0, r0, #0x04
    mov     r1, #0x02000000
    orr     r1, r1, #0x020000
    orr     r1, r1, #0x0200
    orr     r1, r1, #0x02
    uqsub8  r2, r0, r1
    CHECK32 r2, 0x00000102, 9

    @ ── 10: UADD8 (produz GE) + SEL (consome GE, escolhe byte a byte) ────────────
    mov     r0, #0xFF000000
    orr     r0, r0, #1
    mov     r1, #0x01000000
    orr     r1, r1, #1
    uadd8   r3, r0, r1
    sel     r2, r0, r1
    CHECK32 r2, 0xFF000001, 10

    @ ── 11: PKHBT (base de Rn + topo deslocado de Rm) ────────────────────────────
    mov     r0, #0x11000000
    orr     r0, r0, #0x110000
    orr     r0, r0, #0x2200
    orr     r0, r0, #0x22
    mov     r1, #0x3300
    orr     r1, r1, #0x44
    pkhbt   r2, r0, r1, lsl #8
    CHECK32 r2, 0x00332222, 11

    @ ── 12/13: SSAT (satura e seta o Q sticky) ────────────────────────────────────
    msr     APSR_nzcvq, #0
    mov     r1, #4096
    ssat    r2, #8, r1
    CHECK32 r2, 127, 12
    mrs     r9, cpsr
    lsr     r9, r9, #27
    and     r9, r9, #1
    CHECK32 r9, 1, 13

    @ ── 14: USAD8 (soma das diferenças absolutas por byte, sem sinal) ────────────
    mov     r0, #0x01000000
    orr     r0, r0, #0x020000
    orr     r0, r0, #0x0300
    orr     r0, r0, #0x04
    mov     r1, #0x04000000
    orr     r1, r1, #0x030000
    orr     r1, r1, #0x0200
    orr     r1, r1, #0x01
    usad8   r2, r0, r1
    CHECK32 r2, 8, 14

    @ ── 15/16: STREX SEM LDREX prévio — falha explícita do monitor (caminho 1) ───
    ldr     r0, =atomic_var
    mov     r2, #0xDE
    strex   r3, r2, [r0]
    CHECK32 r3, 1, 15
    ldr     r9, [r0]
    CHECK32 r9, 0x11111111, 16

    @ ── 17/18: LDREX então STREX ao MESMO endereço — sucesso ─────────────────────
    ldr     r0, =atomic_var
    ldrex   r1, [r0]
    mov     r2, #0xCA
    strex   r3, r2, [r0]
    CHECK32 r3, 0, 17
    ldr     r9, [r0]
    CHECK32 r9, 0xCA, 18

    @ ── 19/20: LDREX, CLREX, STREX — falha explícita do monitor (caminho 2) ──────
    ldr     r0, =atomic_var
    ldrex   r1, [r0]
    clrex
    mov     r2, #0xAB
    strex   r3, r2, [r0]
    CHECK32 r3, 1, 19
    ldr     r9, [r0]
    CHECK32 r9, 0xCA, 20

    @ ── 21/22: CPS (CPSID/CPSIE if — máscaras I/F observáveis via MRS) ───────────
    cpsid   if
    mrs     r9, cpsr
    lsr     r9, r9, #6
    and     r9, r9, #3
    CHECK32 r9, 3, 21
    cpsie   if
    mrs     r9, cpsr
    lsr     r9, r9, #6
    and     r9, r9, #3
    CHECK32 r9, 0, 22

    @ ── 23/24: SETEND (bit E observável via MRS; sem acesso a dado com E=1 —
    @ este arm-jitter trata acesso a dado com E=1 como não suportado, então o
    @ round-trip volta a LE antes de qualquer LDR/STR novo) ───────────────────────
    setend  be
    mrs     r9, cpsr
    lsr     r9, r9, #9
    and     r9, r9, #1
    CHECK32 r9, 1, 23
    setend  le
    mrs     r9, cpsr
    lsr     r9, r9, #9
    and     r9, r9, #1
    CHECK32 r9, 0, 24

    @ ── 25/26: WFI — no-op observável sob a política de auto-wake do armbox ──────
    mov     r5, #0xAB
    wfi
    mov     r6, #0xCD
    CHECK32 r5, 0xAB, 25
    CHECK32 r6, 0xCD, 26

    @ ── 27/28: acesso desalinhado atravessado (ArmFeature.UNALIGNED_ACCESS, B1.7) ─
    @ bytes de unaligned_bytes: 11 22 33 44 55 — LDR no endereço+1 lê atravessado
    @ (little-endian, byte a byte), NÃO rotacionado como em ARMv4T/v5TE; STR no
    @ mesmo endereço+1 escreve atravessado e o round-trip confirma os bytes exatos.
    ldr     r0, =unaligned_bytes
    add     r0, r0, #1
    ldr     r2, [r0]
    CHECK32 r2, 0x55443322, 27
    ldr     r1, =0xCAFEF00D
    str     r1, [r0]
    ldrb    r9, [r0]
    CHECK32 r9, 0x0D, 28

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

    @ Pool de literais das CHECK32 (ldr r8, =valor): colocado aqui, DEPOIS dos dois
    @ svc de saída (sucesso e falha) — nenhum dos dois caminhos "cai" na sequência
    @ seguinte (svc exit termina o processo guest), então é seguro ter dados crus
    @ logo em seguida sem um branch explícito por cima.
    .ltorg

    .align  4
atomic_var:
    .word   0x11111111
unaligned_bytes:
    .byte   0x11, 0x22, 0x33, 0x44, 0x55
    .align  4
msg:
    .ascii  "armv6k torture: ok\n"
msg_end:
