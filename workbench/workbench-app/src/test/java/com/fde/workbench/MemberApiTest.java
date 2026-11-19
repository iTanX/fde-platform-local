package com.fde.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fde.workbench.dao.entity.ProjectMember;
import com.fde.workbench.dao.entity.ProjectMemberDetail;
import com.fde.workbench.dao.entity.ProjectWithStats;
import com.fde.workbench.dao.entity.User;
import com.fde.workbench.dao.mapper.ProjectMapper;
import com.fde.workbench.dao.mapper.ProjectMemberMapper;
import com.fde.workbench.dao.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * 成员管理接口契约测试（docs/api.md §4、PRD REQ-C1~C4 / REQ-H2~H4）。
 * <p>
 * 数据隔离：每个用例都自建独立用户与项目（用户名带随机后缀），不读写 demo / demo2 / 项目 1，
 * 因此可与演示数据、T1-1 的测试数据共存。
 * <p>
 * 用 JDBC 直接造数的两处（接口尚不存在）：
 * <ul>
 *   <li>技能及其版本 —— 技能上传属于 T2-1；</li>
 *   <li>需求 —— 需求创建属于 T3-1，用来构造"成员仍有需求 → 移除必须整体回滚"的场景。</li>
 * </ul>
 */
@SpringBootTest(properties = "spring.datasource.url=" + TestDatabases.URL)
@AutoConfigureMockMvc
class MemberApiTest {

    /** 随机后缀：保证多次执行不撞用户名 */
    private static final String SUFFIX = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

    /** 合法 SHA-256 占位（wb_skill_version.checksum 为 CHAR(64)） */
    private static final String CHECKSUM = "a".repeat(64);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ProjectMapper projectMapper;

    @Autowired
    private ProjectMemberMapper projectMemberMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ------------------------------------------------------------------
    // 用例 1：列表可见性 —— 成员可看、非成员 403/10201、项目不存在 404/10301
    // ------------------------------------------------------------------

    @Test
    void memberList_visibleToMember_forbiddenToOutsider() throws Exception {
        String owner = "t12_" + SUFFIX + "_l1";
        String member = "t12_" + SUFFIX + "_l2";
        String outsider = "t12_" + SUFFIX + "_l3";
        String ownerToken = registerAndLogin(owner);
        String memberToken = registerAndLogin(member);
        String outsiderToken = registerAndLogin(outsider);
        long projectId = createProject(ownerToken, "成员列表-" + SUFFIX);

        // owner 在列表中，role=owner，joinedAt 带 +08:00
        mockMvc.perform(get("/api/projects/" + projectId + "/members")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].username").value(owner))
                .andExpect(jsonPath("$.data[0].role").value("owner"))
                .andExpect(jsonPath("$.data[0].userId").isNumber())
                .andExpect(jsonPath("$.data[0].displayName").value(owner))
                .andExpect(jsonPath("$.data[0].joinedAt").isNotEmpty());

        // owner 添加成员后，成员本人也能看到列表（AC-H2.1：成员可见成员列表）
        addMemberOk(ownerToken, projectId, member);
        MvcResult list = mockMvc.perform(get("/api/projects/" + projectId + "/members")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andReturn();
        JsonNode items = objectMapper.readTree(list.getResponse().getContentAsString()).path("data");
        // 顺序：owner 在前
        assertEquals("owner", items.get(0).path("role").asText());
        assertEquals("member", items.get(1).path("role").asText());
        assertTrue(items.get(1).path("joinedAt").asText().endsWith("+08:00"), "joinedAt 必须带 +08:00 偏移");
        // 只暴露展示字段，不泄露账号敏感字段
        assertFalse(items.get(0).has("passwordHash"), "MemberVO 不得包含 passwordHash");
        assertEquals(member, items.get(1).path("username").asText());

        // 非成员 → 403 / 10201
        mockMvc.perform(get("/api/projects/" + projectId + "/members")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10201));

        // 项目不存在 → 404 / 10301
        mockMvc.perform(get("/api/projects/99999999/members")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(10301));
    }

    // ------------------------------------------------------------------
    // 用例 2：添加 —— 成功 / 用户不存在 404 / 重复添加 409 / 非 owner 403 / 参数 400
    // ------------------------------------------------------------------

    @Test
    void addMember_success_andErrorCodes() throws Exception {
        String owner = "t12_" + SUFFIX + "_a1";
        String member = "t12_" + SUFFIX + "_a2";
        String ownerToken = registerAndLogin(owner);
        String memberToken = registerAndLogin(member);
        long projectId = createProject(ownerToken, "添加成员-" + SUFFIX);

        // 成功：返回 member 角色，"用户不存在"之外的错误都不会出现
        MvcResult added = mockMvc.perform(post("/api/projects/" + projectId + "/members")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + member + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value(member))
                .andExpect(jsonPath("$.data.role").value("member"))
                .andReturn();
        JsonNode data = objectMapper.readTree(added.getResponse().getContentAsString()).path("data");

        // 落库校验：role=member、created_at 由应用写入
        User memberUser = userMapper.selectByUsername(member);
        ProjectMember record = projectMemberMapper.selectByProjectAndUser(projectId, memberUser.getId());
        assertNotNull(record, "添加后应能在 wb_project_member 查到");
        assertEquals("member", record.getRole());
        assertNotNull(record.getCreatedAt());
        assertEquals(memberUser.getId(), data.path("userId").asLong());

        // 重复添加 → 409 / 10401
        mockMvc.perform(post("/api/projects/" + projectId + "/members")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + member + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(10401));

        // 用户不存在 → 404 / 10301
        mockMvc.perform(post("/api/projects/" + projectId + "/members")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody_" + SUFFIX + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(10301));

        // 参数校验：空用户名 → 400 / 10001
        mockMvc.perform(post("/api/projects/" + projectId + "/members")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001));

        // 非 owner（普通成员）添加 → 403 / 10202（AC-C2.3）
        String third = "t12_" + SUFFIX + "_a3";
        register(third, "");
        mockMvc.perform(post("/api/projects/" + projectId + "/members")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + third + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10202));

        // 非成员添加 → 403 / 10201（先成员后 owner）
        String outsiderToken = registerAndLogin("t12_" + SUFFIX + "_a4");
        mockMvc.perform(post("/api/projects/" + projectId + "/members")
                        .header("Authorization", "Bearer " + outsiderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + third + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10201));
    }

    // ------------------------------------------------------------------
    // 用例 3：转让 —— 双源同时变更、原 owner 失去管理权、revision 冲突、目标校验
    // ------------------------------------------------------------------

    @Test
    void transferOwner_switchesBothSources_andOldOwnerLosesRights() throws Exception {
        String owner = "t12_" + SUFFIX + "_t1";
        String newOwner = "t12_" + SUFFIX + "_t2";
        String other = "t12_" + SUFFIX + "_t3";
        String ownerToken = registerAndLogin(owner);
        String newOwnerToken = registerAndLogin(newOwner);
        registerAndLogin(other);
        long projectId = createProject(ownerToken, "转让-" + SUFFIX);
        addMemberOk(ownerToken, projectId, newOwner);
        addMemberOk(ownerToken, projectId, other);

        User oldOwnerUser = userMapper.selectByUsername(owner);
        User newOwnerUser = userMapper.selectByUsername(newOwner);

        // 转让给"不是成员"的用户 → 404 / 10301
        String stranger = "t12_" + SUFFIX + "_t4";
        String strangerToken = registerAndLogin(stranger);
        mockMvc.perform(post("/api/projects/" + projectId + "/members/"
                        + userMapper.selectByUsername(stranger).getId() + "/transfer-owner")
                        .param("revision", "1")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(10301));
        // 非 owner 发起转让 → 403 / 10202
        mockMvc.perform(post("/api/projects/" + projectId + "/members/" + oldOwnerUser.getId() + "/transfer-owner")
                        .param("revision", "1")
                        .header("Authorization", "Bearer " + newOwnerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10202));

        // 正常转让：revision 1 → 2
        mockMvc.perform(post("/api/projects/" + projectId + "/members/"
                        + newOwnerUser.getId() + "/transfer-owner")
                        .param("revision", "1")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.ownerId").value(newOwnerUser.getId().intValue()))
                .andExpect(jsonPath("$.data.revision").value(2))
                .andExpect(jsonPath("$.data.memberCount").value(3));

        // 双源同步：owner_id 与成员表 role 同时变更，且 owner 只有一个
        ProjectWithStats project = projectMapper.selectById(projectId);
        assertEquals(newOwnerUser.getId(), project.getOwnerId(), "wb_project.owner_id 必须指向新 owner");
        assertEquals(2, project.getRevision());
        List<ProjectMemberDetail> members = projectMemberMapper.selectByProject(projectId);
        assertEquals(1, members.stream().filter(m -> "owner".equals(m.getRole())).count(),
                "任何时刻只能有一个 owner");
        assertEquals("member", projectMemberMapper.selectByProjectAndUser(projectId, oldOwnerUser.getId()).getRole(),
                "原 owner 降为 member（仍是成员）");
        assertEquals("owner", projectMemberMapper.selectByProjectAndUser(projectId, newOwnerUser.getId()).getRole());

        // 原 owner 失去管理权：编辑项目 403/10202、转让 403/10202、移除成员 403/10202（AC-C3.2）
        mockMvc.perform(put("/api/projects/" + projectId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"原 owner 改名\",\"revision\":2}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10202));
        mockMvc.perform(post("/api/projects/" + projectId + "/members/"
                        + oldOwnerUser.getId() + "/transfer-owner")
                        .param("revision", "2")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10202));
        mockMvc.perform(delete("/api/projects/" + projectId + "/members/"
                        + userMapper.selectByUsername(other).getId())
                        .param("revision", "2")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10202));
        // 原 owner 仍能读详情与成员列表（成员身份保留）
        mockMvc.perform(get("/api/projects/" + projectId).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ownerId").value(newOwnerUser.getId().intValue()));
        mockMvc.perform(get("/api/projects/" + projectId + "/members").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        // 新 owner 可编辑项目（AC-H3.1）
        mockMvc.perform(put("/api/projects/" + projectId)
                        .header("Authorization", "Bearer " + newOwnerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"转让后改名-" + SUFFIX + "\",\"revision\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(3));

        // 过期 revision 转让 → 409 / 10402，且数据不变
        mockMvc.perform(post("/api/projects/" + projectId + "/members/"
                        + oldOwnerUser.getId() + "/transfer-owner")
                        .param("revision", "2")
                        .header("Authorization", "Bearer " + newOwnerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(10402));
        assertEquals(newOwnerUser.getId(), projectMapper.selectById(projectId).getOwnerId(), "冲突时不得改动 owner");
        assertEquals("member", projectMemberMapper.selectByProjectAndUser(projectId, oldOwnerUser.getId()).getRole());
    }

    // ------------------------------------------------------------------
    // 用例 4：让 owner_id 与成员表 role 不一致（模拟"只改了一半"）→ 双源校验按非 owner 处理
    // ------------------------------------------------------------------

    @Test
    void ownerCheck_isDoubleSourced_halfUpdatedProjectLocksOut() throws Exception {
        String owner = "t12_" + SUFFIX + "_d1";
        String member = "t12_" + SUFFIX + "_d2";
        String ownerToken = registerAndLogin(owner);
        String memberToken = registerAndLogin(member);
        long projectId = createProject(ownerToken, "双源-" + SUFFIX);
        addMemberOk(ownerToken, projectId, member);
        User ownerUser = userMapper.selectByUsername(owner);
        User memberUser = userMapper.selectByUsername(member);

        // 通过接口把 owner 转给 member，得到合法状态
        mockMvc.perform(post("/api/projects/" + projectId + "/members/"
                        + userMapper.selectByUsername(member).getId() + "/transfer-owner")
                        .param("revision", "1")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        // 【半更新 ①】owner_id 改回原 owner，但成员表里他仍是 member（模拟"转让只改了项目行"）
        jdbcTemplate.update("UPDATE wb_project SET owner_id = ? WHERE id = ?", ownerUser.getId(), projectId);
        mockMvc.perform(post("/api/projects/" + projectId + "/members")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + owner + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10202));

        // 【半更新 ②】owner_id 与成员表都恢复成"原 owner 是 owner"，但 owner_id 指向 member（模拟"转让只改了成员表"）。
        // 注意：当前 owner_id 指向的是 member，他自己也是成员 → 走到 owner 判定后被双源校验拦下（10202），
        // 而不是被成员判定拦下（10201）——这正是"单看成员表会误判成 owner"的反例。
        jdbcTemplate.update("UPDATE wb_project SET owner_id = ? WHERE id = ?", memberUser.getId(), projectId);
        jdbcTemplate.update("UPDATE wb_project_member SET role = 'owner' WHERE project_id = ? AND user_id = ?",
                projectId, ownerUser.getId());
        jdbcTemplate.update("UPDATE wb_project_member SET role = 'member' WHERE project_id = ? AND user_id = ?",
                projectId, memberUser.getId());
        mockMvc.perform(post("/api/projects/" + projectId + "/members")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + owner + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10202));
        mockMvc.perform(post("/api/projects/" + projectId + "/members")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + member + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10202));

        // 恢复为合法状态（双源一致地指向 member）后，member 重新获得管理权：证明判定只看双源、不看历史
        jdbcTemplate.update("UPDATE wb_project_member SET role = 'owner' WHERE project_id = ? AND user_id = ?",
                projectId, memberUser.getId());
        jdbcTemplate.update("UPDATE wb_project_member SET role = 'member' WHERE project_id = ? AND user_id = ?",
                projectId, ownerUser.getId());
        String third = "t12_" + SUFFIX + "_d3";
        register(third, "");
        addMemberOk(memberToken, projectId, third);
    }

    // ------------------------------------------------------------------
    // 用例 5：移除成员 —— 成功清理技能、移除 owner 自己 409、非成员 403、目标不存在 404、revision 过期 409
    // ------------------------------------------------------------------

    @Test
    void removeMember_cleansSkills_andValidatesTargetAndRevision() throws Exception {
        String owner = "t12_" + SUFFIX + "_r1";
        String member = "t12_" + SUFFIX + "_r2";
        String ownerToken = registerAndLogin(owner);
        register(member, "");
        long projectId = createProject(ownerToken, "移除-" + SUFFIX);
        addMemberOk(ownerToken, projectId, member);
        User memberUser = userMapper.selectByUsername(member);
        User ownerUser = userMapper.selectByUsername(owner);

        // 该成员在本项目有 1 个技能、2 个版本；另有一个其他成员的技能不能被误删
        long skillId = insertSkill(projectId, memberUser.getId(), "成员技能");
        insertSkillVersion(skillId, 1);
        insertSkillVersion(skillId, 2);
        long ownerSkillId = insertSkill(projectId, ownerUser.getId(), "owner 技能");
        insertSkillVersion(ownerSkillId, 1);

        // 移除 owner 自己 → 409 / 10404（必须先转让）
        mockMvc.perform(delete("/api/projects/" + projectId + "/members/" + ownerUser.getId())
                        .param("revision", "1")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(10404));
        assertNotNull(projectMemberMapper.selectByProjectAndUser(projectId, ownerUser.getId()));

        // revision 过期 → 409 / 10402，且成员仍在
        mockMvc.perform(delete("/api/projects/" + projectId + "/members/" + memberUser.getId())
                        .param("revision", "99")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(10402));
        assertNotNull(projectMemberMapper.selectByProjectAndUser(projectId, memberUser.getId()));

        // 目标不是成员（已注册但不是该项目成员）→ 404 / 10301
        String strangerToken = registerAndLogin("t12_" + SUFFIX + "_r3");
        assertNotNull(strangerToken);
        mockMvc.perform(delete("/api/projects/" + projectId + "/members/"
                        + userMapper.selectByUsername("t12_" + SUFFIX + "_r3").getId())
                        .param("revision", "1")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(10301));

        // 正常移除：成员记录与技能（含版本）都被清理，其他成员的技能不受影响
        mockMvc.perform(delete("/api/projects/" + projectId + "/members/" + memberUser.getId())
                        .param("revision", "1")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        assertNull(projectMemberMapper.selectByProjectAndUser(projectId, memberUser.getId()), "成员记录必须删除");
        assertEquals(0, countSkills(projectId, memberUser.getId()), "成员技能必须删除");
        assertEquals(0, countSkillVersions(skillId), "技能版本必须删除");
        assertEquals(1, countSkills(projectId, ownerUser.getId()), "其他成员的技能不能被误删");
        assertEquals(1, countSkillVersions(ownerSkillId));

        // 被移除后不能再看项目（403/10201）
        mockMvc.perform(get("/api/projects/" + projectId + "/members")
                        .header("Authorization", "Bearer " + login(member, "")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10201));
        // 成员数回到 1
        mockMvc.perform(get("/api/projects/" + projectId).header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$.data.memberCount").value(1));
    }

    // ------------------------------------------------------------------
    // 用例 6：移除失败回滚 —— 成员仍有需求 → 409/10403，技能与成员记录都保留
    // ------------------------------------------------------------------

    @Test
    void removeMember_rollsBack_whenForeignKeysBlock() throws Exception {
        String owner = "t12_" + SUFFIX + "_k1";
        String member = "t12_" + SUFFIX + "_k2";
        String ownerToken = registerAndLogin(owner);
        register(member, "");
        long projectId = createProject(ownerToken, "回滚-" + SUFFIX);
        addMemberOk(ownerToken, projectId, member);
        User memberUser = userMapper.selectByUsername(member);

        // 该成员既有技能（若流程走通会在第一步被删），又有需求（必须阻止移除）
        long skillId = insertSkill(projectId, memberUser.getId(), "回滚技能");
        insertSkillVersion(skillId, 1);
        long workflowId = insertWorkflow(projectId, memberUser.getId());
        insertRequirement(projectId, workflowId, memberUser.getId());

        mockMvc.perform(delete("/api/projects/" + projectId + "/members/" + memberUser.getId())
                        .param("revision", "1")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(10403))
                .andExpect(jsonPath("$.message").value(containsString("无法移除")));

        // 整体回滚：成员仍在、技能与版本仍在（第一步已执行的删除被回滚）
        // 数据层事实：这说明"有需求就必须拒绝移除"不能只靠外键——fk_requirement_creator 拦不住删成员记录。
        // 因此由 MemberDataChecker 在写操作前显式拦截（也顺带保证"技能 + 成员"不会被删掉一半）。
        assertNotNull(projectMemberMapper.selectByProjectAndUser(projectId, memberUser.getId()),
                "失败后成员记录必须保留");
        assertEquals(1, countSkills(projectId, memberUser.getId()), "失败后技能必须保留");
        assertEquals(1, countSkillVersions(skillId), "失败后技能版本必须保留");
        mockMvc.perform(get("/api/projects/" + projectId).header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$.data.memberCount").value(2));
    }

    // ------------------------------------------------------------------
    // 工具方法
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

    /** 创建项目，返回项目 ID */
    private long createProject(String token, String name) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }

    private void addMemberOk(String token, long projectId, String username) throws Exception {
        mockMvc.perform(post("/api/projects/" + projectId + "/members")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("member"));
    }

    /** 直接插入技能（技能上传接口属于 T2-1）；current_version_id 置空，避免与版本行互相依赖 */
    private long insertSkill(long projectId, long userId, String name) {
        jdbcTemplate.update("INSERT INTO wb_skill (project_id, name, description, current_version_id, created_by,"
                        + " deleted, created_at, updated_at) VALUES (?, ?, NULL, NULL, ?, 0, ?, ?)",
                projectId, name + "-" + SUFFIX, userId, LocalDateTime.now(), LocalDateTime.now());
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void insertSkillVersion(long skillId, int versionNo) {
        jdbcTemplate.update("INSERT INTO wb_skill_version (skill_id, version_no, files_json, checksum, entry_path,"
                        + " uploaded_by, created_at) VALUES (?, ?, ?, ?, 'SKILL.md',"
                        + " (SELECT created_by FROM wb_skill WHERE id = ?), ?)",
                skillId, versionNo, "[{\"path\":\"SKILL.md\",\"size\":1,\"sha256\":\"" + CHECKSUM + "\"}]",
                CHECKSUM, skillId, LocalDateTime.now());
    }

    private long insertWorkflow(long projectId, long userId) {
        jdbcTemplate.update("INSERT INTO wb_workflow (project_id, name, description, created_by, deleted,"
                        + " created_at, updated_at) VALUES (?, ?, NULL, ?, 0, ?, ?)",
                projectId, "回滚流程-" + SUFFIX, userId, LocalDateTime.now(), LocalDateTime.now());
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /** 插入需求（需求创建接口属于 T3-1）：它是 fk_requirement_creator 对成员用户的引用 */
    private void insertRequirement(long projectId, long workflowId, long creatorId) {
        jdbcTemplate.update("INSERT INTO wb_requirement (project_id, workflow_id, title, description, creator_id,"
                        + " status, current_seq, dsh_workspace_path, revision, created_at, updated_at)"
                        + " VALUES (?, ?, ?, NULL, ?, 'in_progress', 1, ?, 1, ?, ?)",
                projectId, workflowId, "回滚需求-" + SUFFIX, creatorId,
                "/tmp/workbench-test/" + SUFFIX, LocalDateTime.now(), LocalDateTime.now());
    }

    private int countSkills(long projectId, long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wb_skill WHERE project_id = ? AND created_by = ?", Integer.class, projectId, userId);
        return count == null ? 0 : count;
    }

    private int countSkillVersions(long skillId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wb_skill_version WHERE skill_id = ?", Integer.class, skillId);
        return count == null ? 0 : count;
    }
}
