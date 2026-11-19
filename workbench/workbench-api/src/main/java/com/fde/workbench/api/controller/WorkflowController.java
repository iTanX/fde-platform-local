package com.fde.workbench.api.controller;

import com.fde.workbench.api.dto.WorkflowCreateRequest;
import com.fde.workbench.api.dto.WorkflowNodesRequest;
import com.fde.workbench.api.dto.WorkflowUpdateRequest;
import com.fde.workbench.api.vo.WorkflowSummaryVO;
import com.fde.workbench.api.vo.WorkflowVO;
import com.fde.workbench.common.bo.RevisionStamp;
import com.fde.workbench.common.result.ApiResult;
import com.fde.workbench.service.WorkflowService;
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
 * 流程接口（docs/api.md §6）：列表、创建、详情、更新名称/说明、删除、整体保存节点列表。
 * <p>
 * Controller 只负责参数绑定与 DTO → 命令对象的转换；权限（M / C）、乐观锁、5 条节点校验规则、
 * seq 重排与级联删除顺序全部在 {@link WorkflowService}。
 * <p>
 * 路由说明：列表与创建挂在 {@code /api/projects/{projectId}/workflows} 下，
 * 其余挂在 {@code /api/workflows/{id}} 下（与 api.md 表格一致），因此本类不加类级 {@code @RequestMapping}。
 */
@RestController
public class WorkflowController {

    private final WorkflowService workflowService;

    public WorkflowController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    /**
     * GET /api/projects/{projectId}/workflows —— 项目流程列表（项目成员即可，含节点数）。
     *
     * @param projectId 项目 ID
     * @return 流程列表
     */
    @GetMapping(value = "/api/projects/{projectId}/workflows", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<List<WorkflowSummaryVO>> list(@PathVariable Long projectId) {
        // DTO/VO 分离：service 视图（时间用 service 层序列化器）在这里转成 api 层出网视图
        return ApiResult.ok(WorkflowSummaryVO.of(workflowService.list(projectId)));
    }

    /**
     * POST /api/projects/{projectId}/workflows —— 创建流程（项目成员即可，只收名称与说明）。
     *
     * @param projectId 项目 ID
     * @param request   {@code {name, description?}}
     * @return 新建流程（节点数为 0）
     */
    @PostMapping(value = "/api/projects/{projectId}/workflows", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<WorkflowSummaryVO> create(@PathVariable Long projectId,
                                               @Valid @RequestBody WorkflowCreateRequest request) {
        return ApiResult.ok(WorkflowSummaryVO.of(
                workflowService.create(projectId, request.name(), request.description())));
    }

    /**
     * GET /api/workflows/{id} —— 流程详情（项目成员即可）：含全部节点、输入项、输出项、技能。
     *
     * @param id 流程 ID
     * @return 流程详情（节点按 seq 排序）
     */
    @GetMapping(value = "/api/workflows/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<WorkflowVO> detail(@PathVariable Long id) {
        return ApiResult.ok(WorkflowVO.of(workflowService.detail(id)));
    }

    /**
     * PUT /api/workflows/{id} —— 更新名称 / 说明（创建者或 owner，带 revision）。
     *
     * @param id      流程 ID
     * @param request {@code {name, description?, revision}}
     * @return 更新后的流程详情
     */
    @PutMapping(value = "/api/workflows/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<WorkflowVO> update(@PathVariable Long id, @Valid @RequestBody WorkflowUpdateRequest request) {
        return ApiResult.ok(WorkflowVO.of(
                workflowService.update(id, request.name(), request.description(), request.revision())));
    }

    /**
     * DELETE /api/workflows/{id}?revision=N —— 删除流程（创建者或 owner，逻辑删除）。
     * <p>
     * revision 走查询参数：部分 HTTP 客户端会丢弃 DELETE 请求体（api.md §1.1），用查询参数更稳妥。
     * 查询参数是纯数字（= {@code updated_at} 毫秒时间戳），由 {@link RevisionStamp} 解析；非数字 → 400/10001。
     * 被需求引用时返回 {@code 409/10403}；revision 已过期返回 {@code 409/10402}。
     *
     * @param id       流程 ID
     * @param revision 前端持有的版本号（必填）
     * @return 空数据
     */
    @DeleteMapping(value = "/api/workflows/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<Void> delete(@PathVariable Long id, @RequestParam RevisionStamp revision) {
        workflowService.delete(id, revision);
        return ApiResult.ok();
    }

    /**
     * PUT /api/workflows/{id}/nodes —— <b>整体保存节点列表</b>（创建者或 owner，带 revision）。
     * <p>
     * 一次性提交整个节点列表：后端按数组顺序重排 seq、更新已有节点、新增 {@code nodeId=null} 的节点、
     * 删除请求中不存在的节点（连同其输入项 / 输出项 / 技能关联）。任一校验失败整体回滚，
     * 返回 {@code 400/10001} 并指明是第几个节点、哪一项。
     *
     * @param id      流程 ID
     * @param request {@code {revision, nodes: [...]}}
     * @return 保存后的流程详情（与 GET 详情结构一致）
     */
    @PutMapping(value = "/api/workflows/{id}/nodes", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<WorkflowVO> saveNodes(@PathVariable Long id, @RequestBody WorkflowNodesRequest request) {
        return ApiResult.ok(WorkflowVO.of(workflowService.saveNodes(id, request.toCommand())));
    }
}
