package dev.vitorsilverio.armbox.linux;

import dev.vitorsilverio.armbox.memory.GuestMemory;
import dev.vitorsilverio.armjitter.coprocessor.CoprocessorBus;

/// CP15 mínimo do armbox: atende só os registradores de ID de thread (`TPIDRURO`/`TPIDRURW`,
/// ARMv6K+), que binários musl/glibc compilados para ARMv7 leem via `MRC p15, 0, Rt, c13, c0, 3`
/// para achar o ponteiro de TLS — sem passar pelo kuser helper `get_tls`
/// ({@link KuserHelpers#GET_TLS_ADDRESS}) que os binários v5/v6 usam.
///
/// O valor de TLS mora num lugar só: a word em {@link KuserHelpers#TLS_VALUE_ADDRESS}, que a
/// syscall privada `set_tls` já escreve na memória guest. `TPIDRURO` lê essa mesma word — kuser
/// helper e CP15 sempre concordam, sem campo duplicado. `TPIDRURW` (leitura/escrita em modo
/// usuário, raramente usado por musl) é um campo próprio deste barramento.
///
/// Qualquer combinação de `crn`/`crm`/`opcode2` fora do bloco de ID de thread nunca chega a
/// {@link #read}/{@link #write} em uso normal (B4.0.4.1): este barramento sobrepõe o predicado
/// fino {@link CoprocessorBus#handles(int, int, int, int, int)} para reivindicar só
/// `c13,c0,{2,3}` — para qualquer outro registrador o predicado fino devolve `false` e o core
/// entrega a mesma exceção ARM Undefined que entregaria para um coprocessador ausente
/// (`handles(15)` grosso continua `true`, mas deixou de ser o predicado consultado). `read`/
/// `write` só lançam `IllegalStateException` se alcançados para um registrador não reivindicado
/// pelo fino — o que só pode acontecer por um bug do executor (que deveria ter consultado o
/// predicado fino antes de chamar), nunca em uso normal.
public final class ArmboxCp15 implements CoprocessorBus {
    private static final int CP15 = 15;

    /// `CRn` do bloco "Process, context and thread ID registers" (ARM ARM B4.1.116/117).
    private static final int CRN_THREAD_ID = 13;
    private static final int CRM_THREAD_ID = 0;
    /// `TPIDRURW` — thread ID read/write, acessível em modo usuário para leitura E escrita.
    private static final int OPCODE2_TPIDRURW = 2;
    /// `TPIDRURO` — thread ID read-only, acessível em modo usuário só para leitura.
    private static final int OPCODE2_TPIDRURO = 3;

    private final GuestMemory memory;
    private int tpidrurw;

    /// @param memory onde {@link KuserHelpers#TLS_VALUE_ADDRESS} guarda o valor de TLS
    public ArmboxCp15(GuestMemory memory) {
        this.memory = memory;
    }

    @Override
    public boolean handles(int coprocessor) {
        return coprocessor == CP15;
    }

    @Override
    public boolean handles(int coprocessor, int opcode1, int crn, int crm, int opcode2) {
        return coprocessor == CP15 && crn == CRN_THREAD_ID && crm == CRM_THREAD_ID
                && (opcode2 == OPCODE2_TPIDRURO || opcode2 == OPCODE2_TPIDRURW);
    }

    @Override
    public int read(int coprocessor, int opcode1, int crn, int crm, int opcode2) {
        if (crn == CRN_THREAD_ID && crm == CRM_THREAD_ID) {
            if (opcode2 == OPCODE2_TPIDRURO) {
                return memory.read32(KuserHelpers.TLS_VALUE_ADDRESS);
            }
            if (opcode2 == OPCODE2_TPIDRURW) {
                return tpidrurw;
            }
        }
        throw unsupported(crn, crm, opcode2);
    }

    @Override
    public void write(int coprocessor, int opcode1, int crn, int crm, int opcode2, int value) {
        if (crn == CRN_THREAD_ID && crm == CRM_THREAD_ID && opcode2 == OPCODE2_TPIDRURW) {
            tpidrurw = value;
            return;
        }
        throw unsupported(crn, crm, opcode2);
    }

    private static IllegalStateException unsupported(int crn, int crm, int opcode2) {
        return new IllegalStateException(
                "bug: executor não consultou handles fino: crn=c%d crm=c%d opcode2=%d"
                        .formatted(crn, crm, opcode2));
    }
}
