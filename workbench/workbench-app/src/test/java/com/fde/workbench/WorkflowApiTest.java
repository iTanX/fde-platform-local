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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 流程管理接口契约测试（docs/api.md §6、PRD REQ-E1~REQ-E5、任务卡 T3-1）。
 * <p>
 * 数据隔离：每个用例都自建独立用户与项目（用户名带随机后缀），不读写演示项目 1 与 demo / demo2 的演示流程，
 * 可与演示数据及其他任务的测试数据共存。
 * <p>
 * 覆盖用例（对应任务卡要求）：
 * <ol>
 *   <li>创建流程 → 列表含节点数；</li>
 *   <li>整体保存（新增 3 节点、多技能、fixed 输入、2 个输出项）→ 再整体保存交换第 1、3 节点 → seq 已重排；</li>
 *   <li>itemKey 重复 → 400/10001；</li>
 *   <li>fixed 无输入项 → 400/10001；</li>
 *   <li>跨项目技能 → 400/10001；</li>
 *   <li>过期 revision → 409/10402；</li>
 *   <li>被需求引用删除 → 409/10403；</li>
 *   <li>非创建者且非 owner 编辑 → 403/10202。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class WorkflowApiTest {

    /** 随机后缀：保证多次执行不撞用户名 / 项目名 */
    private static final String SUFFIX = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ------------------------------------------------------------------
    // 用例 1 + 2：创建 → 整体保存（新增）→ 整体保存（交换顺序）
    // ------------------------------------------------------------------

    @Test
    void createThenSaveNodes_reordersByArrayOrder_andKeepsMultiSkillsAndFixedInputs() throws Exception {
        String owner = "t31_" + SUFFIX + "_o1";
        String ownerToken = registerAndLogin(owner);
        long projectId = createProject(ownerToken, "流程编排-" + SUFFIX);
        long skillA = insertSkill(projectId, "需求拆解-" + SUFFIX, 0);
        long skillB = insertSkill(projectId, "风险识别-" + SUFFIX, 0);

        // ---- 创建流程：只带名称与说明 ----
        JsonNode workflow = readData(mockMvc.perform(post("/api/projects/" + projectId + "/workflows")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "T31 流程-" + SUFFIX, "description", "三个阶段"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn());
        long workflowId = workflow.path("id").asLong();
        // revision 是毫秒时间戳（超出 int 范围），必须按 long 读，否则被截断成负数
        long revision = workflow.path("revision").asLong();
        assertEquals(0, workflow.path("nodeCount").asInt(), "新建流程没有节点");
        assertNotEquals(0, revision, "revision 必须由 updated_at 换算得到");

        // 列表里出现该流程，节点数 0
        mockMvc.perform(get("/api/projects/" + projectId + "/workflows")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value((int) workflowId))
                .andExpect(jsonPath("$.data[0].nodeCount").value(0))
                .andExpect(jsonPath("$.data[0].revision").value(revision));

        // ---- 整体保存：3 个节点（新增），第 2 个节点多技能 + fixed 两输入 + 两个输出项 ----
        JsonNode saved = saveNodesOk(ownerToken, workflowId, revision,
                "["
                        + node(null, "需求澄清", "收集客户原话", "free", "[" + skillA + "]", "[]",
                        "[" + output("clarify-notes", "澄清记录", "text", true) + "]") + ","
                        + node(null, "需求拆解", "拆出需求条目", "fixed",
                        "[" + skillA + "," + skillB + "]",
                        "[" + input("客户原话整理", "text", true) + "," + input("现场资料清单", "any", false) + "]",
                        "[" + output("requirement-items", "需求条目清单", "text", true) + ","
                                + output("acceptance-criteria", "验收标准", "text", true) + "]") + ","
                        + node(null, "需求确认", "核对条目", "free", "[]", "[]",
                        "[" + output("confirm-notes", "确认记录", "text", true) + "]")
                        + "]");

        long newRevision = saved.path("revision").asLong();
        assertNotEquals(revision, newRevision, "保存节点后流程 revision 必须变化");
        JsonNode nodes = saved.path("nodes");
        assertEquals(3, nodes.size());
        assertEquals(3, saved.path("nodeCount").asInt());
        // 顺序 = 数组顺序，seq = 1..n
        assertEquals("需求澄清", nodes.get(0).path("name").asText());
        assertEquals(1, nodes.get(0).path("seq").asInt());
        assertEquals("需求拆解", nodes.get(1).path("name").asText());
        assertEquals(2, nodes.get(1).path("seq").asInt());
        assertEquals("需求确认", nodes.get(2).path("name").asText());
        assertEquals(3, nodes.get(2).path("seq").asInt());
        // 多技能按选择顺序回显
        assertEquals(2, nodes.get(1).path("skills").size());
        assertEquals(skillA, nodes.get(1).path("skills").get(0).path("skillId").asLong());
        assertEquals(skillB, nodes.get(1).path("skills").get(1).path("skillId").asLong());
        // fixed 节点的两个输入项、两个输出项
        assertEquals("fixed", nodes.get(1).path("inputType").asText());
        assertEquals(2, nodes.get(1).path("inputs").size());
        assertEquals(2, nodes.get(1).path("outputs").size());
        assertEquals("requirement-items", nodes.get(1).path("outputs").get(0).path("itemKey").asText());
        assertEquals("acceptance-criteria", nodes.get(1).path("outputs").get(1).path("itemKey").asText());
        // free 节点忽略 inputs：库里不应该留下输入项
        assertEquals(0, nodes.get(0).path("inputs").size());

        long firstId = nodes.get(0).path("id").asLong();
        long secondId = nodes.get(1).path("id").asLong();
        long thirdId = nodes.get(2).path("id").asLong();
        assertEquals(List.of(1, 2, 3), nodeSeqs(workflowId), "库里 seq 必须是 1..n");

        // ---- 再整体保存一次：交换第 1、3 个节点（把编辑结果"拖到第 1 位"） ----
        // 完整的节点定义（重排时必须原样带回来：整体保存 = 全部覆盖）
        String clarifyNode = existing(firstId, "需求澄清", "收集客户原话", "free", "[" + skillA + "]", "[]",
                "[" + output("clarify-notes", "澄清记录", "text", true) + "]");
        String analyzeNode = existing(secondId, "需求拆解", "拆出需求条目", "fixed",
                "[" + skillA + "," + skillB + "]",
                "[" + input("客户原话整理", "text", true) + "," + input("现场资料清单", "any", false) + "]",
                "[" + output("requirement-items", "需求条目清单", "text", true) + ","
                        + output("acceptance-criteria", "验收标准", "text", true) + "]");
        String confirmNode = existing(thirdId, "需求确认", "核对条目", "free", "[]", "[]",
                "[" + output("confirm-notes", "确认记录", "text", true) + "]");
        saveNodesOk(ownerToken, workflowId, newRevision, "[" + confirmNode + "," + analyzeNode + "," + clarifyNode + "]");

        // GET 校验：顺序与 seq 都已重排
        JsonNode detail = readData(mockMvc.perform(get("/api/workflows/" + workflowId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn());
        JsonNode reordered2 = detail.path("nodes");
        assertEquals(3, reordered2.size());
        assertEquals(thirdId, reordered2.get(0).path("id").asLong());
        assertEquals(1, reordered2.get(0).path("seq").asInt());
        assertEquals(secondId, reordered2.get(1).path("id").asLong());
        assertEquals(2, reordered2.get(1).path("seq").asInt());
        assertEquals(firstId, reordered2.get(2).path("id").asLong());
        assertEquals(3, reordered2.get(2).path("seq").asInt());
        assertEquals(List.of(1, 2, 3), nodeSeqs(workflowId), "重排后库里 seq 仍必须是 1..n");

        // ---- 整体保存时删掉一个节点（请求里只保留 2 个）：节点与其子项一起清掉 ----
        long revisionAfterSwap = detail.path("revision").asLong();
        // 请求里只保留前两个节点（第三个「需求确认」不出现 → 应被删除）
        String trimmedAnalyzeNode = existing(secondId, "需求拆解", "拆出需求条目", "fixed",
                "[" + skillA + "," + skillB + "]",
                "[" + input("客户原话整理", "text", true) + "]",
                "[" + output("requirement-items", "需求条目清单", "text", true) + "]");
        saveNodesOk(ownerToken, workflowId, revisionAfterSwap,
                "[" + clarifyNode + "," + trimmedAnalyzeNode + "]");
        assertEquals(List.of(1, 2), nodeSeqs(workflowId));
        assertEquals(0, count("SELECT COUNT(*) FROM wb_workflow_node WHERE id = ?", thirdId),
                "请求里不存在的节点必须被物理删除");
        assertEquals(0, count("SELECT COUNT(*) FROM wb_node_output_item WHERE node_id = ?", thirdId),
                "删除节点必须连带删掉输出项（外键 RESTRICT）");
        assertEquals(0, count("SELECT COUNT(*) FROM wb_workflow_node_skill WHERE node_id = ?", thirdId),
                "删除节点必须连带删掉技能关联（外键 RESTRICT）");
        // 保留节点上的旧输入项也被按新列表重建（这次只留 1 个输入项）
        assertEquals(1, count("SELECT COUNT(*) FROM wb_node_input_item WHERE node_id = ?", secondId));
    }

    // ------------------------------------------------------------------
    // 用例 3：itemKey 在本节点内重复 → 400/10001
    // ------------------------------------------------------------------

    @Test
    void saveNodes_duplicateItemKeyInSameNode_is400() throws Exception {
        String token = registerAndLogin("t31_" + SUFFIX + "_o2");
        long projectId = createProject(token, "itemKey 重复-" + SUFFIX);
        JsonNode workflow = createWorkflow(token, projectId, "重复 key-" + SUFFIX);

        saveNodesRaw(token, workflow, "["
                + node(null, "需求拆解", null, "free", "[]", "[]",
                "[" + output("requirement-items", "需求条目", "text", true) + ","
                        + output("requirement-items", "重复的条目", "text", true) + "]")
                + "]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value(containsString("requirement-items")))
                .andExpect(jsonPath("$.message").value(containsString("重复")));

        // 失败的保存整体回滚：库里没有留下任何节点
        assertEquals(0, count("SELECT COUNT(*) FROM wb_workflow_node WHERE workflow_id = ?",
                workflow.path("id").asLong()));
    }

    // ------------------------------------------------------------------
    // 用例 4：inputType=fixed 但没有输入项 → 400/10001
    // ------------------------------------------------------------------

    @Test
    void saveNodes_fixedWithoutInputs_is400() throws Exception {
        String token = registerAndLogin("t31_" + SUFFIX + "_o3");
        long projectId = createProject(token, "fixed 无输入-" + SUFFIX);
        JsonNode workflow = createWorkflow(token, projectId, "fixed 无输入-" + SUFFIX);

        saveNodesRaw(token, workflow, "["
                + node(null, "需求拆解", null, "fixed", "[]", "[]",
                "[" + output("requirement-items", "需求条目", "text", true) + "]")
                + "]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value(containsString("固定输入项")));

        // 空节点列表同样 400
        saveNodesRaw(token, workflow, "[]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value(containsString("至少要有 1 个节点")));
    }

    // ------------------------------------------------------------------
    // 用例 5：技能属于其他项目 → 400/10001
    // ------------------------------------------------------------------

    @Test
    void saveNodes_skillFromOtherProject_is400() throws Exception {
        String token = registerAndLogin("t31_" + SUFFIX + "_o4");
        long projectId = createProject(token, "跨项目技能-" + SUFFIX);
        long otherProjectId = createProject(token, "另一个项目-" + SUFFIX);
        long foreignSkill = insertSkill(otherProjectId, "别的项目的技能-" + SUFFIX, 0);
        JsonNode workflow = createWorkflow(token, projectId, "跨项目技能-" + SUFFIX);

        saveNodesRaw(token, workflow, "["
                + node(null, "需求拆解", null, "free", "[" + foreignSkill + "]", "[]",
                "[" + output("requirement-items", "需求条目", "text", true) + "]")
                + "]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value(containsString("不属于本项目或已被删除")));

        assertEquals(0, count("SELECT COUNT(*) FROM wb_workflow_node WHERE workflow_id = ?",
                workflow.path("id").asLong()), "校验失败必须整体回滚");
    }

    // ------------------------------------------------------------------
    // 用例 6：过期 revision → 409/10402
    // ------------------------------------------------------------------

    @Test
    void staleRevision_is409_andMissingRevision_is400() throws Exception {
        String token = registerAndLogin("t31_" + SUFFIX + "_o5");
        long projectId = createProject(token, "revision 冲突-" + SUFFIX);
        JsonNode workflow = createWorkflow(token, projectId, "revision-" + SUFFIX);
        long workflowId = workflow.path("id").asLong();
        // revision 是毫秒时间戳（超出 int 范围），必须按 long 读，否则被截断成负数
        long revision = workflow.path("revision").asLong();

        // 第一次保存成功，revision 推进
        JsonNode saved = saveNodesOk(token, workflowId, revision, "["
                + node(null, "需求澄清", null, "free", "[]", "[]",
                "[" + output("clarify-notes", "澄清记录", "text", true) + "]") + "]");
        long newRevision = saved.path("revision").asLong();
        assertTrue(newRevision > revision, "revision 必须严格递增");

        // 第二次仍用旧 revision → 409/10402
        mockMvc.perform(put("/api/workflows/" + workflowId + "/nodes")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":" + revision + ",\"nodes\":["
                                + node(null, "被覆盖的节点", null, "free", "[]", "[]",
                                "[" + output("x", "X", "text", true) + "]") + "]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(10402));

        // PUT 名称同样受 revision 保护
        mockMvc.perform(put("/api/workflows/" + workflowId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "改名-" + SUFFIX, "revision", revision))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(10402));

        // revision 缺失 → 400/10001
        mockMvc.perform(put("/api/workflows/" + workflowId + "/nodes")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodes\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value(containsString("revision")));

        // 旧的 PUT 没有生效：名称未变、节点数仍是 1
        String currentName = jdbcTemplate.queryForObject("SELECT name FROM wb_workflow WHERE id = ?",
                String.class, workflowId);
        assertEquals("revision-" + SUFFIX, currentName);
        assertEquals(List.of(1), nodeSeqs(workflowId));
    }

    // ------------------------------------------------------------------
    // 用例 7：被需求引用 → 删除 409/10403；未被引用 → 逻辑删除成功
    // ------------------------------------------------------------------

    @Test
    void delete_referencedWorkflow_is409_unreferenced_isLogicalDelete() throws Exception {
        String token = registerAndLogin("t31_" + SUFFIX + "_o6");
        long projectId = createProject(token, "删除引用-" + SUFFIX);
        JsonNode referenced = createWorkflow(token, projectId, "被引用的流程-" + SUFFIX);
        JsonNode unreferenced = createWorkflow(token, projectId, "没人用的流程-" + SUFFIX);

        // 直接插一条需求引用（需求创建属 T4-1，这里只造引用关系）
        long referencedId = referenced.path("id").asLong();
        long creatorId = jdbcTemplate.queryForObject("SELECT created_by FROM wb_workflow WHERE id = ?",
                Long.class, referencedId);
        insertRequirement(projectId, referencedId, creatorId);

        mockMvc.perform(delete("/api/workflows/" + referencedId)
                        .param("revision", String.valueOf(referenced.path("revision").asLong()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(10403))
                .andExpect(jsonPath("$.message").value(containsString("引用")));
        assertEquals(1, count("SELECT COUNT(*) FROM wb_workflow WHERE id = ? AND deleted = 0", referencedId),
                "被引用时不能有任何写操作（deleted 必须仍为 0）");

        // 未被引用的流程可以删除（逻辑删除）
        long unreferencedId = unreferenced.path("id").asLong();
        mockMvc.perform(delete("/api/workflows/" + unreferencedId)
                        .param("revision", String.valueOf(unreferenced.path("revision").asLong()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(1, count("SELECT COUNT(*) FROM wb_workflow WHERE id = ? AND deleted = 1", unreferencedId),
                "删除必须是逻辑删除 deleted=1");
        // 删除后列表与详情都看不到
        mockMvc.perform(get("/api/workflows/" + unreferencedId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(10301));
        mockMvc.perform(get("/api/projects/" + projectId + "/workflows")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    // ------------------------------------------------------------------
    // 用例 8：非创建者且非 owner（普通成员）编辑 → 403/10202
    // ------------------------------------------------------------------

    @Test
    void memberWhoIsNotCreator_editAndSaveNodes_is403() throws Exception {
        String owner = "t31_" + SUFFIX + "_o7";
        String ownerToken = registerAndLogin(owner);
        long projectId = createProject(ownerToken, "权限-" + SUFFIX);
        String member = "t31_" + SUFFIX + "_m7";
        registerAndLogin(member);
        addMemberOk(ownerToken, projectId, member);
        String memberToken = login(member, "");

        JsonNode workflow = createWorkflow(ownerToken, projectId, "owner 的流程-" + SUFFIX);
        long workflowId = workflow.path("id").asLong();
        // revision 是毫秒时间戳（超出 int 范围），必须按 long 读，否则被截断成负数
        long revision = workflow.path("revision").asLong();

        // 成员可以看（M），但不能编辑 / 整体保存 / 删除（C）
        mockMvc.perform(get("/api/workflows/" + workflowId)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/workflows/" + workflowId)
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "成员改名", "revision", revision))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10202));

        mockMvc.perform(put("/api/workflows/" + workflowId + "/nodes")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":" + revision + ",\"nodes\":["
                                + node(null, "成员加的节点", null, "free", "[]", "[]",
                                "[" + output("x", "X", "text", true) + "]") + "]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10202));

        mockMvc.perform(delete("/api/workflows/" + workflowId)
                        .param("revision", String.valueOf(revision))
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10202));

        // owner 可以编辑成员创建的流程？这里反过来验证：成员自己创建的流程 owner 也能改（C = 创建者或 owner）
        JsonNode memberWorkflow = createWorkflow(memberToken, projectId, "成员的流程-" + SUFFIX);
        mockMvc.perform(put("/api/workflows/" + memberWorkflow.path("id").asLong())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "owner 改成员的流程-" + SUFFIX,
                                "revision", memberWorkflow.path("revision").asLong()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("owner 改成员的流程-" + SUFFIX));
    }

    // ------------------------------------------------------------------
    // 用例 9：连续多次增删 + 乱序重排，seq 始终保持 1..n 且不撞唯一索引
    // ------------------------------------------------------------------

    /**
     * 回归用例：{@code uk_node_workflow_seq(workflow_id, seq)} 是唯一索引，seq 重排的任何中间态
     * （例如"保留节点直接写 1..n 时还有一行停在目标序号上"、"新增节点的临时负数与残留负数重复"）
     * 都会在真实数据库里直接报 Duplicate entry。这里连续保存 12 轮、每轮随机增删与乱序，
     * 每轮都断言接口 200、库里 seq 恰好是 1..n。
     */
    @Test
    void saveNodes_repeatedRandomReorder_neverHitsUniqueKey() throws Exception {
        String token = registerAndLogin("t31_" + SUFFIX + "_o9");
        long projectId = createProject(token, "乱序重排-" + SUFFIX);
        JsonNode workflow = createWorkflow(token, projectId, "乱序重排-" + SUFFIX);
        long workflowId = workflow.path("id").asLong();
        long revision = workflow.path("revision").asLong();

        java.util.Random random = new java.util.Random(20261119L);
        List<Long> currentIds = new ArrayList<>();
        for (int round = 0; round < 12; round++) {
            // 打乱现有节点顺序（模拟拖拽）
            final int roundNo = round;
            List<Long> order = new ArrayList<>(currentIds);
            java.util.Collections.shuffle(order, random);
            // 随机删除 1 个（至少保留 1 个）与随机新增 1 个（模拟整体保存里的增删）
            if (order.size() > 1 && random.nextBoolean()) {
                order.remove(random.nextInt(order.size()));
            }
            StringBuilder nodes = new StringBuilder("[");
            for (int i = 0; i < order.size(); i++) {
                if (i > 0) {
                    nodes.append(',');
                }
                final int position = i;
                nodes.append(node(order.get(i), "节点-" + order.get(i), null, "free", "[]", "[]",
                        "[" + output("out-" + (position + 1), "输出-" + (position + 1), "text", true) + "]"));
            }
            if (nodes.length() > 1) {
                nodes.append(',');
            }
            nodes.append(node(null, "新增-" + roundNo, null, "fixed", "[]",
                    "[" + input("输入-" + roundNo, "text", true) + "]",
                    "[" + output("new-" + roundNo, "新输出-" + roundNo, "text", true) + "]"));
            nodes.append(']');

            JsonNode saved = saveNodesOk(token, workflowId, revision, nodes.toString());
            revision = saved.path("revision").asLong();
            // 注意：currentIds 在循环里被重新赋值，不能在 lambda 里捕获它
            List<Long> nextIds = new ArrayList<>();
            for (JsonNode item : saved.path("nodes")) {
                nextIds.add(item.path("id").asLong());
            }
            currentIds = nextIds;

            List<Integer> seqs = nodeSeqs(workflowId);
            List<Integer> expected = new ArrayList<>();
            for (int i = 1; i <= currentIds.size(); i++) {
                expected.add(i);
            }
            assertEquals(expected, seqs, "第 " + (round + 1) + " 轮保存后 seq 必须是 1..n");
        }
    }

    // ------------------------------------------------------------------
    // 工具方法：账号 / 项目 / 流程
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
     * 直接用 JSON 字符串提交节点列表（用于校验失败用例）。
     * <p>
     * 注意不要用 {@code Map.of(...)} 拼请求体：{@code Map.of} 的 9 参数重载不接受 null 值，
     * 而节点校验用例里 {@code nodeId} 与 {@code description} 正是 null —— 那样拼会在测试端直接 NPE。
     */
    private ResultActions saveNodesRaw(String token, JsonNode workflow, String nodesJson) throws Exception {
        return mockMvc.perform(put("/api/workflows/" + workflow.path("id").asLong() + "/nodes")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":" + workflow.path("revision").asLong()
                                + ",\"nodes\":" + nodesJson + "}"))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
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

    private static String existing(long nodeId, String name, String description, String inputType,
                                   String skillIds, String inputs, String outputs) {
        return node(nodeId, name, description, inputType, skillIds, inputs, outputs);
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

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private JsonNode readData(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    // ------------------------------------------------------------------
    // 工具方法：直接造数（技能 / 需求引用）
    // ------------------------------------------------------------------

    private long insertSkill(long projectId, String name, int deleted) {
        Long ownerId = jdbcTemplate.queryForObject("SELECT owner_id FROM wb_project WHERE id = ?", Long.class,
                projectId);
        jdbcTemplate.update("INSERT INTO wb_skill (project_id, name, description, current_version_id, created_by,"
                        + " deleted, created_at, updated_at) VALUES (?, ?, NULL, NULL, ?, ?, ?, ?)",
                projectId, name, ownerId, deleted, LocalDateTime.now(), LocalDateTime.now());
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void insertRequirement(long projectId, long workflowId, long creatorId) {
        jdbcTemplate.update("INSERT INTO wb_requirement (project_id, workflow_id, title, description, creator_id,"
                        + " status, current_seq, dsh_workspace_path, revision, created_at, updated_at)"
                        + " VALUES (?, ?, ?, NULL, ?, 'in_progress', 1, ?, 1, ?, ?)",
                projectId, workflowId, "引用流程的需求-" + SUFFIX, creatorId,
                "/tmp/workbench-test/" + SUFFIX, LocalDateTime.now(), LocalDateTime.now());
    }

    /** 流程下全部节点的 seq（按 id 升序取，用于断言"1..n 且无重复"） */
    private List<Integer> nodeSeqs(long workflowId) {
        List<Integer> seqs = new ArrayList<>(jdbcTemplate.queryForList(
                "SELECT seq FROM wb_workflow_node WHERE workflow_id = ? ORDER BY id", Integer.class, workflowId));
        seqs.sort(Integer::compareTo);
        return seqs;
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
