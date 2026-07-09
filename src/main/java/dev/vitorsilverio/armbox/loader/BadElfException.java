package dev.vitorsilverio.armbox.loader;

/// ELF inválido ou fora do escopo suportado pelo loader.
public final class BadElfException extends RuntimeException {
    public BadElfException(String message) {
        super(message);
    }
}
