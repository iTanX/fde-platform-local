package com.fde.workbench.api.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fde.workbench.common.bo.RevisionStamp;
import com.fde.workbench.service.vo.NodeVO;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 流程详情（api 层出网视图，docs/api.md §1.4 WorkflowVO + NodeVO）。
 * <p>
 * 节点、输入项、输出项、技能直接复用 service 层 VO（它们没有时间字段，不需要再换序列化器），
 * 只有本类的时间字段按 api 层 {@link TimeFormats} 输出。
 * <p>
 * <b>revision 取舍</b>：{@code wb_workflow} 表没有 revision 列，本任务禁止改表，
 * 因此 {@code revision} = {@code updated_at} 的毫秒时间戳（{@link RevisionStamp}，JSON 里是整数）：
 * 每次写流程都会严格变大，前端拿到后原样回传即可做乐观锁；过期 → {@code 409/10402}。
 * 注意它<b>不是</b>从 1 开始的小整数（见回传说明：前端不要把它格式化展示，也不要自己 +1）。
 *
 * @param id          流程 ID
 * @param projectId   所属项目 ID
 * @param name        流程名称
 * @param description 流程说明，可空
 * @param nodeCount   节点数
 * @param revision    并发版本号（= updated_at 毫秒时间戳）
 * @param createdBy   创建人
 * @param createdAt   创建时间
 * @param updatedAt   更新时间
 * @param nodes       节点列表（按 seq 升序）
 */
public record WorkflowVO(
        Long id,
        Long projectId,
        String name,
        String description,
        Integer nodeCount,
        RevisionStamp revision,
        Long createdBy,

        @JsonSerialize(using = TimeFormats.BizOffsetSerializer.class)
        LocalDateTime createdAt,

        @JsonSerialize(using = TimeFormats.BizOffsetSerializer.class)
        LocalDateTime updatedAt,

        List<NodeVO> nodes) {

    /**
     * 由 service 视图转换。
     * <p>
     * 参数用全限定名而不是 import：service 层也有一个同名 record {@code com.fde.workbench.service.vo.WorkflowVO}
     * （跨模块无法 import 同名类），这样写能同时保留"出网结构在 api 层定型"与"字段直接复用"。
     *
     * @param source service 层视图
     * @return 出网视图
     */
    public static WorkflowVO of(com.fde.workbench.service.vo.WorkflowVO source) {
        return new WorkflowVO(
                source.id(),
                source.projectId(),
                source.name(),
                source.description(),
                source.nodeCount(),
                source.revision(),
                source.createdBy(),
                toLocal(source.createdAt()),
                toLocal(source.updatedAt()),
                source.nodes());
    }

    private static LocalDateTime toLocal(OffsetDateTime value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
