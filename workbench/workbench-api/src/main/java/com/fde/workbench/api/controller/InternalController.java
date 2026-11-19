package com.fde.workbench.api.controller;

import com.fde.workbench.api.dto.InternalSessionCreateRequest;
import com.fde.workbench.api.dto.NodeReferenceRequest;
import com.fde.workbench.api.dto.OutputCommitRequest;
import com.fde.workbench.api.vo.NodeContextVO;
import com.fde.workbench.api.vo.OutputCommitVO;
import com.fde.workbench.api.vo.RequirementNodesVO;
import com.fde.workbench.api.vo.SessionVO;
import com.fde.workbench.common.error.BizException;
import com.fde.workbench.common.error.ErrorCode;
import com.fde.workbench.common.result.ApiResult;
import com.fde.workbench.dao.entity.Requirement;
import com.fde.workbench.dao.mapper.RequirementMapper;
import com.fde.workbench.service.NodeAdvanceService;
import com.fde.workbench.service.NodeWorkflowService;
import com.fde.workbench.service.SessionService;
import com.fde.workbench.service.bo.NodeReferenceCommand;
import com.fde.workbench.service.bo.SessionRegisterCommand;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部接口（docs/api.md §11）：只给插件 Host 调用，鉴权由 {@code InternalAuthFilter} 完成
 * （{@code X-Internal-Token} + 仅 127.0.0.1），因此本类<b>不</b>读取登录用户、也<b>不</b>校验 JWT。
 * <p>
 * 五个接口：
 * <ul>
 *   <li>{@code GET  /internal/nodes/{nid}/context}：{@code node_context_get} 取节点快照；</li>
 *   <li>{@code GET  /internal/requirements/{rid}/nodes}：按需求列出节点实例摘要，
 *       让插件在不传 {@code nodeInstanceId} 时直接定位"当前节点"（替代逐个探测节点 ID 的临时做法）；</li>
 *   <li>{@code POST /internal/nodes/{nid}/outputs/{itemKey}/commit}：登记产出并推进；</li>
 *   <li>{@code POST /internal/sessions}：插件新建会话后登记；</li>
 *   <li>{@code POST /internal/nodes/{nid}/reference}：记录模型读取了某引用文件。</li>
 * </ul>
 * 操作人统一落成"需求创建人"，见 {@code InternalOperator} 的注释。
 */
@RestController
public class InternalController {

    private final NodeWorkflowService nodeWorkflowService;

    private final NodeAdvanceService nodeAdvanceService;

    private final SessionService sessionService;

    /**
     * 需求与节点实例 Mapper：节点清单接口直接用现成的两条查询，
     * 不为一个只读的摘要拼装再新增 Service / Mapper（查询本身就是一次主键查 + 一次按需求查）。
     */
    private final RequirementMapper requirementMapper;

    public InternalController(NodeWorkflowService nodeWorkflowService,
                              NodeAdvanceService nodeAdvanceService,
                              SessionService sessionService,
                              RequirementMapper requirementMapper) {
        this.nodeWorkflowService = nodeWorkflowService;
        this.nodeAdvanceService = nodeAdvanceService;
        this.sessionService = sessionService;
        this.requirementMapper = requirementMapper;
    }

    /**
     * GET /internal/requirements/{requirementId}/nodes —— 需求下全部节点实例的定位摘要。
     * <p>
     * 供插件在 {@code node_context_get} 未带 {@code nodeInstanceId} 时使用：会话 cwd 形如
     * {@code <storageRoot>/ws/<requirementId>}，插件从中拿到需求 ID，调本接口一次即可选出当前节点
     * （{@code status=current}；需求已完成时取最后一个 {@code done} 节点做只读查看），
     * 不再需要按节点实例 ID 逐个试探 {@code /context}。
     * <p>
     * 只返回摘要（id / seq / name / status），完整快照仍由 {@code /internal/nodes/{nid}/context} 提供。
     *
     * @param requirementId 需求 ID
     * @return 需求基本信息（标题、状态、currentSeq）与按 {@code seq} 升序的节点摘要
     * @throws BizException 需求不存在时 404/10301
     */
    @GetMapping(value = "/internal/requirements/{requirementId}/nodes",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<RequirementNodesVO> requirementNodes(@PathVariable Long requirementId) {
        // 与其它接口同口径：查不到就是 404/10301，不用"空节点列表"掩盖"需求不存在"
        Requirement requirement = requirementId == null ? null : requirementMapper.selectById(requirementId);
        if (requirement == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "需求不存在");
        }
        return ApiResult.ok(RequirementNodesVO.of(requirement,
                requirementMapper.selectNodesByRequirement(requirementId)));
    }

    /**
     * GET /internal/nodes/{nodeInstanceId}/context[?operator=agent] —— 节点上下文。
     *
     * @param nodeInstanceId 节点实例 ID
     * @param operator       调用方标识，可空（默认 agent）
     * @return 上下文（快照、输入文件相对路径、输出项完成状态、上一节点输出、技能名、输出目录）
     */
    @GetMapping(value = "/internal/nodes/{nodeInstanceId}/context",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<NodeContextVO.Context> context(@PathVariable Long nodeInstanceId,
                                                    @RequestParam(value = "operator", required = false)
                                                    String operator) {
        return ApiResult.ok(NodeContextVO.Context.of(nodeWorkflowService.context(nodeInstanceId, operator)));
    }

    /**
     * POST /internal/nodes/{nodeInstanceId}/outputs/{itemKey}/commit —— 登记产出并推进节点。
     * <p>
     * 请求体 {@code {relativePath, sha256, operator}}。校验：{@code itemKey} 属于该节点输出项、
     * {@code relativePath} 在 {@code .workbench/<nodeInstanceId>/} 内、文件存在、sha256 一致。
     *
     * @param nodeInstanceId 节点实例 ID
     * @param itemKey        输出项稳定键
     * @param request        提交请求
     * @return {@code {committed, versionNo, advanced, nextNodeName, requirementCompleted,
     * remainingRequiredOutputs, remainingRequiredInputs}}
     */
    @PostMapping(value = "/internal/nodes/{nodeInstanceId}/outputs/{itemKey}/commit",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<OutputCommitVO> commit(@PathVariable Long nodeInstanceId,
                                            @PathVariable String itemKey,
                                            @RequestBody OutputCommitRequest request) {
        if (request == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请求体不能为空");
        }
        return ApiResult.ok(OutputCommitVO.of(nodeAdvanceService.commitOutput(nodeInstanceId, itemKey,
                request.relativePath(), request.sha256(), request.operator())));
    }

    /**
     * POST /internal/sessions —— 插件新建会话后登记（同 api.md §9 的 POST，但免 JWT）。
     *
     * @param request {@code {requirementId, dshSessionId, nodeId?, title?}}
     * @return 新会话
     */
    @PostMapping(value = "/internal/sessions", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<SessionVO> registerSession(@RequestBody InternalSessionCreateRequest request) {
        if (request == null || request.requirementId() == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "缺少 requirementId");
        }
        return ApiResult.ok(SessionVO.of(sessionService.registerInternal(request.requirementId(),
                new SessionRegisterCommand(request.dshSessionId(), request.nodeId(), request.title()))));
    }

    /**
     * POST /internal/nodes/{nodeInstanceId}/reference —— 记录模型读取了某引用文件。
     *
     * @param nodeInstanceId 节点实例 ID
     * @param request        {@code {kind, fileId, itemKey?, operator?}}
     * @return 记录结果
     */
    @PostMapping(value = "/internal/nodes/{nodeInstanceId}/reference",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<NodeContextVO.Reference> reference(@PathVariable Long nodeInstanceId,
                                                        @RequestBody NodeReferenceRequest request) {
        if (request == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请求体不能为空");
        }
        NodeWorkflowService.ReferenceResult result = nodeWorkflowService.registerReference(nodeInstanceId,
                new NodeReferenceCommand(request.kind(), request.fileId(), request.itemKey(), request.operator()),
                request.operator());
        return ApiResult.ok(new NodeContextVO.Reference(result.recorded(), result.fileId(), result.fileName(),
                result.kind()));
    }
}
