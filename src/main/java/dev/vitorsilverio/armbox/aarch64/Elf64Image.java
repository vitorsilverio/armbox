package dev.vitorsilverio.armbox.aarch64;

/// Resultado do carregamento de um ELF64 AArch64 — irmão de
/// {@link dev.vitorsilverio.armbox.loader.Elf32Image} com endereços `long`.
public record Elf64Image(
        /// Entry point (`e_entry`). A64 não tem bit de modo Thumb no entry (não existe Thumb em
        /// A64) — ao contrário de {@code Elf32Image#entry}, este valor é usado direto.
        long entry,
        /// Fim do maior segmento `PT_LOAD` arredondado para cima em página — início do heap.
        long initialBrk) {
}
