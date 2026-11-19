package com.fde.workbench.api.controller;

import com.fde.workbench.api.dto.RequirementCreateRequest;
import com.fde.workbench.api.dto.RequirementUpdateRequest;
import com.fde.workbench.api.vo.RequirementDetailVO;
import com.fde.workbench.api.vo.RequirementVO;
import com.fde.workbench.common.bo.RevisionStamp;
import com.fde.workbench.common.result.ApiResult;
import com.fde.workbench.common.result.PageResult;
import com.fde.workbench.service.RequirementService;
import com.fde.workbench.service.bo.PageQuery;
import com.fde.workbench.service.bo.RequirementUpdateCommand;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 需求接口（docs/api.md §7）：项目内列表与创建、全局详情 / 编辑 / 删除。
 * <p>
 * Controller 只负责参数绑定与 DTO ↔ VO 转换；权限（M / C）、乐观锁、快照与事务边界全部在
 * {@link RequirementService}。
 * <p>
 * 路由说明：列表与创建挂在 {@code /api/projects/{projectId}/requirements} 下，其余挂在
 * {@code /api/requirements/{id}} 下（与 api.md 表格一致），因此本类不加类级 {@code @RequestMapping}。
 * 节点相关接口（{@code /api/requirements/{rid}/nodes/**}）属于 T5-1，不在本类实现。
 */
@RestController
public class RequirementController {

    private final RequirementService requirementService;

    public RequirementController(RequirementService requirementService) {
        this.requirementService = requirementService;
    }

    /**
     * GET /api/projects/{projectId}/requirements —— 项目需求列表（成员即可，分页）。
     * <p>
     * 查询参数：{@code keyword}（标题模糊）、{@code status}（in_progress / completed）、
     * {@code page}（从 1 开始）、{@code size}（默认 20，最大 100）。
     *
     * @param projectId 项目 ID
     * @param keyword   标题模糊词，可空
     * @param status    状态过滤，可空
     * @param page      页码，可空
     * @param size      每页条数，可空
     * @return 分页结果 {@code {items, total, page, size}}
     */
    @GetMapping(value = "/api/projects/{projectId}/requirements", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<PageResult<RequirementVO>> list(@PathVariable Long projectId,
                                                     @RequestParam(required = false) String keyword,
                                                     @RequestParam(required = false) String status,
                                                     @RequestParam(required = false) Integer page,
                                                     @RequestParam(required = false) Integer size) {
        PageQuery query = PageQuery.of(page, size);
        PageResult<com.fde.workbench.service.vo.RequirementVO> result =
                requirementService.list(projectId, keyword, status, query);
        // DTO/VO 分离：service 视图（含 service 层时间序列化器）在这里转成 api 层出网视图
        List<RequirementVO> items = RequirementVO.of(result.getItems());
        return ApiResult.ok(PageResult.of(items, result.getTotal(), result.getPage(), result.getSize()));
    }

    /**
     * POST /api/projects/{projectId}/requirements —— 创建需求（成员即可），同一事务生成节点实例。
     *
     * @param projectId 项目 ID
     * @param request   {@code {title, description?, workflowId}}
     * @return 需求详情（与 GET 详情结构一致）
     */
    @PostMapping(value = "/api/projects/{projectId}/requirements", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<RequirementDetailVO> create(@PathVariable Long projectId,
                                                 @Valid @RequestBody RequirementCreateRequest request) {
        return ApiResult.ok(RequirementDetailVO.of(requirementService.create(projectId, request.title(),
                request.description(), request.workflowId())));
    }

    /**
     * GET /api/requirements/{id} —— 需求详情（成员即可）：需求本体 + 节点进度 + 权限。
     *
     * @param id 需求 ID
     * @return 需求详情
     */
    @GetMapping(value = "/api/requirements/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<RequirementDetailVO> detail(@PathVariable Long id) {
        return ApiResult.ok(RequirementDetailVO.of(requirementService.detail(id)));
    }

    /**
     * PUT /api/requirements/{id} —— 编辑标题 / 说明（创建人或 owner，带 revision）。
     *
     * @param id      需求 ID
     * @param request {@code {title, description?, revision}}
     * @return 更新后的需求详情
     */
    @PutMapping(value = "/api/requirements/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<RequirementDetailVO> update(@PathVariable Long id,
                                                 @RequestBody RequirementUpdateRequest request) {
        return ApiResult.ok(RequirementDetailVO.of(requirementService.update(id,
                new RequirementUpdateCommand(request.title(), request.description(), request.revision()))));
    }

    /**
     * DELETE /api/requirements/{id}?revision=N —— 删除需求（创建人或 owner，物理删除）。
     * <p>
     * revision 走查询参数：部分 HTTP 客户端会丢弃 DELETE 请求体（api.md §1.1）。
     * 删除会连同节点实例、会话关联一并清理；revision 过期 → {@code 409/10402}。
     *
     * @param id       需求 ID
     * @param revision 前端持有的版本号（必填）
     * @return 空数据
     */
    @DeleteMapping(value = "/api/requirements/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<Void> delete(@PathVariable Long id, @RequestParam RevisionStamp revision) {
        requirementService.delete(id, revision);
        return ApiResult.ok();
    }
}
