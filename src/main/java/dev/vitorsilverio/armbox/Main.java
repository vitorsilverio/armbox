package dev.vitorsilverio.armbox;

import dev.vitorsilverio.armbox.aarch64.Aarch64LinuxMachine;
import dev.vitorsilverio.armbox.baremetal.CortexMMachine;
import dev.vitorsilverio.armjitter.arch.ArmArchitecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// CLI: `armbox [--arch=...] [--machine=linux-user|cortex-m] [--interp|--check] [--ram-size=N] <elf|bin> [args...]`.
public final class Main {
    private static final String MACHINE_LINUX_USER = "linux-user";
    private static final String MACHINE_CORTEX_M = "cortex-m";
    /// Valor de `--arch=` para o pipeline AArch64 (B6.2) — deliberadamente NÃO mapeado a um
    /// {@link ArmArchitecture} (esse enum é só do pipeline ARM/Thumb de 32 bits, G2/G3 do
    /// arm-jitter): o AArch64 tem core/decoder/executor/loader inteiramente separados
    /// ({@link Aarch64LinuxMachine}), então o dispatch acontece antes de qualquer coisa que
    /// dependa de `ArmArchitecture`.
    private static final String ARCH_AARCH64 = "aarch64";

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        Armbox.Backend backend = Armbox.Backend.JIT;
        ArmArchitecture architecture = ArmArchitecture.ARMV5TE;
        String machine = MACHINE_LINUX_USER;
        int ramSizeBytes = CortexMMachine.DEFAULT_RAM_SIZE_BYTES;
        boolean aarch64 = false;
        int index = 0;
        while (index < args.length && args[index].startsWith("--")) {
            String arg = args[index];
            if (arg.startsWith("--arch=")) {
                String value = arg.substring("--arch=".length());
                if (value.equals(ARCH_AARCH64)) {
                    aarch64 = true;
                } else {
                    architecture = switch (value) {
                        case "armv5te" -> ArmArchitecture.ARMV5TE;
                        case "armv6k" -> ArmArchitecture.ARMV6K;
                        case "thumb2" -> ArmArchitecture.ARMV6K_THUMB2;
                        case "armv7a" -> ArmArchitecture.ARMV7A;
                        case "armv6m" -> ArmArchitecture.ARMV6M;
                        case "armv7m" -> ArmArchitecture.ARMV7M;
                        default -> {
                            System.err.println("--arch desconhecido: " + value);
                            usage();
                            yield null;
                        }
                    };
                    if (architecture == null) {
                        return;
                    }
                }
            } else if (arg.startsWith("--machine=")) {
                machine = arg.substring("--machine=".length());
                if (!machine.equals(MACHINE_LINUX_USER) && !machine.equals(MACHINE_CORTEX_M)) {
                    System.err.println("--machine desconhecido: " + machine);
                    usage();
                    return;
                }
            } else if (arg.startsWith("--ram-size=")) {
                ramSizeBytes = Integer.decode(arg.substring("--ram-size=".length()));
            } else {
                switch (arg) {
                    case "--interp" -> backend = Armbox.Backend.INTERPRETED;
                    case "--check" -> backend = Armbox.Backend.CHECK;
                    case "--truffle" -> backend = Armbox.Backend.TRUFFLE;
                    default -> {
                        System.err.println("opção desconhecida: " + arg);
                        usage();
                        return;
                    }
                }
            }
            index++;
        }
        if (index >= args.length) {
            usage();
            return;
        }
        Path imagePath = Path.of(args[index]);
        byte[] image = Files.readAllBytes(imagePath);

        if (aarch64) {
            if (!machine.equals(MACHINE_LINUX_USER)) {
                System.err.println("--arch=aarch64 só suporta --machine=linux-user (B6.2)");
                usage();
                return;
            }
            int exitCode = Aarch64LinuxMachine.run(image, System.out, System.err, System.err);
            System.exit(exitCode);
            return;
        }

        if (machine.equals(MACHINE_CORTEX_M)) {
            if (architecture != ArmArchitecture.ARMV6M && architecture != ArmArchitecture.ARMV7M) {
                System.err.println("--machine=cortex-m exige --arch=armv6m ou --arch=armv7m");
                usage();
                return;
            }
            int exitCode = CortexMMachine.run(image, backend, architecture, ramSizeBytes, System.out, System.err);
            System.exit(exitCode);
            return;
        }

        List<String> argv = new ArrayList<>();
        argv.add(imagePath.getFileName().toString());
        for (int i = index + 1; i < args.length; i++) {
            argv.add(args[i]);
        }
        int exitCode = Armbox.run(image, argv, List.of(), backend, architecture,
                System.in, System.out, System.err, System.err);
        System.exit(exitCode);
    }

    private static void usage() {
        System.err.println("uso: armbox [--arch=armv5te|armv6k|thumb2|armv7a|armv6m|armv7m|aarch64] "
                + "[--machine=linux-user|cortex-m] [--interp|--check] [--ram-size=N] <elf|bin> [args...]");
        System.exit(2);
    }
}
