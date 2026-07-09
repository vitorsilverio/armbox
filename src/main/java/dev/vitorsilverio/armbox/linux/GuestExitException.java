package dev.vitorsilverio.armbox.linux;

/// Lançada pelo handler de `exit`/`exit_group` para interromper a execução imediatamente
/// (o que vem depois do `svc` de exit pode ser dado, não código — não podemos continuar
/// até o fim do slice). Capturada pelo loop principal do runner.
public final class GuestExitException extends RuntimeException {
    private final int exitCode;

    public GuestExitException(int exitCode) {
        super("guest exited with code " + exitCode, null, false, false);
        this.exitCode = exitCode;
    }

    /// Código de saída do processo guest (0–255).
    public int exitCode() {
        return exitCode;
    }
}
