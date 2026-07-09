package dev.vitorsilverio.armbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// CLI: `armbox [--interp|--check] <elf> [args...]`.
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws IOException {
        Armbox.Backend backend = Armbox.Backend.JIT;
        int index = 0;
        while (index < args.length && args[index].startsWith("--")) {
            switch (args[index]) {
                case "--interp" -> backend = Armbox.Backend.INTERPRETED;
                case "--check" -> backend = Armbox.Backend.CHECK;
                default -> {
                    System.err.println("opção desconhecida: " + args[index]);
                    usage();
                    return;
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
        int exitCode = Armbox.run(elf, argv, List.of(), backend,
                System.in, System.out, System.err, System.err);
        System.exit(exitCode);
    }

    private static void usage() {
        System.err.println("uso: armbox [--interp|--check] <elf> [args...]");
        System.exit(2);
    }
}
