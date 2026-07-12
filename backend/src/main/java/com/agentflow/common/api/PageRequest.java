package com.agentflow.common.api;

/**
 * 中文：统一的分页请求参数。Controller 可以直接接收本类，setter 会把越界输入收敛到安全范围，
 * 让后续 Service 和 Repository 不必重复处理页码边界。
 *
 * <p>English: Shared pagination input. Controllers may bind request parameters directly
 * to this class; its setters normalize out-of-range values so downstream services and
 * repositories do not repeat boundary handling.
 */
public class PageRequest {
    public static final int DEFAULT_PAGE = 1;
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private int page = DEFAULT_PAGE;
    private int pageSize = DEFAULT_PAGE_SIZE;

    /**
     * 中文：保留无参构造器，供 Spring MVC 的数据绑定器先创建对象、再调用 setter。
     * English: Kept for Spring MVC data binding, which constructs the object before
     * invoking its setters.
     */
    public PageRequest() {
    }

    /**
     * 中文：便于测试或业务代码直接创建分页参数，仍会复用相同的归一化规则。
     * English: Convenient for tests and application code while reusing the same
     * normalization rules as web binding.
     */
    public PageRequest(int page, int pageSize) {
        setPage(page);
        setPageSize(pageSize);
    }

    public int getPage() {
        return page;
    }

    /**
     * 中文：小于 1 的页码按第一页处理。
     * English: Page numbers below one are normalized to the first page.
     */
    public void setPage(int page) {
        this.page = Math.max(page, DEFAULT_PAGE);
    }

    public int getPageSize() {
        return pageSize;
    }

    /**
     * 中文：每页数量限制在 [1, 100]，防止零长度分页或一次读取过多数据。
     * English: Constrains page size to [1, 100], preventing empty pages and oversized reads.
     */
    public void setPageSize(int pageSize) {
        this.pageSize = Math.clamp(pageSize, 1, MAX_PAGE_SIZE);
    }

    /**
     * 中文：把面向用户的 1-based 页码转换为数据库查询常用的 0-based 偏移量。
     * {@link Math#multiplyExact(int, int)} 会在极端大页码溢出时明确失败，避免产生负 offset。
     *
     * <p>English: Converts a user-facing 1-based page number into a database-friendly
     * 0-based offset. {@link Math#multiplyExact(int, int)} fails explicitly on overflow
     * instead of silently producing a negative offset.
     */
    public int offset() {
        return Math.multiplyExact(page - 1, pageSize);
    }
}
