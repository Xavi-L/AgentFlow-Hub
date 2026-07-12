package com.agentflow.common.api;

import java.util.List;
import java.util.Objects;

/**
 * 中文：统一的分页响应。对象创建后不可变，避免 Controller 返回后集合仍被其他代码修改。
 * English: Shared immutable pagination response. Immutability prevents the response
 * collection from changing after a controller has returned it.
 *
 * @param <T> 中文：列表元素类型；English: the list item type
 */
public class PageResult<T> {
    private final List<T> items;
    private final int page;
    private final int pageSize;
    private final long total;
    private final boolean hasNext;

    private PageResult(List<T> items, int page, int pageSize, long total) {
        this.items = List.copyOf(items);
        this.page = page;
        this.pageSize = pageSize;
        this.total = total;
        // 中文：转为 long 后再乘，避免 int 乘法在大页码下溢出。
        // English: Promote to long before multiplication to avoid integer overflow.
        this.hasNext = (long) page * pageSize < total;
    }

    /**
     * 中文：创建分页结果并在系统边界检查不变量，防止返回自相矛盾的分页元数据。
     * English: Creates a page result and checks invariants at the system boundary so
     * contradictory pagination metadata cannot leak into an API response.
     */
    public static <T> PageResult<T> of(List<T> items, int page, int pageSize, long total) {
        Objects.requireNonNull(items, "items must not be null");
        if (page < 1) {
            throw new IllegalArgumentException("page must be at least 1");
        }
        if (pageSize < 1 || pageSize > PageRequest.MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("pageSize must be between 1 and 100");
        }
        if (total < 0) {
            throw new IllegalArgumentException("total must not be negative");
        }
        return new PageResult<>(items, page, pageSize, total);
    }

    public List<T> getItems() {
        return items;
    }

    public int getPage() {
        return page;
    }

    public int getPageSize() {
        return pageSize;
    }

    public long getTotal() {
        return total;
    }

    public boolean isHasNext() {
        return hasNext;
    }
}
