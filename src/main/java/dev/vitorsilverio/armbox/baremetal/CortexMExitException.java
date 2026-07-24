package dev.vitorsilverio.armbox.baremetal;

/// Lançada pelo handler de semihosting `SYS_EXIT` (B7.5) para interromper a execução
/// imediatamente — mesmo padrão de {@link dev.vitorsilverio.armbox.linux.GuestExitException},
/// mas para o modo `--machine=cortex-m` (sem relação com a ABI Linux).
public final class CortexMExitException extends RuntimeException {
    private final int exitCode;

    public CortexMExitException(int exitCode) {
        super("firmware exited with code " + exitCode, null, false, false);
        this.exitCode = exitCode;
    }

    /// Código de saída do firmware (`SYS_EXIT`, R1).
    public int exitCode() {
        return exitCode;
    }
}
