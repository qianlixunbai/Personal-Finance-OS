package com.financeos.module.ledger.service;

import com.financeos.common.BusinessException;
import com.financeos.module.account.entity.Account;
import com.financeos.module.account.service.AccountBalanceMutation;
import com.financeos.module.account.service.AccountBalanceService;
import com.financeos.module.account.service.LockedAccounts;
import com.financeos.module.ledger.dto.TransferRequest;
import com.financeos.module.ledger.dto.TransferResponse;
import com.financeos.module.ledger.entity.Transfer;
import com.financeos.module.ledger.mapper.TransferMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class TransferService {

    private static final int MAX_DESCRIPTION_LENGTH = 500;

    private final TransferMapper transferMapper;
    private final AccountBalanceService accountBalanceService;
    private final TransactionWriteRules writeRules;

    public TransferService(TransferMapper transferMapper,
                           AccountBalanceService accountBalanceService,
                           TransactionWriteRules writeRules) {
        this.transferMapper = transferMapper;
        this.accountBalanceService = accountBalanceService;
        this.writeRules = writeRules;
    }

    @Transactional
    public TransferResponse create(Long userId, TransferRequest request) {
        validateRequest(userId, request);
        writeRules.validateAmount("INCOME", request.amount(), null);
        if (request.fromAccountId().equals(request.toAccountId())) {
            throw new BusinessException(400, "转出账户与转入账户不能相同");
        }

        LockedAccounts lockedAccounts = accountBalanceService.lockOwnedAccounts(
                userId, List.of(request.fromAccountId(), request.toAccountId()));
        Account source = lockedAccounts.account(request.fromAccountId());
        Account target = lockedAccounts.account(request.toAccountId());
        validateTransferableAccount(source);
        validateTransferableAccount(target);

        Transfer transfer = new Transfer();
        transfer.setUserId(userId);
        transfer.setFromAccountId(source.getId());
        transfer.setToAccountId(target.getId());
        transfer.setAmount(request.amount());
        transfer.setCurrency(TransactionWriteRules.BASE_CURRENCY);
        transfer.setDescription(request.description());
        transfer.setTransactedAt(request.transactedAt());

        requireExactlyOne(transferMapper.insert(transfer));
        accountBalanceService.applyDeltas(lockedAccounts, List.of(
                new AccountBalanceMutation(source.getId(), request.amount().negate(), true),
                new AccountBalanceMutation(target.getId(), request.amount(), true)));

        return new TransferResponse(transfer.getId(), transfer.getFromAccountId(), transfer.getToAccountId(),
                transfer.getAmount(), transfer.getCurrency(), transfer.getDescription(), transfer.getTransactedAt(),
                transfer.getCreatedAt());
    }

    private void validateRequest(Long userId, TransferRequest request) {
        if (request == null) {
            throw new BusinessException(400, "转账参数不能为空");
        }
        if (userId == null || userId <= 0) {
            throw new BusinessException(400, "用户参数不合法");
        }
        if (request.fromAccountId() == null || request.fromAccountId() <= 0
                || request.toAccountId() == null || request.toAccountId() <= 0) {
            throw new BusinessException(400, "账户参数不合法");
        }
        if (request.transactedAt() == null) {
            throw new BusinessException(400, "转账时间不能为空");
        }
        if (request.description() != null && request.description().length() > MAX_DESCRIPTION_LENGTH) {
            throw new BusinessException(400, "备注不能超过 500 个字符");
        }
    }

    private void validateTransferableAccount(Account account) {
        if (account == null) {
            throw new BusinessException(404, "账户不存在");
        }
        if (!"ACTIVE".equals(account.getStatus())) {
            throw new BusinessException(400, "停用账户不允许新增流水");
        }
        if (!TransactionWriteRules.BASE_CURRENCY.equals(account.getCurrency())) {
            throw new BusinessException(400, "当前版本仅支持 CNY 币种");
        }
    }

    private void requireExactlyOne(int affectedRows) {
        if (affectedRows != 1) {
            throw new IllegalStateException("transfer insert did not affect exactly one row");
        }
    }
}
