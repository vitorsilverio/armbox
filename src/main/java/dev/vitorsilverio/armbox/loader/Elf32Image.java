package dev.vitorsilverio.armbox.loader;

/// Resultado do carregamento de um ELF32: o que a pilha inicial (auxv) e o runtime precisam saber.
public record Elf32Image(
        /// Entry point (`e_entry`); bit 0 ligado indica entrada em THUMB.
        int entry,
        /// Fim do maior segmento `PT_LOAD` arredondado para cima em página — início do heap (`brk`).
        int initialBrk,
        /// Endereço virtual dos program headers (`AT_PHDR`), ou 0 se fora de um `PT_LOAD`.
        int programHeaderAddress,
        /// Tamanho de um program header (`AT_PHENT`).
        int programHeaderEntrySize,
        /// Quantidade de program headers (`AT_PHNUM`).
        int programHeaderCount) {
}
