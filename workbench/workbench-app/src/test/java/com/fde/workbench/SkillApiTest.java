package com.fde.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fde.workbench.dao.entity.User;
import com.fde.workbench.dao.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 技能管理接口契约测试（docs/api.md §5、PRD REQ-D1 / REQ-D2）。
 * <p>
 * 数据隔离：每个用例都自建独立用户与项目（用户名带随机后缀），不读写演示项目 1 与 demo / demo2，
 * 因此可与演示数据、T1-1 / T1-2 的测试数据共存。
 * <p>
 * 存储根：测试用 {@code @SpringBootTest(properties = "fde.workbench.storage-root=...")} 指向
 * {@code target/test-data}（远离运行目录 workbench/data），并且先清空——避免测试写入污染演示环境的存储目录，
 * 也让"磁盘上没有越界文件"这类断言有一个干净、可预期的起点。
 */
@SpringBootTest(properties = "fde.workbench.storage-root=target/test-data")
@AutoConfigureMockMvc
class SkillApiTest {

    /** 随机后缀：保证多次执行不撞用户名 / 项目名 */
    private static final String SUFFIX = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

    /** 与 application.yml 中 fde.workbench.storage-root 保持一致（测试覆盖为 target/test-data） */
    private static final String STORAGE_ROOT = "target/test-data";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ------------------------------------------------------------------
    // 用例 1：上传 v1 → 同名再传得 v2 → 下载两版内容不同 → 预览 SKILL.md
    // ------------------------------------------------------------------

    @Test
    void upload_createsVersionOne_thenSameNameCreatesVersionTwo_andDownloadsDifference() throws Exception {
        String owner = "t21_" + SUFFIX + "_u1";
        String ownerToken = registerAndLogin(owner);
        long projectId = createProject(ownerToken, "技能上传-" + SUFFIX);

        // ---- 第一次上传：包内含 SKILL.md（包根）+ 一个中文文件名 ----
        byte[] v1 = zipWithRawEntries(ordered(
                "SKILL.md", "# 需求拆解 v1\n把客户原话拆成需求条目。\n",
                "参考资料/示例.md", "v1 的参考示例\n"));
        MvcResult created = uploadOk(ownerToken, projectId, "zhengli.zip", v1);
        JsonNode skill = readData(created);
        long skillId = skill.path("id").asLong();
        assertEquals("zhengli", skill.path("name").asText(), "包根 SKILL.md 时技能名取 zip 文件名");
        assertEquals(1, skill.path("currentVersionNo").asInt());
        assertTrue(skill.path("createdAt").asText().endsWith("+08:00"), "createdAt 必须带 +08:00");
        long v1Id = skill.path("currentVersionId").asLong();

        // 列表里出现 v1
        mockMvc.perform(get("/api/projects/" + projectId + "/skills")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value((int) skillId))
                .andExpect(jsonPath("$.data[0].currentVersionNo").value(1))
                .andExpect(jsonPath("$.data[0].usageCount").value(0));

        // ---- 第二次上传同名：得到 v2，current_version_id 指向 v2 ----
        byte[] v2 = zipWithRawEntries(ordered(
                "SKILL.md", "# 需求拆解 v2\n内容已更新。\n",
                "参考资料/示例.md", "v2 的参考示例\n"));
        JsonNode upgraded = readData(uploadOk(ownerToken, projectId, "zhengli.zip", v2));
        assertEquals(skillId, upgraded.path("id").asLong(), "同名上传复用同一技能");
        assertEquals(2, upgraded.path("currentVersionNo").asInt());
        long v2Id = upgraded.path("currentVersionId").asLong();
        assertNotEquals(v1Id, v2Id, "current_version_id 必须指向新版本");
        // 库里的 current_version_id 真的换了
        Long currentVersionId = jdbcTemplate.queryForObject(
                "SELECT current_version_id FROM wb_skill WHERE id = ?", Long.class, skillId);
        assertEquals(v2Id, currentVersionId);

        // 列表仍然只有 1 个技能、当前版本 v2
        mockMvc.perform(get("/api/projects/" + projectId + "/skills")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].currentVersionId").value((int) v2Id))
                .andExpect(jsonPath("$.data[0].currentVersionNo").value(2));

        // ---- 版本列表：v1、v2，各自带文件清单（含中文文件名）----
        MvcResult versions = mockMvc.perform(get("/api/skills/" + skillId + "/versions")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].versionNo").value(1))
                .andExpect(jsonPath("$.data[1].versionNo").value(2))
                .andExpect(jsonPath("$.data[1].entryPath").value("SKILL.md"))
                .andExpect(jsonPath("$.data[1].files.length()").value(2))
                .andReturn();
        JsonNode v1Files = readData(versions).get(0).path("files");
        assertTrue(v1Files.get(0).path("path").asText().equals("SKILL.md")
                        || v1Files.get(1).path("path").asText().equals("SKILL.md"),
                "文件清单里必须有 SKILL.md");
        // 清单里的 sha256 与上传内容真实一致
        assertEquals(sha256("# 需求拆解 v1\n把客户原话拆成需求条目。\n".getBytes(StandardCharsets.UTF_8)),
                fileEntry(v1Files, "SKILL.md").path("sha256").asText());

        // ---- 下载两版：内容不同，且与上传内容逐文件一致 ----
        Map<String, String> downloadedV1 = downloadAndUnzip(ownerToken, skillId, 1);
        Map<String, String> downloadedV2 = downloadAndUnzip(ownerToken, skillId, 2);
        assertEquals("# 需求拆解 v1\n把客户原话拆成需求条目。\n", downloadedV1.get("SKILL.md"));
        assertEquals("v1 的参考示例\n", downloadedV1.get("参考资料/示例.md"));
        assertEquals("# 需求拆解 v2\n内容已更新。\n", downloadedV2.get("SKILL.md"));
        assertNotEquals(downloadedV1.get("SKILL.md"), downloadedV2.get("SKILL.md"), "两版内容必须不同");

        // 下载响应的 Content-Disposition 用 filename*=UTF-8'' 编码中文技能名
        mockMvc.perform(get("/api/skills/" + skillId + "/versions/2/download")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("filename*=UTF-8''")))
                .andExpect(header().string("Content-Type", "application/zip"));

        // ---- 预览 SKILL.md：返回正文 ----
        mockMvc.perform(get("/api/skills/" + skillId + "/files")
                        .param("path", "SKILL.md")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.path").value("SKILL.md"))
                .andExpect(jsonPath("$.data.content").value("# 需求拆解 v2\n内容已更新。\n"));
        // 指定版本预览 v1
        mockMvc.perform(get("/api/skills/" + skillId + "/files")
                        .param("path", "SKILL.md")
                        .param("versionNo", "1")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("# 需求拆解 v1\n把客户原话拆成需求条目。\n"));
        // 不在清单内的文件 → 400 / 10001
        mockMvc.perform(get("/api/skills/" + skillId + "/files")
                        .param("path", "../../etc/passwd")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001));

        // ---- 磁盘落地路径符合 database.md §5.5 ----
        assertTrue(java.nio.file.Files.isRegularFile(java.nio.file.Paths.get(
                        STORAGE_ROOT, "projects", String.valueOf(projectId), "skills",
                        String.valueOf(skillId), "v1", "SKILL.md")),
                "v1 应落在 projects/<pid>/skills/<sid>/v1/SKILL.md");
        assertTrue(java.nio.file.Files.isRegularFile(java.nio.file.Paths.get(
                        STORAGE_ROOT, "projects", String.valueOf(projectId), "skills",
                        String.valueOf(skillId), "v2", "参考资料", "示例.md")),
                "中文文件名应原样落盘");
    }

    // ------------------------------------------------------------------
    // 用例 2：包内 SKILL.md 位于 skills/<name>/ 时，技能名取目录名且剥掉前缀
    // ------------------------------------------------------------------

    @Test
    void upload_nestedSkillDirectory_usesDirectoryNameAndStripsPrefix() throws Exception {
        String owner = "t21_" + SUFFIX + "_n1";
        String ownerToken = registerAndLogin(owner);
        long projectId = createProject(ownerToken, "嵌套技能-" + SUFFIX);

        // 有序 Map：SKILL.md 必须先于其它条目（Map.of 的迭代顺序不保证）
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("skills/需求拆解技能/SKILL.md", "---\ndescription: 拆解需求\n---\n# 技能正文\n");
        entries.put("skills/需求拆解技能/examples/a.md", "示例\n");
        byte[] zip = zipWithRawEntries(entries);
        JsonNode skill = readData(uploadOk(ownerToken, projectId, "whatever.zip", zip));
        assertEquals("需求拆解技能", skill.path("name").asText());
        assertEquals("拆解需求", skill.path("description").asText(), "description 取自 frontmatter");

        // 前缀被剥掉：落盘是 v1/SKILL.md，而不是 v1/skills/.../SKILL.md
        Map<String, String> files = downloadAndUnzip(ownerToken, skill.path("id").asLong(), 1);
        assertEquals(2, files.size());
        assertEquals("---\ndescription: 拆解需求\n---\n# 技能正文\n", files.get("SKILL.md"));
        assertEquals("示例\n", files.get("examples/a.md"));
    }

    // ------------------------------------------------------------------
    // 用例 3：无 SKILL.md → 400/10001；路径穿越 zip → 400 且磁盘无越界文件
    // ------------------------------------------------------------------

    @Test
    void upload_rejectsMissingSkillMd_andPathTraversal() throws Exception {
        String owner = "t21_" + SUFFIX + "_e1";
        String ownerToken = registerAndLogin(owner);
        long projectId = createProject(ownerToken, "非法技能包-" + SUFFIX);
        long skillCountBefore = countSkills(projectId);

        // ① 没有 SKILL.md → 400 / 10001
        byte[] noEntry = zipWithRawEntries(new LinkedHashMap<>(Map.of("README.md", "# 不是技能\n")));
        mockMvc.perform(multipart("/api/projects/" + projectId + "/skills")
                        .file(new MockMultipartFile("file", "no-entry.zip", "application/zip", noEntry))
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message", containsString("SKILL.md")));

        // ② 含 ../ 的条目 → 400 / 10001（入口文件再正常也不行），且磁盘上不得出现越界文件
        byte[] withEvil = zipWithRawEntries(ordered(
                "SKILL.md", "# 正常入口\n",
                "../../evil.txt", "pwned\n",
                "a/../../../evil2.txt", "pwned\n"));
        mockMvc.perform(multipart("/api/projects/" + projectId + "/skills")
                        .file(new MockMultipartFile("file", "evil.zip", "application/zip", withEvil))
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message", containsString("非法路径")));
        // ③ 绝对路径条目 → 400 / 10001
        byte[] absolute = zipWithRawEntries(ordered("SKILL.md", "# 正常入口\n", "/tmp/evil3.txt", "pwned\n"));
        mockMvc.perform(multipart("/api/projects/" + projectId + "/skills")
                        .file(new MockMultipartFile("file", "evil.zip", "application/zip", absolute))
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001));

        // ④ 非法包不应留下任何技能行 / 版本行
        assertEquals(skillCountBefore, countSkills(projectId), "非法技能包不能写入技能");
        // ⑤ 磁盘上不得出现越界文件（存储根外与项目目录外都不行）
        assertFalse(java.nio.file.Files.exists(java.nio.file.Paths.get("evil.txt")), "不得写入工作目录");
        assertFalse(java.nio.file.Files.exists(java.nio.file.Paths.get("evil2.txt")), "不得写入工作目录");
        assertFalse(java.nio.file.Files.exists(java.nio.file.Paths.get("evil3.txt")), "不得写入工作目录");
        assertFalse(java.nio.file.Files.exists(java.nio.file.Paths.get(STORAGE_ROOT, "evil.txt")),
                "不得写到存储根目录");
        try (var walk = java.nio.file.Files.walk(java.nio.file.Paths.get(STORAGE_ROOT))) {
            assertTrue(walk.noneMatch(path -> path.getFileName().toString().startsWith("evil")),
                    "存储根内不得出现越界文件");
        }
    }

    // ------------------------------------------------------------------
    // 用例 4：被引用删除 409/10403（含引用清单）；未被引用删除成功且列表消失
    // ------------------------------------------------------------------

    @Test
    void delete_rejectedWhenReferencedByUnfinishedRequirement_thenSucceedsWhenUnreferenced() throws Exception {
        String owner = "t21_" + SUFFIX + "_d1";
        String member = "t21_" + SUFFIX + "_d2";
        String ownerToken = registerAndLogin(owner);
        String memberToken = registerAndLogin(member);
        long projectId = createProject(ownerToken, "删除技能-" + SUFFIX);
        addMemberOk(ownerToken, projectId, member);

        // 构造：流程节点 2 挂技能；未完成需求 R1 的节点实例快照引用该技能；另有已完成需求 R2 也引用（不应阻止删除）
        long skillId = readData(uploadOk(ownerToken, projectId, "referenced.zip",
                zipWithRawEntries(new LinkedHashMap<>(Map.of("SKILL.md", "# 被引用\n"))))).path("id").asLong();
        User ownerUser = userMapper.selectByUsername(owner);
        long workflowId = insertWorkflow(projectId, ownerUser.getId());
        long nodeId = insertWorkflowNode(workflowId, 2, "需求拆解");
        jdbcTemplate.update("INSERT INTO wb_workflow_node_skill (node_id, skill_id, seq) VALUES (?, ?, 1)",
                nodeId, skillId);
        long inProgressRequirement = insertRequirement(projectId, workflowId, ownerUser.getId(), "进行中的需求");
        insertRequirementNode(inProgressRequirement, nodeId, 2, "需求拆解", skillId);
        long completedRequirement = insertRequirement(projectId, workflowId, ownerUser.getId(), "已完成的需求");
        jdbcTemplate.update("UPDATE wb_requirement SET status = 'completed' WHERE id = ?", completedRequirement);
        insertRequirementNode(completedRequirement, nodeId, 2, "需求拆解", skillId);

        // 列表：usageCount 体现流程节点引用数
        mockMvc.perform(get("/api/projects/" + projectId + "/skills")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$.data[0].usageCount").value(1));

        // 被未完成需求引用 → 409 / 10403，data.references 给出需求与节点
        MvcResult rejected = mockMvc.perform(delete("/api/skills/" + skillId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(10403))
                .andExpect(jsonPath("$.data.references.length()").value(1))
                .andExpect(jsonPath("$.data.references[0].requirementId").value((int) inProgressRequirement))
                .andExpect(jsonPath("$.data.references[0].requirementTitle")
                        .value("进行中的需求-" + SUFFIX))
                .andExpect(jsonPath("$.data.references[0].nodeName").value("需求拆解"))
                .andReturn();
        assertNotNull(rejected.getResponse().getContentAsString());
        // 被拒绝后数据原样保留
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT deleted FROM wb_skill WHERE id = ?", Integer.class, skillId));
        assertEquals(1, countVersions(skillId));

        // 需求完成后引用不再阻止删除（PRD AC-D2.2）。技能是 owner 上传的，由 creator 删除（权限 C）
        jdbcTemplate.update("UPDATE wb_requirement SET status = 'completed' WHERE id = ?", inProgressRequirement);
        mockMvc.perform(delete("/api/skills/" + skillId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        // 逻辑删除：deleted=1，版本行与历史快照保留
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT deleted FROM wb_skill WHERE id = ?", Integer.class, skillId));
        assertEquals(1, countVersions(skillId), "逻辑删除必须保留版本行");
        // 列表消失
        mockMvc.perform(get("/api/projects/" + projectId + "/skills")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$.data.length()").value(0));
        // 删完后查不到 → 404 / 10301
        mockMvc.perform(get("/api/skills/" + skillId + "/versions")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(10301));

        // ---- 未被引用：成员上传 → 成员删自己的 → 成功 ----
        long freeSkillId = readData(uploadOk(memberToken, projectId, "free.zip",
                zipWithRawEntries(new LinkedHashMap<>(Map.of("SKILL.md", "# 自由技能\n"))))).path("id").asLong();
        mockMvc.perform(delete("/api/skills/" + freeSkillId)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/projects/" + projectId + "/skills")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ------------------------------------------------------------------
    // 用例 5：删除权限 —— 非创建者成员 403/10202；owner 可删他人技能；非成员 403/10201
    // ------------------------------------------------------------------

    @Test
    void delete_enforcesCreatorOrOwner() throws Exception {
        String owner = "t21_" + SUFFIX + "_p1";
        String member = "t21_" + SUFFIX + "_p2";
        String ownerToken = registerAndLogin(owner);
        String memberToken = registerAndLogin(member);
        long projectId = createProject(ownerToken, "删除权限-" + SUFFIX);
        addMemberOk(ownerToken, projectId, member);

        long skillId = readData(uploadOk(ownerToken, projectId, "owner-skill.zip",
                zipWithRawEntries(new LinkedHashMap<>(Map.of("SKILL.md", "# owner 的\n"))))).path("id").asLong();

        // 非创建者成员删除他人技能 → 403 / 10202
        mockMvc.perform(delete("/api/skills/" + skillId)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10202));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT deleted FROM wb_skill WHERE id = ?", Integer.class, skillId));

        // 非成员删除 → 403 / 10201（先成员后权限）
        String outsiderToken = registerAndLogin("t21_" + SUFFIX + "_p3");
        mockMvc.perform(delete("/api/skills/" + skillId)
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10201));

        // owner 删除成员的技能（权限 C 的另一种情形）
        long memberSkillId = readData(uploadOk(memberToken, projectId, "member-skill.zip",
                zipWithRawEntries(new LinkedHashMap<>(Map.of("SKILL.md", "# 成员的\n"))))).path("id").asLong();
        mockMvc.perform(delete("/api/skills/" + memberSkillId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 非成员不能读技能列表 / 版本
        mockMvc.perform(get("/api/projects/" + projectId + "/skills")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10201));
        // 项目不存在 → 404 / 10301
        mockMvc.perform(get("/api/projects/99999999/skills").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(10301));
    }

    // ------------------------------------------------------------------
    // 工具方法：账号与项目
    // ------------------------------------------------------------------

    private void register(String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk());
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("token").asText();
    }

    /** 空密码注册 + 登录，返回 token */
    private String registerAndLogin(String username) throws Exception {
        register(username, "");
        return login(username, "");
    }

    private long createProject(String token, String name) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return readData(created).path("id").asLong();
    }

    private void addMemberOk(String token, long projectId, String username) throws Exception {
        mockMvc.perform(post("/api/projects/" + projectId + "/members")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\"}"))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // 工具方法：上传 / 下载 / zip 构造
    // ------------------------------------------------------------------

    /** 上传成功并返回响应 */
    private MvcResult uploadOk(String token, long projectId, String fileName, byte[] zip) throws Exception {
        return mockMvc.perform(multipart("/api/projects/" + projectId + "/skills")
                        .file(new MockMultipartFile("file", fileName, "application/zip", zip))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
    }

    /** 下载并按 zip 解出"相对路径 → 文本内容" */
    private Map<String, String> downloadAndUnzip(String token, long skillId, int versionNo) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/skills/" + skillId + "/versions/" + versionNo + "/download")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(
                new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                files.put(entry.getName(), new String(zis.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    /** 按传入顺序构造有序 Map（Map.of 的迭代顺序不保证，SKILL.md 未必是第一个条目） */
    private static Map<String, String> ordered(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    /** 按原始条目名构造 zip（不做任何路径校验，用于构造恶意包） */
    private static byte[] zipWithRawEntries(Map<String, String> entries) throws Exception {

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(entry.getKey()));
                zos.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /**
     * 读响应 data。
     * <p>
     * 必须显式用 UTF-8 解码：MockHttpServletResponse 在没有 charset 参数时按 ISO-8859-1 解码，
     * 会把中文技能名读成 mojibake（"需求拆解技能" → "é¦æ±æ..."），导致断言假失败。
     */
    private JsonNode readData(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).path("data");
    }

    private JsonNode fileEntry(JsonNode files, String path) {
        for (JsonNode file : files) {
            if (path.equals(file.path("path").asText())) {
                return file;
            }
        }
        throw new AssertionError("文件清单里没有：" + path);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    // ------------------------------------------------------------------
    // 工具方法：直接造数（流程 / 需求 / 节点实例属于 T3-1 / T4-1）
    // ------------------------------------------------------------------

    private long insertWorkflow(long projectId, long userId) {
        jdbcTemplate.update("INSERT INTO wb_workflow (project_id, name, description, created_by, deleted,"
                        + " created_at, updated_at) VALUES (?, ?, NULL, ?, 0, ?, ?)",
                projectId, "技能引用流程-" + SUFFIX, userId, LocalDateTime.now(), LocalDateTime.now());
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertWorkflowNode(long workflowId, int seq, String name) {
        jdbcTemplate.update("INSERT INTO wb_workflow_node (workflow_id, seq, name, description, input_type,"
                        + " created_at, updated_at) VALUES (?, ?, ?, NULL, 'free', ?, ?)",
                workflowId, seq, name, LocalDateTime.now(), LocalDateTime.now());
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertRequirement(long projectId, long workflowId, long creatorId, String title) {
        jdbcTemplate.update("INSERT INTO wb_requirement (project_id, workflow_id, title, description, creator_id,"
                        + " status, current_seq, dsh_workspace_path, revision, created_at, updated_at)"
                        + " VALUES (?, ?, ?, NULL, ?, 'in_progress', 1, ?, 1, ?, ?)",
                projectId, workflowId, title + "-" + SUFFIX, creatorId,
                "/tmp/workbench-test/" + SUFFIX, LocalDateTime.now(), LocalDateTime.now());
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /** 插入节点实例，skills_snapshot 引用指定技能（结构见 001_init.sql：skillId / name / versionId / versionNo） */
    private void insertRequirementNode(long requirementId, long workflowNodeId, int seq, String name, long skillId) {
        String snapshot = "[{\"skillId\":" + skillId + ",\"name\":\"引用技能\",\"versionId\":1,\"versionNo\":1}]";
        jdbcTemplate.update("INSERT INTO wb_requirement_node (requirement_id, workflow_node_id, seq, name,"
                        + " description, input_type, inputs_snapshot, outputs_snapshot, skills_snapshot,"
                        + " status, created_at) VALUES (?, ?, ?, ?, NULL, 'free', '[]', '[]', ?, 'waiting', ?)",
                requirementId, workflowNodeId, seq, name, snapshot, LocalDateTime.now());
    }

    private long countSkills(long projectId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wb_skill WHERE project_id = ?", Long.class, projectId);
        return count == null ? 0 : count;
    }

    private int countVersions(long skillId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wb_skill_version WHERE skill_id = ?", Integer.class, skillId);
        return count == null ? 0 : count;
    }
}
