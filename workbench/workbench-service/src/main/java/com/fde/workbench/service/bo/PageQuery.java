package com.fde.workbench.service.bo;

/**
 * 分页查询参数（页码从 1 开始），由 Controller 把 HTTP 查询参数转换成本对象，Service 只依赖它。
 *
 * @param page 页码，≥1
 * @param size 每页条数，1–100
 */
public record PageQuery(int page, int size) {

    /** 页码最小值 */
    public static final int MIN_PAGE = 1;

    /** 每页条数默认值（docs/api.md §1.1） */
    public static final int DEFAULT_SIZE = 20;

    /** 每页条数上限（docs/api.md §1.1） */
    public static final int MAX_SIZE = 100;

    /**
     * 规整页码与条数，防止负数 / 超大页导致 SQL 负偏移或全表扫描。
     *
     * @param page 页码，可空
     * @param size 每页条数，可空
     * @return 规整后的分页参数
     */
    public static PageQuery of(Integer page, Integer size) {
        int p = (page == null || page < MIN_PAGE) ? MIN_PAGE : page;
        int s = (size == null || size < 1) ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        return new PageQuery(p, s);
    }

    /** 转成 SQL LIMIT 偏移量 */
    public int offset() {
        return (page - 1) * size;
    }
}
