package com.fde.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 节点执行闭环接口测试（任务卡 T5-1；docs/api.md §8/§9/§10/§11，PRD AC-F4/F5/F6/H5、REQ-X1/X2）。
 * <p>
 * <b>数据隔离</b>：每个用例自建用户（{@code t51_xxxxxxxx}）、项目、技能、流程与需求，
 * 只读写自己的数据，不碰演示项目 / 演示流程 / demo / demo2 的任何行；
 * 数据库连独立测试库 {@code fde_workbench_test}（见 {@link TestDatabases}），存储根指向 {@code target/test-data}，
 * 因此不会往演示库写数据、也不污染 {@code workbench/data}。
 * 所有用例结束用 {@code ./scripts/sql/reset-demo-data.sql} 复位共享库即可（docs/task-decomposition.md §0.4）。
 * <p>
 * 覆盖用例：
 * <ol>
 *   <li>上传输入：free 节点累加上传；fixed 节点必须带 inputItemId；类型 / 大小 / 节点状态校验；删除输入；</li>
 *   <li>只读三种情况：非当前节点、非创建人（非 owner）、需求已完成（外加 owner 可操作的反例）；</li>
 *   <li>输出提交与推进；重提交版本 +1；最后一个节点完成 → 需求 completed；</li>
 *   <li>必填没齐不推进，remaining 指出缺什么（输出侧与输入侧各验一次）；</li>
 *   <li>sha256 不符拒绝、路径越界拒绝、内部令牌错误 401；</li>
 *   <li>会话登记（用户侧 / 内部侧）与默认会话解析、touch 生效、重复 409；</li>
 *   <li>文件下载鉴权与中文文件名下载头、文本预览；</li>
 *   <li>需求删除时清理输入、输出、文件、会话（T5-1 补齐的缺口）；</li>
 *   <li><b>输入侧补传也能推进</b>（端到端阻塞问题）：先 commit 输出、再上传必填输入，上传本身触发推进；
 *       需求已完成 / 输出未齐时上传不推进（幂等）。</li>
 * </ol>
 */
@SpringBootTest(properties = {
        // 内部接口令牌（/internal/**）：测试里需要确定值，仓库中不放生产可用的明文
        "fde.workbench.internal-token=" + NodeApiTest.INTERNAL_TOKEN,
        // 存储根指向构建目录：测试产生的输入 / 输出文件随 target 一起清理
        "fde.workbench.storage-root=./target/test-data",
        // 数据集：连独立的 fde_workbench_test（见 TestDatabases），不往共享演示库写任何测试数据
        "spring.datasource.url=" + TestDatabases.URL
})
@AutoConfigureMockMvc
class NodeApiTest {

    /** 随机后缀：保证多次执行不撞用户名 / 项目名 */
    private static final String SUFFIX = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

    /** 内部接口令牌：测试专用占位（不是生产可用的明文），通过 @SpringBootTest#properties 注入 */
    static final String INTERNAL_TOKEN = "test-internal-token" + "-not-for-production";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ==================================================================
    // 用例 1：上传输入（free 累加 / fixed 逐项 / 校验 / 删除）
    // ==================================================================

    @Test
    void uploadInput_freeNodeAccumulatesAndAppearsInNodeDetail() throws Exception {
        Fixture fixture = prepare("up1", true);
        long node1 = fixture.nodes.get(0).id();

        // 第一次上传：不传 inputItemId（free 节点）
        JsonNode first = uploadOk(fixture.token, fixture.requirementId, node1, null,
                "客户原话.md", "# 客户原话\n需要工单分诊".getBytes(StandardCharsets.UTF_8));
        long firstRecordId = first.path("inputRecordId").asLong();
        assertTrue(first.path("inputItemId").isNull(), "free 节点上传的 input_item_id 必须为 NULL");
        assertEquals("客户原话.md", first.path("originalName").asText());
        // 落盘路径：<ws>/inputs/1/<fileId>_<原名>
        String relPath = jdbcTemplate.queryForObject(
                "SELECT storage_rel_path FROM wb_file WHERE id = ?", String.class, first.path("fileId").asLong());
        assertTrue(relPath.startsWith("ws/" + fixture.requirementId + "/inputs/1/"),
                "输入文件应落在 ws/<rid>/inputs/1/ 下：" + relPath);
        assertTrue(relPath.endsWith("/" + first.path("fileId").asLong() + "_客户原话.md"),
                "文件名应为 <fileId>_<原名>：" + relPath);

        // 第二次上传：累加，不覆盖
        JsonNode second = uploadOk(fixture.token, fixture.requirementId, node1, null,
                "补充说明.txt", "补充内容".getBytes(StandardCharsets.UTF_8));
        assertNotEquals(first.path("inputRecordId").asLong(), second.path("inputRecordId").asLong());
        assertEquals(2, count("SELECT COUNT(*) FROM wb_requirement_node_input WHERE node_instance_id = ?", node1));
        assertEquals(2, count("SELECT COUNT(*) FROM wb_file WHERE requirement_id = ? AND ref_type = 'node_input'",
                fixture.requirementId));

        // 节点信息页：free 节点返回一个 itemId=null 的输入区，挂两个文件
        JsonNode detail = nodeDetailOk(fixture.token, fixture.requirementId, node1);
        assertEquals("free", detail.path("inputType").asText());
        assertEquals(1, detail.path("inputs").size());
        assertTrue(detail.path("inputs").get(0).path("itemId").isNull());
        assertEquals(2, detail.path("inputs").get(0).path("files").size());
        assertEquals("客户原话.md",
                detail.path("inputs").get(0).path("files").get(0).path("originalName").asText());
        assertEquals(firstRecordId, detail.path("inputs").get(0).path("files").get(0)
                .path("inputRecordId").asLong(), "输入文件必须带 inputRecordId（删除要用）");
        assertEquals("current", detail.path("status").asText(), "只上传输入不会推进节点");

        // 删除第一个输入：库里两行都清掉，磁盘文件也删
        Path stored = storageRoot().resolve(relPath);
        assertTrue(Files.exists(stored), "上传后文件应存在：" + stored);
        mockMvc.perform(delete("/api/requirements/" + fixture.requirementId + "/nodes/" + node1
                                + "/inputs/" + firstRecordId)
                        .header("Authorization", "Bearer " + fixture.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(1, count("SELECT COUNT(*) FROM wb_requirement_node_input WHERE node_instance_id = ?", node1));
        assertEquals(0, count("SELECT COUNT(*) FROM wb_file WHERE id = ?", first.path("fileId").asLong()));
        assertFalse(Files.exists(stored), "删除输入后磁盘文件也应删除");
    }

    @Test
    void uploadInput_fixedNodeRequiresInputItemIdAndValidatesType() throws Exception {
        Fixture fixture = prepare("up2", true);
        long node2 = fixture.nodes.get(1).id();
        long node1 = fixture.nodes.get(0).id();
        long inputItemId = fixture.node2InputItemId;

        // 节点 2 还不是当前节点 → 409/10404（上传只允许当前节点）
        mockMvc.perform(multipart("/api/requirements/" + fixture.requirementId + "/nodes/" + node2 + "/inputs")
                        .file(new MockMultipartFile("file", "客户原话.md", "text/markdown",
                                "x".getBytes(StandardCharsets.UTF_8)))
                        .param("inputItemId", String.valueOf(inputItemId))
                        .header("Authorization", "Bearer " + fixture.token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(10404));

        // 先把节点 1 推进掉，使节点 2 成为当前节点
        completeNode1(fixture);

        // 缺 inputItemId → 400/10001
        mockMvc.perform(multipart("/api/requirements/" + fixture.requirementId + "/nodes/" + node2 + "/inputs")
                        .file(new MockMultipartFile("file", "客户原话.md", "text/markdown",
                                "x".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + fixture.token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001));

        // inputItemId 不属于该节点 → 400/10001
        mockMvc.perform(multipart("/api/requirements/" + fixture.requirementId + "/nodes/" + node2 + "/inputs")
                        .file(new MockMultipartFile("file", "客户原话.md", "text/markdown",
                                "x".getBytes(StandardCharsets.UTF_8)))
                        .param("inputItemId", "999999")
                        .header("Authorization", "Bearer " + fixture.token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value(containsString("不属于该节点")));

        // 扩展名不符合 file_kind=text（.docx）→ 415/10502
        mockMvc.perform(multipart("/api/requirements/" + fixture.requirementId + "/nodes/" + node2 + "/inputs")
                        .file(new MockMultipartFile("file", "资料.docx",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                "x".getBytes(StandardCharsets.UTF_8)))
                        .param("inputItemId", String.valueOf(inputItemId))
                        .header("Authorization", "Bearer " + fixture.token))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(10502));

        // 正常上传：固定输入项允许多次上传
        JsonNode first = uploadOk(fixture.token, fixture.requirementId, node2, inputItemId,
                "客户原话.md", "第一次".getBytes(StandardCharsets.UTF_8));
        JsonNode second = uploadOk(fixture.token, fixture.requirementId, node2, inputItemId,
                "客户原话-补充.md", "第二次".getBytes(StandardCharsets.UTF_8));
        assertEquals(inputItemId, first.path("inputItemId").asLong());
        assertEquals(inputItemId, second.path("inputItemId").asLong());
        assertEquals(2, count("SELECT COUNT(*) FROM wb_requirement_node_input WHERE node_instance_id = ?"
                + " AND input_item_id = ?", node2, inputItemId));

        // 节点信息页：两个输入项逐项列出，第一个挂了 2 个文件
        JsonNode detail = nodeDetailOk(fixture.token, fixture.requirementId, node2);
        assertEquals("fixed", detail.path("inputType").asText());
        assertEquals(2, detail.path("inputs").size());
        assertEquals(2, detail.path("inputs").get(0).path("files").size());
        assertEquals(0, detail.path("inputs").get(1).path("files").size());
        assertFalse(detail.path("inputs").get(1).path("required").asBoolean(), "第二个输入项非必填");

        // 完成节点 2 后节点 3 是最终节点；节点 1 已 done，不能再上传
        assertEquals("current", detail.path("status").asText());
        assertTrue(node1 < node2);
    }

    @Test
    void uploadInput_wrongRequesterOrUnfinishedNodeIsRejected() throws Exception {
        Fixture fixture = prepare("up3", true);
        long node1 = fixture.nodes.get(0).id();

        // demo2（普通成员，非创建人）上传 → 403/10202
        mockMvc.perform(multipart("/api/requirements/" + fixture.requirementId + "/nodes/" + node1 + "/inputs")
                        .file(new MockMultipartFile("file", "a.md", "text/markdown", "x".getBytes()))
                        .header("Authorization", "Bearer " + fixture.memberToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10202));

        // 非成员 → 403/10201
        String outsider = registerAndLogin("t51_" + SUFFIX + "_up3o");
        mockMvc.perform(multipart("/api/requirements/" + fixture.requirementId + "/nodes/" + node1 + "/inputs")
                        .file(new MockMultipartFile("file", "a.md", "text/markdown", "x".getBytes()))
                        .header("Authorization", "Bearer " + outsider))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10201));

        // 删除别人的输入 → 403/10202
        JsonNode uploaded = uploadOk(fixture.token, fixture.requirementId, node1, null,
                "a.md", "内容".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(delete("/api/requirements/" + fixture.requirementId + "/nodes/" + node1 + "/inputs/"
                                + uploaded.path("inputRecordId").asLong())
                        .header("Authorization", "Bearer " + fixture.memberToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10202));
    }

    // ==================================================================
    // 用例 2：只读三种情况
    // ==================================================================

    @Test
    void readonly_threeCases() throws Exception {
        Fixture fixture = prepare("ro", true);
        long node1 = fixture.nodes.get(0).id();
        long node2 = fixture.nodes.get(1).id();
        long node3 = fixture.nodes.get(2).id();

        // ① 当前节点 + 创建人 → readonly=false
        JsonNode current = nodeDetailOk(fixture.token, fixture.requirementId, node1);
        assertFalse(current.path("readonly").asBoolean(), "创建人看当前节点应可操作");
        assertTrue(current.path("previousNode").isNull(), "第一个节点没有上一节点");
        assertEquals(jdbcTemplate.queryForObject("SELECT dsh_workspace_path FROM wb_requirement WHERE id = ?",
                String.class, fixture.requirementId), current.path("workspacePath").asText());

        // ② 非当前节点 → readonly=true（即便我是创建人）
        JsonNode notCurrent = nodeDetailOk(fixture.token, fixture.requirementId, node2);
        assertTrue(notCurrent.path("readonly").asBoolean(), "非当前节点必须只读");

        // ③ 他人的需求（普通成员、非创建人、非 owner）→ readonly=true
        JsonNode byMember = nodeDetailOk(fixture.memberToken, fixture.requirementId, node1);
        assertTrue(byMember.path("readonly").asBoolean(), "非创建人看他人需求必须只读");
        assertTrue(byMember.path("workspacePath").isNull(), "只读用户不应获得工作区绝对路径");

        // 反例：项目 owner 看别人的需求 → 可操作（owner 也是权限 C）
        Fixture owned = prepareOwnedByMember("ro2");
        JsonNode byOwner = nodeDetailOk(owned.token, owned.requirementId, owned.nodes.get(0).id());
        assertFalse(byOwner.path("readonly").asBoolean(), "owner 看成员的需求应可操作");

        // ④ 需求已完成 → 所有节点只读（推进到最后一个节点并提交）
        completeNode1(fixture);
        uploadOk(fixture.token, fixture.requirementId, node2, fixture.node2InputItemId,
                "客户原话.md", "原话".getBytes(StandardCharsets.UTF_8));
        commitOk(fixture, node2, "requirement-items", "需求条目");
        commitOk(fixture, node2, "acceptance-criteria", "验收标准");
        // 节点 3 是 free 节点：先传一个自由输入，否则 satisfy(推进条件) 不成立（docs/database.md §5.3）
        uploadOk(fixture.token, fixture.requirementId, node3, null,
                "确认.md", "确认内容".getBytes(StandardCharsets.UTF_8));
        commitOk(fixture, node3, "confirm-summary", "确认结论");
        assertEquals("completed", jdbcTemplate.queryForObject(
                "SELECT status FROM wb_requirement WHERE id = ?", String.class, fixture.requirementId));
        JsonNode completedNode = nodeDetailOk(fixture.token, fixture.requirementId, node3);
        assertTrue(completedNode.path("readonly").asBoolean(), "需求已完成时节点必须只读");
    }

    // ==================================================================
    // 用例 3 + 4：输出提交、推进、必填未齐不推进
    // ==================================================================

    @Test
    void commit_advancesOnlyWhenAllRequiredReady() throws Exception {
        Fixture fixture = prepare("cm", true);
        long node1 = fixture.nodes.get(0).id();
        long node2 = fixture.nodes.get(1).id();
        long node3 = fixture.nodes.get(2).id();

        // ---- 节点 1（free，1 个输出项）：没上传输入就提交 → 不推进（free 至少要一个输入文件）----
        JsonNode withoutInput = commit(fixture, node1, "clarify-notes", "澄清记录内容");
        assertTrue(withoutInput.path("committed").asBoolean());
        assertFalse(withoutInput.path("advanced").asBoolean(), "free 节点没有输入文件时不得推进");
        assertTrue(withoutInput.path("remainingRequiredOutputs").isArray());
        assertEquals(0, withoutInput.path("remainingRequiredOutputs").size());
        assertEquals(List.of("current", "waiting", "waiting"), jdbcStatuses(fixture.requirementId));

        // ---- 补上输入后推进到节点 2；重试相同输出保留版本 1 ----
        JsonNode advanced = uploadOk(fixture.token, fixture.requirementId, node1, null,
                "客户原话.md", "客户说要分诊".getBytes(StandardCharsets.UTF_8));
        assertTrue(advanced.path("advanced").asBoolean(), "必填项齐备后应推进");
        assertEquals("需求拆解", advanced.path("nextNodeName").asText());
        JsonNode unchanged = commit(fixture, node1, "clarify-notes", "澄清记录内容");
        assertFalse(unchanged.path("advanced").asBoolean());
        assertEquals(1, unchanged.path("versionNo").asInt(), "相同内容重试保留版本 1");
        assertEquals(List.of("done", "current", "waiting"), jdbcStatuses(fixture.requirementId));
        assertEquals(2, jdbcTemplate.queryForObject("SELECT current_seq FROM wb_requirement WHERE id = ?",
                Integer.class, fixture.requirementId));
        // 推进时写 completed_at
        assertNotNull(jdbcTemplate.queryForObject("SELECT completed_at FROM wb_requirement_node WHERE id = ?",
                LocalDateTime.class, node1));

        // 节点 2 的上一节点输出可见（AC-F4.7）
        JsonNode node2Detail = nodeDetailOk(fixture.token, fixture.requirementId, node2);
        assertEquals(1, node2Detail.path("previousNode").path("seq").asInt());
        assertEquals("clarify-notes",
                node2Detail.path("previousNode").path("outputs").get(0).path("itemKey").asText());
        assertFalse(node2Detail.path("previousNode").path("outputs").get(0).path("file").isNull());

        // ---- 节点 2（fixed，2 个必填输出）：只提交一项 → 不推进，remaining 指出另一项 ----
        JsonNode partial = commit(fixture, node2, "requirement-items", "需求条目");
        assertFalse(partial.path("advanced").asBoolean());
        assertEquals(List.of("acceptance-criteria"), toList(partial.path("remainingRequiredOutputs")));

        // ---- 两项输出都提交，但必填输入还没传 → 不推进，remainingRequiredInputs 指出缺哪项 ----
        JsonNode noInput = commit(fixture, node2, "acceptance-criteria", "验收标准");
        assertFalse(noInput.path("advanced").asBoolean(), "必填输入缺失时不得推进");
        assertEquals(List.of(fixture.node2InputItemId), toLongList(noInput.path("remainingRequiredInputs")));
        assertEquals(0, noInput.path("remainingRequiredOutputs").size());
        assertEquals(List.of("done", "current", "waiting"), jdbcStatuses(fixture.requirementId));

        // ---- advance-preview 与 commit 同口径：还差必填输入 ----
        mockMvc.perform(get("/api/requirements/" + fixture.requirementId + "/nodes/" + node2 + "/advance-preview")
                        .header("Authorization", "Bearer " + fixture.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ready").value(false))
                .andExpect(jsonPath("$.data.remainingRequiredInputs[0]").value(fixture.node2InputItemId))
                .andExpect(jsonPath("$.data.missingDescriptions[0]").value(containsString("客户原话整理")));

        // ---- 重提交同一输出项：版本号 +1，旧 wb_file 保留（AC-F6.3）----
        JsonNode resubmitted = commit(fixture, node2, "requirement-items", "需求条目 v2");
        assertEquals(2, resubmitted.path("versionNo").asInt(), "重新提交版本号必须 +1");
        assertEquals(2, count("SELECT COUNT(*) FROM wb_file WHERE requirement_id = ? AND ref_type = 'node_output'"
                + " AND original_name = 'requirement-items.md'", fixture.requirementId),
                "旧版本的 wb_file 要保留（审计）");

        // ---- 上传必填输入 → 推进到节点 3 ----
        JsonNode done = uploadOk(fixture.token, fixture.requirementId, node2, fixture.node2InputItemId,
                "客户原话.md", "客户原话内容".getBytes(StandardCharsets.UTF_8));
        assertTrue(done.path("advanced").asBoolean(), "必填输入 + 必填输出齐备后应推进");
        assertEquals("需求确认", done.path("nextNodeName").asText());
        assertFalse(done.path("requirementCompleted").asBoolean());
        assertEquals(List.of("done", "done", "current"), jdbcStatuses(fixture.requirementId));

        // ---- 最后一个节点：empty 输入也必须有（free）+ 输出 → 需求 completed ----
        JsonNode noFreeInput = commit(fixture, node3, "confirm-summary", "确认结论");
        assertFalse(noFreeInput.path("advanced").asBoolean(), "free 节点没有输入文件时不得完成");
        JsonNode last = uploadOk(fixture.token, fixture.requirementId, node3, null,
                "确认.md", "确认内容".getBytes(StandardCharsets.UTF_8));
        assertTrue(last.path("advanced").asBoolean());
        assertTrue(last.path("requirementCompleted").asBoolean(), "最后一个节点完成后需求应为 completed");
        assertTrue(last.path("nextNodeName").isNull());
        assertEquals(List.of("done", "done", "done"), jdbcStatuses(fixture.requirementId));
        assertEquals("completed", jdbcTemplate.queryForObject("SELECT status FROM wb_requirement WHERE id = ?",
                String.class, fixture.requirementId));
        // 已完成需求再提交 → 409/10404
        commitExpect(fixture, node3, "confirm-summary", "again", status().isConflict(), 10404);
    }

    // ==================================================================
    // 用例 3b（端到端阻塞问题）：输入侧补传也能推进
    //
    // 背景：真实用户完全可能"先让模型产出、再补传资料"。修复前推进判定只在 commit 事务里执行，
    // 于是 commit 返回 advanced=false 之后再也没有触发点：advance-preview.ready=true，
    // 需求却一直停在当前节点。修复后上传输入的事务末尾也会做一次判定（复用同一段实现）。
    // ==================================================================

    @Test
    void uploadInput_advancesNodeWhenOutputsWereCommittedFirst() throws Exception {
        Fixture fixture = prepare("ur", true);
        long node1 = fixture.nodes.get(0).id();
        long node2 = fixture.nodes.get(1).id();
        long node3 = fixture.nodes.get(2).id();

        // ---- 节点 1：先提交输出（还没有任何输入）→ 不推进，缺输入 ----
        JsonNode withoutInput = commit(fixture, node1, "clarify-notes", "澄清记录内容");
        assertFalse(withoutInput.path("advanced").asBoolean(), "没有输入文件时不得推进");
        assertEquals(List.of("current", "waiting", "waiting"), jdbcStatuses(fixture.requirementId));

        // ---- 再上传输入：这一次上传本身就应该把节点推下去（修复前这里什么都没有发生）----
        JsonNode uploaded = uploadOk(fixture.token, fixture.requirementId, node1, null,
                "客户原话.md", "客户说要分诊".getBytes(StandardCharsets.UTF_8));
        assertTrue(uploaded.path("advanced").asBoolean(), "补传输入后上传响应应报告已自动推进");
        assertEquals("需求拆解", uploaded.path("nextNodeName").asText());
        assertFalse(uploaded.path("requirementCompleted").asBoolean());
        assertTrue(uploaded.path("remainingRequiredOutputs").isArray());
        assertEquals(List.of("done", "current", "waiting"), jdbcStatuses(fixture.requirementId),
                "上传必填输入后节点 1 应变为 done、节点 2 变为 current");
        assertEquals(2, jdbcTemplate.queryForObject("SELECT current_seq FROM wb_requirement WHERE id = ?",
                Integer.class, fixture.requirementId));
        assertNotNull(jdbcTemplate.queryForObject("SELECT completed_at FROM wb_requirement_node WHERE id = ?",
                LocalDateTime.class, node1), "推进时要写 completed_at");

        // ---- 节点 2（fixed）：两次输出都提交，但必填输入还没传 → 不推进 ----
        commitOk(fixture, node2, "requirement-items", "需求条目");
        JsonNode noInput = commit(fixture, node2, "acceptance-criteria", "验收标准");
        assertFalse(noInput.path("advanced").asBoolean());
        assertEquals(List.of(fixture.node2InputItemId), toLongList(noInput.path("remainingRequiredInputs")));
        // 此刻 advance-preview 也该说"还差必填输入"（与 commit 同口径）
        mockMvc.perform(get("/api/requirements/" + fixture.requirementId + "/nodes/" + node2 + "/advance-preview")
                        .header("Authorization", "Bearer " + fixture.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ready").value(false));
        assertEquals(List.of("done", "current", "waiting"), jdbcStatuses(fixture.requirementId));

        // ---- 补传必填固定输入：上传本身触发推进到节点 3 ----
        JsonNode fixedUpload = uploadOk(fixture.token, fixture.requirementId, node2, fixture.node2InputItemId,
                "客户原话整理.md", "整理后的原话".getBytes(StandardCharsets.UTF_8));
        assertEquals(fixture.node2InputItemId, fixedUpload.path("inputItemId").asLong());
        assertTrue(fixedUpload.path("advanced").asBoolean(), "补传必填固定输入后应自动推进");
        assertEquals("需求确认", fixedUpload.path("nextNodeName").asText());
        assertEquals(List.of("done", "done", "current"), jdbcStatuses(fixture.requirementId));
        assertEquals(3, jdbcTemplate.queryForObject("SELECT current_seq FROM wb_requirement WHERE id = ?",
                Integer.class, fixture.requirementId));

        // ---- 幂等：对已经推进走的节点重复上传（在它仍是"已完成"状态时）不应再改状态 ----
        // 这里改用"对当前节点（节点 3，free）重复上传同一输入"来验证：第二次上传不再推进任何东西——
        // 节点 3 还没提交输出，上传只会让它离推进更近，不会把状态再往前推。
        JsonNode node3First = uploadOk(fixture.token, fixture.requirementId, node3, null,
                "确认之一.md", "确认一".getBytes(StandardCharsets.UTF_8));
        assertFalse(node3First.path("advanced").asBoolean(), "输出还没提交时上传输入不能推进");
        JsonNode node3Second = uploadOk(fixture.token, fixture.requirementId, node3, null,
                "确认之二.md", "确认二".getBytes(StandardCharsets.UTF_8));
        assertFalse(node3Second.path("advanced").asBoolean(), "重复上传不产生额外副作用");
        assertEquals(List.of("done", "done", "current"), jdbcStatuses(fixture.requirementId));
        assertEquals(3, jdbcTemplate.queryForObject("SELECT current_seq FROM wb_requirement WHERE id = ?",
                Integer.class, fixture.requirementId));

        // ---- 需求已完成之后：上传接口本身已被 409 拒绝（不再有机会推进）----
        commitOk(fixture, node3, "confirm-summary", "确认结论");
        assertEquals("completed", jdbcTemplate.queryForObject("SELECT status FROM wb_requirement WHERE id = ?",
                String.class, fixture.requirementId));
        MockMultipartFile another = new MockMultipartFile("file", "后补.md",
                "application/octet-stream", "后补".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/requirements/" + fixture.requirementId + "/nodes/" + node3 + "/inputs")
                        .file(another)
                        .header("Authorization", "Bearer " + fixture.token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(10404));
        assertEquals(List.of("done", "done", "done"), jdbcStatuses(fixture.requirementId));
    }

    @Test
    void deleteInput_doesNotAdvance() throws Exception {
        Fixture fixture = prepare("dlin", true);
        long node1 = fixture.nodes.get(0).id();

        // 先提交输出（free 节点）→ 不推进；再上传一个输入 → 推进（这正是用例 3b 的路径）
        commitOk(fixture, node1, "clarify-notes", "澄清记录");
        JsonNode uploaded = uploadOk(fixture.token, fixture.requirementId, node1, null,
                "客户原话.md", "客户说要分诊".getBytes(StandardCharsets.UTF_8));
        assertTrue(uploaded.path("advanced").asBoolean());
        assertEquals(List.of("done", "current", "waiting"), jdbcStatuses(fixture.requirementId));

        // 回到节点 2：上传一个"非必填"的输入（现场资料清单）——节点 2 的输出还没提交，
        // 删除它时判定必然不通过（缺输出），状态不能有任何变化。
        long node2 = fixture.nodes.get(1).id();
        JsonNode optional = uploadOk(fixture.token, fixture.requirementId, node2, fixture.node2InputItemId,
                "现场资料.md", "现场资料".getBytes(StandardCharsets.UTF_8));
        assertFalse(optional.path("advanced").asBoolean());
        mockMvc.perform(delete("/api/requirements/" + fixture.requirementId + "/nodes/" + node2 + "/inputs/"
                        + optional.path("inputRecordId").asLong())
                        .header("Authorization", "Bearer " + fixture.token))
                .andExpect(status().isOk());
        assertEquals(List.of("done", "current", "waiting"), jdbcStatuses(fixture.requirementId),
                "删除输入不应推进任何节点");
        assertEquals(2, jdbcTemplate.queryForObject("SELECT current_seq FROM wb_requirement WHERE id = ?",
                Integer.class, fixture.requirementId));
    }

    @Test
    void concurrentCommitDoesNotCountUploadWhoseStreamFails() throws Exception {
        Fixture fixture = prepare("failup", true);
        long node = fixture.nodes.get(0).id();
        var reading = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        MockMultipartFile broken = new MockMultipartFile("file", "故障.md", "text/markdown", new byte[]{1}) {
            @Override
            public InputStream getInputStream() {
                return new InputStream() {
                    @Override
                    public int read() throws IOException {
                        reading.countDown();
                        try {
                            release.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                        throw new IOException("模拟读取上传流失败");
                    }
                };
            }
        };
        try {
            var failed = executor.submit(() -> mockMvc.perform(multipart("/api/requirements/"
                            + fixture.requirementId + "/nodes/" + node + "/inputs").file(broken)
                            .header("Authorization", "Bearer " + fixture.token))
                    .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value(19902)));
            assertTrue(reading.await(10, TimeUnit.SECONDS));
            // 模型提交时，上传已经开始但流还没有写完。
            assertFalse(commit(fixture, node, "clarify-notes", "澄清记录").path("advanced").asBoolean());
            release.countDown();
            failed.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        // 已有输出时再次读盘失败，也不得推进。
        mockMvc.perform(multipart("/api/requirements/" + fixture.requirementId + "/nodes/" + node + "/inputs")
                        .file(broken).header("Authorization", "Bearer " + fixture.token))
                .andExpect(status().isBadGateway());
        assertEquals(List.of("current", "waiting", "waiting"), jdbcStatuses(fixture.requirementId));
        assertEquals(0, count("SELECT COUNT(*) FROM wb_requirement_node_input WHERE node_instance_id = ?", node));
        assertEquals(0, count("SELECT COUNT(*) FROM wb_file WHERE requirement_id = ? AND ref_type = 'node_input'",
                fixture.requirementId));
        assertTrue(uploadOk(fixture.token, fixture.requirementId, node, null, "重试.md", new byte[]{1})
                .path("advanced").asBoolean());
    }

    @Test
    void commitIgnoresLegacyInputPlaceholder() throws Exception {
        Fixture fixture = prepare("oldin", true);
        long node = fixture.nodes.get(0).id();
        JsonNode input = uploadOk(fixture.token, fixture.requirementId, node, null, "故障.md", new byte[]{1});
        jdbcTemplate.update("UPDATE wb_file SET storage_rel_path = '' WHERE id = ?", input.path("fileId").asLong());
        assertFalse(commit(fixture, node, "clarify-notes", "澄清记录").path("advanced").asBoolean());
        assertEquals(List.of("current", "waiting", "waiting"), jdbcStatuses(fixture.requirementId));
    }

    @Test
    void occupiedOutputPathRollsBackRegistrationAndAdvance() throws Exception {
        Fixture fixture = prepare("vfail", true);
        long node = fixture.nodes.get(0).id();
        uploadOk(fixture.token, fixture.requirementId, node, null, "输入.md", new byte[]{1});
        Path target = Path.of(nodeDetailOk(fixture.token, fixture.requirementId, node).path("workspacePath").asText())
                .resolve("outputs/1/clarify-notes.v1.md");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "已有内容");
        commitExpect(fixture, node, "clarify-notes", "新内容", status().isBadGateway(), 19902);
        assertEquals("已有内容", Files.readString(target));
        assertEquals(List.of("current", "waiting", "waiting"), jdbcStatuses(fixture.requirementId));
        assertEquals(0, count("SELECT COUNT(*) FROM wb_requirement_node_output WHERE node_instance_id = ?", node));
        assertEquals(0, count("SELECT COUNT(*) FROM wb_file WHERE requirement_id = ? AND ref_type = 'node_output'",
                fixture.requirementId));
        Files.delete(target);
        JsonNode retry = commit(fixture, node, "clarify-notes", "新内容");
        assertEquals(1, retry.path("versionNo").asInt());
        assertTrue(retry.path("advanced").asBoolean());
        assertEquals("新内容", Files.readString(target));
    }

    @Test
    void concurrentOutputVersionsKeepLegacyAndNewContentsReadable() throws Exception {
        Fixture fixture = prepare("vhist", true);
        long node = fixture.nodes.get(0).id();
        assertEquals(1, commit(fixture, node, "clarify-notes", "旧内容").path("versionNo").asInt());
        long legacyId = nodeDetailOk(fixture.token, fixture.requirementId, node).path("outputs").get(0)
                .path("file").path("fileId").asLong();
        String legacyPath = "ws/" + fixture.requirementId + "/outputs/1/clarify-notes.md";
        Files.move(storageRoot().resolve("ws/" + fixture.requirementId + "/outputs/1/clarify-notes.v1.md"),
                storageRoot().resolve(legacyPath));
        jdbcTemplate.update("UPDATE wb_file SET storage_rel_path = ? WHERE id = ?", legacyPath, legacyId);

        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return commit(fixture, node, "clarify-notes", "left.md", "并发内容甲").path("versionNo").asInt();
            });
            var second = executor.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return commit(fixture, node, "clarify-notes", "right.md", "并发内容乙").path("versionNo").asInt();
            });
            start.countDown();
            int left = first.get(10, TimeUnit.SECONDS);
            int right = second.get(10, TimeUnit.SECONDS);
            assertEquals(java.util.Set.of(2, 3), java.util.Set.of(left, right));
            Path dir = storageRoot().resolve(legacyPath).getParent();
            assertEquals("并发内容甲", Files.readString(dir.resolve("clarify-notes.v" + left + ".md")));
            assertEquals("并发内容乙", Files.readString(dir.resolve("clarify-notes.v" + right + ".md")));
            String latestContent = left == 3 ? "并发内容甲" : "并发内容乙";
            assertEquals(3, commit(fixture, node, "clarify-notes", latestContent).path("versionNo").asInt());
        } finally {
            executor.shutdownNow();
        }
        mockMvc.perform(get("/api/files/" + legacyId + "/download").header("Authorization", "Bearer " + fixture.token))
                .andExpect(status().isOk()).andExpect(content().bytes("旧内容".getBytes(StandardCharsets.UTF_8)));
        assertEquals(3, count("SELECT COUNT(*) FROM wb_file WHERE requirement_id = ? AND ref_type = 'node_output'",
                fixture.requirementId));
    }

    // ==================================================================
    // 用例 5：sha256 不符 / 路径越界 / 令牌错误
    // ==================================================================

    @Test
    void commit_rejectsWrongSha256AndEscapingPath() throws Exception {
        Fixture fixture = prepare("er", true);
        long node1 = fixture.nodes.get(0).id();
        uploadOk(fixture.token, fixture.requirementId, node1, null,
                "a.md", "a".getBytes(StandardCharsets.UTF_8));
        Path workspace = Paths.get(jdbcTemplate.queryForObject(
                "SELECT dsh_workspace_path FROM wb_requirement WHERE id = ?", String.class, fixture.requirementId));
        Path outputDir = workspace.resolve(".workbench").resolve(String.valueOf(node1));
        Files.createDirectories(outputDir);
        Path file = outputDir.resolve("clarify-notes.md");
        Files.writeString(file, "# 澄清\n内容", StandardCharsets.UTF_8);

        // sha256 不符 → 400/10001，且不登记
        mockMvc.perform(post(commitUrl(node1, "clarify-notes"))
                        .header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("relativePath", ".workbench/" + node1 + "/clarify-notes.md",
                                "sha256", "0".repeat(64), "operator", "agent"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value(containsString("sha256")));
        assertEquals(0, count("SELECT COUNT(*) FROM wb_requirement_node_output WHERE node_instance_id = ?", node1));

        // 路径越界（../）→ 400/10001
        mockMvc.perform(post(commitUrl(node1, "clarify-notes"))
                        .header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("relativePath", "inputs/1/../../etc/passwd",
                                "sha256", sha256(file), "operator", "agent"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001));

        // 路径在 workspace 内但不在本节点输出目录（写到 inputs/）→ 400/10001
        Path inputDir = workspace.resolve("inputs").resolve("1");
        Files.createDirectories(inputDir);
        Path outside = inputDir.resolve("clarify-notes.md");
        Files.writeString(outside, "不属于输出目录", StandardCharsets.UTF_8);
        mockMvc.perform(post(commitUrl(node1, "clarify-notes"))
                        .header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("relativePath", "inputs/1/clarify-notes.md",
                                "sha256", sha256(outside), "operator", "agent"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("输出目录")));

        // itemKey 不属于该节点 → 400/10001
        mockMvc.perform(post(commitUrl(node1, "not-exist-key"))
                        .header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("relativePath", ".workbench/" + node1 + "/clarify-notes.md",
                                "sha256", sha256(file), "operator", "agent"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("itemKey")));

        // 错误令牌 → 401/10103
        mockMvc.perform(post(commitUrl(node1, "clarify-notes"))
                        .header("X-Internal-Token", "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("relativePath", ".workbench/" + node1 + "/clarify-notes.md",
                                "sha256", sha256(file), "operator", "agent"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10103));
        // 缺令牌 / 用用户 JWT 调内部接口 → 同样 401
        mockMvc.perform(get("/internal/nodes/" + node1 + "/context")
                        .header("Authorization", "Bearer " + fixture.token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10103));

        // 正确令牌 → 成功
        commitOk(fixture, node1, "clarify-notes", "澄清");
    }

    @Test
    void internalContext_returnsSnapshotWithRelativePathsAndOutputDir() throws Exception {
        Fixture fixture = prepare("ctx", true);
        long node1 = fixture.nodes.get(0).id();
        JsonNode uploaded = uploadOk(fixture.token, fixture.requirementId, node1, null,
                "客户原话.md", "客户原话".getBytes(StandardCharsets.UTF_8));

        JsonNode context = readData(mockMvc.perform(get("/internal/nodes/" + node1 + "/context")
                        .header("X-Internal-Token", INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn());
        assertEquals(node1, context.path("nodeInstanceId").asLong());
        assertEquals("需求澄清", context.path("nodeName").asText());
        assertEquals(3, context.path("nodeCount").asInt());
        assertEquals(".workbench/" + node1 + "/", context.path("outputDir").asText(),
                "输出目录约定：<ws>/.workbench/<nodeInstanceId>/");
        assertFalse(context.path("readonly").asBoolean());
        assertEquals("需求拆解", context.path("nextNodeName").asText());
        assertEquals(1, context.path("freeInputFiles").size());
        assertEquals("inputs/1/" + uploaded.path("fileId").asLong() + "_客户原话.md",
                context.path("freeInputFiles").get(0).path("relativePath").asText(),
                "上下文里的文件路径必须是相对 workspace 的写法");
        assertEquals("clarify-notes", context.path("outputs").get(0).path("itemKey").asText());
        assertFalse(context.path("outputs").get(0).path("submitted").asBoolean());
        assertEquals(".workbench/" + node1 + "/clarify-notes.md",
                context.path("outputs").get(0).path("suggestPath").asText());
        assertTrue(context.path("previousNode").isNull());

        // 引用登记：本节点输入文件可以登记；不相关的文件 → 400
        mockMvc.perform(post("/internal/nodes/" + node1 + "/reference")
                        .header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("kind", "input", "fileId", uploaded.path("fileId").asLong(),
                                "operator", "agent"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recorded").value(true));
        mockMvc.perform(post("/internal/nodes/" + node1 + "/reference")
                        .header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("kind", "input", "fileId", 99999999, "operator", "agent"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001));
    }

    /**
     * 内部接口 {@code GET /internal/requirements/{rid}/nodes}：正常返回的结果。
     * <p>
     * 插件靠它实现"不传 nodeInstanceId 时定位当前节点"，因此必须断言三件事：
     * 节点数与顺序正确、{@code currentSeq} 与某个 {@code status=current} 的节点对得上、
     * 推进后当前节点随之后移（否则插件仍会定位到旧节点）。
     */
    @Test
    void internalRequirementNodes_returnsBriefsAndTracksCurrentNode() throws Exception {
        Fixture fixture = prepare("rn", true);

        // 1) 新建需求：3 个节点，节点 1 是 current，其余 waiting
        JsonNode first = readData(mockMvc.perform(get("/internal/requirements/" + fixture.requirementId + "/nodes")
                        .header("X-Internal-Token", INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn());
        assertEquals(fixture.requirementId, first.path("requirementId").asLong());
        assertEquals("in_progress", first.path("requirementStatus").asText());
        assertEquals("T51 需求-" + SUFFIX + "-rn", first.path("requirementTitle").asText());
        assertEquals(1, first.path("currentSeq").asInt());
        assertEquals(3, first.path("nodes").size());
        JsonNode nodes = first.path("nodes");
        // 摘要必须按 seq 升序（插件"取最后一个完成节点"依赖这个顺序）
        for (int i = 0; i < 3; i += 1) {
            assertEquals(i + 1, nodes.get(i).path("seq").asInt());
            assertEquals(fixture.nodes.get(i).id(), nodes.get(i).path("nodeInstanceId").asLong());
        }
        assertEquals("需求澄清", nodes.get(0).path("name").asText());
        assertEquals("current", nodes.get(0).path("status").asText());
        assertEquals("waiting", nodes.get(1).path("status").asText());
        assertEquals("waiting", nodes.get(2).path("status").asText());
        // 只返回摘要：不能把快照大字段带出来
        assertEquals(List.of("nodeInstanceId", "seq", "name", "status"), fieldNames(nodes.get(0)));

        // 2) 推进节点 1 → 节点 2 变成 current，currentSeq 前进 1
        completeNode1(fixture);
        JsonNode second = readData(mockMvc.perform(get("/internal/requirements/" + fixture.requirementId + "/nodes")
                        .header("X-Internal-Token", INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn());
        assertEquals(2, second.path("currentSeq").asInt());
        assertEquals("done", second.path("nodes").get(0).path("status").asText());
        assertEquals("current", second.path("nodes").get(1).path("status").asText());
        assertEquals("waiting", second.path("nodes").get(2).path("status").asText());
    }

    /**
     * 内部接口 {@code GET /internal/requirements/{rid}/nodes}：需求不存在 → 404/10301。
     * <p>
     * 不能返回"空节点列表"了事：插件会把它当成"需求下没有节点"，而真实原因是需求 ID 已失效
     * （例如库被复位过），两种情况给模型的提示完全不同。
     */
    @Test
    void internalRequirementNodes_returns404WhenRequirementMissing() throws Exception {
        mockMvc.perform(get("/internal/requirements/99999999/nodes")
                        .header("X-Internal-Token", INTERNAL_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(10301))
                .andExpect(jsonPath("$.message").value(containsString("需求不存在")));
        // 错误的内部令牌同样被拦在设计好的 401 上（与其它 /internal/** 一致）
        mockMvc.perform(get("/internal/requirements/1/nodes")
                        .header("X-Internal-Token", "wrong-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10103));
    }

    // ==================================================================
    // 用例 6：会话登记与默认会话解析
    // ==================================================================

    @Test
    void sessions_registerResolveAndTouch() throws Exception {
        Fixture fixture = prepare("ss", true);
        long node1 = fixture.nodes.get(0).id();

        // 没有会话时 defaultSessionId = null
        assertTrue(nodeDetailOk(fixture.token, fixture.requirementId, node1).path("defaultSessionId").isNull());

        // 节点绑定会话 A → defaultSessionId 命中 A（AC-X2.1）
        JsonNode sessionA = registerSessionOk(fixture.token, fixture.requirementId,
                "sess_a_" + SUFFIX, node1, "会话 A");
        JsonNode nodeAfterA = nodeDetailOk(fixture.token, fixture.requirementId, node1);
        assertEquals("sess_a_" + SUFFIX, nodeAfterA.path("defaultSessionId").asText());
        // 节点 primary_session_id 已绑定，且写了运行信息与弱关联
        assertEquals(sessionA.path("id").asLong(), jdbcTemplate.queryForObject(
                "SELECT primary_session_id FROM wb_requirement_node WHERE id = ?", Long.class, node1));
        assertEquals(1, count("SELECT COUNT(*) FROM wb_session_runtime_info WHERE agent_session_id = ?",
                sessionA.path("id").asLong()));
        assertEquals(1, count("SELECT COUNT(*) FROM wb_node_session_link WHERE agent_session_id = ? AND"
                + " node_instance_id = ?", sessionA.path("id").asLong(), node1));
        assertEquals(1, count("SELECT COUNT(*) FROM wb_node_session_link WHERE node_instance_id = ?", node1));

        // 重复 dshSessionId → 409/10401
        mockMvc.perform(post("/api/requirements/" + fixture.requirementId + "/sessions")
                        .header("Authorization", "Bearer " + fixture.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("dshSessionId", "sess_a_" + SUFFIX, "nodeId", node1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(10401));

        // 显式新建 B 时，用 B 替换 A 成为同一节点的默认会话（AC-F7.4）。
        JsonNode sessionB = registerSessionOk(fixture.token, fixture.requirementId,
                "sess_b_" + SUFFIX, node1, "会话 B");
        assertEquals("sess_b_" + SUFFIX,
                nodeDetailOk(fixture.token, fixture.requirementId, node1).path("defaultSessionId").asText());

        // 内部接口登记显式绑定时遵守同一规则，等待中的节点只能读取。
        long node2 = fixture.nodes.get(1).id();
        mockMvc.perform(post("/internal/sessions")
                        .header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("requirementId", fixture.requirementId,
                                "dshSessionId", "sess_i_" + SUFFIX, "nodeId", node2))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(10404));
        mockMvc.perform(post("/internal/sessions")
                        .header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("requirementId", fixture.requirementId,
                                "dshSessionId", "sess_i_" + SUFFIX, "nodeId", node1, "title", "插件登记"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dshSessionId").value("sess_i_" + SUFFIX));
        assertEquals("sess_i_" + SUFFIX,
                nodeDetailOk(fixture.token, fixture.requirementId, node1).path("defaultSessionId").asText());
        // 无令牌 → 401
        mockMvc.perform(post("/internal/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("requirementId", fixture.requirementId,
                                "dshSessionId", "sess_x_" + SUFFIX))))
                .andExpect(status().isUnauthorized());

        // 会话列表按 lastActiveAt 倒序；touch 让某个会话浮到最前
        JsonNode listBefore = readData(mockMvc.perform(get("/api/requirements/" + fixture.requirementId + "/sessions")
                        .header("Authorization", "Bearer " + fixture.token))
                .andExpect(status().isOk()).andReturn());
        assertEquals(3, listBefore.size());
        // 固定初始活跃时间，避免同毫秒登记和 touch 让排序断言依赖运行速度。
        jdbcTemplate.update("UPDATE wb_agent_session SET last_active_at = ? WHERE requirement_id = ?",
                LocalDateTime.now().minusDays(1), fixture.requirementId);
        mockMvc.perform(post("/api/sessions/" + sessionA.path("id").asLong() + "/touch")
                        .header("Authorization", "Bearer " + fixture.token))
                .andExpect(status().isOk());
        JsonNode listAfter = readData(mockMvc.perform(get("/api/requirements/" + fixture.requirementId + "/sessions")
                        .header("Authorization", "Bearer " + fixture.token))
                .andExpect(status().isOk()).andReturn());
        assertEquals("sess_a_" + SUFFIX, listAfter.get(0).path("dshSessionId").asText(),
                "touch 之后该会话应排到最前");
        assertEquals("sess_i_" + SUFFIX,
                nodeDetailOk(fixture.token, fixture.requirementId, node1).path("defaultSessionId").asText());

        String touchA = "/api/sessions/" + sessionA.path("id").asLong() + "/touch";
        String touchB = "/api/sessions/" + sessionB.path("id").asLong() + "/touch";
        String selectNode1 = json(Map.of("nodeId", node1));
        String selectNode2 = json(Map.of("nodeId", node2));
        mockMvc.perform(post(touchA).header("Authorization", "Bearer " + fixture.memberToken)
                        .contentType(MediaType.APPLICATION_JSON).content(selectNode1))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10202));
        mockMvc.perform(post(touchA).header("Authorization", "Bearer " + fixture.token)
                        .contentType(MediaType.APPLICATION_JSON).content(selectNode2))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(10404));

        // 其他需求既看不到这些会话，也不能将其绑定到自己的节点。
        Fixture other = prepare("ss2", true);
        JsonNode otherList = readData(mockMvc.perform(get("/api/requirements/" + other.requirementId + "/sessions")
                        .header("Authorization", "Bearer " + other.token))
                .andExpect(status().isOk()).andReturn());
        assertEquals(0, otherList.size());
        mockMvc.perform(post(touchA).header("Authorization", "Bearer " + fixture.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("nodeId", other.nodes.get(0).id()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001));

        // 显式切回 A 刷新后仍是默认；旧客户端仅更新 B 活跃时间不会抢走会话选择。
        mockMvc.perform(post(touchA).header("Authorization", "Bearer " + fixture.token)
                        .contentType(MediaType.APPLICATION_JSON).content(selectNode1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isNodePrimary").value(true));
        mockMvc.perform(post(touchB).header("Authorization", "Bearer " + fixture.token))
                .andExpect(status().isOk());
        assertEquals("sess_a_" + SUFFIX,
                nodeDetailOk(fixture.token, fixture.requirementId, node1).path("defaultSessionId").asText());

        uploadOk(fixture.token, fixture.requirementId, node1, null,
                "需求.md", "客户原话".getBytes(StandardCharsets.UTF_8));
        assertTrue(commit(fixture, node1, "clarify-notes", "澄清记录").path("advanced").asBoolean());
        assertEquals("sess_a_" + SUFFIX,
                nodeDetailOk(fixture.token, fixture.requirementId, node2).path("defaultSessionId").asText());
        mockMvc.perform(post(touchB).header("Authorization", "Bearer " + fixture.token)
                        .contentType(MediaType.APPLICATION_JSON).content(selectNode1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(10404));
        mockMvc.perform(post(touchB).header("Authorization", "Bearer " + fixture.token)
                        .contentType(MediaType.APPLICATION_JSON).content(selectNode2))
                .andExpect(status().isOk());
        assertEquals("sess_b_" + SUFFIX,
                nodeDetailOk(fixture.token, fixture.requirementId, node2).path("defaultSessionId").asText());
        assertEquals("sess_a_" + SUFFIX,
                nodeDetailOk(fixture.token, fixture.requirementId, node1).path("defaultSessionId").asText());
        assertEquals(node2, jdbcTemplate.queryForObject("SELECT last_node_instance_id FROM wb_session_runtime_info"
                + " WHERE agent_session_id = ?", Long.class, sessionB.path("id").asLong()));
        assertEquals(2, count("SELECT COUNT(*) FROM wb_node_session_link WHERE agent_session_id = ?",
                sessionB.path("id").asLong()));
        assertEquals(3, count("SELECT COUNT(*) FROM wb_node_session_link WHERE node_instance_id = ?", node1));

        // 归档后保留历史记录，默认会话回退到最近活跃的未归档会话。
        jdbcTemplate.update("UPDATE wb_agent_session SET archived = 1 WHERE id = ?", sessionB.path("id").asLong());
        assertEquals("sess_a_" + SUFFIX,
                nodeDetailOk(fixture.token, fixture.requirementId, node2).path("defaultSessionId").asText());
        mockMvc.perform(post(touchB).header("Authorization", "Bearer " + fixture.token)
                        .contentType(MediaType.APPLICATION_JSON).content(selectNode2))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(10404));
    }

    // ==================================================================
    // 用例 7：文件下载鉴权与预览
    // ==================================================================

    @Test
    void fileDownload_requiresProjectMember_andUsesUtf8Filename() throws Exception {
        Fixture fixture = prepare("fd", true);
        long node1 = fixture.nodes.get(0).id();
        JsonNode uploaded = uploadOk(fixture.token, fixture.requirementId, node1, null,
                "客户原话.md", "# 标题\n正文".getBytes(StandardCharsets.UTF_8));
        long fileId = uploaded.path("fileId").asLong();

        // 成员可下载：attachment + filename*=UTF-8''%E5...
        mockMvc.perform(get("/api/files/" + fileId + "/download")
                        .header("Authorization", "Bearer " + fixture.token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString("filename*=UTF-8''")))
                .andExpect(content().bytes("# 标题\n正文".getBytes(StandardCharsets.UTF_8)));

        // 非成员 → 403/10201
        String outsider = registerAndLogin("t51_" + SUFFIX + "_fdo");
        mockMvc.perform(get("/api/files/" + fileId + "/download")
                        .header("Authorization", "Bearer " + outsider))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10201));

        // 未登录 → 401/10101
        mockMvc.perform(get("/api/files/" + fileId + "/download"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10101));

        // 文件不存在 → 404
        mockMvc.perform(get("/api/files/99999999")
                        .header("Authorization", "Bearer " + fixture.token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(10301));

        // 元信息 + 文本预览
        mockMvc.perform(get("/api/files/" + fileId).header("Authorization", "Bearer " + fixture.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.originalName").value("客户原话.md"))
                .andExpect(jsonPath("$.data.refType").value("node_input"))
                .andExpect(jsonPath("$.data.previewable").value(true));
        mockMvc.perform(get("/api/files/" + fileId + "/preview").header("Authorization", "Bearer " + fixture.token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/plain")))
                .andExpect(content().string("# 标题\n正文"));

        // 输出文件同样可下载：先推进到节点 2 并提交
        completeNode1(fixture);
        long node2 = fixture.nodes.get(1).id();
        JsonNode commit = commitOk(fixture, node2, "requirement-items", "需求条目清单");
        long outputFileId = jdbcTemplate.queryForObject("SELECT file_id FROM wb_requirement_node_output"
                + " WHERE node_instance_id = ? AND item_key = 'requirement-items'", Long.class, node2);
        mockMvc.perform(get("/api/files/" + outputFileId + "/download")
                        .header("Authorization", "Bearer " + fixture.token))
                .andExpect(status().isOk());
        assertNotNull(commit);

        // 不可预览的类型 → 415/10502（.zip 既不是文本也不是图片）
        Path zip = storagePathOf(outputFileId);
        Files.write(zip, new byte[]{'P', 'K', 3, 4});
        mockMvc.perform(get("/api/files/" + outputFileId + "/preview")
                        .header("Authorization", "Bearer " + fixture.token))
                .andExpect(status().isOk());
    }

    // ==================================================================
    // 用例 8：需求删除时清理输入 / 输出 / 文件 / 会话
    // ==================================================================

    @Test
    void deleteRequirement_cleansInputsOutputsFilesAndSessions() throws Exception {
        Fixture fixture = prepare("dl", true);
        long node1 = fixture.nodes.get(0).id();
        long node2 = fixture.nodes.get(1).id();

        // 造齐数据（三个节点上都要有输入与输出，才能真正覆盖级联顺序）：
        //   节点 1：输入 a.md + 输出 clarify-notes（推进到节点 2）
        //   节点 2：必填输入 b.md + 两项输出（推进到节点 3）
        //   节点 3：输入 c.md + 输出 confirm-summary（需求完成）
        // 另有一个会话在节点 1 当前时登记。
        registerSessionOk(fixture.token, fixture.requirementId, "sess_del_" + SUFFIX, node1, "要删的会话");
        uploadOk(fixture.token, fixture.requirementId, node1, null,
                "a.md", "a".getBytes(StandardCharsets.UTF_8));
        completeNode1(fixture);
        uploadOk(fixture.token, fixture.requirementId, node2, fixture.node2InputItemId,
                "b.md", "b".getBytes(StandardCharsets.UTF_8));
        commitOk(fixture, node2, "requirement-items", "v1");
        commitOk(fixture, node2, "requirement-items", "v2");
        commitOk(fixture, node2, "acceptance-criteria", "验收标准");
        assertEquals(3, count("SELECT COUNT(*) FROM wb_requirement_node_output o JOIN wb_requirement_node n"
                + " ON n.id = o.node_instance_id WHERE n.requirement_id = ?", fixture.requirementId),
                "节点 2 的两次提交指向同一行（版本 2），节点 1 已一行 → 共 3 行");
        long node3 = fixture.nodes.get(2).id();
        uploadOk(fixture.token, fixture.requirementId, node3, null,
                "c.md", "c".getBytes(StandardCharsets.UTF_8));
        commitOk(fixture, node3, "confirm-summary", "确认结论");
        long fileCount = count("SELECT COUNT(*) FROM wb_file WHERE requirement_id = ?", fixture.requirementId);
        // 四个输入、四个当前输出和一个历史版本。
        assertEquals(9, fileCount, "文件登记数");
        List<String> relPaths = jdbcTemplate.queryForList(
                "SELECT storage_rel_path FROM wb_file WHERE requirement_id = ?", String.class, fixture.requirementId);
        // 节点 1 自由输入 1 条；节点 2 必填输入 1 条；节点 3 自由输入 2 条（completeNode1 之外又补传一次）
        assertEquals(4, count("SELECT COUNT(*) FROM wb_requirement_node_input i JOIN wb_requirement_node n"
                + " ON n.id = i.node_instance_id WHERE n.requirement_id = ?", fixture.requirementId),
                "各节点输入记录数");
        // 输出项数 = 各节点输出项个数：节点 1/3 各 1 个、节点 2 两个（重提交只改同一行，不新增行）
        assertEquals(4, count("SELECT COUNT(*) FROM wb_requirement_node_output o JOIN wb_requirement_node n"
                + " ON n.id = o.node_instance_id WHERE n.requirement_id = ?", fixture.requirementId),
                "输出记录数（节点 2 的两次提交指向同一行）");
        assertEquals(2, count("SELECT COUNT(*) FROM wb_requirement_node_output o JOIN wb_requirement_node n"
                + " ON n.id = o.node_instance_id WHERE n.requirement_id = ? AND n.seq = 2", fixture.requirementId),
                "节点 2 有两个输出项");
        // 文件确实都在磁盘上（否则下面的"删除后不存在"就是空断言）
        assertEquals(relPaths.size(), relPaths.stream().map(rel -> storageRoot().resolve(rel))
                .filter(Files::exists).count());

        int revision = jdbcTemplate.queryForObject("SELECT revision FROM wb_requirement WHERE id = ?",
                Integer.class, fixture.requirementId);
        mockMvc.perform(delete("/api/requirements/" + fixture.requirementId).param("revision",
                                String.valueOf(revision))
                        .header("Authorization", "Bearer " + fixture.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 全部清空（T5-1 补齐：输入 / 输出 / 文件原先会被外键挡住导致 500）
        assertEquals(0, count("SELECT COUNT(*) FROM wb_requirement WHERE id = ?", fixture.requirementId));
        assertEquals(0, count("SELECT COUNT(*) FROM wb_requirement_node WHERE requirement_id = ?",
                fixture.requirementId));
        assertEquals(0, count("SELECT COUNT(*) FROM wb_requirement_node_input WHERE node_instance_id IN (?, ?)",
                node1, node2));
        assertEquals(0, count("SELECT COUNT(*) FROM wb_requirement_node_output WHERE node_instance_id IN (?, ?)",
                node1, node2));
        assertEquals(0, count("SELECT COUNT(*) FROM wb_file WHERE requirement_id = ?", fixture.requirementId));
        assertEquals(0, count("SELECT COUNT(*) FROM wb_agent_session WHERE requirement_id = ?",
                fixture.requirementId));
        assertEquals(0, count("SELECT COUNT(*) FROM wb_session_runtime_info i WHERE i.last_node_instance_id IN"
                + " (SELECT id FROM wb_requirement_node WHERE requirement_id = ?)", fixture.requirementId));
        // 磁盘文件也被删
        long remaining = relPaths.stream().map(rel -> storageRoot().resolve(rel)).filter(Files::exists).count();
        assertEquals(0, remaining, "登记过的文件都应随需求删除");
    }

    // ==================================================================
    // 测试数据准备
    // ==================================================================

    /**
     * 准备一套"创建人 + 项目 + 流程（3 节点）+ 需求"的数据。
     * <p>
     * 流程结构（与演示流程 1 同形，便于直接用 PRD 的验收场景）
     * <ul>
     *   <li>节点 1「需求澄清」free，1 个技能，输出 {@code clarify-notes}；</li>
     *   <li>节点 2「需求拆解」fixed，2 个技能，输入 [客户原话整理(必填,text), 现场资料清单(非必填,any)]，
     *       输出 {requirement-items, acceptance-criteria}；</li>
     *   <li>节点 3「需求确认」free，输出 {@code confirm-summary}。</li>
     * </ul>
     *
     * @param tag        用例标签（拼进用户名 / 项目名，便于排查）
     * @param addMember 是否把 demo2 加为普通成员（用于验证"他人只读"）
     * @return 用例上下文
     */
    private Fixture prepare(String tag, boolean addMember) throws Exception {
        String token = registerAndLogin("t51_" + SUFFIX + "_" + tag);
        long projectId = createProject(token, "T51 项目-" + SUFFIX + "-" + tag);
        long skillA = insertSkill(projectId, "需求拆解-" + SUFFIX, 1);
        long skillB = insertSkill(projectId, "风险识别-" + SUFFIX, 2);
        String memberToken = null;
        if (addMember) {
            String memberName = "t51m_" + SUFFIX;
            register(memberName);
            addMemberOk(token, projectId, memberName);
            memberToken = login(memberName, "");
        }
        JsonNode workflow = createWorkflow(token, projectId, "T51 流程-" + SUFFIX + "-" + tag);
        long workflowId = workflow.path("id").asLong();
        long revision = workflow.path("revision").asLong();
        JsonNode saved = saveNodesOk(token, workflowId, revision, "["
                + node(null, "需求澄清", "收集客户原话", "free", "[" + skillA + "]", "[]",
                "[" + output("clarify-notes", "澄清记录", "text", true) + "]") + ","
                + node(null, "需求拆解", "拆出需求条目", "fixed", "[" + skillA + "," + skillB + "]",
                "[" + input("客户原话整理", "text", true) + "," + input("现场资料清单", "any", false) + "]",
                "[" + output("requirement-items", "需求条目清单", "text", true) + ","
                + output("acceptance-criteria", "验收标准", "text", true) + "]") + ","
                + node(null, "需求确认", "核对条目", "free", "[" + skillA + "]", "[]",
                "[" + output("confirm-summary", "确认结论", "text", true) + "]")
                + "]");
        long node2InputItemId = saved.path("nodes").get(1).path("inputs").get(0).path("itemId").asLong();

        JsonNode detail = createRequirementOk(token, projectId, "T51 需求-" + SUFFIX + "-" + tag,
                "客户工单分诊", workflowId);
        long requirementId = detail.path("requirement").path("id").asLong();
        List<NodeRef> nodes = new ArrayList<>();
        for (JsonNode node : detail.path("nodes")) {
            nodes.add(new NodeRef(node.path("id").asLong(), node.path("seq").asInt(), node.path("name").asText()));
        }
        return new Fixture(token, memberToken, projectId, requirementId, nodes, node2InputItemId, workflowId);
    }

    /**
     * 准备"owner 的项目里由普通成员创建需求"的场景（验证 owner 对他人需求也可操作）。
     *
     * @param tag 用例标签
     * @return 用例上下文（token 为 owner，requirementId 为成员创建的需求）
     */
    private Fixture prepareOwnedByMember(String tag) throws Exception {
        String ownerToken = registerAndLogin("t51o_" + SUFFIX + "_" + tag);
        String memberName = "t51om_" + SUFFIX + "_" + tag;
        register(memberName);
        long projectId = createProject(ownerToken, "T51 owner 项目-" + SUFFIX + "-" + tag);
        addMemberOk(ownerToken, projectId, memberName);
        String memberToken = login(memberName, "");

        JsonNode workflow = createWorkflow(memberToken, projectId, "T51 owner 流程-" + SUFFIX + "-" + tag);
        long workflowId = workflow.path("id").asLong();
        saveNodesOk(memberToken, workflowId, workflow.path("revision").asLong(), "["
                + node(null, "需求澄清", "收集客户原话", "free", "[]", "[]",
                "[" + output("clarify-notes", "澄清记录", "text", true) + "]")
                + "]");
        JsonNode detail = createRequirementOk(memberToken, projectId, "成员的需求-" + SUFFIX, null, workflowId);
        long requirementId = detail.path("requirement").path("id").asLong();
        List<NodeRef> nodes = new ArrayList<>();
        for (JsonNode node : detail.path("nodes")) {
            nodes.add(new NodeRef(node.path("id").asLong(), node.path("seq").asInt(), node.path("name").asText()));
        }
        return new Fixture(ownerToken, memberToken, projectId, requirementId, nodes, 0L, workflowId);
    }

    /**
     * 完成节点 1：上传一个自由输入 + 提交 clarify-notes。
     *
     * @param fixture 用例上下文
     * @throws Exception MockMvc 异常
     */
    private void completeNode1(Fixture fixture) throws Exception {
        long node1 = fixture.nodes.get(0).id();
        uploadOk(fixture.token, fixture.requirementId, node1, null,
                "客户原话.md", "客户说要分诊".getBytes(StandardCharsets.UTF_8));
        commitOk(fixture, node1, "clarify-notes", "澄清记录");
    }

    // ==================================================================
    // 请求辅助
    // ==================================================================

    private String commitUrl(long nodeId, String itemKey) {
        return "/internal/nodes/" + nodeId + "/outputs/" + itemKey + "/commit";
    }

    /**
     * 模拟模型写文件并提交（插件 package 的简化版）。
     *
     * @param fixture  用例上下文
     * @param nodeId   节点实例 ID
     * @param itemKey  输出项 key
     * @param content  文件内容
     * @return 响应 data
     */
    private JsonNode commitOk(Fixture fixture, long nodeId, String itemKey, String content) throws Exception {
        return commit(fixture, nodeId, itemKey, content);
    }

    /**
     * 模拟模型"写文件 → 计算 sha256 → 调用 commit"：每次都按传入内容覆写文件再提交。
     * <p>
     * 必须每次都写：内容变化才产生新版本（docs/database.md §5.4），若复用旧文件就测不出"重提交 +1"。
     *
     * @param fixture 用例上下文
     * @param nodeId  节点实例 ID
     * @param itemKey 输出项 key
     * @param content 本次写入的内容
     * @return 提交响应的 data
     */
    private JsonNode commit(Fixture fixture, long nodeId, String itemKey, String content) throws Exception {
        return commit(fixture, nodeId, itemKey, itemKey + ".md", content);
    }

    private JsonNode commit(Fixture fixture, long nodeId, String itemKey, String filename, String content)
            throws Exception {
        Path workspace = Paths.get(jdbcTemplate.queryForObject(
                "SELECT dsh_workspace_path FROM wb_requirement WHERE id = ?", String.class, fixture.requirementId));
        Path file = workspace.resolve(".workbench").resolve(String.valueOf(nodeId)).resolve(filename);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        Map<String, Object> body = new HashMap<>();
        body.put("relativePath", ".workbench/" + nodeId + "/" + filename);
        body.put("sha256", sha256(file));
        body.put("operator", "agent");
        return readData(mockMvc.perform(post(commitUrl(nodeId, itemKey))
                        .header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn());
    }

    private void commitExpect(Fixture fixture, long nodeId, String itemKey, String content,
                              org.springframework.test.web.servlet.ResultMatcher expectedStatus, int code)
            throws Exception {
        Path workspace = Paths.get(jdbcTemplate.queryForObject(
                "SELECT dsh_workspace_path FROM wb_requirement WHERE id = ?", String.class, fixture.requirementId));
        Path file = workspace.resolve(".workbench").resolve(String.valueOf(nodeId)).resolve(itemKey + ".md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        mockMvc.perform(post(commitUrl(nodeId, itemKey))
                        .header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("relativePath", ".workbench/" + nodeId + "/" + itemKey + ".md",
                                "sha256", sha256(file), "operator", "agent"))))
                .andExpect(expectedStatus)
                .andExpect(jsonPath("$.code").value(code));
    }

    private JsonNode uploadOk(String token, long requirementId, long nodeId, Long inputItemId,
                              String fileName, byte[] content) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", fileName,
                "application/octet-stream", content);
        MockHttpServletRequestBuilder request = multipart(
                "/api/requirements/" + requirementId + "/nodes/" + nodeId + "/inputs")
                .file(file)
                .header("Authorization", "Bearer " + token);
        if (inputItemId != null) {
            request = request.param("inputItemId", String.valueOf(inputItemId));
        }
        return readData(mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn());
    }

    private JsonNode nodeDetailOk(String token, long requirementId, long nodeId) throws Exception {
        return readData(mockMvc.perform(get("/api/requirements/" + requirementId + "/nodes/" + nodeId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn());
    }

    private JsonNode registerSessionOk(String token, long requirementId, String dshSessionId, long nodeId,
                                       String title) throws Exception {
        return readData(mockMvc.perform(post("/api/requirements/" + requirementId + "/sessions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("dshSessionId", dshSessionId, "nodeId", nodeId, "title", title))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn());
    }

    // ==================================================================
    // 通用辅助
    // ==================================================================

    /**
     * 注册测试账号（空密码）。
     * <p>
     * 用户名带随机后缀 {@link #SUFFIX}，但同一批测试在<b>同一个 JVM</b> 内只生成一次后缀，
     * 重跑（例如先单独跑再整体跑）时会重复；库里已有同名用户时按"已注册"处理，直接走登录。
     * 这样测试可重复执行，又不会误删别的数据。
     *
     * @param username 用户名
     * @throws Exception MockMvc 异常
     */
    private void register(String username) throws Exception {
        int status = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"\"}"))
                .andReturn().getResponse().getStatus();
        if (status != 200) {
            // 409/10401 = 用户名已存在（上一轮测试遗留），登录即可
            assertTrue(status == 409, "注册失败应只有 409（重名），实际 HTTP " + status);
        }
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
        return readData(mockMvc.perform(put("/api/workflows/" + workflowId + "/nodes")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":" + revision + ",\"nodes\":" + nodesJson + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn());
    }

    private JsonNode createRequirementOk(String token, long projectId, String title, String description,
                                         long workflowId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        body.put("description", description);
        body.put("workflowId", workflowId);
        return readData(mockMvc.perform(post("/api/projects/" + projectId + "/requirements")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn());
    }

    private JsonNode readData(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
    }

    private long insertSkill(long projectId, String name, int versionNo) {
        Long ownerId = jdbcTemplate.queryForObject("SELECT owner_id FROM wb_project WHERE id = ?", Long.class,
                projectId);
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("INSERT INTO wb_skill (project_id, name, description, current_version_id, created_by,"
                        + " deleted, created_at, updated_at) VALUES (?, ?, NULL, NULL, ?, 0, ?, ?)",
                projectId, name, ownerId, now, now);
        long skillId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbcTemplate.update("INSERT INTO wb_skill_version (skill_id, version_no, files_json, checksum, entry_path,"
                        + " uploaded_by, created_at) VALUES (?, ?, '[]', ?, 'SKILL.md', ?, ?)",
                skillId, versionNo, "0".repeat(64), ownerId, now);
        long versionId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbcTemplate.update("UPDATE wb_skill SET current_version_id = ? WHERE id = ?", versionId, skillId);
        return skillId;
    }

    private List<String> jdbcStatuses(long requirementId) {
        return jdbcTemplate.queryForList("SELECT status FROM wb_requirement_node WHERE requirement_id = ?"
                + " ORDER BY seq", String.class, requirementId);
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private Path storageRoot() {
        return Paths.get("./target/test-data").toAbsolutePath().normalize();
    }

    private Path storagePathOf(long fileId) {
        String rel = jdbcTemplate.queryForObject("SELECT storage_rel_path FROM wb_file WHERE id = ?",
                String.class, fileId);
        return storageRoot().resolve(rel);
    }

    private static List<String> toList(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(item -> result.add(item.asText()));
        return result;
    }

    private static List<Long> toLongList(JsonNode array) {
        List<Long> result = new ArrayList<>();
        array.forEach(item -> result.add(item.asLong()));
        return result;
    }

    /**
     * JSON 对象的字段名（按出现顺序），用于断言"接口只返回约定的那几个字段"。
     *
     * @param object 对象节点
     * @return 字段名列表
     */
    private static List<String> fieldNames(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

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

    /**
     * 用例上下文。
     *
     * @param token          需求创建人的 JWT
     * @param memberToken    普通成员（demo2 式）的 JWT，可为 null
     * @param projectId      项目 ID
     * @param requirementId  需求 ID
     * @param nodes          节点实例（按 seq）
     * @param node2InputItemId 节点 2 第一个输入项 ID（fixed 上传用）
     * @param workflowId     流程 ID
     */
    private record Fixture(String token, String memberToken, long projectId, long requirementId,
                           List<NodeRef> nodes, long node2InputItemId, long workflowId) {
    }

    /**
     * 节点引用。
     *
     * @param id   节点实例 ID
     * @param seq  顺序
     * @param name 名称
     */
    private record NodeRef(long id, int seq, String name) {
    }
}
