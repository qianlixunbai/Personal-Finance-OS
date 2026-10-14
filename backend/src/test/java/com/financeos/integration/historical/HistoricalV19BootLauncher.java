package com.financeos.integration.historical;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** External stdin shutdown control only; loaded alongside the untouched historical Boot JAR. */
public final class HistoricalV19BootLauncher {
    private HistoricalV19BootLauncher() { }

    public static void main(String[] args) throws Exception {
        Class<?> launcher = Class.forName("org.springframework.boot.loader.launch.JarLauncher");
        Path expectedJar = Path.of(args[1]).toRealPath();
        Path loaderJar = Path.of(launcher.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        if (!loaderJar.equals(expectedJar)) throw new IllegalStateException("Unexpected historical Boot loader origin");
        System.out.println("V19_BOOT_LOADER=" + loaderJar);
        System.out.println("V19_JVM_PID=" + ProcessHandle.current().pid());
        Thread controller = new Thread(() -> {
            try {
                String command = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
                if (!"STOP".equals(command)) System.exit(64);
                System.out.println("V19_NORMAL_EXIT_REQUESTED");
                System.exit(0);
            } catch (Exception failure) {
                System.exit(65);
            }
        }, "historical-v19-stdin-controller");
        controller.setDaemon(true);
        controller.start();
        launcher.getMethod("main", String[].class).invoke(null, (Object) Arrays.copyOfRange(args, 2, args.length));
        // Original production main has returned, including synchronous ApplicationReadyEvent listeners.
        Files.writeString(Path.of(args[0]), "historical-production-main-returned\n", StandardCharsets.UTF_8);
        System.out.println("V19_PRODUCTION_MAIN_RETURNED");
    }
}
