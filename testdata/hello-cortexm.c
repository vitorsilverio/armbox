/* hello-cortexm.c — sinal de compilador real bare-metal ARMv7-M (Cortex-M3), B7.5.
 * Compilar com `arm-none-eabi-gcc -mcpu=cortex-m3 -mthumb -nostdlib -T flash.ld`
 * (ver build-testdata.ps1). Mesmo padrão bare-metal do resto do testdata (sem CRT/libc):
 * a tabela de vetores é um array de ponteiros de função em `.isr_vector` — o compilador
 * já emite o bit Thumb correto em cada entrada automaticamente (ponteiro de função Thumb),
 * sem precisar de asm cru como nos .s. Saída via semihosting (`BKPT 0xAB`): SYS_WRITE0 +
 * SYS_EXIT, o mesmo par usado pelos torture tests.
 */

#define SEMIHOST_SYS_WRITE0 0x04
#define SEMIHOST_SYS_EXIT   0x18
#define INITIAL_MSP         0x20010000

static void semihost_write0(const char *message) {
    register int operation __asm__("r0") = SEMIHOST_SYS_WRITE0;
    register const char *argument __asm__("r1") = message;
    __asm__ volatile("bkpt 0xAB" : : "r"(operation), "r"(argument) : "memory");
}

static void semihost_exit(int code) __attribute__((noreturn));
static void semihost_exit(int code) {
    register int operation __asm__("r0") = SEMIHOST_SYS_EXIT;
    register int argument __asm__("r1") = code;
    __asm__ volatile("bkpt 0xAB" : : "r"(operation), "r"(argument) : "memory");
    for (;;) { }
}

void Reset_Handler(void);

/* Só o Reset é usado por este teste (nenhuma outra exceção é esperada) — os demais
 * slots ficam a zero, o que basta: nunca são vetorizados. */
__attribute__((section(".isr_vector"), used))
void (* const isr_vector[])(void) = {
    (void (*)(void)) INITIAL_MSP,
    Reset_Handler,
};

void Reset_Handler(void) {
    semihost_write0("hello cortex-m\n");
    semihost_exit(0);
}
