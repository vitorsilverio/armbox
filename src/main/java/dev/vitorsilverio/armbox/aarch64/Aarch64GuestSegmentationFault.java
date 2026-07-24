package dev.vitorsilverio.armbox.aarch64;

/// Acesso do guest AArch64 a memória não mapeada — equivalente 64-bit de
/// {@link dev.vitorsilverio.armbox.memory.GuestSegmentationFault} (endereço `long` em vez de
/// `int`; NÃO reaproveitada — a classe de 32 bits carrega `int` no construtor e na formatação,
/// misturar as duas obrigaria truncar endereços de 64 bits).
public final class Aarch64GuestSegmentationFault extends RuntimeException {
    private final long address;

    public Aarch64GuestSegmentationFault(long address, String access) {
        super("segmentation fault: %s at 0x%016X".formatted(access, address));
        this.address = address;
    }

    /// Endereço guest que causou a falha.
    public long address() {
        return address;
    }
}
