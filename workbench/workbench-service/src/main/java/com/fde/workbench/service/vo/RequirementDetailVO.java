package com.fde.workbench.service.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fde.workbench.dao.entity.RequirementNode;
import com.fde.workbench.service.ServiceTimeFormats;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 需求详情视图（docs/api.md §7 {@code GET /api/requirements/{id}}）：
 * {@code { requirement, nodes[], permissions }}。
 * <p>
 * {@code nodes[]} 只带进度所需的精简字段（{@code id / seq / name / status / startedAt / completedAt}）：
 * 输入项、输出项、技能、上传文件等节点明细属于节点信息页（T5-1 的 {@code RequirementNodeVO}），
 * 详情接口不重复承载，避免两个接口对同一份数据的形状互相牵制。
 *
 * @param requirement 需求本体
 * @param nodes       节点实例列表（按 seq 升序）
 * @param permissions 当前登录人在该需求上的操作权限
 */
public record RequirementDetailVO(
        RequirementVO requirement,
        List<NodeProgressVO> nodes,
        PermissionsVO permissions) {

    /**
     * 节点进度项。
     *
     * @param id          节点实例 ID（对外即 nodeId）
     * @param seq         执行顺序
     * @param name        快照名称
     * @param status      状态 waiting / current / done
     * @param startedAt   成为当前节点的时间，可空
     * @param completedAt 完成时间，可空
     */
    public record NodeProgressVO(
            Long id,
            Integer seq,
            String name,
            String status,

            @JsonSerialize(using = ServiceTimeFormats.BizOffsetSerializer.class)
            OffsetDateTime startedAt,

            @JsonSerialize(using = ServiceTimeFormats.BizOffsetSerializer.class)
            OffsetDateTime completedAt) {

        /**
         * 由节点实例转换。
         *
         * @param node 节点实例
         * @return 进度项
         */
        public static NodeProgressVO of(RequirementNode node) {
            return new NodeProgressVO(node.getId(), node.getSeq(), node.getName(), node.getStatus(),
                    toOffset(node.getStartedAt()), toOffset(node.getCompletedAt()));
        }

        private static OffsetDateTime toOffset(LocalDateTime time) {
            return time == null ? null : time.atZone(ServiceTimeFormats.BIZ_ZONE).toOffsetDateTime();
        }
    }

    /**
     * 权限视图（docs/api.md §7）。
     *
     * @param canOperate 能否操作：创建人或 owner，<b>且</b>需求 status=in_progress
     * @param isCreator  当前登录人是否是需求创建人
     */
    public record PermissionsVO(Boolean canOperate, Boolean isCreator) {
    }
}
