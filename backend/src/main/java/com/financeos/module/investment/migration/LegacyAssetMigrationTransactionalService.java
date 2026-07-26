package com.financeos.module.investment.migration;

import com.financeos.common.BusinessException;
import com.financeos.module.account.entity.Account;
import com.financeos.module.account.service.AccountBalanceService;
import com.financeos.module.account.service.AccountQueryService;
import com.financeos.module.asset.entity.Asset;
import com.financeos.module.asset.mapper.AssetMapper;
import com.financeos.module.investment.entity.InvestmentTransaction;
import com.financeos.module.investment.instrument.entity.InvestmentInstrument;
import com.financeos.module.investment.instrument.mapper.InvestmentInstrumentMapper;
import com.financeos.module.investment.ledger.InvestmentLedgerCommand;
import com.financeos.module.investment.ledger.InvestmentPositionState;
import com.financeos.module.investment.ledger.InvestmentReplayEngine;
import com.financeos.module.investment.ledger.InvestmentReplayEntry;
import com.financeos.module.investment.ledger.InvestmentReplayResult;
import com.financeos.module.investment.ledger.InvestmentTransactionStatus;
import com.financeos.module.investment.ledger.InvestmentTransactionType;
import com.financeos.module.investment.mapper.InvestmentTransactionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Service
class LegacyAssetMigrationTransactionalService {
    private final AccountBalanceService accountBalanceService;
    private final AccountQueryService accountQueryService;
    private final InvestmentInstrumentMapper instrumentMapper;
    private final AssetMapper assetMapper;
    private final InvestmentTransactionMapper transactionMapper;
    private final LegacyAssetMigrationPreflightService preflightService;
    private final LegacyMigrationConsistencyChecker consistencyChecker;
    private final InvestmentReplayEngine replayEngine = new InvestmentReplayEngine();

    LegacyAssetMigrationTransactionalService(AccountBalanceService accountBalanceService,
                                             AccountQueryService accountQueryService,
                                             InvestmentInstrumentMapper instrumentMapper,
                                             AssetMapper assetMapper,
                                             InvestmentTransactionMapper transactionMapper,
                                             LegacyAssetMigrationPreflightService preflightService,
                                             LegacyMigrationConsistencyChecker consistencyChecker) {
        this.accountBalanceService = accountBalanceService;
        this.accountQueryService = accountQueryService;
        this.instrumentMapper = instrumentMapper;
        this.assetMapper = assetMapper;
        this.transactionMapper = transactionMapper;
        this.preflightService = preflightService;
        this.consistencyChecker = consistencyChecker;
    }

    @Transactional
    MigrationSuccess confirm(Long userId, PreviewTokenPayload token, String idempotencyKey) {
        Account lockedAccount = accountBalanceService.lockOwnedAccounts(userId, List.of(token.accountId())).accounts().getFirst();
        BigDecimal balanceBefore = lockedAccount.getBalance();
        InvestmentInstrument instrument = instrumentMapper.findByUserIdAndIdForUpdate(userId, token.instrumentId());
        if (instrument == null) {
            throw new BusinessException(409, "Migration target instrument changed");
        }
        Asset lockedAsset = assetMapper.selectOwnedForUpdate(userId, token.assetId());
        if (lockedAsset == null) {
            throw new BusinessException(404, "Asset not found");
        }
        LegacyAssetMigrationPreflight preflight = preflightService.preflight(userId, token.assetId(), token.instrumentId(), token.accountId());
        if (!preflight.isReady()
                || !token.sourceVersion().equals(preflight.sourceVersion())
                || !token.requestHash().equals(preflight.requestHash())
                || !token.formulaVersion().equals(MigrationPreviewTokenService.FORMULA_VERSION)) {
            throw new BusinessException(409, "Migration preview is stale; request a new preview");
        }
        if (assetMapper.bindLegacyAsset(userId, token.assetId(), token.accountId(), token.instrumentId()) != 1) {
            throw new BusinessException(409, "Legacy asset can no longer be bound");
        }
        InvestmentTransaction transaction = openingTransaction(userId, preflight, idempotencyKey);
        transactionMapper.insert(transaction);
        InvestmentReplayResult replay = replayEngine.replay(entries(userId, token.assetId()));
        InvestmentPositionState position = replay.position();
        if (assetMapper.finalizeOpeningMigrationProjection(userId, token.assetId(), token.accountId(), token.instrumentId(),
                position.quantity(), position.totalCost().divide(position.quantity(), 8, java.math.RoundingMode.HALF_UP),
                position.totalCost(), position.cumulativeRealizedProfitLoss(), "OPEN", transaction.getId()) != 1) {
            throw new IllegalStateException("migration projection update did not affect exactly one row");
        }
        Asset updated = assetMapper.selectOwnedForUpdate(userId, token.assetId());
        Account accountAfter = accountQueryService.findAccessibleAccount(userId, token.accountId());
        consistencyChecker.verify(preflight, transaction, updated, position, balanceBefore, accountAfter);
        return new MigrationSuccess(updated.getId(), transaction.getId(), token.accountId(), token.instrumentId(), token.requestHash());
    }

    private InvestmentTransaction openingTransaction(Long userId, LegacyAssetMigrationPreflight preflight, String idempotencyKey) {
        BigDecimal zero = BigDecimal.ZERO.setScale(2);
        Instant now = Instant.now();
        InvestmentTransaction transaction = new InvestmentTransaction();
        transaction.setUserId(userId);
        transaction.setAssetId(preflight.asset().getId());
        transaction.setAccountId(preflight.account().getId());
        transaction.setTransactionType(InvestmentTransactionType.OPENING_POSITION.name());
        transaction.setStatus(InvestmentTransactionStatus.POSTED.name());
        transaction.setQuantity(preflight.asset().getQuantity());
        transaction.setUnitPrice(preflight.cost().unitPrice());
        transaction.setGrossAmount(preflight.cost().totalCost());
        transaction.setFeeAmount(zero);
        transaction.setTaxAmount(zero);
        transaction.setNetAmount(zero);
        transaction.setReleasedCostAmount(zero);
        transaction.setRealizedProfitLoss(zero);
        transaction.setCurrency("CNY");
        transaction.setTradeTime(now);
        transaction.setSettlementTime(now);
        transaction.setSource("MIGRATION");
        transaction.setExternalReference("LEGACY_ASSET_OPENING_V1:" + preflight.asset().getId());
        transaction.setIdempotencyKey(idempotencyKey);
        transaction.setRequestHash(preflight.requestHash());
        return transaction;
    }

    private List<InvestmentReplayEntry> entries(Long userId, Long assetId) {
        return transactionMapper.selectAllByUserIdAndAssetId(userId, assetId).stream()
                .map(transaction -> new InvestmentReplayEntry(transaction.getId(), transaction.getTradeTime(),
                        InvestmentTransactionStatus.valueOf(transaction.getStatus()), command(transaction)))
                .toList();
    }

    private InvestmentLedgerCommand command(InvestmentTransaction transaction) {
        return switch (InvestmentTransactionType.valueOf(transaction.getTransactionType())) {
            case BUY -> InvestmentLedgerCommand.buy(transaction.getQuantity(), transaction.getUnitPrice(), transaction.getFeeAmount(), transaction.getTaxAmount());
            case SELL -> InvestmentLedgerCommand.sell(transaction.getQuantity(), transaction.getUnitPrice(), transaction.getFeeAmount(), transaction.getTaxAmount());
            case DIVIDEND -> InvestmentLedgerCommand.dividend(transaction.getGrossAmount(), transaction.getFeeAmount(), transaction.getTaxAmount());
            case OPENING_POSITION -> InvestmentLedgerCommand.openingPosition(transaction.getQuantity(), transaction.getUnitPrice());
        };
    }

    record MigrationSuccess(Long assetId, Long transactionId, Long accountId, Long instrumentId, String requestHash) {
    }
}
