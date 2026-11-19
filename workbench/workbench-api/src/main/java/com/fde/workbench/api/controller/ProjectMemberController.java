package com.fde.workbench.api.controller;

import com.fde.workbench.api.dto.AddMemberRequest;
import com.fde.workbench.api.vo.MemberVO;
import com.fde.workbench.api.vo.ProjectVO;
import com.fde.workbench.common.result.ApiResult;
import com.fde.workbench.service.ProjectMemberService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 成员接口（docs/api.md §4）：成员列表、添加、移除、转让 owner。
 * <p>
 * Controller 只负责参数绑定/校验与 VO 转换；权限（成员 / owner 双源校验）、事务顺序与乐观锁
 * 全部在 {@link ProjectMemberService} 与 {@code ProjectAccessService} 中完成。
 * <p>
 * 版本号约定（api.md §1.1）：DELETE 走查询参数 {@code ?revision=N}（部分客户端会丢弃 DELETE 请求体）；
 * 转让是 POST，同样走查询参数，与前端"确认对话框携带当前项目 revision"的用法一致。
 */
@RestController
@RequestMapping(value = "/api/projects/{projectId}/members", produces = MediaType.APPLICATION_JSON_VALUE)
public class ProjectMemberController {

    private final ProjectMemberService projectMemberService;

    public ProjectMemberController(ProjectMemberService projectMemberService) {
        this.projectMemberService = projectMemberService;
    }

    /**
     * GET /api/projects/{projectId}/members —— 成员列表（项目成员即可）。
     *
     * @param projectId 项目 ID
     * @return 成员列表 {@code [{userId, username, displayName, role, joinedAt}]}
     */
    @GetMapping
    public ApiResult<List<MemberVO>> list(@PathVariable Long projectId) {
        List<MemberVO> items = projectMemberService.list(projectId).stream().map(MemberVO::of).toList();
        return ApiResult.ok(items);
    }

    /**
     * POST /api/projects/{projectId}/members —— 添加成员（仅 owner）。
     *
     * @param projectId 项目 ID
     * @param request   {@code {username}}
     * @return 新成员 MemberVO
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<MemberVO> add(@PathVariable Long projectId,
                                   @Valid @RequestBody AddMemberRequest request) {
        return ApiResult.ok(MemberVO.of(projectMemberService.add(projectId, request.username())));
    }

    /**
     * DELETE /api/projects/{projectId}/members/{userId}?revision=N —— 移除成员（仅 owner）。
     * <p>
     * 事务顺序：先删该成员在本项目创建的技能（含版本），再删成员记录；外键失败整体回滚（409/10403）。
     *
     * @param projectId 项目 ID
     * @param userId    被移除的用户 ID
     * @param revision  项目版本号，必填
     * @return 空数据
     */
    @DeleteMapping("/{userId}")
    public ApiResult<Void> remove(@PathVariable Long projectId,
                                  @PathVariable Long userId,
                                  @RequestParam Integer revision) {
        projectMemberService.remove(projectId, userId, revision);
        return ApiResult.ok();
    }

    /**
     * POST /api/projects/{projectId}/members/{userId}/transfer-owner?revision=N —— 转让 owner（仅 owner）。
     * <p>
     * revision 必填：转让会推进项目 revision，缺失或过期都返回 409/10402。
     *
     * @param projectId 项目 ID
     * @param userId    新 owner 的用户 ID
     * @param revision  项目版本号，必填
     * @return 转让后的 ProjectVO（ownerId 已变更）
     */
    @PostMapping("/{userId}/transfer-owner")
    public ApiResult<ProjectVO> transferOwner(@PathVariable Long projectId,
                                              @PathVariable Long userId,
                                              @RequestParam Integer revision) {
        return ApiResult.ok(ProjectVO.of(projectMemberService.transferOwner(projectId, userId, revision)));
    }
}
