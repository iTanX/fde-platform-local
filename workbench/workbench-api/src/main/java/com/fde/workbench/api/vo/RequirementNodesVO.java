package com.fde.workbench.api.vo;

import com.fde.workbench.dao.entity.Requirement;
import com.fde.workbench.dao.entity.RequirementNode;

import java.util.List;

/**
 * 需求节点清单出网视图（内部接口 {@code GET /internal/requirements/{requirementId}/nodes}）。
 * <p>
 * <b>为什么要有这个接口</b>：插件的 {@code node_context_get} 允许模型不传 {@code nodeInstanceId}，
 * 此时要回答"当前节点是哪个"。之前只能用会话 cwd 里的需求 ID 逐个试探节点实例 ID
 * （最多 256 次 {@code GET /context}），既浪费请求又靠"节点 ID 与需求 ID 相邻"这种偶然事实。
 * 本接口让后端一次给出该需求全部节点实例的定位信息，插件据此直接选中当前节点。
 * <p>
 * <b>为什么只放摘要</b>：这里只回答"有哪些节点、哪个是当前节点"，调用方据此再调
 * {@code GET /internal/nodes/{nid}/context} 取完整快照。输入 / 输出 / 技能快照这些大字段
 * （尤其是三个 JSON 列）不进本响应，避免一次列表请求搬运几十 KB 无关数据。
 *
 * @param requirementId    需求 ID
 * @param requirementTitle 需求标题
 * @param requirementStatus 需求状态：in_progress / completed
 * @param currentSeq       当前节点序号（需求内的执行游标）
 * @param nodes            全部节点实例摘要，按 seq 升序
 */
public record RequirementNodesVO(
        Long requirementId,
        String requirementTitle,
        String requirementStatus,
        Integer currentSeq,
        List<NodeBriefVO> nodes) {

    /**
     * 单个节点实例的定位摘要。
     *
     * @param nodeInstanceId 节点实例 ID（即其它内部接口与节点工具使用的 nodeInstanceId）
     * @param seq            执行顺序，从 1 开始
     * @param name           节点名称（创建需求时的快照）
     * @param status         waiting / current / done
     */
    public record NodeBriefVO(Long nodeInstanceId, Integer seq, String name, String status) {
    }

    /**
     * 由需求行与节点实例行组装。
     * <p>
     * {@code nodes} 入参已由 Mapper 按 {@code seq} 升序取回（见 {@code RequirementMapper#selectNodesByRequirement}），
     * 这里不再排序，保持"数据库给的顺序即执行顺序"这一单一事实来源。
     *
     * @param requirement 需求行（{@code wb_requirement}）
     * @param nodes       该需求全部节点实例（{@code wb_requirement_node}，按 seq 升序）
     * @return 出网视图
     */
    public static RequirementNodesVO of(Requirement requirement, List<RequirementNode> nodes) {
        List<NodeBriefVO> briefs = nodes.stream()
                .map(node -> new NodeBriefVO(node.getId(), node.getSeq(), node.getName(), node.getStatus()))
                .toList();
        return new RequirementNodesVO(requirement.getId(), requirement.getTitle(), requirement.getStatus(),
                requirement.getCurrentSeq(), briefs);
    }
}
