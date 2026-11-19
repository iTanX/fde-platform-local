package com.fde.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fde.workbench.dao.entity.Project;
import com.fde.workbench.dao.entity.ProjectMember;
import com.fde.workbench.dao.entity.User;
import com.fde.workbench.dao.mapper.ProjectMapper;
import com.fde.workbench.dao.mapper.ProjectMemberMapper;
import com.fde.workbench.dao.mapper.UserMapper;
import com.fde.workbench.service.security.PasswordHasher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * 认证与项目接口的契约测试（docs/api.md §2、§3）。
 * <p>
 * 数据隔离：每个用例注册带随机后缀的新用户，并把它放进"自己的项目"，不删除也不修改演示数据
 * （demo / demo2 / 项目 1）；断言只针对"我"的数据，因此可与其他演示数据共存。
 * <p>
 * 三类用例合在一个类里，是因为它们共享同一个 Spring 上下文（启动一次，避免重复建连）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthProjectApiTest {

    /** 随机后缀：保证多次执行不撞用户名 */
    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
    // 注意：用户名只允许 [A-Za-z0-9_]；UUID 片段内已含 "-"，必须去掉
    private static final String SUFFIX = RUN.replace("-", "");

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
    private PasswordHasher passwordHasher;

    // ------------------------------------------------------------------
    // 用例 1：空密码注册与登录
    // ------------------------------------------------------------------

    @Test
    void registerWithEmptyPassword_storesNullHash_andLogsInWithEmptyPassword() throws Exception {
        String username = "t1_" + SUFFIX + "_a";

        // 注册：密码为空字符串 → password_hash 存 NULL（表结构事实源约定）
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").isNumber());

        User user = userMapper.selectByUsername(username);
        assertNotNull(user, "注册后应能在 wb_user 查到");
        assertNull(user.getPasswordHash(), "空密码注册必须存 NULL，而不是空串哈希");
        assertEquals(username, user.getDisplayName(), "displayName 为空时默认等于 username");
        assertNotNull(user.getCreatedAt(), "created_at 由应用写入");

        // 空密码可登录，拿到 token 且带 +08:00 的 expiresAt
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.user.username").value(username))
                .andExpect(jsonPath("$.data.expiresAt").value(org.hamcrest.Matchers.endsWith("+08:00")));

        // 有哈希的空密码用户，传非空密码必须失败
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"x\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10102));
    }

    // ------------------------------------------------------------------
    // 用例 2：非空密码 → BCrypt；错误密码 10102；用户不存在文案
    // ------------------------------------------------------------------

    @Test
    void registerWithPassword_usesBcrypt_andRejectsWrongPassword() throws Exception {
        String username = "t1_" + SUFFIX + "_b";
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"p@ssw0rd\","
                                + "\"displayName\":\"带密码用户\"}"))
                .andExpect(status().isOk());

        User user = userMapper.selectByUsername(username);
        assertNotNull(user.getPasswordHash());
        assertTrue(user.getPasswordHash().startsWith("$2a$") || user.getPasswordHash().startsWith("$2b$"),
                "password_hash 必须是 BCrypt 格式");
        assertTrue(passwordHasher.matches("p@ssw0rd", user.getPasswordHash()));

        // 正确密码登录成功
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"p@ssw0rd\"}"))
                .andExpect(status().isOk());

        // 错误密码 → 401 / 10102
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10102));
    }

    @Test
    void loginUnknownUser_returns10102WithExactMessage() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody_" + SUFFIX + "\",\"password\":\"x\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10102))
                // api.md §2 规定这句文案，属于契约
                .andExpect(jsonPath("$.message").value("用户不存在，请先注册"));
    }

    @Test
    void registerRejectsInvalidUsername_andDuplicate() throws Exception {
        // 2 位不合法（<3）
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ab\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001));

        // 非法字符
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bad-name\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001));

        // 重复注册 → 409 / 10401
        String username = "t1_" + SUFFIX + "_c";
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(10401));
    }

    // ------------------------------------------------------------------
    // 用例 3：鉴权过滤器（无 token / 坏 token → 401 10101；放行清单）
    // ------------------------------------------------------------------

    @Test
    void protectedApiWithoutToken_returns401With10101() throws Exception {
        mockMvc.perform(get("/api/projects"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10101));
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10101));
        mockMvc.perform(get("/api/projects").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10101));
    }

    @Test
    void publicEndpoints_workWithoutToken() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.db").value("ok"));
    }

    // ------------------------------------------------------------------
    // 用例 4：项目创建（同事务写 owner 成员记录）与列表只返回我所属项目
    // ------------------------------------------------------------------

    @Test
    void createProject_writesOwnerMember_andListShowsOnlyMine() throws Exception {
        String username = "t1_" + SUFFIX + "_d";
        register(username, "");
        String token = login(username, "");

        MvcResult created = mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"T1测试项目-" + SUFFIX + "\",\"description\":\"单测\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(1))
                .andExpect(jsonPath("$.data.ownerId").isNumber())
                .andExpect(jsonPath("$.data.memberCount").value(1))
                .andReturn();
        long projectId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        // owner 成员记录必须与项目同事务写入
        User me = userMapper.selectByUsername(username);
        ProjectMember member = projectMemberMapper.selectByProjectAndUser(projectId, me.getId());
        assertNotNull(member, "创建者必须被写成项目成员");
        assertEquals("owner", member.getRole());
        assertNotNull(member.getCreatedAt(), "成员 created_at 由应用写入");

        // 列表只含我所属的项目，且 total 与 items 一致
        MvcResult list = mockMvc.perform(get("/api/projects").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andReturn();
        JsonNode items = objectMapper.readTree(list.getResponse().getContentAsString())
                .path("data").path("items");
        assertEquals(1, items.size());
        assertEquals(projectId, items.get(0).path("id").asLong());
        assertTrue(items.get(0).path("createdAt").asText().endsWith("+08:00"), "createdAt 必须带 +08:00 偏移");

        // keyword 过滤
        mockMvc.perform(get("/api/projects").param("keyword", "不存在的关键字" + SUFFIX)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/projects").param("keyword", SUFFIX)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    // ------------------------------------------------------------------
    // 用例 5：成员 / owner 权限矩阵 + 乐观锁 + 逻辑删除
    // ------------------------------------------------------------------

    @Test
    void projectPermission_andOptimisticLock_andLogicalDelete() throws Exception {
        String owner = "t1_" + SUFFIX + "_e1";
        String outsider = "t1_" + SUFFIX + "_e2";
        String memberUser = "t1_" + SUFFIX + "_e3";
        register(owner, "");
        register(outsider, "");
        register(memberUser, "");
        String ownerToken = login(owner, "");
        String outsiderToken = login(outsider, "");
        String memberToken = login(memberUser, "");

        MvcResult created = mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"权限测试-" + SUFFIX + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        long projectId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        // 非成员访问详情 → 403 / 10201（不泄露资源存在性）
        mockMvc.perform(get("/api/projects/" + projectId).header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10201));
        // 非成员访问列表看不到它
        mockMvc.perform(get("/api/projects").header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        // 模拟 T1-2 的普通成员：直接插一条 role=member（本任务不实现成员接口，只在库里造数据）
        User memberAccount = userMapper.selectByUsername(memberUser);
        ProjectMember pm = new ProjectMember();
        pm.setProjectId(projectId);
        pm.setUserId(memberAccount.getId());
        pm.setRole("member");
        pm.setCreatedAt(LocalDateTime.now());
        projectMemberMapper.insert(pm);

        // 成员可见详情，但不能编辑 → 403 / 10202
        mockMvc.perform(get("/api/projects/" + projectId).header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(projectId))
                .andExpect(jsonPath("$.data.memberCount").value(2));
        mockMvc.perform(put("/api/projects/" + projectId)
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"改名\",\"revision\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10202));

        // owner 编辑成功：revision 1 → 2
        mockMvc.perform(put("/api/projects/" + projectId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"权限测试-改名-" + SUFFIX + "\",\"revision\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("权限测试-改名-" + SUFFIX))
                .andExpect(jsonPath("$.data.revision").value(2));

        // 再用过期 revision=1 编辑 → 409 / 10402
        mockMvc.perform(put("/api/projects/" + projectId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"再次改\",\"revision\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(10402));

        // 编辑缺 revision → 400 / 10001
        mockMvc.perform(put("/api/projects/" + projectId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"缺版本\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001));

        // 成员删除 → 403 / 10202
        mockMvc.perform(delete("/api/projects/" + projectId).param("revision", "2")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10202));

        // owner 逻辑删除：revision=2 → 成功；项目行仍在，deleted=1
        mockMvc.perform(delete("/api/projects/" + projectId).param("revision", "2")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        assertNull(projectMapper.selectById(projectId), "逻辑删除后按 id 查询应视为不存在");
        mockMvc.perform(get("/api/projects/" + projectId).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(10301));

        // 已删除项目不再出现在任何人的列表里
        mockMvc.perform(get("/api/projects").header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/projects").header("Authorization", "Bearer " + memberToken))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    // ------------------------------------------------------------------
    // 工具方法：注册 / 登录取 token
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
}
