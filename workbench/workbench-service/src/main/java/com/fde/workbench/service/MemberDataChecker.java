package com.fde.workbench.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 移除成员前的数据完整性检查：统计该用户在本项目内是否还留有业务数据。
 * <p>
 * 为什么必须有这一步（而不是只靠外键）：以下列都指向 {@code wb_user}，与"他是不是项目成员"无关，
 * 删除成员记录时数据库不会报错，但项目里会留下属于"非成员"的数据：
 * <ul>
 *   <li>{@code wb_requirement.creator_id} —— 该成员创建的需求（外键能拦，但文案要靠本类给出）；</li>
 *   <li>{@code wb_workflow.created_by} —— 该成员创建的流程；</li>
 *   <li>{@code wb_file.uploaded_by} —— 该成员上传 / 产出的文件（含节点输出、会话产出，外键不会拦）；</li>
 *   <li>{@code wb_agent_session.created_by} —— 该成员在需求下创建的 DSH 会话（外键不会拦）。</li>
 * </ul>
 * 技能（{@code wb_skill.created_by}）故意<b>不</b>计入：api.md §4 规定移除成员时清理其技能，
 * 技能在被删除前另需通过 {@code fk_node_skill_skill}（被流程节点引用时失败回滚）。
 * <p>
 * 需求 / 流程 / 文件 / 会话的写接口分别属于 T3-x / T4-x，尚无 Mapper，故用 JdbcTemplate 直接按
 * 归属列计数；这些 SQL 只读、与具体业务规则无关，后续对应任务落地后如需统一可再迁入各自 Mapper。
 */
@Component
public class MemberDataChecker {

    private final JdbcTemplate jdbcTemplate;

    public MemberDataChecker(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 统计该成员在本项目内仍存在的数据。
     *
     * @param projectId 项目 ID
     * @param userId    被移除的用户 ID
     * @return 键为数据类别（中文，可直接拼进提示），值为数量；为空表示可以安全移除
     */
    public Map<String, Long> check(Long projectId, Long userId) {
        Map<String, Long> result = new LinkedHashMap<>();
        putIfPresent(result, "需求", count("SELECT COUNT(*) FROM wb_requirement WHERE project_id = ? AND creator_id = ?",
                projectId, userId));
        putIfPresent(result, "流程", count("SELECT COUNT(*) FROM wb_workflow WHERE project_id = ? AND created_by = ?",
                projectId, userId));
        putIfPresent(result, "文件", count("SELECT COUNT(*) FROM wb_file WHERE project_id = ? AND uploaded_by = ?",
                projectId, userId));
        putIfPresent(result, "会话", count("SELECT COUNT(*) FROM wb_agent_session s"
                + " JOIN wb_requirement r ON r.id = s.requirement_id"
                + " WHERE r.project_id = ? AND s.created_by = ?", projectId, userId));
        return result;
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private static void putIfPresent(Map<String, Long> target, String label, long count) {
        if (count > 0) {
            target.put(label, count);
        }
    }
}
