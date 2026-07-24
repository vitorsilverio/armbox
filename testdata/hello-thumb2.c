/* hello-thumb2.c — binário de compilador real sob Thumb-2 (task B4.0.3), compilado
 * `arm-none-eabi-gcc -march=armv7-a -mthumb -Os -nostdlib -static` (ver
 * testdata/build-testdata.ps1) e rodado com `--arch=armv7a` (não `--arch=thumb2`):
 * o struct com bitfields abaixo faz o gcc emitir UBFX/SBFX ("media" instructions de
 * ARMv7, não ARMv6T2 puro), e o preset `ARMV6K_THUMB2` deliberadamente não tem
 * `ArmFeature.BIT_FIELD` (só `ARMV7A` tem, decisão do épico B3) — mesmo fallback
 * descrito na task B4.0.3 para quando o binário usa algo v7-only. Segue o MESMO
 * padrão bare-metal do resto do testdata (-nostdlib, syscalls cruas via svc #0,
 * sem CRT/libc), como hello-float.c/hello-cortexm.c.
 *
 * Cobre, num único binário, os 4 grupos pedidos pela task:
 *  1) struct com bitfields (fill_bitfields/sum_bitfields) -> UBFX/SBFX/STRH.
 *  2) STRD explícito (pack_pair) via asm inline.
 *  3) switch denso com efeito colateral não-linear por caso (dense_switch) -> gcc
 *     escolhe TBB (tabela de branch de 1 byte) em vez de tabela de valores.
 *  4) qsort pequeno e recursivo (qsort_int) -> IT blocks + cmp/branches curtos nos
 *     laços de partição.
 *
 * Achado real durante a validação desta task: o preset público `ArmArchitecture#
 * ARMV7A` tinha um bug de fiação que fazia UBFX/SBFX/RBIT/SDIV/UDIV/MLS em encoding
 * **Thumb-2** virarem UNDEFINED mesmo com `BIT_FIELD`/`BIT_REVERSE`/`MLS_MULTIPLY`/
 * `DIVIDE` habilitadas no preset — corrigido no arm-jitter
 * (`ArmArchitecture.ARMV7A`, ver o javadoc lá para os detalhes); este binário é o
 * que expôs o bug (armadilha da task: "se algo divergir, o bug provavelmente é do
 * arm-jitter").
 */

struct bitfields {
    unsigned tag : 4;
    unsigned flags : 5;
    int delta : 7;
    unsigned payload : 16;
};

__attribute__((noinline)) static void fill_bitfields(struct bitfields *bf, int i) {
    bf->tag = (unsigned) i & 0xf;
    bf->flags = (unsigned) (i * 3) & 0x1f;
    bf->delta = i - 10;
    bf->payload = (unsigned) (i * 12345) & 0xffff;
}

__attribute__((noinline)) static int sum_bitfields(const struct bitfields *bf) {
    return (int) bf->tag + (int) bf->flags + bf->delta + (int) bf->payload;
}

/* forca strd/ldrd explicitamente: par de valores 64-bit sintetico via long long */
__attribute__((noinline)) static long long pack_pair(int lo, int hi) {
    long long r;
    __asm__ volatile("strd %1, %2, [%0]" :: "r"(&r), "r"(lo), "r"(hi) : "memory");
    return r;
}

/* switch denso com efeitos colaterais nao-lineares por caso: forca gcc a emitir
   tbb/tbh (tabela de branch de 1/2 bytes) em vez de tabela de valores/ldr.w. */
__attribute__((noinline)) static int dense_switch(int x, int *out) {
    switch (x) {
        case 0: *out += 0x11; break;
        case 1: *out ^= 0x22; break;
        case 2: *out += 0x33; break;
        case 3: *out ^= 0x44; break;
        case 4: *out += 0x55; break;
        case 5: *out ^= 0x66; break;
        case 6: *out += 0x77; break;
        case 7: *out ^= 0x88; break;
        case 8: *out += 0x99; break;
        case 9: *out ^= 0xaa; break;
        case 10: *out += 0xbb; break;
        case 11: *out ^= 0xcc; break;
        case 12: *out += 0xdd; break;
        case 13: *out ^= 0xee; break;
        case 14: *out += 0xff; break;
        default: *out = -1; return -1;
    }
    return 0;
}

/* qsort pequeno (recursivo, sem libc): forca IT + cmp/branches nos casos
   dos ramos condicionais curtos (troca/partition). */
static void swap_int(int *a, int *b) {
    int t = *a;
    *a = *b;
    *b = t;
}

static void qsort_int(int *arr, int lo, int hi) {
    if (lo >= hi) {
        return;
    }
    int pivot = arr[(lo + hi) / 2];
    int i = lo, j = hi;
    while (i <= j) {
        while (arr[i] < pivot) {
            i++;
        }
        while (arr[j] > pivot) {
            j--;
        }
        if (i <= j) {
            swap_int(&arr[i], &arr[j]);
            i++;
            j--;
        }
    }
    qsort_int(arr, lo, j);
    qsort_int(arr, i, hi);
}

/* ---- syscalls crus, mesmo padrao de hello-float.c/hello.s ---- */
static long syscall3(long n, long a0, long a1, long a2) {
    register long r7 __asm__("r7") = n;
    register long r0 __asm__("r0") = a0;
    register long r1 __asm__("r1") = a1;
    register long r2 __asm__("r2") = a2;
    __asm__ volatile("svc #0" : "+r"(r0) : "r"(r1), "r"(r2), "r"(r7) : "memory");
    return r0;
}

#define SYS_WRITE 4
#define SYS_EXIT 1

static void write_all(const char *s, int len) {
    syscall3(SYS_WRITE, 1, (long) s, len);
}

static void exit_process(int code) {
    syscall3(SYS_EXIT, code, 0, 0);
}

__attribute__((naked)) void _start(void) {
    __asm__ volatile(
        "bl c_main\n\t"
        "mov r0, #0\n\t"
        "mov r7, #1\n\t"
        "svc #0\n\t");
}

static void put_hex_digit(char *buf, int *pos, int nibble) {
    buf[(*pos)++] = (char) (nibble < 10 ? '0' + nibble : 'a' + nibble - 10);
}

static void put_hex32(char *buf, int *pos, unsigned value) {
    for (int shift = 28; shift >= 0; shift -= 4) {
        put_hex_digit(buf, pos, (int) ((value >> shift) & 0xf));
    }
}

void c_main(void) {
    /* 1) bitfields, forcando ubfx/sbfx/strh no acesso a campos compactados */
    struct bitfields bf;
    int bf_acc = 0;
    for (int i = 0; i < 16; i++) {
        fill_bitfields(&bf, i);
        bf_acc += sum_bitfields(&bf);
    }

    /* 2) strd/ldrd explicito */
    long long packed = pack_pair(bf_acc, bf_acc ^ 0x5a5a5a5a);

    /* 3) switch denso -> tbb/tbh, chamado em loop pra nao ser eliminado */
    int sw_acc = 0;
    for (int i = 0; i < 15; i++) {
        dense_switch(i, &sw_acc);
    }

    /* 4) qsort pequeno -> IT blocks + cmp/branches curtos */
    int values[12] = {42, 7, -3, 99, 5, 5, -100, 1000, 0, 17, -17, 63};
    qsort_int(values, 0, 11);

    int checksum = sw_acc ^ (int) packed ^ (int) (packed >> 32);
    for (int i = 0; i < 12; i++) {
        checksum = (checksum * 31) + values[i];
    }

    char buf[16];
    int pos = 0;
    put_hex32(buf, &pos, (unsigned) checksum);
    buf[pos++] = '\n';
    write_all(buf, pos);

    exit_process(0);
}
