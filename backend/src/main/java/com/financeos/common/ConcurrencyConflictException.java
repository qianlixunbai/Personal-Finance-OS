package com.financeos.common;

public class ConcurrencyConflictException extends RuntimeException {
    public ConcurrencyConflictException() {
        super("并发操作冲突，请重试");
    }
}
