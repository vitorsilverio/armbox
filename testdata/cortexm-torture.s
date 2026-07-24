@ cortexm-torture.s — torture test bare-metal ARMv7-M (Cortex-M3), B7.5.
@ Monta com arm-none-eabi-gcc -mcpu=cortex-m3 -mthumb -nostdlib -T flash.ld (ver build-testdata.ps1).
@ Sai via semihosting SYS_EXIT (bkpt 0xAB, r0=0x18, r1=código): 0 = tudo passou, cada bit do
@ código de saída identifica uma checagem que falhou (ver comentários abaixo de cada uma).
@
@ Convenção de registradores: r4 guarda STATUS_ADDR (ponteiro) do início ao fim — nunca é
@ scratch. r0-r3 são scratch livre em todo bloco (inclusive dentro dos handlers de exceção:
@ só R0-R3/R12/LR/PC/xPSR são empilhados/restaurados automaticamente pelo hardware, então um
@ handler nunca precisa preservar r4+ — mas por isso também NUNCA pode usar r4+ como scratch
@ sem preservar). r6/r7 guardam valores que precisam sobreviver a um handler de exceção
@ disparado no meio de um bloco (ex.: snapshot do contador de SysTick antes de um `PRIMASK`).
    .syntax unified
    .thumb

    .equ MSP_TOP,            0x20010000
    .equ PSP_TOP,            0x20008000
    .equ STATUS_ADDR,        0x20000000
    .equ SYSTICK_COUNT_ADDR, 0x20000004
    .equ PENDSV_FLAG_ADDR,   0x20000008
    .equ LDREX_TEST_ADDR,    0x2000000C

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
    .word _fault_handler + 1     @ MemManage
    .word _fault_handler + 1     @ BusFault
    .word _fault_handler + 1     @ UsageFault
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

    @ Marca STATUS_ADDR (em r4) com o bit `bit` — usado por toda checagem que falha.
    .macro fail bit
    ldr r0, [r4]
    orr r0, r0, #(1 << \bit)
    str r0, [r4]
    .endm

    .thumb_func
_fault_handler:
    movs r0, #SEMIHOST_SYS_EXIT
    movs r1, #0xFF
    bkpt 0xAB

    .thumb_func
_svc_handler:
    @ EXC_RETURN bit2: 0 = MSP, 1 = PSP (qual pilha recebeu o frame desta SVC).
    tst lr, #4
    ite eq
    mrseq r0, msp
    mrsne r0, psp
    ldr r1, [r0, #24]      @ ReturnAddress empilhado
    subs r1, r1, #2        @ endereço da própria instrução SVC (16 bits)
    ldrh r2, [r1]
    and r2, r2, #0xFF      @ imm8 do SVC
    add r2, r2, #0x70      @ marca de retorno reconhecível pelo chamador (SVC #imm -> imm+0x70)
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
    beq check7
primask_rw_fail:
    fail 10
check7:

    @ ── checagem 7 (v7-M): MOVW/MOVT, SDIV/UDIV, UBFX, LDREX/STREX ──
    movw r0, #0x1234
    movt r0, #0x5678
    ldr r1, =0x56781234
    cmp r0, r1
    beq div_check
    fail 11
div_check:
    movs r0, #100
    movs r1, #7
    sdiv r2, r0, r1
    cmp r2, #14
    beq udiv_check
    fail 12
udiv_check:
    movs r0, #100
    movs r1, #7
    udiv r2, r0, r1
    cmp r2, #14
    beq ubfx_check
    fail 13
ubfx_check:
    ldr r0, =0xABCDEF12
    ubfx r1, r0, #4, #8
    cmp r1, #0xF1
    beq ldrex_check
    fail 14
ldrex_check:
    ldr r0, =LDREX_TEST_ADDR
    movs r1, #0x55
    str r1, [r0]
    ldrex r2, [r0]
    cmp r2, #0x55
    beq strex_check
    fail 15
strex_check:
    ldr r0, =LDREX_TEST_ADDR
    ldrex r2, [r0]                 @ reabre o monitor de exclusividade antes do STREX
    movs r3, #0x66
    strex r1, r3, [r0]
    cmp r1, #0
    beq strex_value_check
    fail 16
strex_value_check:
    ldr r2, [r0]
    cmp r2, #0x66
    beq all_done
    fail 17

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
