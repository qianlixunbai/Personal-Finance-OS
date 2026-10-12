package com.financeos.module.importing.storage;

import java.nio.file.*;
import java.util.UUID;

/** Explicit one-time operator command. Never run by application startup or cleanup. */
public final class ImportStorageProvisioner {
    public static void main(String[] arguments) {
        try {
            if (arguments.length != 1 || !"--provision".equals(arguments[0])) throw new IllegalArgumentException();
            UUID deployment = UUID.fromString(required("FINANCE_IMPORT_STORAGE_DEPLOYMENT_ID"));
            UUID rawId = UUID.fromString(required("FINANCE_IMPORT_STORAGE_RAW_ID"));
            UUID planId = UUID.fromString(required("FINANCE_IMPORT_STORAGE_PLAN_ID"));
            Path raw = Path.of(required("FINANCE_IMPORT_STORAGE_RAW_ROOT")).toAbsolutePath().normalize();
            Path plan = Path.of(required("FINANCE_IMPORT_STORAGE_PLAN_ROOT")).toAbsolutePath().normalize();
            if (raw.equals(plan) || raw.startsWith(plan) || plan.startsWith(raw) || rawId.equals(planId)) throw new IllegalArgumentException();
            requireEmpty(raw); requireEmpty(plan);
            TrustedImportStorageRoot.provision(raw, deployment, rawId, "RAW");
            TrustedImportStorageRoot.provision(plan, deployment, planId, "PLAN");
            if (raw.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                var permissions = java.nio.file.attribute.PosixFilePermissions.fromString("rwx------");
                Files.setPosixFilePermissions(raw, permissions); Files.setPosixFilePermissions(plan, permissions);
            }
            System.out.println("Import storage provisioned. Verify service ownership and exclusive access before enabling the application.");
        } catch (Exception exception) {
            System.err.println("Import storage provisioning refused. Existing or partial bindings require operator verification.");
            System.exit(2);
        }
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException();
        return value;
    }
    private static void requireEmpty(Path root) throws Exception {
        if (!Files.exists(root)) return;
        if (!root.toRealPath().equals(root)) throw new IllegalArgumentException();
        try (var entries = Files.list(root)) { if (entries.findAny().isPresent()) throw new IllegalArgumentException(); }
    }
}
