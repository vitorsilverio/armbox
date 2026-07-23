@ armv7a-torture.s — binário ELF real ARMv7-A auto-verificável (task B3.7, fecha o épico B3).
@
@ Cobre o subconjunto "inteiro v7" das tasks B3.1/B3.2 (checagens 1-14: MOVW/MOVT,
@ UBFX/SBFX/BFI/BFC, RBIT, MLS, SDIV/UDIV incl. x/0 e MIN_VALUE/-1, DMB) mais a seção VFP
@ das tasks B3.3-B3.6 (checagens 15-28: VMOV imediato single/double, transferência
@ core<->S e core-par<->D, VADD/VMUL/VDIV/VSQRT, VCMP+VMRS APSR_nzcv, VCVT int<->float,
@ VLDR/VSTR, VPUSH/VPOP). O preset exercitado é `ARMV7A` — o preset "fechado" do épico B3
@ inteiro (ver `ArmArchitecture.ARMV7A`), não features isoladas.
@
@ Vetores concretos reaproveitados de propósito (mesmo padrão de honestidade de B4.0.1/
@ B4.0.2 — "use os mesmos vetores dos testes Java de equivalência"):
@   - UBFX/SBFX de 0x80 lsb0/width8 -> 0x80 (sem sinal) / 0xFFFFFFFF80 (com sinal,
@     bit7 do campo extraído = sinal) — mesmos vetores de ArmV7MediaDecoderTest (B3.1)/
@     Thumb2DataProcessingDecoderTest (B3.2).
@   - BFC limpa [11:4] de 0xFFFFFFFF -> 0xFFFFF00F; BFI insere 0xAB em [11:4] sem tocar
@     o resto -> 0xFFFFFABF — mesmos vetores de B3.1/B3.2.
@   - RBIT de 0x80000001 -> 0x80000001 (palíndromo) — mesmo vetor de B3.1/B3.2.
@   - MLS: Rd = Ra - Rn*Rm com Rn=3,Rm=4,Ra=10 -> 10-12=-2 — mesmo vetor "10-3*4=-2" de
@     B3.1/B3.2 (a ordem do enunciado é Ra-Rn*Rm, não Rn*Rm-Ra).
@   - SDIV: -7/2=-3 truncado; MIN_VALUE/-1=MIN_VALUE sem overflow trap; UDIV
@     0xFFFFFFFF/2=0x7FFFFFFF — mesmos vetores de B3.1/B3.2.
@   - VSQRT: sqrtf(2.0)=0x3FB504F3 — MESMO vetor exato de IrVfpExecutorTest (B3.4),
@     `assertEquals(0x3FB504F3, core.vfp().s(1))`.
@
@ Cada checagem compara o resultado real contra o valor esperado pré-calculado e, se
@ divergir, sai com um código de saída ÚNICO (1..28) — identifica exatamente qual falhou.
@ Sucesso = exit 0 com a mensagem "armv7a torture: ok". "Teste do teste":
@ Armv7TortureTest também roda armv7a-torture-broken.s (valor esperado deliberadamente
@ errado) e confirma exit-code != 0 (código 77, mesma convenção de armv6k-torture-broken.s).
    .syntax unified
    .arch armv7ve
    .fpu vfpv3-d16
    .arm
    .text
    .global _start

_start:
    @ r8 é reservado como scratch interno da macro CHECK32 — nunca usar para dados de
    @ teste. r9 é scratch de extração (CPSR mascarado, transferências VFP core<->S/D etc.).

    .macro CHECK32 reg, value, code
    ldr     r8, =\value
    cmp     \reg, r8
    beq     90f
    mov     r0, #\code
    b       fail
90:
    .endm

    @ ══════════════════════════════════════════════════════════════════════════════
    @ Parte 1 — inteiro v7 (B3.1/B3.2): MOVW/MOVT, bitfield, RBIT, MLS, SDIV/UDIV, DMB.
    @ ══════════════════════════════════════════════════════════════════════════════

    @ ── 1: MOVW + MOVT compondo uma constante de 32 bits ──────────────────────────
    movw    r0, #0x1234
    movt    r0, #0x5678
    CHECK32 r0, 0x56781234, 1

    @ ── 2/3: UBFX / SBFX do mesmo campo (lsb=0, width=8) de 0x80 — sem sinal vs
    @ com sinal (bit7 do campo extraído é o bit de sinal) ─────────────────────────
    mov     r1, #0x80
    ubfx    r0, r1, #0, #8
    CHECK32 r0, 0x80, 2
    sbfx    r0, r1, #0, #8
    CHECK32 r0, 0xFFFFFF80, 3

    @ ── 4: BFC limpa os bits [11:4] de 0xFFFFFFFF ─────────────────────────────────
    mvn     r0, #0
    bfc     r0, #4, #8
    CHECK32 r0, 0xFFFFF00F, 4

    @ ── 5: BFI insere 0xAB em [11:4] de 0xFFFFFFFF sem tocar o resto ──────────────
    mvn     r0, #0
    mov     r1, #0xAB
    bfi     r0, r1, #4, #8
    CHECK32 r0, 0xFFFFFABF, 5

    @ ── 6: RBIT — palíndromo de bits ───────────────────────────────────────────────
    ldr     r1, =0x80000001
    rbit    r0, r1
    CHECK32 r0, 0x80000001, 6

    @ ── 7: MLS — Rd = Ra - Rn*Rm, 10 - 3*4 = -2 ───────────────────────────────────
    mov     r1, #3
    mov     r2, #4
    mov     r3, #10
    mls     r0, r1, r2, r3
    CHECK32 r0, 0xFFFFFFFE, 7

    @ ── 8: SDIV normal — -7/2 = -3 truncado para zero ─────────────────────────────
    mov     r1, #7
    rsb     r1, r1, #0
    mov     r2, #2
    sdiv    r0, r1, r2
    CHECK32 r0, 0xFFFFFFFD, 8

    @ ── 9: SDIV por zero -> 0, sem exceção ─────────────────────────────────────────
    mov     r1, #5
    mov     r2, #0
    sdiv    r0, r1, r2
    CHECK32 r0, 0, 9

    @ ── 10: SDIV MIN_VALUE/-1 -> MIN_VALUE, sem overflow trap ─────────────────────
    mov     r1, #0x80000000
    mvn     r2, #0
    sdiv    r0, r1, r2
    CHECK32 r0, 0x80000000, 10

    @ ── 11: UDIV normal — 0xFFFFFFFF/2 = 0x7FFFFFFF ───────────────────────────────
    mvn     r1, #0
    mov     r2, #2
    udiv    r0, r1, r2
    CHECK32 r0, 0x7FFFFFFF, 11

    @ ── 12: UDIV por zero -> 0, sem exceção ────────────────────────────────────────
    mov     r1, #5
    mov     r2, #0
    udiv    r0, r1, r2
    CHECK32 r0, 0, 12

    @ ── 13/14: DMB — sem efeito observável além de consumir ciclo/fetch (G4) ──────
    mov     r5, #0xAB
    dmb     sy
    mov     r6, #0xCD
    CHECK32 r5, 0xAB, 13
    CHECK32 r6, 0xCD, 14

    @ ══════════════════════════════════════════════════════════════════════════════
    @ Parte 2 — VFP (B3.3-B3.6): VMOV imediato/transferência, ALU, VCMP+VMRS, VCVT,
    @ VLDR/VSTR, VPUSH/VPOP. Usa d0(=s0/s1) só para o teste de par core<->D; s8-s20
    @ para o resto (sem sobreposição com d0/d1).
    @ ══════════════════════════════════════════════════════════════════════════════

    @ ── 15: VMOV imediato single (s8 = 1.0) + transferência core<->S ─────────────
    vmov.f32 s8, #1.0
    vmov    r0, s8
    CHECK32 r0, 0x3F800000, 15

    @ ── 16/17: VMOV imediato double (d0 = 2.0) + transferência core-par<->D ──────
    vmov.f64 d0, #2.0
    vmov    r0, r1, d0
    CHECK32 r0, 0, 16
    CHECK32 r1, 0x40000000, 17

    @ ── 18: VADD.F32 — 1.0 + 2.0 = 3.0 ─────────────────────────────────────────────
    vmov.f32 s9, #2.0
    vadd.f32 s10, s8, s9
    vmov    r0, s10
    CHECK32 r0, 0x40400000, 18

    @ ── 19: VMUL.F32 — 1.0 * 2.0 = 2.0 ─────────────────────────────────────────────
    vmul.f32 s11, s8, s9
    vmov    r0, s11
    CHECK32 r0, 0x40000000, 19

    @ ── 20: VDIV.F32 — 2.0 / 2.0 = 1.0 ─────────────────────────────────────────────
    vdiv.f32 s12, s11, s9
    vmov    r0, s12
    CHECK32 r0, 0x3F800000, 20

    @ ── 21: VSQRT.F32 — sqrt(2.0) = 0x3FB504F3 (mesmo vetor de IrVfpExecutorTest) ──
    vsqrt.f32 s13, s9
    vmov    r0, s13
    CHECK32 r0, 0x3FB504F3, 21

    @ ── 22: VCMP + VMRS APSR_nzcv — 2.0 > 1.0 -> N=0,Z=0,C=1,V=0 (nibble 0b0010) ───
    vcmp.f32 s9, s8
    vmrs    APSR_nzcv, fpscr
    mrs     r9, cpsr
    lsr     r9, r9, #28
    and     r9, r9, #0xF
    CHECK32 r9, 0b0010, 22

    @ ── 23/24: VCVT float<->int, ida e volta (3.0 <-> 3) ──────────────────────────
    vcvt.s32.f32 s14, s10
    vmov    r0, s14
    CHECK32 r0, 3, 23
    vcvt.f32.s32 s15, s14
    vmov    r0, s15
    CHECK32 r0, 0x40400000, 24

    @ ── 25/26: VLDR de memória + VSTR round-trip (bits de pi, 0x40490FDB) ─────────
    adr     r1, pi_bits
    vldr    s16, [r1]
    vmov    r0, s16
    CHECK32 r0, 0x40490FDB, 25
    adr     r1, scratch_word
    vstr    s16, [r1]
    ldr     r0, [r1]
    CHECK32 r0, 0x40490FDB, 26

    @ ── 27: VPUSH/VPOP — round-trip de s8 (1.0) através da pilha VFP ──────────────
    vpush   {s8-s13}
    vmov.f32 s8, #4.0
    vpop    {s8-s13}
    vmov    r0, s8
    CHECK32 r0, 0x3F800000, 27

    @ ── 28: transferência core->S->core (round-trip dos bits de pi) ──────────────
    ldr     r2, =0x40490FDB
    vmov    s20, r2
    vmov    r0, s20
    CHECK32 r0, 0x40490FDB, 28

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

    @ Pool de literais das CHECK32 (ldr r8, =valor): depois dos dois svc de saída —
    @ nenhum dos dois caminhos cai na sequência seguinte (svc exit termina o processo
    @ guest), então é seguro ter dados crus logo em seguida sem um branch por cima.
    .ltorg

    .align  4
scratch_word:
    .word   0
pi_bits:
    .word   0x40490FDB
msg:
    .ascii  "armv7a torture: ok\n"
msg_end:
