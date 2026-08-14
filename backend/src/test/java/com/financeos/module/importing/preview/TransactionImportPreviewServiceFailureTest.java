package com.financeos.module.importing.preview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.module.account.mapper.AccountMapper;
import com.financeos.module.category.mapper.CategoryMapper;
import com.financeos.module.importing.dto.TransactionImportFormat;
import com.financeos.module.importing.dto.TransactionImportPreviewRequest;
import com.financeos.module.importing.mapper.TransactionImportSessionMapper;
import com.financeos.module.importing.service.TransactionImportSessionService;
import com.financeos.module.importing.storage.StoredImportFile;
import com.financeos.module.importing.storage.TemporaryImportFileStorage;
import com.financeos.module.ledger.mapper.TransactionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionImportPreviewServiceFailureTest {

    @Test
    void removesTheRawPayloadWhenParsingFails() throws Exception {
        TemporaryImportFileStorage storage = mock(TemporaryImportFileStorage.class);
        when(storage.save(any(), anyString())).thenReturn(new StoredImportFile("raw-ref", 10, "digest"));
        when(storage.open("raw-ref")).thenReturn(new ByteArrayInputStream("date\n\"unterminated".getBytes(StandardCharsets.UTF_8)));

        TransactionImportPreviewService service = service(storage, mock(TransactionImportSessionService.class));

        assertThatThrownBy(() -> service.create(1L, file(), new TransactionImportPreviewRequest(TransactionImportFormat.CSV, null)))
                .isInstanceOf(ImportPreviewException.class);

        verify(storage).delete("raw-ref");
    }

    @Test
    void removesTheRawPayloadWhenSessionCreationFails() throws Exception {
        TemporaryImportFileStorage storage = mock(TemporaryImportFileStorage.class);
        when(storage.save(any(), anyString())).thenReturn(new StoredImportFile("raw-ref", 10, "digest"));
        when(storage.open("raw-ref")).thenReturn(new ByteArrayInputStream("date\n2026-01-01\n".getBytes(StandardCharsets.UTF_8)));
        TransactionImportSessionService sessions = mock(TransactionImportSessionService.class);
        when(sessions.createMappingRequiredSession(any(), any())).thenThrow(new IllegalStateException("database unavailable"));

        TransactionImportPreviewService service = service(storage, sessions);

        assertThatThrownBy(() -> service.create(1L, file(), new TransactionImportPreviewRequest(TransactionImportFormat.CSV, null)))
                .isInstanceOf(IllegalStateException.class);

        verify(storage).delete("raw-ref");
    }

    private TransactionImportPreviewService service(TemporaryImportFileStorage storage, TransactionImportSessionService sessions) {
        return new TransactionImportPreviewService(sessions, mock(TransactionImportSessionMapper.class), storage,
                mock(AccountMapper.class), mock(CategoryMapper.class), mock(TransactionMapper.class),
                mock(TemporaryImportFileStorage.class), new ObjectMapper(), Clock.systemUTC());
    }

    private MultipartFile file() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("statement.csv");
        when(file.getContentType()).thenReturn("text/csv");
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream("ignored".getBytes(StandardCharsets.UTF_8)));
        return file;
    }
}
