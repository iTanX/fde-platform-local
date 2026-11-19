/**
 * 工具的服务面纯函数：结果排版、截断、写盘校验、摘要。
 *
 * 这些函数不碰 Cordis Context、也不依赖模型，`scripts/verify-host.ts` 直接对它们断言。
 * 「服务面」= 最终交给模型看的那段文本；工具再把它包成 v2 的 `{ok, message, data}` 信封
 * （v2 执行管线要求工具的 canonical value 必须是对象，见 `tools.ts` 头部注释）。
 */

import { createHash } from 'node:crypto'
import { readFile, stat, writeFile, mkdir, realpath } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import type { NodeContext, OutputCommit, OutputItemRef } from './backend.ts'
import {
  extensionAllowed,
  formatBytes,
  isInside,
  nodeOutputDir,
  toWorkspaceRelative,
} from './paths.ts'

/** 单个输出项落盘前的校验结果（传给 `node_output_write` 渲染）。 */
export interface WriteOutcome {
  /** 相对 workspace 的提交路径。 */
  relativePath: string
  /** 内容摘要（十六进制，小写）。 */
  sha256: string
  /** 字节数。 */
  bytes: number
}

/**
 * 计算字节的 sha256（与后端 `FileDigestService` 的十六进制小写格式一致）。
 * @param bytes - 文件字节
 * @returns 64 位十六进制摘要
 */
export function sha256(bytes: Uint8Array): string {
  return createHash('sha256').update(bytes).digest('hex')
}

/**
 * 判断文本块是否像二进制（含 NUL 或替换字符占比过高）。
 * 引用读取时用它决定「给正文」还是「给元信息 + 提示用原生 read 工具」。
 * @param head - 文件开头若干字节
 * @returns 是否为二进制
 */
export function looksBinary(head: Uint8Array): boolean {
  for (const byte of head) {
    if (byte === 0) return true
  }
  return false
}

/**
 * 按 UTF-8 字节上限截断文本，并把「截了多少」算清楚（按字节而非字符，避免多字节字符被切坏）。
 * @param text - 原文
 * @param maxBytes - 正文上限（字节）
 * @returns `{ body, bytes, truncatedBytes }`；`truncatedBytes` 为 0 表示没截断
 */
export function truncateText(text: string, maxBytes: number): { body: string, bytes: number, truncatedBytes: number } {
  const all = Buffer.from(text, 'utf8')
  if (all.length <= maxBytes) return { body: text, bytes: all.length, truncatedBytes: 0 }
  // 多让出 4 字节，保证切点落在完整字符边界上（UTF-8 单字符最长 4 字节）
  let end = maxBytes
  while (end > 0 && ((all[end] ?? 0) & 0b1100_0000) === 0b1000_0000) end -= 1
  const body = all.subarray(0, end).toString('utf8')
  return { body, bytes: end, truncatedBytes: all.length - end }
}

/**
 * 把节点上下文排版成模型可读的紧凑结构（相对路径、只列必要字段）。
 *
 * 刻意不做 JSON 全量倾倒：字段名对模型没有信息量，而「哪些必填、已提交没、该写哪、
 * 路径是什么」才是它下一步要用的东西。
 * @param context - 后端节点上下文
 * @returns 多行文本
 */
export function formatNodeContext(context: NodeContext): string {
  const lines: string[] = []
  lines.push(`需求《${context.requirementTitle}》（ID ${context.requirementId}，状态 ${context.requirementStatus}）`)
  lines.push(
    `节点 ${context.nodeSeq}/${context.nodeCount}「${context.nodeName}」（ID ${context.nodeInstanceId}，状态 ${context.nodeStatus}）`,
  )
  if (context.nodeDescription !== null && context.nodeDescription !== undefined && context.nodeDescription.trim().length > 0) {
    lines.push(`节点说明：${context.nodeDescription.trim()}`)
  }
  lines.push(
    `生效技能：${context.skills.length === 0 ? '无' : context.skills.map(s => (s.versionNo === undefined || s.versionNo === null ? s.name : `${s.name}(v${s.versionNo})`)).join('、')}`,
  )
  lines.push('')

  // ---- 输入 ----
  lines.push('【输入资料】')
  if (context.inputType === 'fixed') {
    if (context.inputItems.length === 0) lines.push('- （无固定输入项）')
    for (const item of context.inputItems) {
      const required = item.required ? '必填' : '选填'
      if (item.files.length === 0) {
        lines.push(`- ${item.name}（${required}，${item.fileKind}）：尚未上传`)
      } else {
        lines.push(`- ${item.name}（${required}，${item.fileKind}）：`)
        for (const file of item.files) {
          lines.push(`    ${file.relativePath}（${formatBytes(file.sizeBytes)}，fileId ${file.fileId}）`)
        }
      }
    }
  } else if (context.freeInputFiles.length === 0) {
    lines.push('- 本节点为自由上传，当前没有文件')
  } else {
    lines.push(`- 本节点为自由上传，共 ${context.freeInputFiles.length} 个文件：`)
    for (const file of context.freeInputFiles) {
      lines.push(`    ${file.relativePath}（${formatBytes(file.sizeBytes)}，fileId ${file.fileId}）`)
    }
  }
  lines.push('')

  // ---- 上一节点输出 ----
  if (context.previousNode !== null && context.previousNode !== undefined) {
    const previous = context.previousNode
    lines.push(`【上一节点】${previous.seq}. ${previous.name}`)
    if (previous.outputs.length === 0) lines.push('- （没有输出文件）')
    for (const output of previous.outputs) {
      lines.push(`- ${output.name}（${output.itemKey}）：${output.relativePath}（fileId ${output.fileId}）`)
    }
    lines.push('')
  }

  // ---- 输出 ----
  lines.push('【输出项】（用 node_output_write 写入，再 node_output_commit 登记）')
  if (context.outputs.length === 0) lines.push('- 本节点没有输出项')
  for (const output of context.outputs) {
    const state = output.submitted
      ? `已登记 v${output.versionNo ?? '?'}`
      : '未登记'
    lines.push(
      `- ${output.name}  itemKey=${output.itemKey}  类型=${output.fileKind}  ${output.required ? '必填' : '选填'}  ${state}`,
    )
    lines.push(`    建议写入路径：${output.suggestPath}`)
    if (output.submitted && output.relativePath !== null && output.relativePath !== undefined) {
      lines.push(`    当前文件：${output.relativePath}`)
    }
  }
  lines.push('')
  lines.push(`输出目录：${context.outputDir}`)
  lines.push(
    context.nodeStatus === 'current'
      ? '本节点正在办理：写完每个必填项后立即提交。'
      : `本节点状态为 ${context.nodeStatus}：请勿再写入或提交。`,
  )
  if (context.nextNodeName !== null && context.nextNodeName !== undefined) {
    lines.push(`下一个节点：${context.nextNodeName}`)
  }
  return lines.join('\n')
}

/**
 * 把 commit 结果排版成模型可读文本，并原样带上 remaining 信息与「别重试」提示。
 * @param commit - 后端提交结果
 * @param itemKey - 本次提交的输出项
 * @returns 多行文本
 */
export function formatCommitResult(commit: OutputCommit, itemKey: string): string {
  const lines: string[] = []
  lines.push(`输出项 ${itemKey} 已登记（版本 v${commit.versionNo}）。`)
  const remainingOutputs = commit.remainingRequiredOutputs.length
  if (remainingOutputs > 0) {
    lines.push(`本节点还剩 ${remainingOutputs} 个必填输出项未登记：${commit.remainingRequiredOutputs.join('、')}。请继续生成并提交。`)
  } else {
    lines.push('本节点所有必填输出项都已登记。')
  }
  if (commit.remainingRequiredInputs.length > 0) {
    const inputs = commit.remainingRequiredInputs.join('、')
    if (commit.advanced) {
      // 理论上前端推进条件包含必填输入，出现这种组合说明后端规则变了：如实转述，不自行解释
      lines.push(`注意：后端返回 advanced=true，但同时还有必填输入未上传（inputItemId：${inputs}）。`)
    } else {
      lines.push(
        '输出已登记，但用户还没上传必填资料，请提示用户去节点信息页上传，不要重试'
        + `（未上传的必填输入项 inputItemId：${inputs}）。`,
      )
    }
  }
  if (commit.advanced) {
    lines.push(`节点已推进，下一个节点：${commit.nextNodeName ?? '（未知）'}。`)
  }
  if (commit.requirementCompleted) {
    lines.push('需求已全部完成。请告诉用户可以在页面下载所有产出。')
  }
  return lines.join('\n')
}

/** 写盘校验的输入（已完成 content / sourcePath 二选一解析，只差校验与落盘）。 */
export interface WriteRequest {
  /** 节点上下文（提供输出项与 nodeInstanceId）。 */
  context: NodeContext
  /** 真实 workspace 绝对路径。 */
  workspacePath: string
  /** 输出项稳定键。 */
  itemKey: string
  /** 源文件名（给定时用它取扩展名；不给则按 fileKind 取默认值）。 */
  sourceName?: string
  /** 单文件大小上限（字节）。 */
  maxOutputBytes: number
}

/**
 * 校验一次写盘请求并算出最终字节与落盘位置（**不写盘**，便于单测边界）。
 *
 * 拒绝的情况（全部给中文原因）：输出项不存在、content 与 sourcePath 同时给/都没给、
 * 扩展名不符合输出项的 fileKind、超过大小上限、目标目录越界。
 * @param request - 写盘请求
 * @param bytes - 已读出的内容字节（`content` 或 `sourcePath` 二选一在调用方完成）
 * @returns 目标绝对路径、相对路径、字节与摘要
 */
export function prepareOutputWrite(
  request: WriteRequest,
  bytes: Uint8Array,
): { absolutePath: string, relativePath: string, bytes: number, sha256: string } {
  const { context, workspacePath, itemKey, maxOutputBytes } = request
  const item = context.outputs.find(output => output.itemKey === itemKey)
  if (item === undefined) {
    const known = context.outputs.map(output => output.itemKey).join('、')
    throw new Error(`输出项 ${itemKey} 不属于本节点。可用输出项：${known.length > 0 ? known : '（本节点没有输出项）'}`)
  }
  if (bytes.length === 0) {
    throw new Error(`输出项 ${itemKey} 的内容为空，未写入。请生成实际内容后再写。`)
  }
  if (bytes.length > maxOutputBytes) {
    throw new Error(
      `输出项 ${itemKey} 的内容 ${formatBytes(bytes.length)} 超过单文件上限 ${formatBytes(maxOutputBytes)}，未写入。`,
    )
  }
  const outputDir = nodeOutputDir(workspacePath, context.nodeInstanceId)
  // 写法固定为 `<输出目录>/<itemKey>.<ext>`：itemKey 由后端保证是 `[a-z0-9-]`，不会带路径分隔符
  const extension = extensionFor(item, request.sourceName)
  const absolutePath = resolve(outputDir, `${itemKey}${extension === '' ? '' : `.${extension}`}`)
  // 只允许写 `.workbench/<nid>/` 下——即使前面的拼接被篡改也不会越界
  if (!isInside(outputDir, absolutePath)) {
    throw new Error(`拒绝写入 ${absolutePath}：模型输出只允许写在 ${outputDir} 之内。`)
  }
  return {
    absolutePath,
    relativePath: toWorkspaceRelative(workspacePath, absolutePath),
    bytes: bytes.length,
    sha256: sha256(bytes),
  }
}

/**
 * 决定输出文件的扩展名：优先用 sourcePath 的扩展名，其次按 fileKind 取默认值。
 * @param item - 输出项（带 fileKind）
 * @param sourcePath - 源文件路径（可空）
 * @returns 扩展名（不含点；空串表示没有扩展名）
 * @throws Error 扩展名不符合 fileKind
 */
function extensionFor(item: OutputItemRef, sourcePath: string | undefined): string {
  // 复制文件时跟随源扩展名；直接给 content 时按 fileKind 取默认扩展名。
  // 这条路径下 item.fileKind 一定是支持的类型，所以默认值总能通过下面的白名单校验；
  // 反过来若有人把 fileKind 传成未知值，白名单会拦住而不是写出无扩展名文件。
  const fromSource = sourcePath === undefined ? '' : (sourcePath.split(/[\\/]/).pop()?.split('.').slice(1).pop() ?? '').toLowerCase()
  const extension = fromSource.length > 0 ? fromSource : defaultExtension(item.fileKind)
  const probe = `x.${extension}`
  if (!extensionAllowed(item.fileKind, probe)) {
    throw new Error(
      `输出项 ${item.itemKey} 要求 ${item.fileKind} 类型文件（允许的扩展名见提示），当前扩展名 .${extension || '（无）'} 不被接受。`
      + `建议改用 ${item.suggestPath}`,
    )
  }
  return extension
}

/**
 * 按 fileKind 取默认扩展名。
 * @param fileKind - text / document / any
 * @returns 默认扩展名
 */
export function defaultExtension(fileKind: string): string {
  switch (fileKind.trim().toLowerCase()) {
    case 'text':
      return 'md'
    case 'document':
      return 'docx'
    default:
      return 'md'
  }
}

/**
 * 真正落盘：建目录 → 写文件 → 回读校验字节数与摘要。
 *
 * 为什么回读：摘要必须对应磁盘上的真实内容（后端 commit 时会再算一遍并比对），
 * 写失败或被截断时宁可在这里报错，也不要带着一个假的 sha256 去提交。
 * @param prepared - `prepareOutputWrite` 的结果（含要写出的字节）
 * @param bytes - 内容字节
 * @returns 落盘结果（摘要以回读为准）
 */
export async function writeOutputFile(
  prepared: { absolutePath: string, relativePath: string, bytes: number, sha256: string },
  bytes: Uint8Array,
): Promise<WriteOutcome> {
  await mkdir(dirname(prepared.absolutePath), { recursive: true })
  await writeFile(prepared.absolutePath, bytes)
  const written = await readFile(prepared.absolutePath)
  const digest = sha256(written)
  if (written.length !== prepared.bytes || digest !== prepared.sha256) {
    throw new Error(
      `写入 ${prepared.relativePath} 后回读校验失败（期望 ${prepared.bytes} 字节/${prepared.sha256.slice(0, 12)}…，`
      + `实际 ${written.length} 字节/${digest.slice(0, 12)}…）。请重试写入。`,
    )
  }
  return { relativePath: prepared.relativePath, sha256: digest, bytes: written.length }
}

/**
 * 读取引用文件并排版成模型可读文本。
 * @param params - 绝对路径、显示用相对路径、正文上限、元信息
 * @returns 多行文本（超长时带「已截断，剩余 N 字节」）
 */
export async function readReference(
  params: {
    absolutePath: string
    relativePath: string
    maxBytes: number
    info: { fileKind?: string, itemKey?: string, fileId?: number, sizeBytes?: number }
  },
): Promise<{ text: string, truncatedBytes: number }> {
  const info = await stat(params.absolutePath).catch((error: unknown) => {
    throw new Error(`引用文件不存在或不可读：${params.relativePath}（${error instanceof Error ? error.message : String(error)}）`)
  })
  if (!info.isFile()) throw new Error(`引用路径不是文件：${params.relativePath}`)
  const buffer = await readFile(params.absolutePath)
  const head = buffer.subarray(0, 8192)
  const meta = [
    `文件：${params.relativePath}`,
    `大小：${formatBytes(buffer.length)}`,
    `sha256：${sha256(buffer)}`,
  ]
  if (params.info.itemKey !== undefined) meta.push(`输出项：${params.info.itemKey}`)
  if (params.info.fileId !== undefined) meta.push(`fileId：${params.info.fileId}`)
  if (looksBinary(head)) {
    return {
      text: `${meta.join('\n')}\n这是二进制文件，未直接返回正文。需要查看时请用原生 read 工具读取绝对路径：${params.absolutePath}`,
      truncatedBytes: 0,
    }
  }
  const { body, truncatedBytes } = truncateText(buffer.toString('utf8'), params.maxBytes)
  const tail = truncatedBytes > 0
    ? `\n\n[已截断，剩余 ${truncatedBytes} 字节未返回；需要完整内容请用原生 read 工具读取绝对路径 ${params.absolutePath}]`
    : ''
  return { text: `${meta.join('\n')}\n\n----- 正文 -----\n${body}${tail}`, truncatedBytes }
}

/**
 * 求真实路径（软链解开）；不存在时返回规范化路径本身。
 * @param path - 待解析路径
 * @returns 真实路径
 */
export async function realPathOf(path: string): Promise<string> {
  return await realpath(path).catch(() => resolve(path))
}
