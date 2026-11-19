package com.fde.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 需求与节点实例接口契约测试（docs/api.md §7、PRD REQ-F1~F3 / AC-F1.1~F3.1、任务卡 T4-1）。
 * <p>
 * 数据隔离：每个用例都自建独立用户与项目（用户名带随机后缀 {@code t41_xxxxxxxx}），只读写自己的数据，
 * 不碰演示项目 1 / demo / demo2 的演示流程与技能，可与其它任务的测试数据共存。
 * <p>
 * 覆盖用例（对应任务卡"补自动化测试"要求）：
 * <ol>
 *   <li>创建后节点状态正确：第 1 个 current + startedAt，其余 waiting，current_seq=1，workspace 路径为绝对路径且目录已建；</li>
 *   <li>快照完整：含多技能与版本号（同一节点两个技能、其中一个有 v2 版本）、固定输入项、输出项；
 *       free 节点输入项快照为空数组；</li>
 *   <li>改流程后旧需求快照不变（改节点名、换技能、加强制要求）；</li>
 *   <li>非创建人编辑 → 403/10202；owner 可编辑他人的需求；</li>
 *   <li>列表关键字与状态筛选（含 LIKE 通配符转义与非法 status）；</li>
 *   <li>删除后节点实例级联清理（含会话三张表），revision 过期 → 409/10402。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class RequirementApiTest {

    /** 随机后缀：保证多次执行不撞用户名 / 项目名 / 标题 */
    private static final String SUFFIX = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ------------------------------------------------------------------
    // 用例 1 + 2：创建 → 节点状态 + 快照完整
    // ------------------------------------------------------------------

    @Test
    void create_generatesNodesWithCorrectStatusAndFullSnapshot() throws Exception {
        String token = registerAndLogin("t41_" + SUFFIX + "_c1");
        long projectId = createProject(token, "需求创建-" + SUFFIX);
        long skillA = insertSkill(projectId, "需求拆解-" + SUFFIX, 0);
        // 技能 B 升到 v2：快照必须记录"创建那一刻的当前版本"
        long skillB = insertSkill(projectId, "风险识别-" + SUFFIX, 0);
        insertSkillVersion(skillB, 2);
        JsonNode workflow = createWorkflow(token, projectId, "标准流程-" + SUFFIX);
        long workflowId = workflow.path("id").asLong();
        long revision = workflow.path("revision").asLong();

        // 节点 1：free，无技能，1 个输出项
        // 节点 2：fixed，2 个技能（A、B 各带版本），2 个输入项（一个必填一个非必填），2 个输出项
        // 节点 3：free，1 个技能 A
        JsonNode saved = saveNodesOk(token, workflowId, revision, "["
                + node(null, "需求澄清", "收集客户原话", "free", "[]", "[]",
                "[" + output("clarify-notes", "澄清记录", "text", true) + "]") + ","
                + node(null, "需求拆解", "拆出需求条目", "fixed", "[" + skillA + "," + skillB + "]",
                "[" + input("客户原话整理", "text", true) + "," + input("现场资料清单", "any", false) + "]",
                "[" + output("requirement-items", "需求条目清单", "text", true) + ","
                        + output("acceptance-criteria", "验收标准", "text", true) + "]") + ","
                + node(null, "需求确认", "核对条目", "free", "[" + skillA + "]", "[]",
                "[" + output("confirm-notes", "确认记录", "text", true) + "]")
                + "]");
        assertNotEquals(revision, saved.path("revision").asLong(), "保存节点后流程 revision 必须变化");
        // 流程节点上的输入项 / 输出项 ID：快照的 itemId 必须与它们一致
        long clarifyOutputId = saved.path("nodes").get(0).path("outputs").get(0).path("itemId").asLong();
        long input1Id = saved.path("nodes").get(1).path("inputs").get(0).path("itemId").asLong();
        long input2Id = saved.path("nodes").get(1).path("inputs").get(1).path("itemId").asLong();
        long requirementItemsId = saved.path("nodes").get(1).path("outputs").get(0).path("itemId").asLong();
        long acceptanceId = saved.path("nodes").get(1).path("outputs").get(1).path("itemId").asLong();

        // ---- 创建需求 ----
        JsonNode detail = createRequirementOk(token, projectId, "客户工单分诊-" + SUFFIX, "来自客服系统", workflowId);
        JsonNode requirement = detail.path("requirement");
        long requirementId = requirement.path("id").asLong();
        assertEquals(projectId, requirement.path("projectId").asLong());
        assertEquals(workflowId, requirement.path("workflowId").asLong());
        assertEquals("标准流程-" + SUFFIX, requirement.path("workflowName").asText(), "列表 / 详情要带流程名");
        assertEquals("in_progress", requirement.path("status").asText());
        assertEquals(1, requirement.path("currentSeq").asInt(), "current_seq 从 1 开始");
        assertEquals(3, requirement.path("nodeCount").asInt());
        assertEquals(0, requirement.path("doneNodeCount").asInt());
        assertEquals(1, requirement.path("revision").asInt(), "新需求 revision = 1");
        assertEquals(true, requirement.path("editable").asBoolean());

        // ---- 节点状态：第 1 个 current + startedAt，其余 waiting 且没有 startedAt ----
        JsonNode nodes = detail.path("nodes");
        assertEquals(3, nodes.size());
        assertEquals(List.of(1, 2, 3), seqs(nodes));
        assertEquals("current", nodes.get(0).path("status").asText());
        assertFalse(nodes.get(0).path("startedAt").isNull(), "当前节点必须写 started_at");
        assertEquals("需求澄清", nodes.get(0).path("name").asText());
        for (int i = 1; i < 3; i++) {
            assertEquals("waiting", nodes.get(i).path("status").asText(), "第 " + (i + 1) + " 个节点应为 waiting");
            assertTrue(nodes.get(i).path("startedAt").isNull(), "未开始的节点没有 started_at");
            assertTrue(nodes.get(i).path("completedAt").isNull());
        }
        // 库里再确认一次（接口与 SQL 两条路径）：seq 1 = current，seq 2、3 = waiting
        assertEquals(List.of("current", "waiting", "waiting"), jdbcStatuses(requirementId, 1, 3));

        // ---- workspace：绝对路径 = <storage-root>/ws/<requirementId>，且目录已创建 ----
        String workspacePath = jdbcTemplate.queryForObject(
                "SELECT dsh_workspace_path FROM wb_requirement WHERE id = ?", String.class, requirementId);
        assertNotNull(workspacePath);
        Path workspace = Paths.get(workspacePath);
        assertTrue(workspace.isAbsolute(), "数据库必须存绝对路径：" + workspacePath);
        assertEquals("ws", workspace.getParent().getFileName().toString(),
                "路径必须是 <storage-root>/ws/<requirementId>：" + workspacePath);
        assertEquals(String.valueOf(requirementId), workspace.getFileName().toString());
        assertTrue(Files.isDirectory(workspace), "workspace 目录必须已创建：" + workspacePath);

        // ---- 快照：逐列校验（从库里读原始 JSON，与接口字段分开验证）----
        // 节点 1：free → inputs_snapshot 必须为空数组
        Map<String, Object> node1 = nodeRow(requirementId, 1);
        assertEquals("需求澄清", node1.get("name"));
        assertEquals("收集客户原话", node1.get("description"));
        assertEquals("free", node1.get("input_type"));
        assertEquals(0, parse(String.valueOf(node1.get("inputs_snapshot"))).size(), "free 节点输入项快照为空");
        JsonNode n1Outputs = parse(String.valueOf(node1.get("outputs_snapshot")));
        assertEquals(1, n1Outputs.size());
        assertEquals(clarifyOutputId, n1Outputs.get(0).path("itemId").asLong());
        assertEquals("clarify-notes", n1Outputs.get(0).path("itemKey").asText());
        assertEquals("澄清记录", n1Outputs.get(0).path("name").asText());
        assertEquals("text", n1Outputs.get(0).path("fileKind").asText());
        assertEquals(true, n1Outputs.get(0).path("required").asBoolean());
        assertEquals(0, parse(String.valueOf(node1.get("skills_snapshot"))).size());

        // 节点 2：fixed → 2 个输入项 + 2 个输出项 + 2 个技能（含版本号）
        Map<String, Object> node2 = nodeRow(requirementId, 2);
        assertEquals("需求拆解", node2.get("name"));
        assertEquals("fixed", node2.get("input_type"));
        JsonNode n2Inputs = parse(String.valueOf(node2.get("inputs_snapshot")));
        assertEquals(2, n2Inputs.size());
        assertEquals(input1Id, n2Inputs.get(0).path("itemId").asLong());
        assertEquals("客户原话整理", n2Inputs.get(0).path("name").asText());
        assertEquals("text", n2Inputs.get(0).path("fileKind").asText());
        assertEquals(true, n2Inputs.get(0).path("required").asBoolean());
        assertEquals(input2Id, n2Inputs.get(1).path("itemId").asLong());
        assertEquals(false, n2Inputs.get(1).path("required").asBoolean(), "非必填项 required 必须保留 false");
        JsonNode n2Outputs = parse(String.valueOf(node2.get("outputs_snapshot")));
        assertEquals(2, n2Outputs.size());
        assertEquals(requirementItemsId, n2Outputs.get(0).path("itemId").asLong());
        assertEquals("requirement-items", n2Outputs.get(0).path("itemKey").asText());
        assertEquals(acceptanceId, n2Outputs.get(1).path("itemId").asLong());
        assertEquals("acceptance-criteria", n2Outputs.get(1).path("itemKey").asText());
        JsonNode n2Skills = parse(String.valueOf(node2.get("skills_snapshot")));
        assertEquals(2, n2Skills.size(), "多技能必须全部快照");
        assertEquals(skillA, n2Skills.get(0).path("skillId").asLong(), "技能按选择顺序");
        assertEquals("需求拆解-" + SUFFIX, n2Skills.get(0).path("name").asText());
        assertEquals(1, n2Skills.get(0).path("versionNo").asInt(), "技能 A 只有 v1");
        assertEquals(skillB, n2Skills.get(1).path("skillId").asLong());
        assertEquals("风险识别-" + SUFFIX, n2Skills.get(1).path("name").asText());
        assertEquals(2, n2Skills.get(1).path("versionNo").asInt(), "技能 B 当前是 v2，快照必须记版本号");
        long skillBVersionId = n2Skills.get(1).path("versionId").asLong();
        assertEquals(jdbcTemplate.queryForObject("SELECT id FROM wb_skill_version WHERE skill_id = ? AND version_no = 2",
                Long.class, skillB), skillBVersionId, "versionId 必须指向 v2 的版本行");

        // 节点 3：free + 1 个技能
        Map<String, Object> node3 = nodeRow(requirementId, 3);
        assertEquals(0, parse(String.valueOf(node3.get("inputs_snapshot"))).size());
        JsonNode n3Skills = parse(String.valueOf(node3.get("skills_snapshot")));
        assertEquals(1, n3Skills.size());
        assertEquals(skillA, n3Skills.get(0).path("skillId").asLong());

        // ---- 权限：创建人 ----
        assertTrue(detail.path("permissions").path("canOperate").asBoolean());
        assertTrue(detail.path("permissions").path("isCreator").asBoolean());

        // ---- 不创建会话：会话登记是 T5-1 的事 ----
        assertEquals(0, count("SELECT COUNT(*) FROM wb_agent_session WHERE requirement_id = ?", requirementId),
                "创建需求时不得创建会话");
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wb_requirement_node WHERE requirement_id = ? AND primary_session_id IS NOT NULL",
                Long.class, requirementId));
    }

    // ------------------------------------------------------------------
    // 用例 3：修改流程后旧需求快照不变（PRD AC-F2.2）
    // ------------------------------------------------------------------

    @Test
    void modifyWorkflow_doesNotAffectExistingRequirementSnapshot() throws Exception {
        String token = registerAndLogin("t41_" + SUFFIX + "_c2");
        long projectId = createProject(token, "快照隔离-" + SUFFIX);
        long skillA = insertSkill(projectId, "技能A-" + SUFFIX, 0);
        long skillB = insertSkill(projectId, "技能B-" + SUFFIX, 0);
        JsonNode workflow = createWorkflow(token, projectId, "会被改的流程-" + SUFFIX);
        long workflowId = workflow.path("id").asLong();
        JsonNode saved = saveNodesOk(token, workflowId, workflow.path("revision").asLong(), "["
                + node(null, "需求澄清", "原说明", "free", "[" + skillA + "]", "[]",
                "[" + output("clarify-notes", "澄清记录", "text", true) + "]") + ","
                + node(null, "需求拆解", "原说明2", "fixed", "[" + skillA + "]",
                "[" + input("客户原话整理", "text", true) + "]",
                "[" + output("requirement-items", "需求条目清单", "text", true) + "]")
                + "]");

        long requirementId = createRequirementOk(token, projectId, "改流程前创建-" + SUFFIX, null, workflowId)
                .path("requirement").path("id").asLong();
        List<Map<String, Object>> before = snapshotRows(requirementId);
        assertEquals(2, before.size());

        // ---- 改流程：第 1 个节点换技能、改说明；第 2 个节点的输入项改成必填文本以外的类型并改名 ----
        long firstNodeId = saved.path("nodes").get(0).path("id").asLong();
        long secondNodeId = saved.path("nodes").get(1).path("id").asLong();
        saveNodesOk(token, workflowId, saved.path("revision").asLong(), "["
                + node(firstNodeId, "需求澄清（改名）", "改后的说明", "free", "[" + skillB + "]", "[]",
                "[" + output("clarify-notes-v2", "澄清记录v2", "document", false) + "]") + ","
                + node(secondNodeId, "需求拆解（改名）", "改后的说明2", "fixed", "[" + skillB + "," + skillA + "]",
                "[" + input("改后的输入项", "any", false) + "]",
                "[" + output("requirement-items", "需求条目清单v2", "text", true) + "]")
                + "]");

        // ---- 快照逐列不变（名称、说明、输入类型、三个 JSON 列、状态、seq）----
        List<Map<String, Object>> after = snapshotRows(requirementId);
        assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) {
            assertEquals(before.get(i), after.get(i), "第 " + (i + 1) + " 个节点实例的快照被流程修改影响了");
        }
        // 再明确断言几个关键点，避免"整行相等"掩盖格式化差异
        JsonNode skillsAfter = parse(String.valueOf(after.get(0).get("skills_snapshot")));
        assertEquals(1, skillsAfter.size());
        assertEquals(skillA, skillsAfter.get(0).path("skillId").asLong(), "旧需求仍绑定原来的技能 A");
        assertEquals("需求澄清", after.get(0).get("name"), "旧需求的节点名称不随流程改名");
        assertEquals("原说明", after.get(0).get("description"), "旧需求的节点说明不随流程修改");
        JsonNode outputsAfter = parse(String.valueOf(after.get(0).get("outputs_snapshot")));
        assertEquals("clarify-notes", outputsAfter.get(0).path("itemKey").asText(), "itemKey 也不得随流程变化");
        assertEquals(true, outputsAfter.get(0).path("required").asBoolean());

        // ---- 改完之后新建的需求拿到的是新快照（证明差异来自快照而不是流程根本没改）----
        long newRequirementId = createRequirementOk(token, projectId, "改流程后创建-" + SUFFIX, null, workflowId)
                .path("requirement").path("id").asLong();
        List<Map<String, Object>> fresh = snapshotRows(newRequirementId);
        assertEquals("需求澄清（改名）", fresh.get(0).get("name"));
        assertEquals(skillB, parse(String.valueOf(fresh.get(0).get("skills_snapshot"))).get(0)
                .path("skillId").asLong());
        assertEquals("clarify-notes-v2",
                parse(String.valueOf(fresh.get(0).get("outputs_snapshot"))).get(0).path("itemKey").asText());
    }

    // ------------------------------------------------------------------
    // 用例 4：权限 —— 非创建人编辑 403，owner 可编辑
    // ------------------------------------------------------------------

    @Test
    void onlyCreatorOrOwner_canUpdate() throws Exception {
        String owner = "t41_" + SUFFIX + "_o3";
        String ownerToken = registerAndLogin(owner);
        long projectId = createProject(ownerToken, "需求权限-" + SUFFIX);
        String member = "t41_" + SUFFIX + "_m3";
        registerAndLogin(member);
        addMemberOk(ownerToken, projectId, member);
        String memberToken = login(member, "");

        JsonNode workflow = createWorkflow(ownerToken, projectId, "权限流程-" + SUFFIX);
        long workflowId = workflow.path("id").asLong();
        saveNodesOk(ownerToken, workflowId, workflow.path("revision").asLong(), "["
                + node(null, "需求澄清", null, "free", "[]", "[]",
                "[" + output("clarify-notes", "澄清记录", "text", true) + "]") + "]");

        // 成员创建的需求
        JsonNode created = createRequirementOk(memberToken, projectId, "成员的需求-" + SUFFIX, null, workflowId);
        long requirementId = created.path("requirement").path("id").asLong();
        assertTrue(created.path("permissions").path("canOperate").asBoolean());
        assertTrue(created.path("permissions").path("isCreator").asBoolean());

        // owner 看成员的成员需求：可看、可编辑（C），但不是 creator
        JsonNode ownerView = readData(mockMvc.perform(get("/api/requirements/" + requirementId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn());
        assertTrue(ownerView.path("permissions").path("canOperate").asBoolean(), "owner 可以操作成员的需求");
        assertFalse(ownerView.path("permissions").path("isCreator").asBoolean());
        assertTrue(ownerView.path("requirement").path("editable").asBoolean(), "owner 可以编辑");

        // 非创建人 member：自己的需求能改
        mockMvc.perform(put("/api/requirements/" + requirementId)
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "成员改自己的-" + SUFFIX, "revision", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requirement.title").value("成员改自己的-" + SUFFIX))
                .andExpect(jsonPath("$.data.requirement.revision").value(2));

        // owner 创建的需求 → 普通成员（非创建者、非 owner）编辑：403/10202
        JsonNode ownerCreated = createRequirementOk(ownerToken, projectId, "owner 的需求-" + SUFFIX, null, workflowId);
        long ownerRequirementId = ownerCreated.path("requirement").path("id").asLong();
        mockMvc.perform(get("/api/requirements/" + ownerRequirementId)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissions.canOperate").value(false))
                .andExpect(jsonPath("$.data.permissions.isCreator").value(false))
                .andExpect(jsonPath("$.data.requirement.editable").value(false));
        mockMvc.perform(put("/api/requirements/" + ownerRequirementId)
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "成员越权改名", "revision", 1))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10202));
        mockMvc.perform(delete("/api/requirements/" + ownerRequirementId)
                        .param("revision", "1")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10202));
        // owner 仍然可以编辑自己的需求（C 的另一半）
        mockMvc.perform(put("/api/requirements/" + ownerRequirementId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "owner 改自己的-" + SUFFIX, "revision", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requirement.revision").value(2));

        // 非成员：连详情都看不到（403/10201，不泄露存在性）
        String outsider = "t41_" + SUFFIX + "_x3";
        String outsiderToken = registerAndLogin(outsider);
        mockMvc.perform(get("/api/requirements/" + ownerRequirementId)
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10201));
    }

    // ------------------------------------------------------------------
    // 用例 5：列表关键字与状态筛选
    // ------------------------------------------------------------------

    @Test
    void list_filtersByKeywordAndStatus() throws Exception {
        String token = registerAndLogin("t41_" + SUFFIX + "_l4");
        long projectId = createProject(token, "需求列表-" + SUFFIX);
        JsonNode workflow = createWorkflow(token, projectId, "列表流程-" + SUFFIX);
        long workflowId = workflow.path("id").asLong();
        saveNodesOk(token, workflowId, workflow.path("revision").asLong(), "["
                + node(null, "需求澄清", null, "free", "[]", "[]",
                "[" + output("clarify-notes", "澄清记录", "text", true) + "]") + "]");

        long alpha = createRequirementOk(token, projectId, "Alpha 工单-" + SUFFIX, null, workflowId)
                .path("requirement").path("id").asLong();
        long beta = createRequirementOk(token, projectId, "Beta 工单-" + SUFFIX, null, workflowId)
                .path("requirement").path("id").asLong();
        long percent = createRequirementOk(token, projectId, "100% 完成率-" + SUFFIX, null, workflowId)
                .path("requirement").path("id").asLong();
        createRequirementOk(token, projectId, "Gamma 无关-" + SUFFIX, null, workflowId);

        // 把 Alpha 置为已完成（模拟推进到最后一个节点后完工，T5-1 的真实动作）
        jdbcTemplate.update("UPDATE wb_requirement SET status = 'completed' WHERE id = ?", alpha);

        // 关键字：标题模糊
        JsonNode byKeyword = listOk(token, projectId, "keyword=工单", null);
        assertEquals(2, byKeyword.path("total").asInt(), "只有 Alpha / Beta 含『工单』");
        List<Long> ids = ids(byKeyword.path("items"));
        assertTrue(ids.contains(alpha) && ids.contains(beta));

        // 状态筛选：in_progress 不含 Alpha
        JsonNode inProgress = listOk(token, projectId, null, "status=in_progress");
        assertEquals(3, inProgress.path("total").asInt());
        assertFalse(ids(inProgress.path("items")).contains(alpha), "已完成的需求不该出现在进行中列表");
        JsonNode completed = listOk(token, projectId, null, "status=completed");
        assertEquals(1, completed.path("total").asInt());
        assertEquals(alpha, ids(completed.path("items")).get(0));

        // 关键字 + 状态组合
        JsonNode both = listOk(token, projectId, "keyword=工单", "status=completed");
        assertEquals(1, both.path("total").asInt());
        assertEquals(alpha, ids(both.path("items")).get(0));

        // LIKE 通配符必须转义：搜 "100%" 只能命中含字面 "100%" 的那条，而不是全部
        // （MockMvc 的 param 传的是解码后的值，这里直接传 "100%"）
        JsonNode literal = listOk(token, projectId, "keyword=100%", null);
        assertEquals(1, literal.path("total").asInt(), "% 必须当字面量处理");
        assertEquals(percent, ids(literal.path("items")).get(0));

        // 分页：size=2 时第一页 2 条，total 不受分页影响
        JsonNode page1 = readData(mockMvc.perform(get("/api/projects/" + projectId + "/requirements")
                        .param("page", "1").param("size", "2")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn());
        assertEquals(4, page1.path("total").asInt());
        assertEquals(2, page1.path("items").size());
        assertEquals(1, page1.path("page").asInt());
        assertEquals(2, page1.path("size").asInt());

        // 非法 status → 400/10001
        mockMvc.perform(get("/api/projects/" + projectId + "/requirements")
                        .param("status", "done")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value(containsString("in_progress")));

        // 非成员看不到列表
        String outsiderToken = registerAndLogin("t41_" + SUFFIX + "_lx4");
        mockMvc.perform(get("/api/projects/" + projectId + "/requirements")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10201));
    }

    // ------------------------------------------------------------------
    // 用例 6：删除后节点级联清理 + revision 冲突 / 缺失
    // ------------------------------------------------------------------

    @Test
    void delete_cascadesNodesAndSessions_andRejectsStaleRevision() throws Exception {
        String token = registerAndLogin("t41_" + SUFFIX + "_d5");
        long projectId = createProject(token, "需求删除-" + SUFFIX);
        JsonNode workflow = createWorkflow(token, projectId, "删除流程-" + SUFFIX);
        long workflowId = workflow.path("id").asLong();
        saveNodesOk(token, workflowId, workflow.path("revision").asLong(), "["
                + node(null, "需求澄清", null, "free", "[]", "[]",
                "[" + output("clarify-notes", "澄清记录", "text", true) + "]") + ","
                + node(null, "需求拆解", null, "free", "[]", "[]",
                "[" + output("requirement-items", "需求条目清单", "text", true) + "]")
                + "]");
        long requirementId = createRequirementOk(token, projectId, "待删除需求-" + SUFFIX, null, workflowId)
                .path("requirement").path("id").asLong();
        assertEquals(2, count("SELECT COUNT(*) FROM wb_requirement_node WHERE requirement_id = ?", requirementId));
        long firstNodeId = jdbcTemplate.queryForObject(
                "SELECT id FROM wb_requirement_node WHERE requirement_id = ? AND seq = 1", Long.class,
                requirementId);

        // 造一条会话关联（T5-1 才会创建，这里直接插，用于验证级联）
        long userId = jdbcTemplate.queryForObject("SELECT created_by FROM wb_workflow WHERE id = ?", Long.class,
                workflowId);
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("INSERT INTO wb_agent_session (requirement_id, dsh_session_id, dsh_workspace_path,"
                        + " title, created_by, last_active_at, archived, created_at, updated_at)"
                        + " VALUES (?, ?, ?, NULL, ?, ?, 0, ?, ?)",
                requirementId, "t41_sess_" + SUFFIX, "/tmp/t41-" + SUFFIX, userId, now, now, now);
        long sessionId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbcTemplate.update("INSERT INTO wb_session_runtime_info (agent_session_id, last_node_instance_id,"
                + " agent_preset_id, last_prompt_at, message_count, created_at, updated_at)"
                + " VALUES (?, ?, NULL, NULL, 0, ?, ?)", sessionId, firstNodeId, now, now);
        jdbcTemplate.update("INSERT INTO wb_node_session_link (node_instance_id, agent_session_id, created_at)"
                + " VALUES (?, ?, ?)", firstNodeId, sessionId, now);
        jdbcTemplate.update("UPDATE wb_requirement_node SET primary_session_id = ? WHERE id = ?",
                sessionId, firstNodeId);

        // revision 缺失 → 400/10001
        mockMvc.perform(delete("/api/requirements/" + requirementId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value(containsString("revision")));

        // 先编辑一次把 revision 推到 2，再用旧 revision 1 删除 → 409/10402
        mockMvc.perform(put("/api/requirements/" + requirementId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"改过的标题-" + SUFFIX + "\",\"revision\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requirement.revision").value(2));
        mockMvc.perform(delete("/api/requirements/" + requirementId)
                        .param("revision", "1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(10402));
        // 冲突时什么都没删（事务整体回滚）
        assertEquals(1, count("SELECT COUNT(*) FROM wb_requirement WHERE id = ?", requirementId));
        assertEquals(2, count("SELECT COUNT(*) FROM wb_requirement_node WHERE requirement_id = ?", requirementId));
        assertEquals(1, count("SELECT COUNT(*) FROM wb_agent_session WHERE id = ?", sessionId));

        // 用当前 revision 删除 → 200，节点实例与会话关联全部清理
        mockMvc.perform(delete("/api/requirements/" + requirementId)
                        .param("revision", "2")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(0, count("SELECT COUNT(*) FROM wb_requirement WHERE id = ?", requirementId));
        assertEquals(0, count("SELECT COUNT(*) FROM wb_requirement_node WHERE requirement_id = ?", requirementId));
        assertEquals(0, count("SELECT COUNT(*) FROM wb_agent_session WHERE id = ?", sessionId));
        assertEquals(0, count("SELECT COUNT(*) FROM wb_session_runtime_info WHERE agent_session_id = ?", sessionId));
        assertEquals(0, count("SELECT COUNT(*) FROM wb_node_session_link WHERE agent_session_id = ?", sessionId));

        // 删除后再查 → 404/10301
        mockMvc.perform(get("/api/requirements/" + requirementId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(10301));
    }

    // ------------------------------------------------------------------
    // 用例 7：创建校验（流程不属于本项目 / 流程没有节点 / 缺 workflowId / 标题为空）
    // ------------------------------------------------------------------

    @Test
    void create_validatesWorkflow() throws Exception {
        String token = registerAndLogin("t41_" + SUFFIX + "_v6");
        long projectId = createProject(token, "创建校验-" + SUFFIX);
        long otherProjectId = createProject(token, "另一个项目-" + SUFFIX);
        JsonNode foreign = createWorkflow(token, otherProjectId, "别的项目的流程-" + SUFFIX);

        // 流程不属于本项目 → 404/10301（与"流程不存在"同口径，不泄露别的项目的数据）
        mockMvc.perform(post("/api/projects/" + projectId + "/requirements")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "跨项目-" + SUFFIX,
                                "workflowId", foreign.path("id").asLong()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(10301));

        // 流程没有节点 → 400/10001（没有节点就没有"当前节点"，无法建实例）
        JsonNode empty = createWorkflow(token, projectId, "空流程-" + SUFFIX);
        mockMvc.perform(post("/api/projects/" + projectId + "/requirements")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "空流程需求-" + SUFFIX,
                                "workflowId", empty.path("id").asLong()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value(containsString("节点")));
        assertEquals(0, count("SELECT COUNT(*) FROM wb_requirement WHERE project_id = ?", projectId),
                "校验失败不能留下需求行");

        // 缺 workflowId → 400/10001
        mockMvc.perform(post("/api/projects/" + projectId + "/requirements")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"没有流程-" + SUFFIX + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001));

        // 标题空 → 400/10001
        mockMvc.perform(post("/api/projects/" + projectId + "/requirements")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "   ", "workflowId", empty.path("id").asLong()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001));
    }

    // ------------------------------------------------------------------
    // 工具方法：账号 / 项目 / 流程 / 需求
    // ------------------------------------------------------------------

    private void register(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"\"}"))
                .andExpect(status().isOk());
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("token").asText();
    }

    /** 空密码注册 + 登录，返回 token */
    private String registerAndLogin(String username) throws Exception {
        register(username);
        return login(username, "");
    }

    private long createProject(String token, String name) throws Exception {
        return readData(mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", name))))
                .andExpect(status().isOk())
                .andReturn()).path("id").asLong();
    }

    private void addMemberOk(String token, long projectId, String username) throws Exception {
        mockMvc.perform(post("/api/projects/" + projectId + "/members")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username))))
                .andExpect(status().isOk());
    }

    private JsonNode createWorkflow(String token, long projectId, String name) throws Exception {
        return readData(mockMvc.perform(post("/api/projects/" + projectId + "/workflows")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", name))))
                .andExpect(status().isOk())
                .andReturn());
    }

    private JsonNode saveNodesOk(String token, long workflowId, long revision, String nodesJson) throws Exception {
        ResultActions actions = mockMvc.perform(put("/api/workflows/" + workflowId + "/nodes")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":" + revision + ",\"nodes\":" + nodesJson + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        return readData(actions.andReturn());
    }

    /**
     * 创建需求并断言成功，返回详情 {@code data}。
     * <p>
     * 注意不要用 {@code Map.of(...)} 拼带 null 的请求体：{@code Map.of} 不接受 null 值。
     */
    private JsonNode createRequirementOk(String token, long projectId, String title, String description,
                                         long workflowId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        body.put("description", description);
        body.put("workflowId", workflowId);
        ResultActions actions = mockMvc.perform(post("/api/projects/" + projectId + "/requirements")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        return readData(actions.andReturn());
    }

    private JsonNode listOk(String token, long projectId, String keywordParam, String statusParam)
            throws Exception {
        var request = get("/api/projects/" + projectId + "/requirements")
                .header("Authorization", "Bearer " + token);
        if (keywordParam != null) {
            // keywordParam 形如 "keyword=工单"（解码后的值），这里按第一个 = 拆开
            int idx = keywordParam.indexOf('=');
            request = request.param(keywordParam.substring(0, idx), keywordParam.substring(idx + 1));
        }
        if (statusParam != null) {
            int idx = statusParam.indexOf('=');
            request = request.param(statusParam.substring(0, idx), statusParam.substring(idx + 1));
        }
        return readData(mockMvc.perform(request).andExpect(status().isOk()).andReturn());
    }

    private JsonNode readData(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    private JsonNode parse(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private static List<Integer> seqs(JsonNode nodes) {
        List<Integer> result = new ArrayList<>();
        for (JsonNode node : nodes) {
            result.add(node.path("seq").asInt());
        }
        return result;
    }

    private static List<Long> ids(JsonNode items) {
        List<Long> result = new ArrayList<>();
        for (JsonNode item : items) {
            result.add(item.path("id").asLong());
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 工具方法：直接读写数据库（断言快照原值 / 造数）
    // ------------------------------------------------------------------

    private long insertSkill(long projectId, String name, int deleted) {
        Long ownerId = jdbcTemplate.queryForObject("SELECT owner_id FROM wb_project WHERE id = ?", Long.class,
                projectId);
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("INSERT INTO wb_skill (project_id, name, description, current_version_id, created_by,"
                        + " deleted, created_at, updated_at) VALUES (?, ?, NULL, NULL, ?, ?, ?, ?)",
                projectId, name, ownerId, deleted, now, now);
        long skillId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        insertSkillVersion(skillId, 1);
        return skillId;
    }

    /** 直接给技能插一个版本行并把技能指向它（模拟"已上传过某版本"） */
    private void insertSkillVersion(long skillId, int versionNo) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("INSERT INTO wb_skill_version (skill_id, version_no, files_json, checksum, entry_path,"
                        + " uploaded_by, created_at) VALUES (?, ?, '[]', ?, 'SKILL.md', ?, ?)",
                skillId, versionNo, "0".repeat(64), 1L, now);
        long versionId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbcTemplate.update("UPDATE wb_skill SET current_version_id = ? WHERE id = ?", versionId, skillId);
    }

    /** 取某个节点实例的整行快照字段（库中原值） */
    private Map<String, Object> nodeRow(long requirementId, int seq) {
        return jdbcTemplate.queryForMap("SELECT seq, name, description, input_type, inputs_snapshot,"
                + " outputs_snapshot, skills_snapshot, status, started_at FROM wb_requirement_node"
                + " WHERE requirement_id = ? AND seq = ?", requirementId, seq);
    }

    /** 取需求下全部节点实例的快照列（按 seq 排序），用于"改流程前后逐列比较" */
    private List<Map<String, Object>> snapshotRows(long requirementId) {
        return jdbcTemplate.queryForList("SELECT seq, name, description, input_type, inputs_snapshot,"
                + " outputs_snapshot, skills_snapshot, status, started_at FROM wb_requirement_node"
                + " WHERE requirement_id = ? ORDER BY seq", requirementId);
    }

    private List<String> jdbcStatuses(long requirementId, int fromSeq, int toSeq) {
        return jdbcTemplate.queryForList("SELECT status FROM wb_requirement_node WHERE requirement_id = ?"
                + " AND seq BETWEEN ? AND ? ORDER BY seq", String.class, requirementId, fromSeq, toSeq);
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    /** 组装一个节点 JSON（nodeId 可空） */
    private static String node(Long nodeId, String name, String description, String inputType,
                               String skillIds, String inputs, String outputs) {
        return "{\"nodeId\":" + (nodeId == null ? "null" : nodeId)
                + ",\"name\":" + quote(name)
                + ",\"description\":" + (description == null ? "null" : quote(description))
                + ",\"inputType\":\"" + inputType + "\""
                + ",\"skillIds\":" + skillIds
                + ",\"inputs\":" + inputs
                + ",\"outputs\":" + outputs + "}";
    }

    private static String input(String name, String fileKind, boolean required) {
        return "{\"name\":" + quote(name) + ",\"fileKind\":\"" + fileKind + "\",\"required\":" + required + "}";
    }

    private static String output(String itemKey, String name, String fileKind, boolean required) {
        return "{\"itemKey\":\"" + itemKey + "\",\"name\":" + quote(name)
                + ",\"fileKind\":\"" + fileKind + "\",\"required\":" + required + "}";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
