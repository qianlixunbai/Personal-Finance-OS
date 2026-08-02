package com.financeos.module.investment.command;

import com.financeos.module.account.entity.Account;
import com.financeos.module.account.mapper.AccountMapper;
import com.financeos.module.asset.entity.Asset;
import com.financeos.module.asset.mapper.AssetMapper;
import com.financeos.module.investment.entity.InvestmentTransaction;
import com.financeos.module.investment.entity.InvestmentTransactionCorrection;
import com.financeos.module.investment.ledger.InvestmentReplayResult;
import com.financeos.module.investment.mapper.InvestmentTransactionCorrectionMapper;
import com.financeos.module.investment.mapper.InvestmentTransactionMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Verifies the persisted replacement command envelope, facts, and projections before commit. */
@Component
class InvestmentReplacementConsistencyChecker {
    private final AccountMapper accountMapper;
    private final AssetMapper assetMapper;
    private final InvestmentTransactionMapper transactionMapper;
    private final InvestmentTransactionCorrectionMapper correctionMapper;

    InvestmentReplacementConsistencyChecker(AccountMapper accountMapper, AssetMapper assetMapper,
                                            InvestmentTransactionMapper transactionMapper,
                                            InvestmentTransactionCorrectionMapper correctionMapper) {
        this.accountMapper = accountMapper;
        this.assetMapper = assetMapper;
        this.transactionMapper = transactionMapper;
        this.correctionMapper = correctionMapper;
    }

    void verify(InvestmentTransaction originalBefore, InvestmentTransaction reversalExpected,
                InvestmentTransaction replacementExpected, InvestmentTransactionCorrection correctionExpected,
                Asset assetBefore, BigDecimal balanceAfter, int versionAfter,
                InvestmentReplayResult candidate, InvestmentReplayResult persistedReplay) {
        Account account = accountMapper.findByUserIdAndId(originalBefore.getUserId(), originalBefore.getAccountId());
        Asset asset = assetMapper.findByUserIdAndId(originalBefore.getUserId(), originalBefore.getAssetId());
        InvestmentTransaction original = transactionMapper.findByUserIdAndId(originalBefore.getUserId(), originalBefore.getId());
        InvestmentTransaction reversal = transactionMapper.findByUserIdAndId(originalBefore.getUserId(), reversalExpected.getId());
        InvestmentTransaction replacement = transactionMapper.findByUserIdAndId(originalBefore.getUserId(), replacementExpected.getId());
        InvestmentTransactionCorrection correction = correctionMapper.findByUserIdAndId(originalBefore.getUserId(), correctionExpected.getId());

        require(sameFact(originalBefore, original), "original snapshot changed");
        require(sameFact(reversalExpected, reversal), "grouped reversal changed");
        require(sameFact(replacementExpected, replacement), "replacement fact changed");
        require(sameCorrection(correctionExpected, correction), "combined replacement receipt changed");
        require(candidate.trace().steps().equals(persistedReplay.trace().steps()), "replacement replay trace changed");
        require(candidate.trace().canonicalDigest().equals(persistedReplay.trace().canonicalDigest()),
                "replacement replay digest changed");
        require(account != null && same(account.getBalance(), balanceAfter), "account balance differs from replacement receipt");
        require(asset != null
                        && same(asset.getQuantity(), persistedReplay.position().quantity())
                        && same(asset.getTotalCost(), persistedReplay.position().totalCost())
                        && same(asset.getRealizedProfitLoss(), persistedReplay.position().cumulativeRealizedProfitLoss())
                        && same(asset.getAvgCost(), replacementExpected.getPositionAvgCostAfter())
                        && Objects.equals(asset.getPositionStatus(), replacementExpected.getPositionStatusAfter())
                        && Objects.equals(asset.getProjectionVersion(), versionAfter)
                        && Objects.equals(asset.getLastTransactionId(), replacementExpected.getId()),
                "asset projection differs from replacement receipt");
        require(same(asset.getCurrentPrice(), assetBefore.getCurrentPrice())
                        && same(asset.getMarketValue(), assetBefore.getMarketValue()),
                "replacement overwrote reference price fields");
        require(correction != null
                        && Objects.equals(correction.getOriginalTransactionId(), originalBefore.getId())
                        && Objects.equals(correction.getReversalTransactionId(), reversalExpected.getId())
                        && Objects.equals(correction.getReplacementTransactionId(), replacementExpected.getId())
                        && Objects.equals(correction.getLastTransactionId(), replacementExpected.getId())
                        && Objects.equals(correction.getCorrectionGroupId(), reversalExpected.getCorrectionGroupId())
                        && Objects.equals(correction.getCorrectionGroupId(), replacementExpected.getCorrectionGroupId())
                        && same(correction.getReversalCashDelta(), reversal.getCashDelta())
                        && same(correction.getReplacementCashDelta(), replacement.getCashDelta())
                        && same(correction.getCommandCashDelta(), reversal.getCashDelta().add(replacement.getCashDelta()))
                        && same(correction.getBalanceAfter(), replacement.getAccountBalanceAfter()),
                "replacement facts do not match their command envelope");
    }

    private boolean sameFact(InvestmentTransaction expected, InvestmentTransaction actual) {
        return expected != null && actual != null
                && Objects.equals(expected.getId(), actual.getId())
                && Objects.equals(expected.getUserId(), actual.getUserId())
                && Objects.equals(expected.getAssetId(), actual.getAssetId())
                && Objects.equals(expected.getAccountId(), actual.getAccountId())
                && Objects.equals(expected.getTransactionType(), actual.getTransactionType())
                && Objects.equals(expected.getStatus(), actual.getStatus())
                && same(expected.getQuantity(), actual.getQuantity())
                && same(expected.getUnitPrice(), actual.getUnitPrice())
                && same(expected.getGrossAmount(), actual.getGrossAmount())
                && same(expected.getFeeAmount(), actual.getFeeAmount())
                && same(expected.getTaxAmount(), actual.getTaxAmount())
                && same(expected.getNetAmount(), actual.getNetAmount())
                && same(expected.getReleasedCostAmount(), actual.getReleasedCostAmount())
                && same(expected.getRealizedProfitLoss(), actual.getRealizedProfitLoss())
                && Objects.equals(expected.getCurrency(), actual.getCurrency())
                && sameTimestamp(expected.getTradeTime(), actual.getTradeTime())
                && sameTimestamp(expected.getSettlementTime(), actual.getSettlementTime())
                && Objects.equals(expected.getNote(), actual.getNote())
                && Objects.equals(expected.getExternalReference(), actual.getExternalReference())
                && Objects.equals(expected.getSource(), actual.getSource())
                && Objects.equals(expected.getIdempotencyKey(), actual.getIdempotencyKey())
                && Objects.equals(expected.getRequestHash(), actual.getRequestHash())
                && same(expected.getAccountBalanceAfter(), actual.getAccountBalanceAfter())
                && same(expected.getPositionQuantityAfter(), actual.getPositionQuantityAfter())
                && same(expected.getPositionAvgCostAfter(), actual.getPositionAvgCostAfter())
                && same(expected.getPositionTotalCostAfter(), actual.getPositionTotalCostAfter())
                && same(expected.getPositionRealizedProfitLossAfter(), actual.getPositionRealizedProfitLossAfter())
                && Objects.equals(expected.getPositionStatusAfter(), actual.getPositionStatusAfter())
                && Objects.equals(expected.getProjectionVersionAfter(), actual.getProjectionVersionAfter())
                && Objects.equals(expected.getReplacesTransactionId(), actual.getReplacesTransactionId())
                && Objects.equals(expected.getReversedAt(), actual.getReversedAt())
                && Objects.equals(expected.getReversalReason(), actual.getReversalReason())
                && Objects.equals(expected.getOriginalTransactionId(), actual.getOriginalTransactionId())
                && Objects.equals(expected.getCorrectionReason(), actual.getCorrectionReason())
                && same(expected.getCashDelta(), actual.getCashDelta())
                && Objects.equals(expected.getCorrectionGroupId(), actual.getCorrectionGroupId())
                && Objects.equals(expected.getReplayAnchorTransactionId(), actual.getReplayAnchorTransactionId())
                && Objects.equals(expected.getReplaySequence(), actual.getReplaySequence());
    }

    private boolean sameCorrection(InvestmentTransactionCorrection expected, InvestmentTransactionCorrection actual) {
        return expected != null && actual != null
                && Objects.equals(expected.getId(), actual.getId())
                && Objects.equals(expected.getCorrectionGroupId(), actual.getCorrectionGroupId())
                && Objects.equals(expected.getUserId(), actual.getUserId())
                && Objects.equals(expected.getAccountId(), actual.getAccountId())
                && Objects.equals(expected.getAssetId(), actual.getAssetId())
                && Objects.equals(expected.getInstrumentId(), actual.getInstrumentId())
                && Objects.equals(expected.getOriginalTransactionId(), actual.getOriginalTransactionId())
                && Objects.equals(expected.getTransactionType(), actual.getTransactionType())
                && Objects.equals(expected.getCorrectionKind(), actual.getCorrectionKind())
                && Objects.equals(expected.getIdempotencyKey(), actual.getIdempotencyKey())
                && Objects.equals(expected.getRequestHash(), actual.getRequestHash())
                && Objects.equals(expected.getCorrectionReason(), actual.getCorrectionReason())
                && Objects.equals(expected.getReversalTransactionId(), actual.getReversalTransactionId())
                && Objects.equals(expected.getReplacementTransactionId(), actual.getReplacementTransactionId())
                && same(expected.getReversalCashDelta(), actual.getReversalCashDelta())
                && same(expected.getReplacementCashDelta(), actual.getReplacementCashDelta())
                && same(expected.getCommandCashDelta(), actual.getCommandCashDelta())
                && same(expected.getBalanceAfter(), actual.getBalanceAfter())
                && same(expected.getPositionQuantityAfter(), actual.getPositionQuantityAfter())
                && same(expected.getPositionAvgCostAfter(), actual.getPositionAvgCostAfter())
                && same(expected.getPositionTotalCostAfter(), actual.getPositionTotalCostAfter())
                && same(expected.getPositionRealizedProfitLossAfter(), actual.getPositionRealizedProfitLossAfter())
                && Objects.equals(expected.getPositionStatusAfter(), actual.getPositionStatusAfter())
                && Objects.equals(expected.getProjectionVersion(), actual.getProjectionVersion())
                && Objects.equals(expected.getLastTransactionId(), actual.getLastTransactionId());
    }

    private boolean same(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private boolean sameTimestamp(Instant left, Instant right) {
        return left == null ? right == null : right != null
                && Duration.between(left, right).abs().compareTo(Duration.ofNanos(1_000)) <= 0;
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new InvestmentReplacementConsistencyException(message, null);
        }
    }
}
