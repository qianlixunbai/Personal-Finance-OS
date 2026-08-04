package com.financeos.module.investment.read.dto;

import java.util.List;

public record CursorPage<T>(List<T> records, String nextCursor, boolean hasMore, int size) {
    public CursorPage {
        records = records == null ? List.of() : List.copyOf(records);
        if (hasMore != (nextCursor != null)) {
            throw new IllegalArgumentException("nextCursor must exist exactly when hasMore is true");
        }
    }
}
