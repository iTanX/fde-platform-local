package com.fde.workbench.service.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fde.workbench.dao.entity.SkillReference;

import java.util.List;

/**
 * 技能被引用清单视图（docs/api.md §5 DELETE 的 409 响应体）。
 * <p>
 * 接口约定 {@code data} 直接是 {@code {references: [...]}}（api.md §5 示例），
 * 与全局异常处理产出的 {@code {code, message, data}} 拼起来正好是完整响应。
 *
 * @param references 引用该技能的流程模板节点与未完成需求节点
 */
public record SkillReferencesVO(List<Reference> references) {

    /** 每条引用只携带所属来源的字段。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Reference(String kind, Long workflowId, String workflowName,
                            Long requirementId, String requirementTitle, String nodeName) {

        /**
         * 由查询投影转换。
         *
         * @param reference 引用投影
         * @return 引用视图
         */
        public static Reference of(SkillReference reference) {
            return new Reference(
                    reference.getKind(), reference.getWorkflowId(), reference.getWorkflowName(),
                    reference.getRequirementId(),
                    reference.getRequirementTitle(),
                    reference.getNodeName());
        }
    }

    /**
     * 由查询投影列表转换。
     *
     * @param references 引用投影列表
     * @return 视图对象
     */
    public static SkillReferencesVO of(List<SkillReference> references) {
        return new SkillReferencesVO(references.stream().map(Reference::of).toList());
    }
}
