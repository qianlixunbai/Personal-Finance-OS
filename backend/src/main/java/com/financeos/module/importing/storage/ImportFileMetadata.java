package com.financeos.module.importing.storage;

public final class ImportFileMetadata {
    private ImportFileMetadata() {
    }

    public static String sanitizeOriginalFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "upload";
        }
        String normalized = filename.replace('\\', '/');
        int finalSeparator = normalized.lastIndexOf('/');
        String basename = finalSeparator >= 0 ? normalized.substring(finalSeparator + 1) : normalized;
        StringBuilder clean = new StringBuilder();
        basename.codePoints().filter(codePoint -> !Character.isISOControl(codePoint)).limit(255)
                .forEach(clean::appendCodePoint);
        return clean.isEmpty() ? "upload" : clean.toString();
    }
}
