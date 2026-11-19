/**
 * 四个模型工具：`node_context_get` / `node_reference_read` / `node_output_write` / `node_output_commit`。
 *
 * 契约见 docs/plugin-integration.md §5.2 与任务卡 T5-2。实现取向：
 *   - 参数与结果都按"模型视角"写：描述说清**什么时候该用、用了会发生什么**，不写实现细节；
 *   - 一切失败（参数不对、路径越界、后端 4xx/5xx、网络不通）都返回**可读中文**，不抛裸异常；
 *   - 结果统一走 `{ok, message, data}` 信封：`message` 是给模型读的那段文本，
 *     `data` 给程序化消费与验证脚本用。
 *
 * 为什么是对象信封而不是纯文本：v2 执行管线要求每个工具声明 `output { schema, render }`，
 * 且 canonical value 必须是 JSON 对象（`tools.register` 会在注册时断言）。`render` 只把
 * `message` 转成一个文本块，模型看到的仍是紧凑文本，不是 JSON。
 *
 * 节点定位（`nodeInstanceId`）的两条路径：
 *   1. 显式传入：模型先调过 `node_context_get`，后续每一次调用都带上它；这是官方做法，
 *      也是多会话/多节点并行时唯一安全的做法；
 *   2. 缺省推导：按会话 cwd 与 `Session.header.cwd` 解析出需求，由
 *      `NodeDirectory`（`workbench.ts`）给出该需求**最近一次通过 `/context` 确认过的节点**。
 *      覆盖"需求只有一个工作区、模型第一次调用就没给 ID"的常见情形。
 */

import { readFile } from 'node:fs/promises'
import { isAbsolute, resolve } from 'node:path'
import type { Context } from '@deepseek-ai/cordis'
import { defineTool } from '@deepseek-ai/dsh-tools'
import type { ToolRunContext } from '@deepseek-ai/dsh-tools'
import { BackendClient } from './backend.ts'
import type { FileRef, NodeContext } from './backend.ts'
import { formatBytes, isInside, nodeOutputDir, normalizeWorkspaceRelative } from './paths.ts'
import {
  formatCommitResult,
  formatNodeContext,
  prepareOutputWrite,
  readReference,
  sha256,
  writeOutputFile,
} from './logic.ts'
import type { NodeDirectory } from './workbench.ts'
import type { Settings } from './settings.ts'

/** 三个工具共用的选择条件（显式 `undefined` 表示"用当前节点"）。 */
export interface NodeSelection {
  /** 模型显式给出的节点实例 ID。 */
  nodeInstanceId: number | undefined
}

/**
 * 定位钩子（<b>只给离线验证脚本用</b>，生产代码不读写）。
 *
 * 为什么要有它：验证脚本要断言"不给 nodeInstanceId 时，定位当前节点只发 1 次节点清单 +
 * 1 次 `/context`，且推进后缓存会失效"，这些行为藏在 `registerTools` 的闭包里，从外部拿不到实例。
 * 把注册时的 `locate` 与 `directory.invalidate`（推进后调用的同一个入口）暴露出来，
 * 让脚本能像工具那样走一遍真实的定位路径。这是唯一的测试接缝，不改变工具行为。
 */
export const locateHooks: {
  locate?: (selection: NodeSelection, exec: ToolRunContext) => Promise<NodeContext>
  invalidate?: (requirementId: number) => void
} = {}

/**
 * 结果信封（所有工具统一）。
 *
 * `data` 声明为 `JsonValue`：这是 DSH 工具 canonical value 的要求（必须是无损 JSON），
 * 也保证工具结果能原样写进会话语料、再被回放。
 */
type JsonValue = Parameters<import('@deepseek-ai/dsh-tools').ToolOutputDefinition['render']>[1]

export interface ToolEnvelope {
  ok: boolean
  message: string
  data?: JsonValue
}

/**
 * 工具输出 schema：`{ok, message, data?}`。
 * `data` 声明为开放对象（additionalProperties: true）——各工具塞进去的字段不同，
 * 而模型只读 `message`，`data` 是给验证脚本与程序化消费用的。
 */
const ENVELOPE_SCHEMA = {
  type: 'object' as const,
  additionalProperties: false,
  properties: {
    ok: { type: 'boolean' as const, required: true as const },
    message: { type: 'string' as const, required: true as const },
    data: { type: 'json' as const },
  },
}

/**
 * 渲染信封：模型只看 `message`；失败时加前缀，让模型一眼分辨。
 * @param _args - 模型参数（未使用）
 * @param value - 工具结果
 * @returns 一个文本块
 */
function renderEnvelope(_args: unknown, value: { ok: boolean, message: string }): { type: 'text', text: string }[] {
  return [{ type: 'text', text: value.ok ? value.message : `【失败】${value.message}` }]
}

/**
 * 把任意异常翻译成失败信封（message 已是中文）。
 * @param error - 捕获到的异常
 * @returns 失败信封
 */
function fail(error: unknown): ToolEnvelope {
  return { ok: false, message: error instanceof Error ? error.message : String(error) }
}

/** 工具注册所需的依赖。 */
export interface RegisterToolsOptions {
  /** 内部接口客户端。 */
  client: BackendClient
  /** 解析后的配置。 */
  settings: Settings
  /** 节点目录（记着每个需求/会话的当前节点）。 */
  directory: NodeDirectory
}

/** 可读的文件引用（引用读取工具与错误提示共同使用）。 */
interface ReferenceCandidate {
  file: FileRef
  kind: 'input' | 'previousOutput' | 'ownOutput'
  note: string
  /** 匹配 itemKey 用的键（输入项用名字，输出项用 itemKey）。 */
  itemKey?: string
}

/**
 * 收集本节点全部可读引用文件。
 * @param context - 节点上下文
 * @returns 候选列表（输入 → 上一节点输出 → 本节点已提交输出）
 */
export function referenceCandidates(context: NodeContext): ReferenceCandidate[] {
  const candidates: ReferenceCandidate[] = []
  for (const item of context.inputItems) {
    for (const file of item.files) {
      candidates.push({ file, kind: 'input', note: `输入项「${item.name}」`, itemKey: item.name })
    }
  }
  for (const file of context.freeInputFiles) {
    candidates.push({ file, kind: 'input', note: '自由上传输入', itemKey: file.name })
  }
  const previous = context.previousNode
  if (previous !== null && previous !== undefined) {
    for (const output of previous.outputs) {
      candidates.push({
        // previousNode 项没有 sizeBytes/sha256，用占位值；读取时以磁盘为准
        file: { fileId: output.fileId, name: output.name, relativePath: output.relativePath, sizeBytes: 0, sha256: '' },
        kind: 'previousOutput',
        note: `上一节点「${previous.name}」的输出「${output.name}」`,
        itemKey: output.itemKey,
      })
    }
  }
  for (const output of context.outputs) {
    if (output.submitted && output.relativePath !== null && output.relativePath !== undefined) {
      candidates.push({
        file: { fileId: 0, name: output.fileName ?? output.name, relativePath: output.relativePath, sizeBytes: 0, sha256: '' },
        kind: 'ownOutput',
        note: `本节点已提交输出「${output.name}」`,
        itemKey: output.itemKey,
      })
    }
  }
  return candidates
}

/**
 * 按 fileId 或 itemKey 选择引用文件。
 * @param context - 节点上下文
 * @param selection - `{ itemKey?, fileId? }`
 * @returns 命中的候选与剩余的候选（给"有多个候选"的提示用）
 */
function selectReference(
  context: NodeContext,
  selection: { itemKey: string | undefined, fileId: number | undefined },
): { hit: ReferenceCandidate | undefined, ambiguous: ReferenceCandidate[] | undefined } {
  const candidates = referenceCandidates(context)
  if (selection.fileId !== undefined) {
    return { hit: candidates.find(candidate => candidate.file.fileId === selection.fileId), ambiguous: undefined }
  }
  if (selection.itemKey === undefined) return { hit: undefined, ambiguous: undefined }
  const normalized = selection.itemKey.trim().toLowerCase()
  const matched = candidates.filter(candidate => (candidate.itemKey ?? '').toLowerCase() === normalized
    || (candidate.file.relativePath.split('/').pop() ?? '').toLowerCase().startsWith(`${normalized}.`))
  if (matched.length > 1) return { hit: undefined, ambiguous: matched }
  return { hit: matched[0], ambiguous: undefined }
}

/**
 * 列出可读引用文件（错误提示用）。
 * @param context - 节点上下文
 * @returns 一行摘要
 */
function describeAvailable(context: NodeContext): string {
  const candidates = referenceCandidates(context)
  if (candidates.length === 0) return '（本节点当前没有可读的文件）'
  return candidates
    .map(candidate => `${candidate.kind}:${candidate.itemKey ?? '-'}→${candidate.file.relativePath}`
      + (candidate.file.fileId > 0 ? `(fileId ${candidate.file.fileId})` : ''))
    .join('；')
}

/**
 * 注册四个工具。
 * @param ctx - 插件上下文
 * @param options - 客户端、配置与节点目录
 */
export function registerTools(ctx: Context, options: RegisterToolsOptions): void {
  const { client, settings, directory } = options

  /**
   * 解析出节点上下文；失败时抛中文错误。
   * @param exec - 工具执行上下文（取会话与取消信号）
   * @param selection - 模型给出的选择
   * @param requireCurrent - 是否要求节点正在办理（写入/提交时）
   */
  const locate = async (exec: ToolRunContext, selection: NodeSelection, requireCurrent: boolean): Promise<NodeContext> => {
    if (exec.agent === undefined) {
      throw new Error('无法确定当前会话：请在工作台的需求节点会话里使用这些工具。')
    }
    const context = await directory.resolveNode(exec.agent, selection.nodeInstanceId, exec.signal)
    if (requireCurrent && context.nodeStatus !== 'current') {
      throw new Error(
        `节点 ${context.nodeSeq}「${context.nodeName}」当前状态是 ${context.nodeStatus}，不是正在办理的节点。`
        + (context.nodeStatus === 'done' ? '该节点已完成，如需修改请联系用户在工作台里退回或新建需求。' : '请等待前面的节点完成。'),
      )
    }
    return context
  }
  // 供离线验证脚本使用（见 locateHooks 的注释）：转成验证脚本的调用形状，不改变工具行为
  locateHooks.locate = async (selection, exec) => await locate(exec, selection, false)
  locateHooks.invalidate = (requirementId: number) => { directory.invalidate(requirementId) }

  // ------------------------------------------------------------------ 1. node_context_get
  ctx.tools.register(defineTool({
    name: 'node_context_get',
    description: 'Read the delivery node you are working on: its name and description, the skills in effect, '
      + 'the input files the user uploaded, the previous node\'s outputs, and the output items you must produce '
      + '(with itemKey, file kind, required flag and the suggested write path). Call it before writing any '
      + 'output, and again whenever you resume or after the user uploaded files. Without nodeInstanceId it '
      + 'follows the current node of this requirement.',
    parameters: {
      nodeInstanceId: {
        type: 'integer',
        description: 'Node instance id from a previous node_context_get. Omit to read the current node of this requirement.',
      },
    },
    output: { schema: ENVELOPE_SCHEMA, render: renderEnvelope },
    isConcurrencySafe: () => true,
    timeoutMs: 20_000,
    async execute(args, exec) {
      try {
        const context = await locate(exec, { nodeInstanceId: args.nodeInstanceId }, false)
        return {
          ok: true,
          message: formatNodeContext(context),
          data: {
            nodeInstanceId: context.nodeInstanceId,
            requirementId: context.requirementId,
            nodeSeq: context.nodeSeq,
            nodeCount: context.nodeCount,
            nodeName: context.nodeName,
            nodeStatus: context.nodeStatus,
            outputDir: context.outputDir,
            outputs: context.outputs.map(output => ({
              itemKey: output.itemKey,
              name: output.name,
              fileKind: output.fileKind,
              required: output.required,
              submitted: output.submitted,
              suggestPath: output.suggestPath,
            })),
          },
        }
      } catch (error) {
        return fail(error)
      }
    },
    presentCall: args => ({
      card: 'generic',
      title: '读取节点上下文',
      kind: 'read',
      ...(args.nodeInstanceId === undefined ? {} : { rawInput: `节点 ${args.nodeInstanceId}` }),
    }),
  }))

  // ------------------------------------------------------------------ 2. node_reference_read
  ctx.tools.register(defineTool({
    name: 'node_reference_read',
    description: 'Read one reference file of this node: an input the user uploaded, an output committed by the '
      + 'previous node, or an output you already committed here. Returns file metadata and the text content, '
      + 'truncated when long (the remaining byte count is stated). The read is recorded for the page. '
      + 'Use it instead of guessing file paths.',
    parameters: {
      kind: {
        type: 'string',
        enum: ['input', 'previousOutput', 'ownOutput'],
        required: true,
        description: 'input = uploaded input of this node; previousOutput = an output of the previous node; ownOutput = an output you already committed on this node.',
      },
      itemKey: { type: 'string', description: 'Output item key (for previousOutput/ownOutput), or the input item name.' },
      fileId: { type: 'integer', description: 'Exact file id as listed by node_context_get; preferred when an item has several files.' },
      nodeInstanceId: { type: 'integer', description: 'Node instance id from node_context_get. Omit to use the current node.' },
    },
    output: { schema: ENVELOPE_SCHEMA, render: renderEnvelope },
    isConcurrencySafe: () => true,
    timeoutMs: 30_000,
    async execute(args, exec) {
      try {
        if (args.itemKey === undefined && args.fileId === undefined) {
          throw new Error('请至少提供 itemKey 或 fileId，指明要读哪个引用文件。')
        }
        const context = await locate(exec, { nodeInstanceId: args.nodeInstanceId }, false)
        const { hit, ambiguous } = selectReference(context, { itemKey: args.itemKey, fileId: args.fileId })
        if (ambiguous !== undefined && ambiguous.length > 1) {
          throw new Error(
            `itemKey=${args.itemKey} 匹配到多个文件，请改用 fileId：`
            + ambiguous.map(candidate => `${candidate.file.relativePath}(fileId ${candidate.file.fileId})`).join('、'),
          )
        }
        if (hit === undefined) {
          throw new Error(
            `没有找到符合条件的引用文件（itemKey=${args.itemKey ?? '未给'}，fileId=${args.fileId ?? '未给'}）。`
            + `本节点可读的文件：${describeAvailable(context)}`,
          )
        }
        if (hit.kind !== args.kind) {
          throw new Error(`按 itemKey/fileId 找到的是 ${hit.kind}（${hit.note}），与 kind=${args.kind} 不一致。请把 kind 改成 ${hit.kind}。`)
        }
        const workspacePath = context.workspacePath
        const absolutePath = resolve(workspacePath, hit.file.relativePath)
        if (!isInside(workspacePath, absolutePath)) {
          throw new Error(`引用文件路径越出需求 workspace：${hit.file.relativePath}`)
        }
        const read = await readReference({
          absolutePath,
          relativePath: hit.file.relativePath,
          maxBytes: settings.maxReferenceBytes,
          info: {
            ...(hit.file.fileId > 0 ? { fileId: hit.file.fileId } : {}),
            ...(args.itemKey === undefined ? {} : { itemKey: args.itemKey }),
          },
        })
        // 登记"模型读了什么"：仅页面展示用，失败不影响读取
        let recordNote = ''
        if (hit.file.fileId > 0) {
          try {
            await client.reference(
              context.nodeInstanceId,
              {
                kind: hit.kind,
                fileId: hit.file.fileId,
                ...(args.itemKey === undefined ? {} : { itemKey: args.itemKey }),
                operator: 'agent',
              },
              exec.signal,
            )
          } catch (error) {
            recordNote = `\n（提示：引用登记失败，不影响内容读取：${error instanceof Error ? error.message : String(error)}）`
          }
        }
        return {
          ok: true,
          message: `${read.text}${recordNote}`,
          data: {
            kind: hit.kind,
            relativePath: hit.file.relativePath,
            fileId: hit.file.fileId,
            truncatedBytes: read.truncatedBytes,
          },
        }
      } catch (error) {
        return fail(error)
      }
    },
    presentCall: args => ({
      card: 'generic',
      title: '读取引用文件',
      kind: 'read',
      rawInput: args.itemKey ?? (args.fileId === undefined ? '' : `fileId ${args.fileId}`),
    }),
  }))

  // ------------------------------------------------------------------ 3. node_output_write
  ctx.tools.register(defineTool({
    name: 'node_output_write',
    description: 'Save the content of one output item into the node output directory and return the '
      + 'relativePath, sha256 and byte count that node_output_commit needs. This is the only allowed way to '
      + 'save a deliverable (do not use write/edit/bash for it). The file name and extension follow the output '
      + 'item, so pass just itemKey.',
    parameters: {
      itemKey: { type: 'string', required: true, description: 'Output item key exactly as listed by node_context_get.' },
      content: { type: 'string', description: 'Full text content to write. Provide either content or sourcePath.' },
      sourcePath: { type: 'string', description: 'Existing file to copy (absolute, or relative to the workspace). Provide either content or sourcePath.' },
      nodeInstanceId: { type: 'integer', description: 'Node instance id from node_context_get. Omit to use the current node.' },
    },
    output: { schema: ENVELOPE_SCHEMA, render: renderEnvelope },
    timeoutMs: 60_000,
    async execute(args, exec) {
      try {
        if ((args.content === undefined) === (args.sourcePath === undefined)) {
          throw new Error('content 与 sourcePath 必须二选一：直接给正文用 content，复制已有文件用 sourcePath。')
        }
        const context = await locate(exec, { nodeInstanceId: args.nodeInstanceId }, true)
        const workspacePath = context.workspacePath
        const sourceName = args.sourcePath === undefined
          ? undefined
          : (isAbsolute(args.sourcePath) ? args.sourcePath : resolve(workspacePath, args.sourcePath))
        const bytes = args.content !== undefined
          ? Buffer.from(args.content, 'utf8')
          : await readFile(sourceName as string).catch((error: unknown) => {
            throw new Error(`读取 sourcePath 失败：${args.sourcePath}（${error instanceof Error ? error.message : String(error)}）`)
          })
        const prepared = prepareOutputWrite(
          {
            context,
            workspacePath,
            itemKey: args.itemKey,
            maxOutputBytes: settings.maxOutputBytes,
            ...(sourceName === undefined ? {} : { sourceName }),
          },
          bytes,
        )
        const written = await writeOutputFile(prepared, bytes)
        const itemName = context.outputs.find(output => output.itemKey === args.itemKey)?.name ?? args.itemKey
        const remaining = context.outputs.filter(output => output.required && !output.submitted && output.itemKey !== args.itemKey)
        return {
          ok: true,
          message: `已写入「${itemName}」→ ${written.relativePath}（${formatBytes(written.bytes)}，sha256 ${written.sha256}）。\n`
            + '下一步立即调用 node_output_commit，参数：'
            + `itemKey="${args.itemKey}"、relativePath="${written.relativePath}"、sha256="${written.sha256}"。\n`
            + (remaining.length === 0
              ? '本节点没有其它未提交的必填输出项。'
              : `本节点还有 ${remaining.length} 个必填输出项未提交：${remaining.map(output => `${output.itemKey}（${output.name}）`).join('、')}。`),
          data: { relativePath: written.relativePath, sha256: written.sha256, bytes: written.bytes },
        }
      } catch (error) {
        return fail(error)
      }
    },
    presentCall: args => ({ card: 'generic', title: `写入输出 ${args.itemKey}`, kind: 'edit', rawInput: args.itemKey }),
  }))

  // ------------------------------------------------------------------ 4. node_output_commit
  ctx.tools.register(defineTool({
    name: 'node_output_commit',
    description: 'Register one written output item with the workbench so the user can see and download it. '
      + 'When the node\'s required outputs (and required inputs) are complete, the node advances to the next '
      + 'one. Pass exactly the relativePath and sha256 returned by node_output_write. If the result says '
      + 'required inputs are still missing, do not retry: ask the user to upload them on the node information page.',
    parameters: {
      itemKey: { type: 'string', required: true, description: 'Output item key.' },
      relativePath: { type: 'string', required: true, description: 'The relativePath returned by node_output_write for this item.' },
      sha256: { type: 'string', required: true, description: 'The sha256 returned by node_output_write for this item.' },
      nodeInstanceId: { type: 'integer', description: 'Node instance id from node_context_get. Omit to use the current node.' },
    },
    output: { schema: ENVELOPE_SCHEMA, render: renderEnvelope },
    timeoutMs: 60_000,
    async execute(args, exec) {
      try {
        const context = await locate(exec, { nodeInstanceId: args.nodeInstanceId }, true)
        const item = context.outputs.find(output => output.itemKey === args.itemKey)
        if (item === undefined) {
          const known = context.outputs.map(output => output.itemKey).join('、')
          throw new Error(`输出项 ${args.itemKey} 不属于本节点。可用输出项：${known.length > 0 ? known : '（本节点没有输出项）'}`)
        }
        const workspacePath = context.workspacePath
        const relativePath = normalizeWorkspaceRelative(workspacePath, args.relativePath)
        const absolutePath = resolve(workspacePath, relativePath)
        // 本地先核一次（后端还会再核）：路径在本节点输出目录内、文件存在、摘要一致
        if (!isInside(nodeOutputDir(workspacePath, context.nodeInstanceId), absolutePath)) {
          throw new Error(
            `relativePath 必须位于 ${context.outputDir} 之内（收到 ${args.relativePath}）。`
            + '请使用 node_output_write 返回的 relativePath，不要改写。',
          )
        }
        const disk = await readFile(absolutePath).catch(() => undefined)
        if (disk === undefined) {
          throw new Error(`文件不存在：${relativePath}。请先用 node_output_write 写入，再提交它返回的 relativePath 与 sha256。`)
        }
        const actual = sha256(disk)
        if (actual !== args.sha256.trim().toLowerCase()) {
          throw new Error(
            `sha256 与磁盘内容不一致（收到 ${args.sha256}，实际 ${actual}）。`
            + '请重新调用 node_output_write 写入，再用它新返回的 sha256 提交。',
          )
        }
        const commit = await client.commitOutput(
          context.nodeInstanceId,
          args.itemKey,
          { relativePath, sha256: actual, operator: 'agent' },
          exec.signal,
        )
        // 后端推进后，本需求的"当前节点"已经变了：让节点目录失效，下一次按需重取
        if (commit.advanced) directory.invalidate(context.requirementId)
        return {
          ok: true,
          message: formatCommitResult(commit, args.itemKey),
          data: {
            committed: commit.committed,
            versionNo: commit.versionNo,
            advanced: commit.advanced,
            nextNodeName: commit.nextNodeName ?? null,
            requirementCompleted: commit.requirementCompleted,
            remainingRequiredOutputs: commit.remainingRequiredOutputs,
            remainingRequiredInputs: commit.remainingRequiredInputs,
          },
        }
      } catch (error) {
        return fail(error)
      }
    },
    presentCall: args => ({ card: 'generic', title: `提交输出 ${args.itemKey}`, kind: 'edit', rawInput: args.itemKey }),
  }))
}

/** 工具名（注册与验证脚本共用）。 */
export const TOOL_NAMES = ['node_context_get', 'node_reference_read', 'node_output_write', 'node_output_commit'] as const
