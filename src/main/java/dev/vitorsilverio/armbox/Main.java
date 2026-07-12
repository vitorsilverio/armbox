package dev.vitorsilverio.armbox;

import dev.vitorsilverio.armjitter.arch.ArmArchitecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// CLI: `armbox [--arch=armv5te|armv6k|thumb2] [--interp|--check] <elf> [args...]`.
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws IOException {
        Armbox.Backend backend = Armbox.Backend.JIT;
        ArmArchitecture architecture = ArmArchitecture.ARMV5TE;
        int index = 0;
        while (index < args.length && args[index].startsWith("--")) {
            String arg = args[index];
            if (arg.startsWith("--arch=")) {
                String value = arg.substring("--arch=".length());
                architecture = switch (value) {
                    case "armv5te" -> ArmArchitecture.ARMV5TE;
                    case "armv6k" -> ArmArchitecture.ARMV6K;
                    case "thumb2" -> ArmArchitecture.ARMV6K_THUMB2_PARTIAL;
                    default -> {
                        System.err.println("--arch desconhecido: " + value);
                        usage();
                        yield null;
                    }
                };
                if (architecture == null) {
                    return;
                }
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
        Path elfPath = Path.of(args[index]);
        List<String> argv = new ArrayList<>();
        argv.add(elfPath.getFileName().toString());
        for (int i = index + 1; i < args.length; i++) {
            argv.add(args[i]);
        }
        byte[] elf = Files.readAllBytes(elfPath);
        int exitCode = Armbox.run(elf, argv, List.of(), backend, architecture,
                System.in, System.out, System.err, System.err);
        System.exit(exitCode);
    }

    private static void usage() {
        System.err.println("uso: armbox [--arch=armv5te|armv6k|thumb2] [--interp|--check] <elf> [args...]");
        System.exit(2);
    }
}
