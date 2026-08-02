package com.financeos.module.investment.command;

import com.financeos.common.BusinessException;
import com.financeos.module.account.entity.Account;
import com.financeos.module.account.service.AccountBalanceMutation;
import com.financeos.module.account.service.AccountBalanceService;
import com.financeos.module.asset.entity.Asset;
import com.financeos.module.asset.mapper.AssetMapper;
import com.financeos.module.investment.command.dto.FirstBuyRequest;
import com.financeos.module.investment.command.dto.InvestmentCommandResponse;
import com.financeos.module.investment.command.dto.InvestmentDividendRequest;
import com.financeos.module.investment.command.dto.InvestmentReversalRequest;
import com.financeos.module.investment.command.dto.InvestmentReversalResponse;
import com.financeos.module.investment.command.dto.InvestmentTradeRequest;
import com.financeos.module.investment.entity.InvestmentTransaction;
import com.financeos.module.investment.instrument.InstrumentStatus;
import com.financeos.module.investment.instrument.InvestmentAssetClass;
import com.financeos.module.investment.instrument.entity.InvestmentInstrument;
import com.financeos.module.investment.instrument.mapper.InvestmentInstrumentMapper;
import com.financeos.module.investment.ledger.InvestmentCalculationResult;
import com.financeos.module.investment.ledger.InvestmentLedgerCalculator;
import com.financeos.module.investment.ledger.InvestmentLedgerCommand;
import com.financeos.module.investment.ledger.InvestmentLedgerValidationException;
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
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HexFormat;

@Service
class InvestmentCommandTransactionalService {
    private static final Set<InvestmentAssetClass> BROKERAGE_CLASSES = Set.of(
            InvestmentAssetClass.STOCK, InvestmentAssetClass.ETF, InvestmentAssetClass.FUND, InvestmentAssetClass.BOND);

    private final AccountBalanceService accountBalanceService;
    private final InvestmentInstrumentMapper instrumentMapper;
    private final AssetMapper assetMapper;
    private final InvestmentTransactionMapper transactionMapper;
    private final InvestmentWriteConsistencyChecker consistencyChecker;
    private final InvestmentLedgerCalculator calculator = new InvestmentLedgerCalculator();
    private final InvestmentReplayEngine replayEngine;

    InvestmentCommandTransactionalService(AccountBalanceService accountBalanceService,
                                          InvestmentInstrumentMapper instrumentMapper,
                                          AssetMapper assetMapper,
                                          InvestmentTransactionMapper transactionMapper,
                                          InvestmentWriteConsistencyChecker consistencyChecker,
                                          InvestmentReplayEngine replayEngine) {
        this.accountBalanceService = accountBalanceService;
        this.instrumentMapper = instrumentMapper;
        this.assetMapper = assetMapper;
        this.transactionMapper = transactionMapper;
        this.consistencyChecker = consistencyChecker;
        this.replayEngine = replayEngine;
    }

    @Transactional
    InvestmentCommandResponse firstBuy(Long userId, String idempotencyKey, FirstBuyRequest request) {
        TradeAmounts amounts = amounts(request.trade());
        String key = idempotencyKey(idempotencyKey);
        String hash = requestHash("BUY", null, request.accountId(), request.instrumentId(), amounts);
        Account account = lockAccount(userId, request.accountId());
        InvestmentInstrument instrument = lockInstrument(userId, request.instrumentId());
        validateBinding(account, instrument, true);
        InvestmentTransaction existing = transactionMapper.findByUserIdAndIdempotencyKey(userId, key);
        if (existing != null) {
            verifyHash(existing, hash);
            return response(existing, instrument.getId(), true);
        }
        if (assetMapper.existsTransactionDrivenPosition(userId, account.getId(), instrument.getId())) {
            throw new BusinessException(409, "Transaction-driven position already exists");
        }
        Asset asset = createEmptyAsset(userId, account, instrument);
        return post(userId, account, instrument, asset, key, hash, amounts, InvestmentTransactionType.BUY);
    }

    @Transactional
    InvestmentCommandResponse trade(Long userId, Long assetId, String idempotencyKey, InvestmentTradeRequest request, String type) {
        if (assetId == null || assetId <= 0) {
            throw new BusinessException(400, "assetId must be positive");
        }
        TradeAmounts amounts = amounts(request);
        String key = idempotencyKey(idempotencyKey);
        Asset discovered = assetMapper.findByUserIdAndId(userId, assetId);
        if (discovered == null) {
            throw new BusinessException(404, "Asset not found");
        }
        String hash = requestHash(type, assetId, discovered.getAccountId(), discovered.getInstrumentId(), amounts);
        Account account = lockAccount(userId, discovered.getAccountId());
        InvestmentInstrument instrument = lockInstrument(userId, discovered.getInstrumentId());
        Asset asset = assetMapper.selectOwnedForUpdate(userId, assetId);
        if (asset == null || !"TRANSACTION_DRIVEN".equals(asset.getPositionMode())
                || !account.getId().equals(asset.getAccountId()) || !instrument.getId().equals(asset.getInstrumentId())) {
            throw new BusinessException(409, "Position changed while preparing the command");
        }
        InvestmentTransaction existing = transactionMapper.findByUserIdAndIdempotencyKey(userId, key);
        if (existing != null) {
            verifyHash(existing, hash);
            return response(existing, instrument.getId(), true);
        }
        InvestmentTransactionType transactionType = InvestmentTransactionType.valueOf(type);
        validateBinding(account, instrument, transactionType == InvestmentTransactionType.BUY);
        return post(userId, account, instrument, asset, key, hash, amounts, transactionType);
    }

    @Transactional
    InvestmentCommandResponse dividend(Long userId, Long assetId, String idempotencyKey, InvestmentDividendRequest request) {
        if (assetId == null || assetId <= 0) {
            throw new BusinessException(400, "assetId must be positive");
        }
        DividendAmounts amounts = dividendAmounts(request);
        String key = idempotencyKey(idempotencyKey);
        Asset discovered = assetMapper.findByUserIdAndId(userId, assetId);
        if (discovered == null) {
            throw new BusinessException(404, "Asset not found");
        }
        String hash = dividendRequestHash(userId, assetId, amounts);
        Account account = lockAccount(userId, discovered.getAccountId());
        InvestmentInstrument instrument = lockInstrument(userId, discovered.getInstrumentId());
        Asset asset = assetMapper.selectOwnedForUpdate(userId, assetId);
        if (asset == null || !"TRANSACTION_DRIVEN".equals(asset.getPositionMode())
                || !account.getId().equals(asset.getAccountId()) || !instrument.getId().equals(asset.getInstrumentId())) {
            throw new BusinessException(409, "Position changed while preparing the command");
        }
        InvestmentTransaction existing = transactionMapper.findByUserIdAndIdempotencyKey(userId, key);
        if (existing != null) {
            verifyHash(existing, hash);
            return response(existing, instrument.getId(), true);
        }
        validateBinding(account, instrument, false);
        return postDividend(userId, account, instrument, asset, key, hash, amounts);
    }

    @Transactional
    InvestmentReversalResponse reverse(Long userId, Long transactionId, String idempotencyKey, InvestmentReversalRequest request) {
        if (transactionId == null || transactionId <= 0) {
            throw new BusinessException(400, "transactionId must be positive");
        }
        String key = idempotencyKey(idempotencyKey);
        String reason = correctionReason(request);
        String hash = reversalRequestHash(userId, transactionId, reason);
        accountBalanceService.configureLockTimeoutForCurrentTransaction();
        InvestmentTransaction original = transactionMapper.findByUserIdAndIdForUpdate(userId, transactionId);
        if (original == null) {
            throw new BusinessException(404, "Investment transaction not found");
        }
        Asset discovered = assetMapper.findByUserIdAndId(userId, original.getAssetId());
        if (discovered == null || discovered.getInstrumentId() == null) {
            throw new BusinessException(409, "Position binding is invalid for reversal");
        }
        Account account = lockAccount(userId, original.getAccountId());
        InvestmentInstrument instrument = lockInstrument(userId, discovered.getInstrumentId());
        Asset asset = assetMapper.selectOwnedForUpdate(userId, original.getAssetId());
        if (asset == null || !"TRANSACTION_DRIVEN".equals(asset.getPositionMode())
                || !account.getId().equals(asset.getAccountId()) || !instrument.getId().equals(asset.getInstrumentId())) {
            throw new BusinessException(409, "Position changed while preparing the reversal");
        }
        InvestmentTransaction sameKey = transactionMapper.findByUserIdAndIdempotencyKey(userId, key);
        if (sameKey != null) {
            verifyHash(sameKey, hash);
            if (!"REVERSAL".equals(sameKey.getTransactionType()) || !original.getId().equals(sameKey.getOriginalTransactionId())) {
                throw new BusinessException(409, "Idempotency-Key was already used with a different request");
            }
            return reversalResponse(sameKey, instrument.getId(), asset, true);
        }
        validateReversibleOriginal(original);
        validateBinding(account, instrument, false);
        if (transactionMapper.findReversalByUserIdAndOriginalTransactionId(userId, original.getId()) != null) {
            throw new BusinessException(409, "Investment transaction was already reversed");
        }

        List<InvestmentReplayEntry> persistedEntries = entries(userId, asset.getId());
        try {
            replayEngine.replay(persistedEntries);
        } catch (InvestmentLedgerValidationException exception) {
            throw new IllegalStateException("Persisted investment history is invalid", exception);
        }
        InvestmentReplayResult candidateReplay;
        try {
            candidateReplay = replayEngine.replay(persistedEntries, Set.of(original.getId()));
        } catch (InvestmentLedgerValidationException exception) {
            throw new BusinessException(409, "Reversal would make investment history invalid");
        }

        BigDecimal cashDelta = reversalCashDelta(original);
        BigDecimal balanceAfter = account.getBalance().add(cashDelta).setScale(2);
        requireAccountBalanceRange(balanceAfter);
        int versionAfter = asset.getProjectionVersion() + 1;
        InvestmentTransaction reversal = reversalTransaction(userId, account.getId(), asset.getId(), key, hash, original,
                reason, cashDelta, candidateReplay.position(), balanceAfter, versionAfter);
        transactionMapper.insert(reversal);
        accountBalanceService.applyDeltas(
                accountBalanceService.lockOwnedAccounts(userId, List.of(account.getId())),
                List.of(new AccountBalanceMutation(account.getId(), cashDelta, false)));
        InvestmentReplayResult secondReplay;
        try {
            secondReplay = replayEngine.replay(entries(userId, asset.getId()));
        } catch (InvestmentLedgerValidationException exception) {
            throw new IllegalStateException("Persisted reversal replay failed", exception);
        }
        if (!samePosition(candidateReplay.position(), secondReplay.position())) {
            throw new IllegalStateException("Candidate and persisted reversal replays disagree");
        }
        InvestmentPositionState position = secondReplay.position();
        BigDecimal avgCost = averageCost(position);
        String status = position.quantity().signum() == 0 ? "CLOSED" : "OPEN";
        if (assetMapper.updateTransactionDrivenProjection(userId, asset.getId(), asset.getProjectionVersion(),
                position.quantity(), avgCost, position.totalCost(), position.cumulativeRealizedProfitLoss(),
                status, reversal.getId()) != 1) {
            throw new IllegalStateException("investment reversal projection update did not affect exactly one row");
        }
        Account updatedAccount = lockAccount(userId, account.getId());
        Asset updatedAsset = assetMapper.selectOwnedForUpdate(userId, asset.getId());
        InvestmentTransaction persistedReversal = transactionMapper.findByUserIdAndId(userId, reversal.getId());
        consistencyChecker.verifyReversal(original, persistedReversal, updatedAsset, updatedAccount.getBalance(),
                asset.getCurrentPrice(), asset.getMarketValue());
        return reversalResponse(persistedReversal, instrument.getId(), updatedAsset, false);
    }

    private InvestmentCommandResponse post(Long userId, Account account, InvestmentInstrument instrument, Asset asset,
                                            String key, String hash, TradeAmounts amounts,
                                            InvestmentTransactionType type) {
        InvestmentPositionState positionBeforeCommand = replayEngine.replay(entries(userId, asset.getId())).position();
        if (type == InvestmentTransactionType.SELL
                && (positionBeforeCommand.quantity().signum() == 0
                || amounts.quantity().compareTo(positionBeforeCommand.quantity()) > 0)) {
            throw new BusinessException(409, "Sell quantity exceeds current position");
        }
        InvestmentCalculationResult result;
        try {
            result = calculator.calculate(positionBeforeCommand, command(type, amounts));
        } catch (InvestmentLedgerValidationException exception) {
            throw new BusinessException(400, exception.getMessage());
        }
        BigDecimal balanceAfter = account.getBalance().add(result.cashDelta()).setScale(2);
        int versionAfter = asset.getProjectionVersion() + 1;
        InvestmentTransaction transaction = transaction(userId, account.getId(), asset.getId(), key, hash, type, amounts, result,
                balanceAfter, versionAfter);
        transactionMapper.insert(transaction);
        InvestmentReplayResult replay;
        try {
            replay = replayEngine.replay(entries(userId, asset.getId()));
        } catch (InvestmentLedgerValidationException exception) {
            throw new BusinessException(409, "Investment history changed concurrently");
        }
        InvestmentPositionState replayedPosition = replay.position();
        BigDecimal replayedAvgCost = replayedPosition.quantity().signum() == 0
                ? BigDecimal.ZERO.setScale(8)
                : replayedPosition.totalCost().divide(replayedPosition.quantity(), 8, RoundingMode.HALF_UP);
        Map<Long, BigDecimal> balanceUpdates = accountBalanceService.applyDeltas(
                accountBalanceService.lockOwnedAccounts(userId, List.of(account.getId())),
                List.of(new AccountBalanceMutation(account.getId(), result.cashDelta(), true)));
        String status = replayedPosition.quantity().signum() == 0 ? "CLOSED" : "OPEN";
        if (assetMapper.updateTransactionDrivenProjection(userId, asset.getId(), asset.getProjectionVersion(),
                replayedPosition.quantity(), replayedAvgCost, replayedPosition.totalCost(), replayedPosition.cumulativeRealizedProfitLoss(),
                status, transaction.getId()) != 1) {
            throw new IllegalStateException("investment projection update did not affect exactly one row");
        }
        Asset updated = assetMapper.selectOwnedForUpdate(userId, asset.getId());
        consistencyChecker.verify(transaction, updated, balanceUpdates.get(account.getId()));
        return response(transaction, instrument.getId(), false);
    }

    private InvestmentCommandResponse postDividend(Long userId, Account account, InvestmentInstrument instrument, Asset asset,
                                                    String key, String hash, DividendAmounts amounts) {
        List<InvestmentReplayEntry> existingEntries = entries(userId, asset.getId());
        InvestmentPositionState positionBeforeCommand;
        try {
            positionBeforeCommand = replayEngine.replay(existingEntries).position();
        } catch (InvestmentLedgerValidationException exception) {
            throw new BusinessException(409, "Dividend requires a posted buy or opening position history");
        }
        if (existingEntries.stream().noneMatch(entry -> entry.status() == InvestmentTransactionStatus.POSTED
                && (entry.command().transactionType() == InvestmentTransactionType.BUY
                || entry.command().transactionType() == InvestmentTransactionType.OPENING_POSITION))) {
            throw new BusinessException(409, "Dividend requires a posted buy or opening position history");
        }
        InvestmentCalculationResult result;
        try {
            result = calculator.calculate(positionBeforeCommand,
                    InvestmentLedgerCommand.dividend(amounts.grossAmount(), amounts.feeAmount(), amounts.taxAmount()));
        } catch (InvestmentLedgerValidationException exception) {
            throw new BusinessException(400, exception.getMessage());
        }
        BigDecimal balanceAfter = account.getBalance().add(result.cashDelta()).setScale(2);
        requireAccountBalanceRange(balanceAfter);
        int versionAfter = asset.getProjectionVersion() + 1;
        InvestmentTransaction transaction = dividendTransaction(userId, account.getId(), asset.getId(), key, hash, amounts, result,
                balanceAfter, versionAfter);
        transactionMapper.insert(transaction);
        accountBalanceService.applyDeltas(
                accountBalanceService.lockOwnedAccounts(userId, List.of(account.getId())),
                List.of(new AccountBalanceMutation(account.getId(), result.cashDelta(), false)));
        Account updatedAccount = lockAccount(userId, account.getId());
        InvestmentReplayResult replay;
        try {
            replay = replayEngine.replay(entries(userId, asset.getId()));
        } catch (InvestmentLedgerValidationException exception) {
            throw new BusinessException(409, "Dividend cannot be posted because investment history changed concurrently");
        }
        InvestmentPositionState replayedPosition = replay.position();
        BigDecimal replayedAvgCost = replayedPosition.quantity().signum() == 0
                ? BigDecimal.ZERO.setScale(8)
                : replayedPosition.totalCost().divide(replayedPosition.quantity(), 8, RoundingMode.HALF_UP);
        String status = replayedPosition.quantity().signum() == 0 ? "CLOSED" : "OPEN";
        if (assetMapper.updateTransactionDrivenProjection(userId, asset.getId(), asset.getProjectionVersion(),
                replayedPosition.quantity(), replayedAvgCost, replayedPosition.totalCost(), replayedPosition.cumulativeRealizedProfitLoss(),
                status, transaction.getId()) != 1) {
            throw new IllegalStateException("investment projection update did not affect exactly one row");
        }
        Asset updated = assetMapper.selectOwnedForUpdate(userId, asset.getId());
        InvestmentTransaction persistedTransaction = transactionMapper.findByUserIdAndId(userId, transaction.getId());
        consistencyChecker.verify(persistedTransaction, updated, updatedAccount.getBalance());
        return response(transaction, instrument.getId(), false);
    }

    private Account lockAccount(Long userId, Long accountId) {
        return accountBalanceService.lockOwnedAccounts(userId, List.of(accountId)).accounts().getFirst();
    }

    private void validateReversibleOriginal(InvestmentTransaction original) {
        InvestmentTransactionType type;
        try {
            type = InvestmentTransactionType.valueOf(original.getTransactionType());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(409, "Investment transaction type cannot be reversed");
        }
        if ((type != InvestmentTransactionType.BUY && type != InvestmentTransactionType.SELL && type != InvestmentTransactionType.DIVIDEND)
                || !InvestmentTransactionStatus.POSTED.name().equals(original.getStatus())
                || original.getOriginalTransactionId() != null || original.getReplacesTransactionId() != null
                || original.getReversedAt() != null || original.getReversalReason() != null) {
            throw new BusinessException(409, "Investment transaction cannot be reversed");
        }
    }

    private InvestmentInstrument lockInstrument(Long userId, Long instrumentId) {
        InvestmentInstrument instrument = instrumentMapper.findByUserIdAndIdForUpdate(userId, instrumentId);
        if (instrument == null) {
            throw new BusinessException(404, "Investment instrument not found");
        }
        return instrument;
    }

    private void validateBinding(Account account, InvestmentInstrument instrument, boolean newBuy) {
        if (!"CNY".equals(account.getCurrency())) {
            throw new BusinessException(400, "Investment accounts must use CNY in the current version");
        }
        if (!supported(account.getType(), instrument.getAssetClass())) {
            throw new BusinessException(400, "Account type does not support the investment asset class");
        }
        if (!"ACTIVE".equals(account.getStatus()) && !"INACTIVE".equals(account.getStatus())) {
            throw new BusinessException(409, "Unknown account status cannot accept investment commands");
        }
        if (instrument.getStatus() != InstrumentStatus.ACTIVE && instrument.getStatus() != InstrumentStatus.INACTIVE) {
            throw new BusinessException(409, "Unknown investment instrument status cannot accept investment commands");
        }
        if (newBuy && (!"ACTIVE".equals(account.getStatus()) || instrument.getStatus() != InstrumentStatus.ACTIVE)) {
            throw new BusinessException(409, "Inactive account or investment instrument cannot accept a buy");
        }
    }

    private boolean supported(String accountType, InvestmentAssetClass assetClass) {
        if (accountType == null || assetClass == null) return false;
        return switch (accountType.trim().toUpperCase(Locale.ROOT)) {
            case "BROKERAGE" -> BROKERAGE_CLASSES.contains(assetClass);
            case "CRYPTO_WALLET" -> assetClass == InvestmentAssetClass.CRYPTO;
            default -> false;
        };
    }

    private Asset createEmptyAsset(Long userId, Account account, InvestmentInstrument instrument) {
        Asset asset = new Asset();
        asset.setUserId(userId);
        asset.setAccountId(account.getId());
        asset.setInstrumentId(instrument.getId());
        asset.setName(instrument.getName());
        asset.setSymbol(instrument.getSymbol());
        asset.setType(instrument.getAssetClass().name());
        asset.setMarket(instrument.getMarket());
        asset.setCurrency("CNY");
        asset.setQuantity(BigDecimal.ZERO.setScale(8));
        asset.setAvgCost(BigDecimal.ZERO.setScale(8));
        asset.setTotalCost(BigDecimal.ZERO.setScale(2));
        asset.setRealizedProfitLoss(BigDecimal.ZERO.setScale(2));
        asset.setPositionStatus("CLOSED");
        asset.setPositionMode("TRANSACTION_DRIVEN");
        asset.setProjectionVersion(0);
        assetMapper.insert(asset);
        return asset;
    }

    private InvestmentPositionState position(Asset asset) {
        return new InvestmentPositionState(asset.getQuantity(), asset.getTotalCost(), asset.getRealizedProfitLoss());
    }

    private InvestmentLedgerCommand command(InvestmentTransactionType type, TradeAmounts amounts) {
        return type == InvestmentTransactionType.BUY
                ? InvestmentLedgerCommand.buy(amounts.quantity(), amounts.unitPrice(), amounts.feeAmount(), amounts.taxAmount())
                : InvestmentLedgerCommand.sell(amounts.quantity(), amounts.unitPrice(), amounts.feeAmount(), amounts.taxAmount());
    }

    private List<InvestmentReplayEntry> entries(Long userId, Long assetId) {
        return transactionMapper.selectAllByUserIdAndAssetId(userId, assetId).stream()
                .map(transaction -> new InvestmentReplayEntry(transaction.getId(), transaction.getTradeTime(),
                        InvestmentTransactionStatus.valueOf(transaction.getStatus()), switch (InvestmentTransactionType.valueOf(transaction.getTransactionType())) {
                    case BUY -> InvestmentLedgerCommand.buy(transaction.getQuantity(), transaction.getUnitPrice(), transaction.getFeeAmount(), transaction.getTaxAmount());
                    case SELL -> InvestmentLedgerCommand.sell(transaction.getQuantity(), transaction.getUnitPrice(), transaction.getFeeAmount(), transaction.getTaxAmount());
                    case DIVIDEND -> InvestmentLedgerCommand.dividend(transaction.getGrossAmount(), transaction.getFeeAmount(), transaction.getTaxAmount());
                    case OPENING_POSITION -> InvestmentLedgerCommand.openingPosition(transaction.getQuantity(), transaction.getUnitPrice());
                    case REVERSAL -> InvestmentLedgerCommand.reversal();
                }, transaction.getOriginalTransactionId(), transaction.getReplayAnchorTransactionId(),
                        transaction.getReplaySequence() == null ? 0 : transaction.getReplaySequence()))
                .toList();
    }

    private InvestmentTransaction reversalTransaction(Long userId, Long accountId, Long assetId, String key, String hash,
                                                      InvestmentTransaction original, String reason, BigDecimal cashDelta,
                                                      InvestmentPositionState position, BigDecimal balanceAfter, int versionAfter) {
        Instant now = Instant.now();
        InvestmentTransaction reversal = new InvestmentTransaction();
        reversal.setUserId(userId);
        reversal.setAccountId(accountId);
        reversal.setAssetId(assetId);
        reversal.setTransactionType(InvestmentTransactionType.REVERSAL.name());
        reversal.setStatus(InvestmentTransactionStatus.POSTED.name());
        reversal.setQuantity(original.getQuantity());
        reversal.setUnitPrice(original.getUnitPrice());
        reversal.setGrossAmount(original.getGrossAmount());
        reversal.setFeeAmount(original.getFeeAmount());
        reversal.setTaxAmount(original.getTaxAmount());
        reversal.setNetAmount(original.getNetAmount());
        reversal.setReleasedCostAmount(BigDecimal.ZERO.setScale(2));
        reversal.setRealizedProfitLoss(BigDecimal.ZERO.setScale(2));
        reversal.setCurrency(original.getCurrency());
        reversal.setTradeTime(now);
        reversal.setSettlementTime(now);
        reversal.setSource("CORRECTION");
        reversal.setIdempotencyKey(key);
        reversal.setRequestHash(hash);
        reversal.setOriginalTransactionId(original.getId());
        reversal.setCorrectionReason(reason);
        reversal.setCashDelta(cashDelta);
        reversal.setAccountBalanceAfter(balanceAfter);
        reversal.setPositionQuantityAfter(position.quantity());
        reversal.setPositionAvgCostAfter(averageCost(position));
        reversal.setPositionTotalCostAfter(position.totalCost());
        reversal.setPositionRealizedProfitLossAfter(position.cumulativeRealizedProfitLoss());
        reversal.setPositionStatusAfter(position.quantity().signum() == 0 ? "CLOSED" : "OPEN");
        reversal.setProjectionVersionAfter(versionAfter);
        reversal.setCreatedAt(now);
        return reversal;
    }

    private InvestmentReversalResponse reversalResponse(InvestmentTransaction reversal, Long instrumentId, Asset asset,
                                                         boolean idempotentReplay) {
        return new InvestmentReversalResponse("REVERSED", reversal.getOriginalTransactionId(), reversal.getId(), null,
                reversal.getAssetId(), reversal.getAccountId(), instrumentId, decimal(reversal.getCashDelta()),
                decimal(reversal.getAccountBalanceAfter()), decimal(reversal.getPositionQuantityAfter()),
                decimal(reversal.getPositionAvgCostAfter()), decimal(reversal.getPositionTotalCostAfter()),
                decimal(reversal.getPositionRealizedProfitLossAfter()), reversal.getPositionStatusAfter(),
                reversal.getProjectionVersionAfter(), reversal.getId(), decimal(asset.getCurrentPrice()),
                decimal(asset.getMarketValue()), idempotentReplay, reversal.getCreatedAt());
    }

    private String correctionReason(InvestmentReversalRequest request) {
        String reason = request == null ? null : request.reason();
        if (reason == null || reason.isBlank() || reason.length() > 500 || !reason.equals(reason.trim())) {
            throw new BusinessException(400, "Reversal reason must be 1 to 500 non-blank characters without surrounding spaces");
        }
        return reason;
    }

    private BigDecimal reversalCashDelta(InvestmentTransaction original) {
        return "BUY".equals(original.getTransactionType()) ? original.getNetAmount() : original.getNetAmount().negate();
    }

    private boolean samePosition(InvestmentPositionState left, InvestmentPositionState right) {
        return left.quantity().compareTo(right.quantity()) == 0
                && left.totalCost().compareTo(right.totalCost()) == 0
                && left.cumulativeRealizedProfitLoss().compareTo(right.cumulativeRealizedProfitLoss()) == 0;
    }

    private BigDecimal averageCost(InvestmentPositionState position) {
        return position.quantity().signum() == 0 ? BigDecimal.ZERO.setScale(8)
                : position.totalCost().divide(position.quantity(), 8, RoundingMode.HALF_UP);
    }

    private InvestmentTransaction transaction(Long userId, Long accountId, Long assetId, String key, String hash,
                                              InvestmentTransactionType type, TradeAmounts amounts,
                                              InvestmentCalculationResult result, BigDecimal balanceAfter, int versionAfter) {
        Instant now = Instant.now();
        InvestmentTransaction transaction = new InvestmentTransaction();
        transaction.setUserId(userId);
        transaction.setAccountId(accountId);
        transaction.setAssetId(assetId);
        transaction.setTransactionType(type.name());
        transaction.setStatus(InvestmentTransactionStatus.POSTED.name());
        transaction.setQuantity(amounts.quantity());
        transaction.setUnitPrice(amounts.unitPrice());
        transaction.setGrossAmount(result.grossAmount());
        transaction.setFeeAmount(amounts.feeAmount());
        transaction.setTaxAmount(amounts.taxAmount());
        transaction.setNetAmount(result.netAmount());
        transaction.setReleasedCostAmount(result.releasedCostAmount());
        transaction.setRealizedProfitLoss(result.realizedProfitLoss());
        transaction.setCurrency("CNY");
        transaction.setTradeTime(now);
        transaction.setSettlementTime(now);
        transaction.setSource("MANUAL");
        transaction.setIdempotencyKey(key);
        transaction.setRequestHash(hash);
        transaction.setAccountBalanceAfter(balanceAfter);
        transaction.setPositionQuantityAfter(result.newQuantity());
        transaction.setPositionAvgCostAfter(result.newAvgCost());
        transaction.setPositionTotalCostAfter(result.newTotalCost());
        transaction.setPositionRealizedProfitLossAfter(result.newCumulativeRealizedProfitLoss());
        transaction.setPositionStatusAfter(result.newQuantity().signum() == 0 ? "CLOSED" : "OPEN");
        transaction.setProjectionVersionAfter(versionAfter);
        transaction.setCreatedAt(now);
        return transaction;
    }

    private InvestmentTransaction dividendTransaction(Long userId, Long accountId, Long assetId, String key, String hash,
                                                      DividendAmounts amounts, InvestmentCalculationResult result,
                                                      BigDecimal balanceAfter, int versionAfter) {
        Instant now = Instant.now();
        InvestmentTransaction transaction = new InvestmentTransaction();
        transaction.setUserId(userId);
        transaction.setAccountId(accountId);
        transaction.setAssetId(assetId);
        transaction.setTransactionType(InvestmentTransactionType.DIVIDEND.name());
        transaction.setStatus(InvestmentTransactionStatus.POSTED.name());
        transaction.setGrossAmount(result.grossAmount());
        transaction.setFeeAmount(amounts.feeAmount());
        transaction.setTaxAmount(amounts.taxAmount());
        transaction.setNetAmount(result.netAmount());
        transaction.setReleasedCostAmount(result.releasedCostAmount());
        transaction.setRealizedProfitLoss(result.realizedProfitLoss());
        transaction.setCurrency("CNY");
        transaction.setTradeTime(now);
        transaction.setSettlementTime(now);
        transaction.setExternalReference(amounts.externalReference());
        transaction.setNote(amounts.note());
        transaction.setSource("MANUAL");
        transaction.setIdempotencyKey(key);
        transaction.setRequestHash(hash);
        transaction.setAccountBalanceAfter(balanceAfter);
        transaction.setPositionQuantityAfter(result.newQuantity());
        transaction.setPositionAvgCostAfter(result.newAvgCost());
        transaction.setPositionTotalCostAfter(result.newTotalCost());
        transaction.setPositionRealizedProfitLossAfter(result.newCumulativeRealizedProfitLoss());
        transaction.setPositionStatusAfter(result.newQuantity().signum() == 0 ? "CLOSED" : "OPEN");
        transaction.setProjectionVersionAfter(versionAfter);
        transaction.setCreatedAt(now);
        return transaction;
    }

    static InvestmentCommandResponse response(InvestmentTransaction transaction, Long instrumentId, boolean idempotentReplay) {
        return new InvestmentCommandResponse(transaction.getId(), transaction.getAssetId(), transaction.getAccountId(), instrumentId,
                transaction.getTransactionType(), transaction.getTradeTime(), transaction.getSettlementTime(),
                decimal(transaction.getGrossAmount()), decimal(transaction.getFeeAmount()), decimal(transaction.getTaxAmount()), decimal(transaction.getNetAmount()),
                decimal(transaction.getTransactionType().equals("BUY") ? transaction.getNetAmount().negate() : transaction.getNetAmount()),
                decimal(transaction.getAccountBalanceAfter()), idempotentReplay, transaction.getCreatedAt(), new InvestmentCommandResponse.FinalPosition(
                decimal(transaction.getPositionQuantityAfter()), decimal(transaction.getPositionAvgCostAfter()),
                decimal(transaction.getPositionTotalCostAfter()), decimal(transaction.getPositionRealizedProfitLossAfter()),
                transaction.getPositionStatusAfter(), transaction.getProjectionVersionAfter(), transaction.getId()));
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private TradeAmounts amounts(InvestmentTradeRequest request) {
        try {
            TradeAmounts amounts = new TradeAmounts(new BigDecimal(request.quantity()), new BigDecimal(request.unitPrice()),
                    new BigDecimal(request.feeAmount()), new BigDecimal(request.taxAmount()));
            validateScale(amounts.quantity(), 8, "quantity");
            validateScale(amounts.unitPrice(), 8, "unitPrice");
            validateScale(amounts.feeAmount(), 2, "feeAmount");
            validateScale(amounts.taxAmount(), 2, "taxAmount");
            return amounts;
        } catch (RuntimeException exception) {
            if (exception instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException(400, "Amounts must be decimal strings");
        }
    }

    private DividendAmounts dividendAmounts(InvestmentDividendRequest request) {
        try {
            if (request == null || request.grossAmount() == null || request.grossAmount().isBlank()
                    || request.feeAmount() == null || request.taxAmount() == null
                    || (request.externalReference() != null && request.externalReference().length() > 100)
                    || (request.note() != null && request.note().length() > 500)) {
                throw new BusinessException(400, "Dividend request fields are invalid");
            }
            DividendAmounts amounts = new DividendAmounts(new BigDecimal(request.grossAmount()), new BigDecimal(request.feeAmount()),
                    new BigDecimal(request.taxAmount()), request.externalReference(), request.note());
            validateScale(amounts.grossAmount(), 2, "grossAmount");
            validateScale(amounts.feeAmount(), 2, "feeAmount");
            validateScale(amounts.taxAmount(), 2, "taxAmount");
            validatePrecision(amounts.grossAmount(), "grossAmount");
            validatePrecision(amounts.feeAmount(), "feeAmount");
            validatePrecision(amounts.taxAmount(), "taxAmount");
            return amounts;
        } catch (RuntimeException exception) {
            if (exception instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException(400, "Amounts must be decimal strings");
        }
    }

    private void validateScale(BigDecimal value, int scale, String name) {
        if (value.scale() > scale) {
            throw new BusinessException(400, name + " scale must not exceed " + scale);
        }
    }

    private void validatePrecision(BigDecimal value, String name) {
        if (value.precision() > 28) {
            throw new BusinessException(400, name + " precision must not exceed 28");
        }
    }

    private void requireAccountBalanceRange(BigDecimal value) {
        if (value.precision() > 18) {
            throw new BusinessException(400, "Account balance exceeds NUMERIC(18,2) range");
        }
    }

    private String idempotencyKey(String value) {
        if (value == null || value.isBlank() || value.length() > 100 || !value.equals(value.trim())) {
            throw new BusinessException(400, "Idempotency-Key must be 1 to 100 non-blank characters without surrounding spaces");
        }
        return value;
    }

    private void verifyHash(InvestmentTransaction existing, String hash) {
        if (!hash.equals(existing.getRequestHash())) {
            throw new BusinessException(409, "Idempotency-Key was already used with a different request");
        }
    }

    private String requestHash(String type, Long assetId, Long accountId, Long instrumentId, TradeAmounts amounts) {
        String canonical = String.join("|", type, String.valueOf(assetId), String.valueOf(accountId), String.valueOf(instrumentId),
                amounts.quantity().setScale(8).toPlainString(), amounts.unitPrice().setScale(8).toPlainString(),
                amounts.feeAmount().setScale(2).toPlainString(), amounts.taxAmount().setScale(2).toPlainString());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte item : digest) hex.append(String.format("%02x", item));
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String dividendRequestHash(Long userId, Long assetId, DividendAmounts amounts) {
        String canonical = String.join("\n",
                "operation=DIVIDEND",
                "formulaVersion=INVESTMENT_DIVIDEND_V1",
                "userId=" + userId,
                "assetId=" + assetId,
                "grossAmount=" + amounts.grossAmount().setScale(2).toPlainString(),
                "feeAmount=" + amounts.feeAmount().setScale(2).toPlainString(),
                "taxAmount=" + amounts.taxAmount().setScale(2).toPlainString(),
                "currency=CNY",
                "tradeTimePolicy=SERVER_POST_TIME_V1",
                "externalReference=" + canonicalOptional(amounts.externalReference()),
                "note=" + canonicalOptional(amounts.note()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String reversalRequestHash(Long userId, Long originalTransactionId, String reason) {
        String canonical = String.join("\n",
                "operation=REVERSAL",
                "formulaVersion=INVESTMENT_REVERSAL_V1",
                "userId=" + userId,
                "originalTransactionId=" + originalTransactionId,
                "reason=" + canonicalOptional(reason),
                "cashPolicy=NEGATE_ORIGINAL_EFFECTIVE_CASH_V1",
                "replayPolicy=EXCLUDE_ORIGINAL_V1");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String canonicalOptional(String value) {
        return value == null ? "NULL" : "VALUE:" + value.getBytes(StandardCharsets.UTF_8).length + ":" + value;
    }

    private record TradeAmounts(BigDecimal quantity, BigDecimal unitPrice, BigDecimal feeAmount, BigDecimal taxAmount) {
    }

    private record DividendAmounts(BigDecimal grossAmount, BigDecimal feeAmount, BigDecimal taxAmount,
                                   String externalReference, String note) {
    }
}
