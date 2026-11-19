package com.fde.workbench.api.controller;

import com.fde.workbench.api.dto.ProjectCreateRequest;
import com.fde.workbench.api.dto.ProjectUpdateRequest;
import com.fde.workbench.api.vo.ProjectVO;
import com.fde.workbench.common.result.ApiResult;
import com.fde.workbench.common.result.PageResult;
import com.fde.workbench.dao.entity.ProjectWithStats;
import com.fde.workbench.service.ProjectService;
import com.fde.workbench.service.bo.PageQuery;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 项目接口（docs/api.md §3）：列表、创建、详情、编辑、逻辑删除。
 * <p>
 * Controller 只负责参数绑定/校验与 DTO↔VO 转换；权限、事务与乐观锁都在 {@link ProjectService}。
 */
@RestController
@RequestMapping(value = "/api/projects", produces = MediaType.APPLICATION_JSON_VALUE)
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    /**
     * GET /api/projects —— 我所属的项目（分页，keyword 模糊匹配名称）。
     *
     * @param keyword 名称模糊匹配词，可空
     * @param page    页码，从 1 开始，默认 1
     * @param size    每页条数，默认 20，最大 100
     * @return 分页结果 {@code {items, total, page, size}}
     */
    @GetMapping
    public ApiResult<PageResult<ProjectVO>> list(@RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) Integer page,
                                                @RequestParam(required = false) Integer size) {
        PageQuery query = PageQuery.of(page, size);
        PageResult<ProjectWithStats> result = projectService.listMine(keyword, query);
        // DTO/VO 分离：实体（含 deleted 等内部字段）不直接出网，逐条转 ProjectVO
        List<ProjectVO> items = result.getItems().stream().map(ProjectVO::of).toList();
        return ApiResult.ok(PageResult.of(items, result.getTotal(), result.getPage(), result.getSize()));
    }

    /**
     * POST /api/projects —— 创建项目（创建人即 owner）。
     *
     * @param request {@code {name, description?}}
     * @return 新建项目 ProjectVO
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<ProjectVO> create(@Valid @RequestBody ProjectCreateRequest request) {
        return ApiResult.ok(ProjectVO.of(projectService.create(request.name(), request.description())));
    }

    /**
     * GET /api/projects/{id} —— 项目详情（仅成员）。
     *
     * @param id 项目 ID
     * @return ProjectVO
     */
    @GetMapping("/{id}")
    public ApiResult<ProjectVO> detail(@PathVariable Long id) {
        return ApiResult.ok(ProjectVO.of(projectService.detail(id)));
    }

    /**
     * PUT /api/projects/{id} —— 编辑项目（仅 owner，带 revision 乐观锁）。
     *
     * @param id      项目 ID
     * @param request {@code {name, description?, revision}}
     * @return 更新后的 ProjectVO
     */
    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<ProjectVO> update(@PathVariable Long id, @Valid @RequestBody ProjectUpdateRequest request) {
        return ApiResult.ok(ProjectVO.of(
                projectService.update(id, request.name(), request.description(), request.revision())));
    }

    /**
     * DELETE /api/projects/{id} —— 逻辑删除（仅 owner）。
     * <p>
     * revision 走查询参数：DELETE 请求体在部分 HTTP 客户端被丢弃，用查询参数更稳妥。
     * 与 PUT 一致，revision 为必填，保证"读到旧版本就删"不会误删已被他人改过的项目。
     *
     * @param id       项目 ID
     * @param revision 乐观锁版本号，必填
     * @return 空数据
     */
    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable Long id, @RequestParam Integer revision) {
        projectService.delete(id, revision);
        return ApiResult.ok();
    }
}
