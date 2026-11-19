/**
 * Host 半的路径约定与扩展名白名单（纯函数，可脱离模型直接单测）。
 *
 * 与后端保持一致的三个事实（来源：`workbench-service/.../NodeStoragePaths.java`）：
 *   1. 需求 workspace 一律是 `<storageRoot>/ws/<requirementId>`，它同时也是 DSH 会话 cwd；
 *   2. 模型唯一允许写的位置是 `<ws>/.workbench/<nodeInstanceId>/`；
 *   3. 提交与校验用的路径串一律是「相对 workspace 根、`/` 分隔」，例如
 *      `.workbench/101/requirement-items.md`。
 *
 * 把这些规则集中在纯函数里，既让工具实现变薄，也让 `scripts/verify-host.ts`
 * 能在没有模型、没有 DSH 进程的情况下逐条断言边界（越界路径、扩展名、大小）。
 */

import { isAbsolute, relative, resolve, sep } from 'node:path'

/** 模型输出目录名（相对 workspace 根），与后端 `NodeStoragePaths.MODEL_DIR` 一致。 */
export const MODEL_DIR = '.workbench'

/** 需求 workspace 的父目录名（`<storageRoot>/ws/<requirementId>` 里的 `ws`）。 */
export const WS_DIR = 'ws'

/** `fileKind = text` 允许的扩展名（后端 `TEXT_EXTENSIONS`）。 */
export const TEXT_EXTENSIONS = ['md', 'txt', 'markdown'] as const

/** `fileKind = document` 允许的扩展名（后端 `DOCUMENT_EXTENSIONS`）。 */
export const DOCUMENT_EXTENSIONS = ['docx', 'pdf', 'xlsx', 'pptx'] as const

/**
 * 把若干路径段拼成规范的绝对路径（不触盘）。
 * @param parts - 路径段
 * @returns 规范化后的绝对路径
 */
export function absolutePath(...parts: string[]): string {
  return resolve(...parts)
}

/**
 * 解析 DSH 会话 cwd：只有当它形如 `<storageRoot>/ws/<数字>` 时才算需求 workspace。
 * @param cwd - 会话当前目录（未提供返回 undefined）
 * @param storageRoot - 存储根的规范绝对路径（见 `resolveStorageRoot`）
 * @returns `{ requirementId, workspacePath }`；不属于需求 workspace 时返回 undefined
 */
export function parseWorkspaceCwd(
  cwd: string | undefined,
  storageRoot: string,
): { requirementId: number; workspacePath: string } | undefined {
  if (cwd === undefined || cwd.length === 0) return undefined
  const workspacePath = resolve(cwd)
  const parent = resolve(storageRoot, WS_DIR)
  // 必须在 `<storageRoot>/ws/` 的正下方，且最后一段是纯数字需求 ID；
  // 用 relative() 比较而不是前缀字符串，避免 `/…/ws2/1` 这类同前缀目录被误判。
  if (relative(parent, workspacePath) === '' || relative(parent, workspacePath).startsWith('..')) return undefined
  const tail = relative(parent, workspacePath)
  if (tail.includes(sep) || tail.includes('/')) return undefined
  if (!/^\d+$/.test(tail)) return undefined
  return { requirementId: Number(tail), workspacePath }
}

/**
 * 计算节点输出目录的绝对路径：`<ws>/.workbench/<nodeInstanceId>`。
 * @param workspacePath - 需求 workspace 绝对路径
 * @param nodeInstanceId - 节点实例 ID
 * @returns 绝对路径（调用方负责创建）
 */
export function nodeOutputDir(workspacePath: string, nodeInstanceId: number): string {
  return resolve(workspacePath, MODEL_DIR, String(nodeInstanceId))
}

/**
 * 判断 `child` 是否位于 `parent` 之内（含 parent 自身以外的严格子路径）。
 * 用于「只允许写 `.workbench/<nid>/` 下」的最终校验：软链与 `..` 都已经过 resolve 折平。
 * @param parent - 允许的根目录（绝对路径）
 * @param child - 待判断路径（绝对路径）
 * @returns 是否在根目录之内
 */
export function isInside(parent: string, child: string): boolean {
  const rel = relative(resolve(parent), resolve(child))
  return rel.length > 0 && !rel.startsWith('..') && !isAbsolute(rel)
}

/**
 * 取小写扩展名（不含点），与后端 `NodeStoragePaths.extensionOf` 一致。
 * @param fileNameOrPath - 文件名或路径
 * @returns 扩展名；没有返回空串
 */
export function extensionOf(fileNameOrPath: string): string {
  const base = fileNameOrPath.split(/[\\/]/).pop() ?? ''
  const dot = base.lastIndexOf('.')
  if (dot < 0 || dot === base.length - 1) return ''
  return base.slice(dot + 1).toLowerCase()
}

/**
 * 判断扩展名是否符合 `fileKind`（与后端 `extensionAllowed(kind, name, false)` 对齐：
 * 模型输出必须有扩展名）。
 * @param fileKind - `text` / `document` / `any`
 * @param fileName - 文件名或路径
 * @returns 是否允许
 */
export function extensionAllowed(fileKind: string | undefined | null, fileName: string): boolean {
  const extension = extensionOf(fileName)
  const kind = (fileKind ?? 'any').trim().toLowerCase()
  switch (kind) {
    case 'text':
      return (TEXT_EXTENSIONS as readonly string[]).includes(extension)
    case 'document':
      return (DOCUMENT_EXTENSIONS as readonly string[]).includes(extension)
    default:
      return extension.length > 0
  }
}

/**
 * 把绝对路径折成「相对 workspace 根、`/` 分隔」的提交路径。
 * @param workspacePath - 需求 workspace 绝对路径
 * @param absolute - 其下的文件绝对路径
 * @returns 如 `.workbench/101/requirement-items.md`
 */
export function toWorkspaceRelative(workspacePath: string, absolute: string): string {
  return relative(resolve(workspacePath), resolve(absolute)).split(sep).join('/')
}

/**
 * 把模型给的提交路径规范成「相对 workspace 根」的写法（不触盘）：
 * 接受相对路径，也接受位于本 workspace 内的绝对路径；其余一律拒绝。
 * @param workspacePath - 需求 workspace 绝对路径
 * @param relativePath - 模型给出的路径
 * @returns 规范化后的相对路径
 * @throws Error 路径为空、越出 workspace、或指向 workspace 自身
 */
export function normalizeWorkspaceRelative(workspacePath: string, relativePath: string): string {
  const value = relativePath.trim()
  if (value.length === 0) throw new Error('relativePath 不能为空')
  const workspace = resolve(workspacePath)
  const target = isAbsolute(value) ? resolve(value) : resolve(workspace, value)
  const rel = relative(workspace, target)
  if (rel.length === 0 || rel.startsWith('..') || isAbsolute(rel)) {
    throw new Error(`relativePath 必须在需求 workspace 之内：${relativePath}`)
  }
  return rel.split(sep).join('/')
}

/**
 * 把字节数写成人类可读文本（错误提示里用）。
 * @param bytes - 字节数
 * @returns 如 `10.0 MiB`
 */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MiB`
}
