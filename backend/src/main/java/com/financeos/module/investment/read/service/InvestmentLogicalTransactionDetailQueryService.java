package com.financeos.module.investment.read.service;

import com.financeos.common.BusinessException;
import com.financeos.module.investment.entity.InvestmentTransaction;
import com.financeos.module.investment.entity.InvestmentTransactionCorrection;
import com.financeos.module.investment.mapper.InvestmentTransactionCorrectionMapper;
import com.financeos.module.investment.mapper.InvestmentTransactionMapper;
import com.financeos.module.investment.read.dto.InvestmentTransactionAuditTimeline;
import com.financeos.module.investment.read.dto.InvestmentTransactionDetail;
import com.financeos.module.investment.read.mapper.InvestmentReadMapper;
import com.financeos.module.investment.read.mapper.InvestmentReadRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
public class InvestmentLogicalTransactionDetailQueryService {
    private final InvestmentTransactionMapper transactionMapper;
    private final InvestmentTransactionCorrectionMapper correctionMapper;
    private final InvestmentReadMapper readMapper;

    public InvestmentLogicalTransactionDetailQueryService(InvestmentTransactionMapper transactionMapper,
                                                           InvestmentTransactionCorrectionMapper correctionMapper,
                                                           InvestmentReadMapper readMapper) {
        this.transactionMapper = transactionMapper;
        this.correctionMapper = correctionMapper;
        this.readMapper = readMapper;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public InvestmentTransactionDetail get(Long userId, Long logicalTransactionId) {
        State state = load(userId, logicalTransactionId);
        return new InvestmentTransactionDetail(state.original.getId(), state.original.getTransactionType(), state.correctionStatus,
                state.effective, state.original.getTradeTime(), state.original.getSettlementTime(),
                new InvestmentTransactionDetail.TransactionAccount(state.position.getAccountId(), state.position.getAccountName()),
                new InvestmentTransactionDetail.TransactionInstrument(state.position.getInstrumentId(), state.position.getInstrumentSymbol(),
                        state.position.getInstrumentName(), state.position.getInstrumentMarket(), state.position.getInstrumentAssetClass(),
                        state.position.getInstrumentQuoteCurrency()), businessValues(state.original),
                state.replacement == null ? null : businessValues(state.replacement), postingReceipt(state.original),
                correctionFinalReceipt(state), correctionSummary(state), currentPosition(state.position));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public InvestmentTransactionAuditTimeline auditTimeline(Long userId, Long logicalTransactionId) {
        State state = load(userId, logicalTransactionId);
        InvestmentTransactionAuditTimeline.AuditEvent original = new InvestmentTransactionAuditTimeline.AuditEvent(
                "ORIGINAL_POSTING", state.original.getCreatedAt(), state.original.getId(), null,
                state.original.getTransactionType(), null, "POSTING_TIME", auditBusinessValues(state.original),
                auditPostingReceipt(state.original), null, List.of());
        List<InvestmentTransactionAuditTimeline.AuditEvent> events = switch (state.correctionStatus) {
            case "UNCHANGED" -> List.of(original);
            case "REVERSED" -> List.of(original, new InvestmentTransactionAuditTimeline.AuditEvent(
                    "STANDALONE_REVERSAL", state.reversal.getCreatedAt(), state.reversal.getId(), state.original.getId(),
                    state.reversal.getTransactionType(), state.reversal.getCorrectionReason(), "CORRECTION_FINAL",
                    auditBusinessValues(state.reversal), null, auditCorrectionFinalReceipt(state.reversal), List.of()));
            case "REPLACED" -> List.of(original, new InvestmentTransactionAuditTimeline.AuditEvent(
                    "REPLACEMENT_COMMAND", state.correction.getCreatedAt(), null, state.original.getId(), null,
                    state.correction.getCorrectionReason(), "CORRECTION_FINAL", null, null,
                    auditCorrectionFinalReceipt(state.correction), List.of(
                            new InvestmentTransactionAuditTimeline.ReplacementPhysicalFact("GROUPED_REVERSAL", state.reversal.getId(),
                                    state.reversal.getTransactionType(), "NOT_APPLICABLE", auditBusinessValues(state.reversal)),
                            new InvestmentTransactionAuditTimeline.ReplacementPhysicalFact("REPLACEMENT_FACT", state.replacement.getId(),
                                    state.replacement.getTransactionType(), "CORRECTION_FINAL", auditBusinessValues(state.replacement)))));
            default -> throw new IllegalStateException("Unsupported correction status");
        };
        return new InvestmentTransactionAuditTimeline(state.original.getId(), state.correctionStatus, events,
                new InvestmentTransactionAuditTimeline.AuditCurrentPosition(InvestmentReadSupport.decimal(state.position.getQuantity(), 8),
                        InvestmentReadSupport.decimal(state.position.getAverageCost(), 8),
                        InvestmentReadSupport.decimal(state.position.getTotalCost(), 2),
                        InvestmentReadSupport.decimal(state.position.getCumulativeRealizedProfitLoss(), 2), state.position.getPositionStatus()));
    }

    private State load(Long userId, Long logicalTransactionId) {
        Long id = InvestmentReadSupport.positive("logicalTransactionId", logicalTransactionId);
        InvestmentTransaction original = transactionMapper.findByUserIdAndId(userId, id);
        if (!logicalOriginal(original)) {
            throw new BusinessException(404, "Investment transaction not found");
        }
        InvestmentReadRow position = readMapper.selectPositionDetail(userId, original.getAssetId());
        if (position == null) {
            throw new IllegalStateException("Investment transaction position relation is inconsistent");
        }
        InvestmentTransaction reversal = transactionMapper.findReversalByUserIdAndOriginalTransactionId(userId, id);
        InvestmentTransactionCorrection correction = correctionMapper.findByUserIdAndOriginalTransactionId(userId, id);
        InvestmentTransaction replacement = correction == null ? null
                : transactionMapper.findByUserIdAndId(userId, correction.getReplacementTransactionId());
        if (correction != null) {
            validateReplacement(original, reversal, replacement, correction);
            return new State(original, position, reversal, replacement, correction, "REPLACED", true);
        }
        if (reversal != null) {
            validateStandaloneReversal(original, reversal);
            return new State(original, position, reversal, null, null, "REVERSED", false);
        }
        return new State(original, position, null, null, null, "UNCHANGED", true);
    }

    private boolean logicalOriginal(InvestmentTransaction value) {
        return value != null
                && List.of("OPENING_POSITION", "BUY", "SELL", "DIVIDEND").contains(value.getTransactionType())
                && value.getOriginalTransactionId() == null
                && value.getCorrectionGroupId() == null;
    }

    private void validateStandaloneReversal(InvestmentTransaction original, InvestmentTransaction reversal) {
        if (!"REVERSAL".equals(reversal.getTransactionType()) || reversal.getCorrectionGroupId() != null
                || !Objects.equals(reversal.getOriginalTransactionId(), original.getId())) {
            throw new IllegalStateException("Standalone reversal relation is inconsistent");
        }
    }

    private void validateReplacement(InvestmentTransaction original, InvestmentTransaction reversal,
                                     InvestmentTransaction replacement, InvestmentTransactionCorrection correction) {
        if (reversal == null || replacement == null || !"REVERSAL".equals(reversal.getTransactionType())
                || reversal.getCorrectionGroupId() == null
                || !Objects.equals(reversal.getCorrectionGroupId(), replacement.getCorrectionGroupId())
                || !Objects.equals(reversal.getCorrectionGroupId(), correction.getCorrectionGroupId())
                || !Objects.equals(reversal.getOriginalTransactionId(), original.getId())
                || replacement.getOriginalTransactionId() != null
                || !Objects.equals(correction.getReversalTransactionId(), reversal.getId())
                || !Objects.equals(correction.getReplacementTransactionId(), replacement.getId())
                || !Objects.equals(correction.getOriginalTransactionId(), original.getId())
                || !Objects.equals(correction.getAccountId(), original.getAccountId())
                || !Objects.equals(correction.getAssetId(), original.getAssetId())
                || !Objects.equals(correction.getTransactionType(), original.getTransactionType())
                || !Objects.equals(replacement.getReplayAnchorTransactionId(), original.getId())
                || !Objects.equals(replacement.getTransactionType(), original.getTransactionType())) {
            throw new IllegalStateException("Replacement relation is inconsistent");
        }
    }

    private InvestmentTransactionDetail.TransactionBusinessValues businessValues(InvestmentTransaction value) {
        return new InvestmentTransactionDetail.TransactionBusinessValues(InvestmentReadSupport.decimal(value.getQuantity(), 8),
                InvestmentReadSupport.decimal(value.getUnitPrice(), 8), InvestmentReadSupport.decimal(value.getGrossAmount(), 2),
                InvestmentReadSupport.decimal(value.getFeeAmount(), 2), InvestmentReadSupport.decimal(value.getTaxAmount(), 2),
                InvestmentReadSupport.decimal(value.getNetAmount(), 2), InvestmentReadSupport.decimal(value.getReleasedCostAmount(), 2),
                InvestmentReadSupport.decimal(value.getRealizedProfitLoss(), 2), value.getNote(), value.getExternalReference());
    }

    private InvestmentTransactionDetail.PostingReceipt postingReceipt(InvestmentTransaction value) {
        return value.getAccountBalanceAfter() == null ? null : new InvestmentTransactionDetail.PostingReceipt(
                InvestmentReadSupport.decimal(value.getAccountBalanceAfter(), 2), InvestmentReadSupport.decimal(value.getPositionQuantityAfter(), 8),
                InvestmentReadSupport.decimal(value.getPositionAvgCostAfter(), 8), InvestmentReadSupport.decimal(value.getPositionTotalCostAfter(), 2),
                InvestmentReadSupport.decimal(value.getPositionRealizedProfitLossAfter(), 2), value.getPositionStatusAfter());
    }

    private InvestmentTransactionDetail.CorrectionFinalReceipt correctionFinalReceipt(State state) {
        if (state.correction != null) {
            return new InvestmentTransactionDetail.CorrectionFinalReceipt(InvestmentReadSupport.decimal(state.correction.getBalanceAfter(), 2),
                    InvestmentReadSupport.decimal(state.correction.getPositionQuantityAfter(), 8),
                    InvestmentReadSupport.decimal(state.correction.getPositionAvgCostAfter(), 8),
                    InvestmentReadSupport.decimal(state.correction.getPositionTotalCostAfter(), 2),
                    InvestmentReadSupport.decimal(state.correction.getPositionRealizedProfitLossAfter(), 2), state.correction.getPositionStatusAfter());
        }
        return state.reversal == null ? null : correctionFinalReceipt(state.reversal);
    }

    private InvestmentTransactionDetail.CorrectionFinalReceipt correctionFinalReceipt(InvestmentTransaction value) {
        return new InvestmentTransactionDetail.CorrectionFinalReceipt(InvestmentReadSupport.decimal(value.getAccountBalanceAfter(), 2),
                InvestmentReadSupport.decimal(value.getPositionQuantityAfter(), 8), InvestmentReadSupport.decimal(value.getPositionAvgCostAfter(), 8),
                InvestmentReadSupport.decimal(value.getPositionTotalCostAfter(), 2),
                InvestmentReadSupport.decimal(value.getPositionRealizedProfitLossAfter(), 2), value.getPositionStatusAfter());
    }

    private InvestmentTransactionDetail.CorrectionSummary correctionSummary(State state) {
        if (state.correction != null) {
            return new InvestmentTransactionDetail.CorrectionSummary(state.correction.getCreatedAt(), state.correction.getCorrectionReason(),
                    state.correction.getReversalTransactionId(), state.correction.getReplacementTransactionId());
        }
        return state.reversal == null ? null : new InvestmentTransactionDetail.CorrectionSummary(state.reversal.getCreatedAt(),
                state.reversal.getCorrectionReason(), state.reversal.getId(), null);
    }

    private InvestmentTransactionDetail.CurrentPosition currentPosition(InvestmentReadRow value) {
        return new InvestmentTransactionDetail.CurrentPosition(InvestmentReadSupport.decimal(value.getQuantity(), 8),
                InvestmentReadSupport.decimal(value.getAverageCost(), 8), InvestmentReadSupport.decimal(value.getTotalCost(), 2),
                InvestmentReadSupport.decimal(value.getCumulativeRealizedProfitLoss(), 2), value.getPositionStatus());
    }

    private InvestmentTransactionAuditTimeline.AuditBusinessValues auditBusinessValues(InvestmentTransaction value) {
        return new InvestmentTransactionAuditTimeline.AuditBusinessValues(InvestmentReadSupport.decimal(value.getQuantity(), 8),
                InvestmentReadSupport.decimal(value.getUnitPrice(), 8), InvestmentReadSupport.decimal(value.getGrossAmount(), 2),
                InvestmentReadSupport.decimal(value.getFeeAmount(), 2), InvestmentReadSupport.decimal(value.getTaxAmount(), 2),
                InvestmentReadSupport.decimal(value.getNetAmount(), 2), InvestmentReadSupport.decimal(value.getReleasedCostAmount(), 2),
                InvestmentReadSupport.decimal(value.getRealizedProfitLoss(), 2), value.getNote(), value.getExternalReference());
    }

    private InvestmentTransactionAuditTimeline.AuditPostingReceipt auditPostingReceipt(InvestmentTransaction value) {
        return value.getAccountBalanceAfter() == null ? null : new InvestmentTransactionAuditTimeline.AuditPostingReceipt(
                InvestmentReadSupport.decimal(value.getAccountBalanceAfter(), 2), InvestmentReadSupport.decimal(value.getPositionQuantityAfter(), 8),
                InvestmentReadSupport.decimal(value.getPositionAvgCostAfter(), 8), InvestmentReadSupport.decimal(value.getPositionTotalCostAfter(), 2),
                InvestmentReadSupport.decimal(value.getPositionRealizedProfitLossAfter(), 2), value.getPositionStatusAfter());
    }

    private InvestmentTransactionAuditTimeline.AuditCorrectionFinalReceipt auditCorrectionFinalReceipt(InvestmentTransaction value) {
        return new InvestmentTransactionAuditTimeline.AuditCorrectionFinalReceipt(InvestmentReadSupport.decimal(value.getAccountBalanceAfter(), 2),
                InvestmentReadSupport.decimal(value.getPositionQuantityAfter(), 8), InvestmentReadSupport.decimal(value.getPositionAvgCostAfter(), 8),
                InvestmentReadSupport.decimal(value.getPositionTotalCostAfter(), 2),
                InvestmentReadSupport.decimal(value.getPositionRealizedProfitLossAfter(), 2), value.getPositionStatusAfter());
    }

    private InvestmentTransactionAuditTimeline.AuditCorrectionFinalReceipt auditCorrectionFinalReceipt(InvestmentTransactionCorrection value) {
        return new InvestmentTransactionAuditTimeline.AuditCorrectionFinalReceipt(InvestmentReadSupport.decimal(value.getBalanceAfter(), 2),
                InvestmentReadSupport.decimal(value.getPositionQuantityAfter(), 8), InvestmentReadSupport.decimal(value.getPositionAvgCostAfter(), 8),
                InvestmentReadSupport.decimal(value.getPositionTotalCostAfter(), 2),
                InvestmentReadSupport.decimal(value.getPositionRealizedProfitLossAfter(), 2), value.getPositionStatusAfter());
    }

    private record State(InvestmentTransaction original, InvestmentReadRow position, InvestmentTransaction reversal,
                         InvestmentTransaction replacement, InvestmentTransactionCorrection correction,
                         String correctionStatus, boolean effective) {
    }
}
