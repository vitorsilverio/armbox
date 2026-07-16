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
/// Qualquer combinação de `crn`/`crm`/`opcode2` fora do bloco de ID de thread — ou uma escrita em
/// `TPIDRURO`, somente leitura em modo usuário no hardware real — lança
/// `IllegalStateException`, o mesmo tipo/estilo que {@link CoprocessorBus#none()} já usa para
/// acesso fora de contrato: `handles(15)` retornar `true` não pode "engolir" acessos que este
/// barramento não implementa. **Limitação documentada**: diferente de `handles(15)==false` (que o
/// core intercepta ANTES de chamar {@link #read}/{@link #write}, entregando uma exceção ARM
/// Undefined limpa ao guest via `ArmCore#requestException`), uma vez que `handles` devolve
/// `true` o contrato de {@link CoprocessorBus} não dá a esta classe acesso ao `ArmCore` nem a
/// chance de sinalizar "PC mudou" para o executor — `IrSystemExecutor#executeCoprocessor` sempre
/// devolve `false` (bloco continua) depois de chamar `read`/`write` quando `handles` é `true`.
/// Por isso um registrador de CP15 não suportado aqui não pode replicar byte a byte a entrega de
/// exceção Undefined ao código guest (redirecionar o PC para o vetor e continuar rodando o
/// guest) — o melhor que este barramento pode fazer dentro do contrato é falhar alto (exceção
/// Java não capturada), nunca devolver um valor inventado silenciosamente.
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
                "CP15 register not implemented by armbox: crn=c%d crm=c%d opcode2=%d"
                        .formatted(crn, crm, opcode2));
    }
}
