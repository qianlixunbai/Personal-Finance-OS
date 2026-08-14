package com.financeos.module.importing.preview;

import java.io.InputStream;

public interface TransactionImportParser {
    ParsedImportFile parse(InputStream input);
}
