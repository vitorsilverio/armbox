package dev.vitorsilverio.armbox.linux;

import dev.vitorsilverio.armbox.memory.GuestMemory;

/// Página mágica de kuser helpers do kernel Linux ARM (`Documentation/arm/kernel_user_helpers.rst`).
///
/// Binários glibc/musl estáticos chamam endereços fixos em `0xFFFF0Fxx` para atomics,
/// barreira e TLS. Sem esta página, qualquer binário com libc trava. As implementações
/// abaixo são as versões NÃO-SMP do kernel (código ARM comum — single-core, sem LDREX),
/// escritas como instruções na memória guest para rodarem no core normalmente (incl. JIT).
public final class KuserHelpers {
    private static final int PAGE_BASE = 0xFFFF0000;

    public static final int MEMORY_BARRIER_ADDRESS = 0xFFFF0FA0;
    public static final int CMPXCHG_ADDRESS = 0xFFFF0FC0;
    public static final int GET_TLS_ADDRESS = 0xFFFF0FE0;
    /// Word onde o valor de TLS fica guardado; `ARM_set_tls` escreve aqui.
    public static final int TLS_VALUE_ADDRESS = 0xFFFF0FF0;
    private static final int VERSION_ADDRESS = 0xFFFF0FFC;
    /// Número de helpers implementados (contrato do kernel: `(0xffff0f00 - addr) / 32`).
    private static final int HELPER_VERSION = 3;

    // Encodings ARM (little-endian) — comentados instrução a instrução abaixo.
    private static final int LDR_R3_AT_R2 = 0xE5923000;      // ldr   r3, [r2]
    private static final int SUBS_R3_R3_R0 = 0xE0533000;     // subs  r3, r3, r0
    private static final int STREQ_R1_AT_R2 = 0x05821000;    // streq r1, [r2]
    private static final int RSBS_R0_R3_0 = 0xE2730000;      // rsbs  r0, r3, #0
    private static final int BX_LR = 0xE12FFF1E;             // bx    lr
    private static final int LDR_R0_PC_8 = 0xE59F0008;       // ldr   r0, [pc, #8]

    private KuserHelpers() {
    }

    /// Mapeia e preenche a página em uma [GuestMemory].
    public static void mapInto(GuestMemory memory) {
        memory.map(PAGE_BASE, GuestMemory.PAGE_SIZE);

        // kuser_memory_barrier: single-core, nada a ordenar.
        memory.write32(MEMORY_BARRIER_ADDRESS, BX_LR);

        // kuser_cmpxchg (versão não-SMP do kernel):
        //   ldr r3,[r2]; subs r3,r3,r0; streq r1,[r2]; rsbs r0,r3,#0; bx lr
        // Sucesso: r0=0 e flag Z setada (o rsbs de r3==0 produz ambos).
        memory.write32(CMPXCHG_ADDRESS, LDR_R3_AT_R2);
        memory.write32(CMPXCHG_ADDRESS + 4, SUBS_R3_R3_R0);
        memory.write32(CMPXCHG_ADDRESS + 8, STREQ_R1_AT_R2);
        memory.write32(CMPXCHG_ADDRESS + 12, RSBS_R0_R3_0);
        memory.write32(CMPXCHG_ADDRESS + 16, BX_LR);

        // kuser_get_tls: ldr r0,[pc,#8] lê TLS_VALUE_ADDRESS (pc=+8 do fetch); bx lr.
        memory.write32(GET_TLS_ADDRESS, LDR_R0_PC_8);
        memory.write32(GET_TLS_ADDRESS + 4, BX_LR);
        memory.write32(TLS_VALUE_ADDRESS, 0);

        memory.write32(VERSION_ADDRESS, HELPER_VERSION);
    }
}
