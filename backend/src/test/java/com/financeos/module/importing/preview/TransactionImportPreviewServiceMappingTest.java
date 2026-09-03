package com.financeos.module.importing.preview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.module.account.mapper.AccountMapper;
import com.financeos.module.category.mapper.CategoryMapper;
import com.financeos.module.importing.dto.TransactionImportMapping;
import com.financeos.module.importing.dto.TransactionImportPreviewRequest;
import com.financeos.module.importing.entity.TransactionImportSession;
import com.financeos.module.importing.mapper.TransactionImportSessionMapper;
import com.financeos.module.importing.service.TransactionImportSessionService;
import com.financeos.module.importing.storage.StoredImportFile;
import com.financeos.module.importing.storage.TemporaryImportFileStorage;
import com.financeos.module.ledger.mapper.TransactionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TransactionImportPreviewServiceMappingTest {

    @Test
    void discoversSourceValuesWhenAccountAndCategoryMappingsAreEmptyWithoutQueryingMappers() {
        PreviewHarness harness = harness();
        when(harness.accountMapper().findOwnedByIds(any(), any())).thenReturn(List.of());
        when(harness.categoryMapper().selectVisibleByIds(any(), any())).thenReturn(List.of());

        var response = harness.preview(Map.of(), Map.of());

        assertThat(response.rows()).singleElement().satisfies(row -> {
            assertThat(row.sourceValues()).containsEntry("account", "Cash").containsEntry("category", "Food");
            assertThat(row.errors()).extracting("code").contains("ACCOUNT_NOT_MAPPED", "CATEGORY_NOT_MAPPED");
        });
        verifyNoInteractions(harness.accountMapper(), harness.categoryMapper());
    }

    @Test
    void queriesOnlyAccountMapperWhenOnlyAccountMappingsContainIds() {
        PreviewHarness harness = harness();
        when(harness.accountMapper().findOwnedByIds(1L, List.of(11L))).thenReturn(List.of());
        when(harness.categoryMapper().selectVisibleByIds(any(), any())).thenReturn(List.of());

        harness.preview(Map.of("Cash", 11L), Map.of());

        verify(harness.accountMapper()).findOwnedByIds(1L, List.of(11L));
        verifyNoInteractions(harness.categoryMapper());
    }

    @Test
    void queriesOnlyCategoryMapperWhenOnlyCategoryMappingsContainIds() {
        PreviewHarness harness = harness();
        when(harness.accountMapper().findOwnedByIds(any(), any())).thenReturn(List.of());
        when(harness.categoryMapper().selectVisibleByIds(1L, List.of(22L))).thenReturn(List.of());

        harness.preview(Map.of(), Map.of("Food", 22L));

        verify(harness.categoryMapper()).selectVisibleByIds(1L, List.of(22L));
        verifyNoInteractions(harness.accountMapper());
    }

    private PreviewHarness harness() {
        TransactionImportSessionService sessionService = mock(TransactionImportSessionService.class);
        TransactionImportSessionMapper sessionMapper = mock(TransactionImportSessionMapper.class);
        TemporaryImportFileStorage storage = mock(TemporaryImportFileStorage.class);
        TemporaryImportFileStorage planStorage = mock(TemporaryImportFileStorage.class);
        AccountMapper accountMapper = mock(AccountMapper.class);
        CategoryMapper categoryMapper = mock(CategoryMapper.class);
        TransactionImportPreviewService service = new TransactionImportPreviewService(sessionService, sessionMapper, storage,
                accountMapper, categoryMapper, mock(TransactionMapper.class), planStorage, new ObjectMapper(), Clock.systemUTC());
        return new PreviewHarness(service, sessionService, sessionMapper, storage, planStorage, accountMapper, categoryMapper);
    }

    private record PreviewHarness(TransactionImportPreviewService service, TransactionImportSessionService sessionService,
                                  TransactionImportSessionMapper sessionMapper, TemporaryImportFileStorage storage,
                                  TemporaryImportFileStorage planStorage, AccountMapper accountMapper, CategoryMapper categoryMapper) {
        private com.financeos.module.importing.dto.TransactionImportPreviewResponse preview(Map<String, Long> accountMappings,
                                                                                             Map<String, Long> categoryMappings) {
            TransactionImportSession session = session();
            when(storage.save(any(), anyString())).thenReturn(new StoredImportFile("raw", 1, "file-digest"));
            when(storage.open("raw")).thenReturn(new ByteArrayInputStream(csv().getBytes(StandardCharsets.UTF_8)));
            when(sessionService.createMappingRequiredSession(any(), any())).thenReturn(session);
            when(planStorage.save(any(), anyString())).thenReturn(new StoredImportFile("plan", 1, "plan-digest"));
            when(sessionMapper.markPreviewReady(any(), any(), anyString(), anyString(), anyString(), any())).thenReturn(1);
            when(sessionMapper.findByIdAndUserId(session.getId(), 1L)).thenReturn(session);

            return service.create(1L, new MockMultipartFile("file", "statement.csv", "text/csv", csv().getBytes(StandardCharsets.UTF_8)),
                    new TransactionImportPreviewRequest(null, mapping(accountMappings, categoryMappings)));
        }

        private static TransactionImportSession session() {
            TransactionImportSession session = new TransactionImportSession();
            session.setId(UUID.randomUUID());
            session.setUserId(1L);
            session.setPreallocatedBatchId(UUID.randomUUID());
            session.setRevision(1);
            session.setFileDigest("file-digest");
            session.setOptionsDigest("options-digest");
            session.setExpiresAt(Instant.now().plusSeconds(60));
            return session;
        }

        private static TransactionImportMapping mapping(Map<String, Long> accountMappings, Map<String, Long> categoryMappings) {
            return new TransactionImportMapping(
                    Map.of("date", "date", "type", "type", "amount", "amount", "account", "account",
                            "category", "category", "description", "description"),
                    Map.of("expense", "EXPENSE"), accountMappings, categoryMappings);
        }

        private static String csv() {
            return "date,type,amount,account,category,description\n2026-08-01,expense,10.00,Cash,Food,lunch\n";
        }
    }
}
