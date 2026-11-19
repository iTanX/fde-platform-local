package com.fde.workbench.common.result;

import java.util.List;

/**
 * 分页数据结构 {@code {items, total, page, size}}，作为 {@link ApiResult#getData()} 的载荷，
 * 结构见 docs/api.md §1.2。
 *
 * @param <T> 列表元素类型
 */
public class PageResult<T> {

    /** 当前页数据 */
    private List<T> items;

    /** 总记录数 */
    private long total;

    /** 当前页码，从 1 开始 */
    private int page;

    /** 每页条数 */
    private int size;

    public PageResult() {
    }

    public PageResult(List<T> items, long total, int page, int size) {
        this.items = items;
        this.total = total;
        this.page = page;
        this.size = size;
    }

    /**
     * 构造分页结果。
     *
     * @param items 当前页数据
     * @param total 总记录数
     * @param page  当前页码（从 1 开始）
     * @param size  每页条数
     */
    public static <T> PageResult<T> of(List<T> items, long total, int page, int size) {
        return new PageResult<>(items, total, page, size);
    }

    /**
     * 构造空分页结果，用于无数据场景。
     *
     * @param page 当前页码
     * @param size 每页条数
     */
    public static <T> PageResult<T> empty(int page, int size) {
        return new PageResult<>(List.of(), 0L, page, size);
    }

    public List<T> getItems() {
        return items;
    }

    public void setItems(List<T> items) {
        this.items = items;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }
}
