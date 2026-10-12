package com.financeos.module.importing.entity;

import lombok.Data;
import java.time.Instant;

@Data
public class TransactionImportFileObject {
    private String reference;
    private String storageId;
    private String kind;
    private Long ownerUserId;
    private String intendedSessionId;
    private String sessionId;
    private String state;
    private boolean writeComplete;
    private long fenceEpoch;
    private String leaseOwner;
    private Instant leaseUntil;
    private Instant createdAt;
}
