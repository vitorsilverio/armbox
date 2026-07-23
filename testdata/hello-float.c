/* hello-float.c — binário ELF real ARMv7-A hard-float (task B3.7), compilado
 * `arm-none-eabi-gcc -march=armv7-a -mfpu=vfp -mfloat-abi=hard -O2 -nostdlib -static`
 * (ver testdata/build-testdata.ps1).
 *
 * Armadilha da task: a spec pedia a "mesma libc mínima que hello.elf atual usa" —
 * hello.elf (e todo o resto do testdata) NÃO usa libc nenhuma, é bare-metal com
 * syscalls cruas via `svc #0` (-nostdlib). Este arquivo segue o MESMO padrão em vez
 * de linkar contra newlib/picolibc: _start próprio (sem CRT), sem printf, sem
 * memcpy/memset de biblioteca. Isso evita de propósito o risco da armadilha descrita
 * na spec (libc pré-compilada com memcpy vetorizado em NEON mesmo sob -mfpu=vfp) —
 * não há NENHUMA libc linkada para carregar essa rotina. A divisão inteira por 10 no
 * loop de formatação é por uma CONSTANTE em tempo de compilação, que o gcc resolve
 * inline via multiplicação+shift (sem chamar __aeabi_idiv de libgcc), então nem
 * libgcc é necessária no link.
 *
 * Cálculo: série de Leibniz (6 termos) aproximando π/4 = 1 - 1/3 + 1/5 - 1/7 + 1/9 - 1/11,
 * em `double` real (VADD/VSUB/VDIV/VCVT.F64 nativos sob -mfloat-abi=hard, sem
 * emulação de software) — stdout determinístico, formatado manualmente (sem printf
 * de float) como "0.DDDDDD\n" (6 casas decimais, arredondado).
 */

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

/* Sem CRT: _start monta o próprio quadro e chama c_main diretamente. */
__attribute__((naked)) void _start(void) {
    __asm__ volatile(
        "bl c_main\n\t"
        "mov r0, #0\n\t"
        "mov r7, #1\n\t"
        "svc #0\n\t");
}

/* `c_main` roda em hard-float real: cada operação abaixo vira VADD.F64/VSUB.F64/
 * VDIV.F64/VCVT nativos do VFP, nunca uma chamada de biblioteca. */
void c_main(void) {
    double sum = 0.0;
    double sign = 1.0;
    const int LEIBNIZ_TERMS = 6;
    for (int i = 0; i < LEIBNIZ_TERMS; i++) {
        double denominator = (double) (2 * i + 1);
        sum += sign / denominator;
        sign = -sign;
    }

    /* Escala para 6 casas decimais fixas e arredonda (sum está em (0,1)). */
    const long DECIMAL_SCALE = 1000000L;
    long scaled = (long) (sum * (double) DECIMAL_SCALE + 0.5);

    char buffer[16];
    int pos = 0;
    buffer[pos++] = '0';
    buffer[pos++] = '.';
    long place = DECIMAL_SCALE / 10; /* 100000 */
    for (int digit_index = 0; digit_index < 6; digit_index++) {
        int digit = 0;
        while (scaled >= place) {
            scaled -= place;
            digit++;
        }
        buffer[pos++] = (char) ('0' + digit);
        place /= 10; /* constante em tempo de compilação — sem __aeabi_idiv */
    }
    buffer[pos++] = '\n';

    write_all(buffer, pos);
    exit_process(0);
}
