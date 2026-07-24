/* Linker script mínimo para firmware bare-metal Cortex-M (B7.5): flash em 0x0 com a tabela de
   vetores na origem (KEEP obrigatório — sem ele o gc-sections come a tabela e o boot lê lixo,
   ver Armadilhas da task), RAM em 0x20000000 (não usada por este script: os testes endereçam
   RAM por constante imediata, sem .data/.bss). */
MEMORY
{
    FLASH (rx) : ORIGIN = 0x00000000, LENGTH = 1M
    RAM (rwx)  : ORIGIN = 0x20000000, LENGTH = 1M
}

SECTIONS
{
    .text : {
        KEEP(*(.isr_vector))
        *(.text*)
        *(.rodata*)
    } > FLASH
}
