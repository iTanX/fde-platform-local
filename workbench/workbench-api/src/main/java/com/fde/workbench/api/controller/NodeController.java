package com.fde.workbench.api.controller;

import com.fde.workbench.api.dto.SessionCreateRequest;
import com.fde.workbench.api.dto.SessionTouchRequest;
import com.fde.workbench.api.vo.AdvancePreviewVO;
import com.fde.workbench.api.vo.NodeInputUploadVO;
import com.fde.workbench.api.vo.RequirementNodeVO;
import com.fde.workbench.api.vo.SessionVO;
import com.fde.workbench.common.result.ApiResult;
import com.fde.workbench.service.NodeWorkflowService;
import com.fde.workbench.service.SessionService;
import com.fde.workbench.service.bo.SessionRegisterCommand;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 节点与需求会话接口（docs/api.md §8 节点信息、§9 会话）。
 * <p>
 * Controller 只做参数绑定与 VO 转换；权限（M / C）、只读判定、上传校验、文件落盘与路径防护
 * 全部在 {@link NodeWorkflowService} / {@link SessionService} 中。
 * <p>
 * 路由：节点接口挂在 {@code /api/requirements/{requirementId}/nodes/{nodeId}} 下，
 * 会话接口挂在 {@code /api/requirements/{requirementId}/sessions} 与 {@code /api/sessions/{sessionId}/touch}
 * 下（与 api.md 表格一致），因此本类不加类级 {@code @RequestMapping}。
 */
@RestController
public class NodeController {

    private final NodeWorkflowService nodeWorkflowService;

    private final SessionService sessionService;

    public NodeController(NodeWorkflowService nodeWorkflowService, SessionService sessionService) {
        this.nodeWorkflowService = nodeWorkflowService;
        this.sessionService = sessionService;
    }

    // ------------------------------------------------------------------
    // §8 节点信息
    // ------------------------------------------------------------------

    /**
     * GET /api/requirements/{requirementId}/nodes/{nodeId} —— 节点信息页（项目成员即可）。
     * <p>
     * 返回 {@code RequirementNodeVO}：节点名称 / 说明 / 状态 / 输入类型 / 技能、输入区（逐项 + 已上传文件）、
     * 输出区（含 {@code submitted} / {@code versionNo}）、上一节点输出、只读标记与默认会话。
     *
     * @param requirementId 需求 ID
     * @param nodeId        节点实例 ID
     * @return 节点信息
     */
    @GetMapping(value = "/api/requirements/{requirementId}/nodes/{nodeId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<RequirementNodeVO> detail(@PathVariable Long requirementId, @PathVariable Long nodeId) {
        return ApiResult.ok(RequirementNodeVO.of(nodeWorkflowService.detail(requirementId, nodeId)));
    }

    /**
     * POST /api/requirements/{requirementId}/nodes/{nodeId}/inputs —— 上传节点输入（multipart，权限 C）。
     * <p>
     * 字段：{@code file}（必填）、{@code inputItemId}（固定输入节点必填，自由输入节点必须不传）。
     * 同一固定输入项允许多次上传（累加，不覆盖）。
     *
     * @param requirementId 需求 ID
     * @param nodeId        节点实例 ID
     * @param file          上传文件
     * @param inputItemId   固定输入项 ID，可空
     * @return 上传结果（fileId / originalName / sizeBytes / inputRecordId / inputItemId / uploadedAt）
     */
    @PostMapping(value = "/api/requirements/{requirementId}/nodes/{nodeId}/inputs",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<NodeInputUploadVO> upload(@PathVariable Long requirementId,
                                               @PathVariable Long nodeId,
                                               @RequestParam("file") MultipartFile file,
                                               @RequestParam(value = "inputItemId", required = false) Long inputItemId)
            throws IOException {
        try (InputStream in = file.getInputStream()) {
            return ApiResult.ok(NodeInputUploadVO.of(nodeWorkflowService.uploadInput(requirementId, nodeId,
                    inputItemId, file.getOriginalFilename(), in)));
        }
    }

    /**
     * DELETE /api/requirements/{requirementId}/nodes/{nodeId}/inputs/{inputRecordId} —— 删除已上传输入（权限 C）。
     *
     * @param requirementId 需求 ID
     * @param nodeId        节点实例 ID
     * @param inputRecordId 输入记录 ID
     * @return 空数据
     */
    @DeleteMapping(value = "/api/requirements/{requirementId}/nodes/{nodeId}/inputs/{inputRecordId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<Void> deleteInput(@PathVariable Long requirementId,
                                       @PathVariable Long nodeId,
                                       @PathVariable Long inputRecordId) {
        nodeWorkflowService.deleteInput(requirementId, nodeId, inputRecordId);
        return ApiResult.ok();
    }

    /**
     * GET /api/requirements/{requirementId}/nodes/{nodeId}/advance-preview —— 推进条件预览（权限 C）。
     *
     * @param requirementId 需求 ID
     * @param nodeId        节点实例 ID
     * @return {@code {ready, remainingRequiredOutputs, remainingRequiredInputs, requiresAnyInput, missingDescriptions}}
     */
    @GetMapping(value = "/api/requirements/{requirementId}/nodes/{nodeId}/advance-preview",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<AdvancePreviewVO> advancePreview(@PathVariable Long requirementId,
                                                      @PathVariable Long nodeId) {
        return ApiResult.ok(AdvancePreviewVO.of(nodeWorkflowService.advancePreview(requirementId, nodeId)));
    }

    // ------------------------------------------------------------------
    // §9 会话
    // ------------------------------------------------------------------

    /**
     * GET /api/requirements/{requirementId}/sessions —— 需求下会话列表（项目成员即可，按 lastActiveAt 倒序）。
     *
     * @param requirementId 需求 ID
     * @return 会话列表
     */
    @GetMapping(value = "/api/requirements/{requirementId}/sessions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<List<SessionVO>> sessions(@PathVariable Long requirementId) {
        return ApiResult.ok(sessionService.list(requirementId).stream().map(SessionVO::of).toList());
    }

    /**
     * POST /api/requirements/{requirementId}/sessions —— 登记新会话并绑定节点（权限 C）。
     * <p>
     * 请求体 {@code {dshSessionId, nodeId, title}}；{@code dshSessionId} 重复 → {@code 409/10401}。
     *
     * @param requirementId 需求 ID
     * @param request       登记请求
     * @return 新会话
     */
    @PostMapping(value = "/api/requirements/{requirementId}/sessions", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<SessionVO> registerSession(@PathVariable Long requirementId,
                                                @Valid @RequestBody SessionCreateRequest request) {
        return ApiResult.ok(SessionVO.of(sessionService.register(requirementId,
                new SessionRegisterCommand(request.dshSessionId(), request.nodeId(), request.title()))));
    }

    /**
     * POST /api/sessions/{sessionId}/touch —— 更新会话活跃时间，可同时设为当前节点的默认会话（权限 C）。
     *
     * @param sessionId 会话 ID
     * @param request   可选请求体，包含当前节点实例 ID
     * @return 更新后的会话
     */
    @PostMapping(value = "/api/sessions/{sessionId}/touch", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<SessionVO> touch(@PathVariable Long sessionId,
                                     @RequestBody(required = false) SessionTouchRequest request) {
        return ApiResult.ok(SessionVO.of(sessionService.touch(sessionId, request == null ? null : request.nodeId())));
    }
}
