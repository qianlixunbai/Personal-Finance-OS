package com.financeos.module.investment.command;

import com.financeos.common.BusinessException;
import com.financeos.module.account.entity.Account;
import com.financeos.module.account.service.AccountBalanceMutation;
import com.financeos.module.account.service.AccountBalanceService;
import com.financeos.module.asset.entity.Asset;
import com.financeos.module.asset.mapper.AssetMapper;
import com.financeos.module.investment.command.dto.BuyReplacementRequest;
import com.financeos.module.investment.command.dto.DividendReplacementRequest;
import com.financeos.module.investment.command.dto.InvestmentReplacementRequest;
import com.financeos.module.investment.command.dto.InvestmentReplacementResponse;
import com.financeos.module.investment.command.dto.SellReplacementRequest;
import com.financeos.module.investment.entity.InvestmentTransaction;
import com.financeos.module.investment.entity.InvestmentTransactionCorrection;
import com.financeos.module.investment.instrument.entity.InvestmentInstrument;
import com.financeos.module.investment.instrument.mapper.InvestmentInstrumentMapper;
import com.financeos.module.investment.ledger.*;
import com.financeos.module.investment.mapper.InvestmentTransactionCorrectionMapper;
import com.financeos.module.investment.mapper.InvestmentTransactionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
class InvestmentReplacementTransactionalService {
    private final AccountBalanceService accountBalanceService;
    private final InvestmentInstrumentMapper instrumentMapper;
    private final AssetMapper assetMapper;
    private final InvestmentTransactionMapper transactionMapper;
    private final InvestmentTransactionCorrectionMapper correctionMapper;
    private final InvestmentReplayEngine replayEngine;
    private final InvestmentReplacementConsistencyChecker consistencyChecker;

    InvestmentReplacementTransactionalService(AccountBalanceService accountBalanceService,
                                              InvestmentInstrumentMapper instrumentMapper, AssetMapper assetMapper,
                                              InvestmentTransactionMapper transactionMapper,
                                              InvestmentTransactionCorrectionMapper correctionMapper,
                                              InvestmentReplayEngine replayEngine,
                                              InvestmentReplacementConsistencyChecker consistencyChecker) {
        this.accountBalanceService = accountBalanceService;
        this.instrumentMapper = instrumentMapper;
        this.assetMapper = assetMapper;
        this.transactionMapper = transactionMapper;
        this.correctionMapper = correctionMapper;
        this.replayEngine = replayEngine;
        this.consistencyChecker = consistencyChecker;
    }

    @Transactional
    InvestmentReplacementResponse replace(Long userId, Long transactionId, String idempotencyKey,
                                          InvestmentReplacementRequest request) {
        if (transactionId == null || transactionId <= 0) throw new BusinessException(400, "transactionId must be positive");
        String key = idempotencyKey(idempotencyKey);
        accountBalanceService.configureLockTimeoutForCurrentTransaction();
        InvestmentTransaction original = transactionMapper.findByUserIdAndIdForUpdate(userId, transactionId);
        if (original == null) throw new BusinessException(404, "Investment transaction not found");
        ReplacementAmounts amounts = amounts(original.getTransactionType(), request);
        String hash = requestHash(userId, original, amounts);

        Asset discovered = assetMapper.findByUserIdAndId(userId, original.getAssetId());
        if (discovered == null || discovered.getInstrumentId() == null) throw new BusinessException(409, "Position binding is invalid for replacement");
        Account account = accountBalanceService.lockOwnedAccounts(userId, List.of(original.getAccountId())).accounts().getFirst();
        InvestmentInstrument instrument = instrumentMapper.findByUserIdAndIdForUpdate(userId, discovered.getInstrumentId());
        if (instrument == null) throw new BusinessException(404, "Investment instrument not found");
        Asset asset = assetMapper.selectOwnedForUpdate(userId, original.getAssetId());
        if (asset == null || !"TRANSACTION_DRIVEN".equals(asset.getPositionMode())
                || !account.getId().equals(asset.getAccountId()) || !instrument.getId().equals(asset.getInstrumentId())) {
            throw new BusinessException(409, "Position changed while preparing the replacement");
        }
        InvestmentTransactionCorrection sameKey = correctionMapper.findByUserIdAndIdempotencyKeyForUpdate(userId, key);
        if (sameKey != null) {
            if (!hash.equals(sameKey.getRequestHash()) || !original.getId().equals(sameKey.getOriginalTransactionId()))
                throw new BusinessException(409, "Idempotency-Key was already used with a different request");
            return response(sameKey, true);
        }
        validateOriginal(original);
        if (correctionMapper.findByUserIdAndOriginalTransactionId(userId, original.getId()) != null
                || transactionMapper.findReversalByUserIdAndOriginalTransactionId(userId, original.getId()) != null) {
            throw new BusinessException(409, "Investment transaction was already corrected");
        }

        List<InvestmentReplayEntry> persisted = entries(userId, asset.getId());
        try { replayEngine.replay(persisted); } catch (InvestmentLedgerValidationException e) { throw new IllegalStateException("Persisted investment history is invalid", e); }
        UUID groupId = UUID.randomUUID();
        InvestmentLedgerCommand replacementCommand = command(original.getTransactionType(), amounts);
        List<InvestmentReplayEntry> candidateEntries = new ArrayList<>(persisted);
        candidateEntries.add(new InvestmentReplayEntry(Long.MAX_VALUE, original.getTradeTime(), InvestmentTransactionStatus.POSTED,
                replacementCommand, null, original.getId(), (short) 1));
        InvestmentReplayResult candidate;
        try { candidate = replayEngine.replay(candidateEntries, Set.of(original.getId())); }
        catch (InvestmentLedgerValidationException e) { throw new BusinessException(409, "Replacement would make investment history invalid"); }
        InvestmentCalculationResult replacementCalculation = candidate.trace().steps().stream()
                .filter(step -> step.logicalFactIdentity() == original.getId() && step.replaySequence() == 1)
                .findFirst().orElseThrow(() -> new IllegalStateException("Replacement replay step is missing")).calculation();
        BigDecimal reversalDelta = "BUY".equals(original.getTransactionType()) ? original.getNetAmount() : original.getNetAmount().negate();
        BigDecimal replacementDelta = replacementCalculation.cashDelta();
        BigDecimal commandDelta = reversalDelta.add(replacementDelta).setScale(2);
        BigDecimal balanceAfter = account.getBalance().add(commandDelta).setScale(2);
        if (balanceAfter.precision() > 18) throw new BusinessException(400, "Account balance exceeds NUMERIC(18,2) range");
        int versionAfter = asset.getProjectionVersion() + 1;

        InvestmentTransaction reversal = groupedReversal(userId, account.getId(), asset.getId(), original, groupId, amounts.reason(), reversalDelta);
        transactionMapper.insert(reversal);
        InvestmentTransaction replacement = replacementFact(userId, account.getId(), asset.getId(), original, groupId, amounts,
                replacementCalculation, candidate.position(), balanceAfter, versionAfter);
        transactionMapper.insert(replacement);
        accountBalanceService.applyDeltas(accountBalanceService.lockOwnedAccounts(userId, List.of(account.getId())),
                List.of(new AccountBalanceMutation(account.getId(), commandDelta, false)));
        InvestmentReplayResult second;
        try { second = replayEngine.replay(entries(userId, asset.getId())); }
        catch (InvestmentLedgerValidationException e) { throw new IllegalStateException("Persisted replacement replay failed", e); }
        if (!candidate.trace().equals(second.trace())) throw new IllegalStateException("Candidate and persisted replacement traces disagree");
        InvestmentPositionState position = second.position();
        BigDecimal avgCost = position.quantity().signum() == 0 ? BigDecimal.ZERO.setScale(8)
                : position.totalCost().divide(position.quantity(), 8, RoundingMode.HALF_UP);
        if (assetMapper.updateTransactionDrivenProjection(userId, asset.getId(), asset.getProjectionVersion(), position.quantity(), avgCost,
                position.totalCost(), position.cumulativeRealizedProfitLoss(), position.quantity().signum() == 0 ? "CLOSED" : "OPEN", replacement.getId()) != 1)
            throw new IllegalStateException("replacement projection update did not affect exactly one row");
        InvestmentTransactionCorrection correction = correction(userId, account, asset, instrument, original, groupId, key, hash, amounts,
                reversal, replacement, reversalDelta, replacementDelta, commandDelta, balanceAfter, position, versionAfter);
        correctionMapper.insert(correction);
        consistencyChecker.verify(original, reversal, replacement, correction, asset, balanceAfter, versionAfter, candidate, second);
        return response(correction, false);
    }

    private void validateOriginal(InvestmentTransaction original) {
        if (!("BUY".equals(original.getTransactionType()) || "SELL".equals(original.getTransactionType()) || "DIVIDEND".equals(original.getTransactionType()))
                || !"POSTED".equals(original.getStatus()) || original.getCorrectionGroupId() != null || original.getOriginalTransactionId() != null)
            throw new BusinessException(409, "Investment transaction cannot be replaced");
    }

    private InvestmentTransaction groupedReversal(Long userId, Long accountId, Long assetId, InvestmentTransaction original, UUID groupId, String reason, BigDecimal cashDelta) {
        InvestmentTransaction result = new InvestmentTransaction();
        result.setUserId(userId); result.setAccountId(accountId); result.setAssetId(assetId); result.setTransactionType("REVERSAL"); result.setStatus("POSTED");
        result.setQuantity(original.getQuantity()); result.setUnitPrice(original.getUnitPrice()); result.setGrossAmount(original.getGrossAmount()); result.setFeeAmount(original.getFeeAmount()); result.setTaxAmount(original.getTaxAmount()); result.setNetAmount(original.getNetAmount());
        result.setReleasedCostAmount(BigDecimal.ZERO.setScale(2)); result.setRealizedProfitLoss(BigDecimal.ZERO.setScale(2)); result.setCurrency(original.getCurrency());
        result.setTradeTime(Instant.now()); result.setSettlementTime(Instant.now()); result.setSource("CORRECTION"); result.setIdempotencyKey("corr:" + groupId + ":reversal"); result.setRequestHash(hash("reversal:" + groupId));
        result.setOriginalTransactionId(original.getId()); result.setCorrectionReason(reason); result.setCashDelta(cashDelta); result.setCorrectionGroupId(groupId); result.setReplaySequence((short) 0); result.setCreatedAt(Instant.now());
        return result;
    }

    private InvestmentTransaction replacementFact(Long userId, Long accountId, Long assetId, InvestmentTransaction original, UUID groupId,
                                                   ReplacementAmounts amounts, InvestmentCalculationResult calculation, InvestmentPositionState finalPosition,
                                                   BigDecimal balanceAfter, int versionAfter) {
        InvestmentTransaction result = new InvestmentTransaction();
        result.setUserId(userId); result.setAccountId(accountId); result.setAssetId(assetId); result.setTransactionType(original.getTransactionType()); result.setStatus("POSTED");
        result.setQuantity(amounts.quantity()); result.setUnitPrice(amounts.unitPrice()); result.setGrossAmount(calculation.grossAmount()); result.setFeeAmount(amounts.feeAmount()); result.setTaxAmount(amounts.taxAmount()); result.setNetAmount(calculation.netAmount());
        result.setReleasedCostAmount(calculation.releasedCostAmount()); result.setRealizedProfitLoss(calculation.realizedProfitLoss()); result.setCurrency(original.getCurrency()); result.setTradeTime(original.getTradeTime()); result.setSettlementTime(original.getSettlementTime());
        result.setExternalReference(amounts.externalReference()); result.setNote(amounts.note()); result.setSource("CORRECTION"); result.setIdempotencyKey("corr:" + groupId + ":replacement"); result.setRequestHash(hash("replacement:" + groupId)); result.setCashDelta(calculation.cashDelta());
        BigDecimal finalAvgCost = finalPosition.quantity().signum() == 0 ? BigDecimal.ZERO.setScale(8)
                : finalPosition.totalCost().divide(finalPosition.quantity(), 8, RoundingMode.HALF_UP);
        result.setCorrectionGroupId(groupId); result.setReplayAnchorTransactionId(original.getId()); result.setReplaySequence((short) 1); result.setAccountBalanceAfter(balanceAfter); result.setPositionQuantityAfter(finalPosition.quantity()); result.setPositionAvgCostAfter(finalAvgCost); result.setPositionTotalCostAfter(finalPosition.totalCost()); result.setPositionRealizedProfitLossAfter(finalPosition.cumulativeRealizedProfitLoss()); result.setPositionStatusAfter(finalPosition.quantity().signum() == 0 ? "CLOSED" : "OPEN"); result.setProjectionVersionAfter(versionAfter); result.setCreatedAt(Instant.now());
        return result;
    }

    private InvestmentTransactionCorrection correction(Long userId, Account account, Asset asset, InvestmentInstrument instrument, InvestmentTransaction original, UUID groupId, String key, String hash, ReplacementAmounts amounts, InvestmentTransaction reversal, InvestmentTransaction replacement, BigDecimal reversalDelta, BigDecimal replacementDelta, BigDecimal commandDelta, BigDecimal balanceAfter, InvestmentPositionState position, int versionAfter) {
        InvestmentTransactionCorrection value = new InvestmentTransactionCorrection();
        value.setCorrectionGroupId(groupId); value.setUserId(userId); value.setAccountId(account.getId()); value.setAssetId(asset.getId()); value.setInstrumentId(instrument.getId()); value.setOriginalTransactionId(original.getId()); value.setTransactionType(original.getTransactionType()); value.setCorrectionKind("REPLACEMENT"); value.setIdempotencyKey(key); value.setRequestHash(hash); value.setCorrectionReason(amounts.reason()); value.setReversalTransactionId(reversal.getId()); value.setReplacementTransactionId(replacement.getId()); value.setReversalCashDelta(reversalDelta); value.setReplacementCashDelta(replacementDelta); value.setCommandCashDelta(commandDelta); value.setBalanceAfter(balanceAfter); value.setPositionQuantityAfter(position.quantity()); value.setPositionAvgCostAfter(position.quantity().signum() == 0 ? BigDecimal.ZERO.setScale(8) : position.totalCost().divide(position.quantity(), 8, RoundingMode.HALF_UP)); value.setPositionTotalCostAfter(position.totalCost()); value.setPositionRealizedProfitLossAfter(position.cumulativeRealizedProfitLoss()); value.setPositionStatusAfter(position.quantity().signum() == 0 ? "CLOSED" : "OPEN"); value.setProjectionVersion(versionAfter); value.setLastTransactionId(replacement.getId()); value.setCreatedAt(Instant.now());
        return value;
    }

    private InvestmentReplacementResponse response(InvestmentTransactionCorrection value, boolean replay) {
        return new InvestmentReplacementResponse("REPLACED", value.getCorrectionGroupId().toString(), value.getOriginalTransactionId(), value.getReversalTransactionId(), value.getReplacementTransactionId(), value.getTransactionType(), value.getAssetId(), value.getAccountId(), value.getInstrumentId(), d(value.getReversalCashDelta()), d(value.getReplacementCashDelta()), d(value.getCommandCashDelta()), d(value.getBalanceAfter()), new com.financeos.module.investment.command.dto.InvestmentCommandResponse.FinalPosition(d(value.getPositionQuantityAfter()), d(value.getPositionAvgCostAfter()), d(value.getPositionTotalCostAfter()), d(value.getPositionRealizedProfitLossAfter()), value.getPositionStatusAfter(), value.getProjectionVersion(), value.getLastTransactionId()), value.getLastTransactionId(), replay, value.getCreatedAt());
    }

    private ReplacementAmounts amounts(String type, InvestmentReplacementRequest request) {
        if (request == null || request.reason() == null || request.reason().isBlank() || request.reason().length() > 500 || !request.reason().equals(request.reason().trim())) throw new BusinessException(400, "Replacement reason must be 1 to 500 non-blank characters without surrounding spaces");
        try {
            if ("BUY".equals(type) && request instanceof BuyReplacementRequest r) return trade(r.quantity(), r.unitPrice(), r.feeAmount(), r.taxAmount(), r.externalReference(), r.note(), r.reason());
            if ("SELL".equals(type) && request instanceof SellReplacementRequest r) return trade(r.quantity(), r.unitPrice(), r.feeAmount(), r.taxAmount(), r.externalReference(), r.note(), r.reason());
            if ("DIVIDEND".equals(type) && request instanceof DividendReplacementRequest r) return dividend(r.grossAmount(), r.feeAmount(), r.taxAmount(), r.externalReference(), r.note(), r.reason());
        } catch (NumberFormatException e) { throw new BusinessException(400, "Amounts must be decimal strings"); }
        throw new BusinessException(400, "Replacement request does not match the original transaction type");
    }
    private ReplacementAmounts trade(String quantity, String unitPrice, String fee, String tax, String externalReference, String note, String reason) {
        BigDecimal q = decimal(quantity, 8, "quantity"), p = decimal(unitPrice, 8, "unitPrice"), f = decimal(defaultZero(fee), 2, "feeAmount"), t = decimal(defaultZero(tax), 2, "taxAmount");
        if (q.signum() <= 0 || p.signum() <= 0 || f.signum() < 0 || t.signum() < 0) throw new BusinessException(400, "Replacement trade amounts are invalid");
        return new ReplacementAmounts(q, p, null, f, t, optional(externalReference, 100), optional(note, 500), reason);
    }
    private ReplacementAmounts dividend(String gross, String fee, String tax, String externalReference, String note, String reason) {
        BigDecimal g = decimal(gross, 2, "grossAmount"), f = decimal(defaultZero(fee), 2, "feeAmount"), t = decimal(defaultZero(tax), 2, "taxAmount");
        if (g.signum() < 0 || f.signum() < 0 || t.signum() < 0 || f.add(t).compareTo(g) > 0) throw new BusinessException(400, "Replacement dividend amounts are invalid");
        return new ReplacementAmounts(null, null, g, f, t, optional(externalReference, 100), optional(note, 500), reason);
    }
    private BigDecimal decimal(String value, int scale, String name) { if (value == null || value.isBlank()) throw new BusinessException(400, name + " is required"); BigDecimal v = new BigDecimal(value); if (v.scale() > scale || v.precision() > 28) throw new BusinessException(400, name + " is outside the supported precision"); return v.setScale(scale); }
    private String optional(String value, int max) { if (value == null) return null; if (value.length() > max) throw new BusinessException(400, "Replacement text is too long"); return value.trim().isEmpty() ? null : value.trim(); }
    private String defaultZero(String value) { return value == null ? "0.00" : value; }
    private InvestmentLedgerCommand command(String type, ReplacementAmounts a) { return switch (type) { case "BUY" -> InvestmentLedgerCommand.buy(a.quantity(), a.unitPrice(), a.feeAmount(), a.taxAmount()); case "SELL" -> InvestmentLedgerCommand.sell(a.quantity(), a.unitPrice(), a.feeAmount(), a.taxAmount()); case "DIVIDEND" -> InvestmentLedgerCommand.dividend(a.grossAmount(), a.feeAmount(), a.taxAmount()); default -> throw new BusinessException(409, "Investment transaction cannot be replaced"); }; }
    private List<InvestmentReplayEntry> entries(Long userId, Long assetId) { return transactionMapper.selectAllByUserIdAndAssetId(userId, assetId).stream().map(t -> new InvestmentReplayEntry(t.getId(), t.getTradeTime(), InvestmentTransactionStatus.valueOf(t.getStatus()), switch (InvestmentTransactionType.valueOf(t.getTransactionType())) { case BUY -> InvestmentLedgerCommand.buy(t.getQuantity(), t.getUnitPrice(), t.getFeeAmount(), t.getTaxAmount()); case SELL -> InvestmentLedgerCommand.sell(t.getQuantity(), t.getUnitPrice(), t.getFeeAmount(), t.getTaxAmount()); case DIVIDEND -> InvestmentLedgerCommand.dividend(t.getGrossAmount(), t.getFeeAmount(), t.getTaxAmount()); case OPENING_POSITION -> InvestmentLedgerCommand.openingPosition(t.getQuantity(), t.getUnitPrice()); case REVERSAL -> InvestmentLedgerCommand.reversal(); }, t.getOriginalTransactionId(), t.getReplayAnchorTransactionId(), t.getReplaySequence() == null ? 0 : t.getReplaySequence())).toList(); }
    private String idempotencyKey(String value) { if (value == null || value.isBlank() || value.length() > 100 || !value.equals(value.trim())) throw new BusinessException(400, "Idempotency-Key must be 1 to 100 non-blank characters without surrounding spaces"); return value; }
    private String requestHash(Long userId, InvestmentTransaction original, ReplacementAmounts a) { return hash(String.join("\n", "operation=REPLACEMENT", "formulaVersion=INVESTMENT_REPLACEMENT_V1", "userId=" + userId, "originalTransactionId=" + original.getId(), "originalType=" + original.getTransactionType(), "reason=" + a.reason(), "quantity=" + d(a.quantity()), "unitPrice=" + d(a.unitPrice()), "grossAmount=" + d(a.grossAmount()), "feeAmount=" + d(a.feeAmount()), "taxAmount=" + d(a.taxAmount()), "externalReference=" + canonicalOptional(a.externalReference()), "note=" + canonicalOptional(a.note()), "currency=CNY", "replayPolicy=EXPLICIT_ORIGINAL_ANCHOR_V1", "cashPolicy=NET_ORIGINAL_AND_REPLACEMENT_V1")); }
    private String hash(String text) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException("SHA-256 is unavailable", e); } }
    private String canonicalOptional(String value) { return value == null ? "NULL" : "VALUE:" + value.getBytes(StandardCharsets.UTF_8).length + ":" + value; }
    private static String d(BigDecimal value) { return value == null ? "null" : value.toPlainString(); }
    private record ReplacementAmounts(BigDecimal quantity, BigDecimal unitPrice, BigDecimal grossAmount, BigDecimal feeAmount, BigDecimal taxAmount, String externalReference, String note, String reason) { }
}
