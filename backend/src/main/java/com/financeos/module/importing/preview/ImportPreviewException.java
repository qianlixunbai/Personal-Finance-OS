package com.financeos.module.importing.preview;

import com.financeos.common.BusinessException;

public final class ImportPreviewException extends BusinessException {
    public ImportPreviewException(int status, String message) {
        super(status, message);
    }
}
