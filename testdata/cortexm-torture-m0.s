@ cortexm-torture-m0.s — subconjunto ARMv6-M (Cortex-M0) do cortexm-torture.s, B7.5: sem
@ MOVW/MOVT, SDIV/UDIV, UBFX, LDREX/STREX (nenhum existe em ARMv6-M) e sem blocos IT (Thumb-2
@ largo, também ausente em v6-M — o dispatch MSP/PSP do SVC usa branch em vez de `ite`/`mrseq`).
@ Monta com arm-none-eabi-gcc -mcpu=cortex-m0 -mthumb -nostdlib -T flash.ld, roda com --arch=armv6m.
    .syntax unified
    .thumb

    .equ MSP_TOP,            0x20010000
    .equ PSP_TOP,            0x20008000
    .equ STATUS_ADDR,        0x20000000
    .equ SYSTICK_COUNT_ADDR, 0x20000004
    .equ PENDSV_FLAG_ADDR,   0x20000008

    .equ SYST_CSR,  0xE000E010
    .equ SYST_RVR,  0xE000E014
    .equ SYST_CVR,  0xE000E018
    .equ ICSR_ADDR, 0xE000ED04
    .equ ICSR_PENDSVSET, (1 << 28)
    .equ SYST_CSR_ENABLE_TICKINT_CLKSRC, 0x7

    .equ SEMIHOST_SYS_EXIT, 0x18

    .section .isr_vector, "a"
    .word MSP_TOP
    .word _reset_handler + 1
    .word _fault_handler + 1     @ NMI
    .word _fault_handler + 1     @ HardFault
    .word 0                      @ MemManage (não existe em v6-M)
    .word 0                      @ BusFault (não existe em v6-M)
    .word 0                      @ UsageFault (não existe em v6-M)
    .word 0
    .word 0
    .word 0
    .word 0
    .word _svc_handler + 1       @ SVCall (11)
    .word _fault_handler + 1     @ DebugMonitor
    .word 0
    .word _pendsv_handler + 1    @ PendSV (14)
    .word _systick_handler + 1   @ SysTick (15)

    .text

    @ Marca STATUS_ADDR (em r4) com o bit `bit` — usado por toda checagem que falha. ARMv6-M
    @ (Thumb-1) não tem `ORR`/`TST` com imediato (só forma registrador-registrador) nem imediato
    @ de 32 bits além de 8 bits em MOVS — monta a máscara via MOVS+LSLS.
    .macro fail bit
    ldr r0, [r4]
    movs r1, #1
    lsls r1, r1, #\bit
    orrs r0, r0, r1
    str r0, [r4]
    .endm

    .thumb_func
_fault_handler:
    movs r0, #SEMIHOST_SYS_EXIT
    movs r1, #0xFF
    bkpt 0xAB

    .thumb_func
_svc_handler:
    @ EXC_RETURN bit2: 0 = MSP, 1 = PSP. Sem `ite`/`mrseq`/`mrsne` (Thumb-2, ausente em v6-M) —
    @ mesmo dispatch de cortexm-torture.s, mas com branch simples. `TST` de Thumb-1 só aceita
    @ registrador-registrador (sem imediato) e só r0-r7 — `lr` (r14) precisa ir para um low
    @ register antes.
    mov r0, lr
    movs r1, #4
    tst r0, r1
    beq svc_use_msp
    mrs r0, psp
    b svc_have_sp
svc_use_msp:
    mrs r0, msp
svc_have_sp:
    ldr r1, [r0, #24]      @ ReturnAddress empilhado
    subs r1, r1, #2        @ endereço da própria instrução SVC (16 bits)
    ldrh r2, [r1]
    movs r3, #0xFF
    ands r2, r2, r3        @ imm8 do SVC
    movs r3, #0x70
    adds r2, r2, r3        @ marca de retorno (SVC #imm -> imm+0x70)
    str r2, [r0]           @ sobrescreve R0 empilhado
    bx lr

    .thumb_func
_systick_handler:
    ldr r0, =SYSTICK_COUNT_ADDR
    ldr r1, [r0]
    adds r1, r1, #1
    str r1, [r0]
    cmp r1, #1
    bne 1f
    @ na PRIMEIRA vez, pende PendSV de dentro do handler (prioridades: mesma prioridade não
    @ preempta, só entra depois que este handler retornar).
    ldr r2, =ICSR_ADDR
    ldr r3, =ICSR_PENDSVSET
    str r3, [r2]
1:
    bx lr

    .thumb_func
_pendsv_handler:
    ldr r0, =PENDSV_FLAG_ADDR
    movs r1, #1
    str r1, [r0]
    bx lr

    .global _reset_handler
    .thumb_func
_reset_handler:
    ldr r4, =STATUS_ADDR
    movs r0, #0
    str r0, [r4]                  @ status = 0

    @ ── checagem 1: reset entra com MSP correto ──
    mrs r0, msp
    ldr r1, =MSP_TOP
    cmp r0, r1
    beq check2
    fail 0
check2:

    @ ── checagem 2: SVC #7 (MSP) ──
    movs r0, #0
    svc #7
    cmp r0, #0x77
    beq check3
    fail 1
check3:

    @ ── checagem 3: troca para PSP via CONTROL, SVC de novo (frame na PSP) ──
    ldr r0, =PSP_TOP
    msr psp, r0
    movs r1, #2                   @ CONTROL.SPSEL
    msr control, r1
    isb
    movs r0, #0
    svc #9
    cmp r0, #0x79
    beq check3_restore
    fail 2
check3_restore:
    movs r1, #0
    msr control, r1                @ volta para MSP para o resto do teste
    isb

    @ ── checagem 4: SysTick (RVR curto, TICKINT) incrementa contador >= 3 vezes ──
    ldr r0, =SYST_RVR
    movs r1, #100
    str r1, [r0]
    ldr r0, =SYST_CVR
    movs r1, #0
    str r1, [r0]
    ldr r0, =SYST_CSR
    ldr r1, =SYST_CSR_ENABLE_TICKINT_CLKSRC
    str r1, [r0]
    movs r6, #0                    @ guarda de iteração (evita travar para sempre se quebrado)
wait_systick:
    ldr r0, =SYSTICK_COUNT_ADDR
    ldr r1, [r0]
    cmp r1, #3
    bge systick_done
    adds r6, r6, #1
    ldr r2, =2000000
    cmp r6, r2
    bge systick_timeout
    b wait_systick
systick_timeout:
    fail 3
systick_done:
    @ desliga o SysTick para não interferir no resto do teste
    ldr r0, =SYST_CSR
    movs r1, #0
    str r1, [r0]

    @ ── checagem 4b: PendSV pendido de dentro do SysTick entrou depois ──
    ldr r0, =PENDSV_FLAG_ADDR
    ldr r1, [r0]
    cmp r1, #1
    beq check5
    fail 4
check5:

    @ ── checagem 5: PRIMASK segura SysTick; liberar entrega a pendida ──
    ldr r0, =SYSTICK_COUNT_ADDR
    ldr r6, [r0]                    @ r6 = snapshot do contador antes de mascarar
    cpsid i
    ldr r0, =SYST_RVR
    movs r1, #50
    str r1, [r0]
    ldr r0, =SYST_CVR
    movs r1, #0
    str r1, [r0]
    ldr r0, =SYST_CSR
    ldr r1, =SYST_CSR_ENABLE_TICKINT_CLKSRC
    str r1, [r0]
    movs r1, #0
spin_masked:
    adds r1, r1, #1
    ldr r2, =5000
    cmp r1, r2
    blt spin_masked
    ldr r0, =SYSTICK_COUNT_ADDR
    ldr r0, [r0]
    cmp r0, r6
    beq primask_hold_ok
    fail 5
primask_hold_ok:
    cpsie i                        @ libera: a SysTick pendente deve entrar em seguida
    movs r1, #0
spin_wait_release:
    ldr r0, =SYSTICK_COUNT_ADDR
    ldr r0, [r0]
    cmp r0, r6
    bne release_ok
    adds r1, r1, #1
    ldr r2, =2000000
    cmp r1, r2
    bge release_timeout
    b spin_wait_release
release_timeout:
    fail 6
release_ok:
    ldr r0, =SYST_CSR
    movs r1, #0
    str r1, [r0]

    @ ── checagem 6: MRS/MSR de MSP/PSP/CONTROL/PRIMASK ida-e-volta ──
    mrs r0, msp
    msr msp, r0
    mrs r1, msp
    cmp r1, r0
    beq mrs_psp
    fail 7
mrs_psp:
    ldr r0, =PSP_TOP
    msr psp, r0
    mrs r1, psp
    cmp r1, r0
    beq mrs_control
    fail 8
mrs_control:
    mrs r0, control
    msr control, r0
    mrs r1, control
    cmp r1, r0
    beq mrs_primask
    fail 9
mrs_primask:
    movs r0, #1
    msr primask, r0
    mrs r1, primask
    cmp r1, #1
    bne primask_rw_fail
    movs r0, #0
    msr primask, r0
    mrs r1, primask
    cmp r1, #0
    beq all_done
primask_rw_fail:
    fail 10

all_done:
    ldr r0, =STATUS_ADDR
    ldr r1, [r0]
    cmp r1, #0
    beq exit_pass
    movs r1, #1            @ código de saída é booleano (0/1): o status detalhado (bitmask)
                            @ excederia 255 e truncaria mod 256 no exit code do processo real.
exit_pass:
    movs r0, #SEMIHOST_SYS_EXIT
    bkpt 0xAB
hang:
    b hang
