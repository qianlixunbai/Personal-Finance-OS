package com.financeos.module.importing.storage;

import com.financeos.common.BusinessException;

public class InvalidImportFileException extends BusinessException {
    public InvalidImportFileException(int code, String message) {
        super(code, message);
    }
}
