package dev.vitorsilverio.armbox.memory;

/// Acesso do guest a memória não mapeada — o equivalente user-mode de um SIGSEGV.
public final class GuestSegmentationFault extends RuntimeException {
    private final int address;

    public GuestSegmentationFault(int address, String access) {
        super("segmentation fault: %s at 0x%08X".formatted(access, address));
        this.address = address;
    }

    /// Endereço guest que causou a falha.
    public int address() {
        return address;
    }
}
