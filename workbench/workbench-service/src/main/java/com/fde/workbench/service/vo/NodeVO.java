package com.fde.workbench.service.vo;

import java.util.List;

/**
 * 流程节点视图对象（docs/api.md §1.4 NodeVO）。
 * <p>
 * 回显顺序一律以 {@code seq} 为准：节点按节点 seq 排，输入项 / 输出项 / 技能按各自 seq 排，
 * 因此前端"拖到第 1 位保存后刷新顺序一致"（PRD AC-E2.1）不需要再做二次排序。
 *
 * @param id          节点 ID
 * @param seq         执行顺序（从 1 开始）
 * @param name        节点名称
 * @param description 节点说明，可空
 * @param inputType   {@code free} / {@code fixed}
 * @param skills      可用技能（顺序即加载顺序）
 * @param inputs      固定输入项（{@code free} 节点为空数组）
 * @param outputs     输出项（至少 1 项）
 */
public record NodeVO(
        Long id,
        Integer seq,
        String name,
        String description,
        String inputType,
        List<SkillRefVO> skills,
        List<InputVO> inputs,
        List<OutputVO> outputs) {

    /**
     * 节点上的技能引用。
     *
     * @param skillId 技能 ID
     * @param name    技能名称（技能被逻辑删除后仍保留名称，便于展示历史配置）
     * @param seq     加载顺序，从 1 开始
     */
    public record SkillRefVO(Long skillId, String name, Integer seq) {
    }

    /**
     * 固定输入项。
     *
     * @param itemId      输入项 ID
     * @param seq         节点内顺序
     * @param name        名称
     * @param description 说明，可空
     * @param fileKind    文件类型
     * @param required    是否必填
     */
    public record InputVO(Long itemId, Integer seq, String name, String description,
                          String fileKind, Boolean required) {
    }

    /**
     * 固定输出项。
     *
     * @param itemId      输出项 ID
     * @param seq         节点内顺序
     * @param itemKey     稳定键（模型工具按它提交产出）
     * @param name        名称
     * @param description 输出要求，可空
     * @param fileKind    文件类型
     * @param required    是否必填
     */
    public record OutputVO(Long itemId, Integer seq, String itemKey, String name, String description,
                           String fileKind, Boolean required) {
    }
}
