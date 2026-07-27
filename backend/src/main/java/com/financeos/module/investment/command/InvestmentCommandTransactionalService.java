package com.financeos.module.investment.command;

import com.financeos.common.BusinessException;
import com.financeos.module.account.entity.Account;
import com.financeos.module.account.service.AccountBalanceMutation;
import com.financeos.module.account.service.AccountBalanceService;
import com.financeos.module.asset.entity.Asset;
import com.financeos.module.asset.mapper.AssetMapper;
import com.financeos.module.investment.command.dto.FirstBuyRequest;
import com.financeos.module.investment.command.dto.InvestmentCommandResponse;
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
    private final InvestmentReplayEngine replayEngine = new InvestmentReplayEngine();

    InvestmentCommandTransactionalService(AccountBalanceService accountBalanceService,
                                          InvestmentInstrumentMapper instrumentMapper,
                                          AssetMapper assetMapper,
                                          InvestmentTransactionMapper transactionMapper,
                                          InvestmentWriteConsistencyChecker consistencyChecker) {
        this.accountBalanceService = accountBalanceService;
        this.instrumentMapper = instrumentMapper;
        this.assetMapper = assetMapper;
        this.transactionMapper = transactionMapper;
        this.consistencyChecker = consistencyChecker;
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
        InvestmentReplayResult replay = replayEngine.replay(entries(userId, asset.getId()));
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

    private Account lockAccount(Long userId, Long accountId) {
        return accountBalanceService.lockOwnedAccounts(userId, List.of(accountId)).accounts().getFirst();
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
                }))
                .toList();
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
        return transaction;
    }

    static InvestmentCommandResponse response(InvestmentTransaction transaction, Long instrumentId, boolean idempotentReplay) {
        return new InvestmentCommandResponse(transaction.getId(), transaction.getAssetId(), transaction.getAccountId(), instrumentId,
                transaction.getTransactionType(), decimal(transaction.getGrossAmount()), decimal(transaction.getNetAmount()),
                decimal(transaction.getTransactionType().equals("BUY") ? transaction.getNetAmount().negate() : transaction.getNetAmount()),
                decimal(transaction.getAccountBalanceAfter()), idempotentReplay, new InvestmentCommandResponse.FinalPosition(
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

    private void validateScale(BigDecimal value, int scale, String name) {
        if (value.scale() > scale) {
            throw new BusinessException(400, name + " scale must not exceed " + scale);
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

    private record TradeAmounts(BigDecimal quantity, BigDecimal unitPrice, BigDecimal feeAmount, BigDecimal taxAmount) {
    }
}
