/**
 * 插件 `Config`（Host 半）——全部可配，不硬编码；`internalToken` 不设明文默认值。
 *
 * 字段来历（对应后端 `workbench-app/src/main/resources/application.yml` 的 `fde.workbench.*`）：
 *   - `javaBaseUrl`      Java 后端根地址；
 *   - `internalToken`    `/internal/**` 的 `X-Internal-Token`（后端是 `fde.workbench.internal-token`，
 *                        环境变量 `FDE_INTERNAL_TOKEN`）；默认空串 = 明确拒绝，绝不偷偷免鉴权；
 *   - `storageRoot`      存储根（后端 `fde.workbench.storage-root`）。DSH 会话 cwd 形如
 *                        `<storageRoot>/ws/<需求ID>`，工具据此判断"这个会话属于哪个需求"；
 *   - `maxOutputBytes`   单个输出文件上限（后端 `node-output-max-bytes`，默认 10 MiB）；
 *   - `maxReferenceBytes` 引用读取单次返回的正文上限（token 保护，超出显式标注"已截断"）。
 */

import { resolve } from 'node:path'

/** 插件配置（可全部省略，省略时用下面 `resolveSettings` 的默认/推导值）。 */
export interface Config {
  /** Java 后端根地址，如 `http://127.0.0.1:8081`。 */
  javaBaseUrl?: string
  /** 内部接口令牌；不配就是不配，没有明文默认值。 */
  internalToken?: string
  /** 需求 workspace 的父目录：`<storageRoot>/ws/<requirementId>`。省略时按 cwd 推导。 */
  storageRoot?: string
  /** 单文件输出上限（字节），默认 10485760（10 MiB）。 */
  maxOutputBytes?: number
  /** 引用读取正文上限（字节），默认 65536（64 KiB）。 */
  maxReferenceBytes?: number
}

/** 默认后端地址（本机开发端口，见 docs/architecture.md）。 */
export const DEFAULT_JAVA_BASE_URL = 'http://127.0.0.1:8081'

/** 默认单文件输出上限：10 MiB，与后端 `node-output-max-bytes` 一致。 */
export const DEFAULT_MAX_OUTPUT_BYTES = 10 * 1024 * 1024

/** 默认引用读取上限：64 KiB（约 2 万汉字，按 token 预算取的安全值）。 */
export const DEFAULT_MAX_REFERENCE_BYTES = 64 * 1024

/** 每个配置项的说明（文档、报错、cordis.patch.yml 注释共用一份文案）。 */
export const CONFIG_FIELDS = {
  javaBaseUrl: `Java 后端根地址，默认 ${DEFAULT_JAVA_BASE_URL}`,
  internalToken: '内部接口令牌，必须与后端 FDE_INTERNAL_TOKEN 一致；不配置时 /internal/** 调用会被后端拒绝',
  storageRoot: '存储根：会话 cwd 形如 <storageRoot>/ws/<需求ID>；留空则按会话 cwd 推导',
  maxOutputBytes: `单文件输出上限（字节），默认 ${DEFAULT_MAX_OUTPUT_BYTES}（10 MiB）`,
  maxReferenceBytes: `引用读取单次返回的正文上限（字节），默认 ${DEFAULT_MAX_REFERENCE_BYTES}（64 KiB）`,
} as const

/**
 * 校验用户配置的类型（TypeScript 之外的一层保险）。
 *
 * 不用 schemastery：那会给 Host 产物再加一个运行时依赖，而这里只有 5 个标量字段，
 * 一个显式校验函数更直白，报错也更可控（中文、指出字段名）。
 * @param config - 原始配置
 * @throws TypeError 字段类型不对
 */
export function validateConfig(config: Config): void {
  for (const field of ['javaBaseUrl', 'internalToken', 'storageRoot'] as const) {
    if (config[field] !== undefined && typeof config[field] !== 'string') {
      throw new TypeError(`workbench: Config.${field} 必须是字符串`)
    }
  }
  for (const field of ['maxOutputBytes', 'maxReferenceBytes'] as const) {
    if (config[field] !== undefined && typeof config[field] !== 'number') {
      throw new TypeError(`workbench: Config.${field} 必须是数字`)
    }
  }
}

/** 解析后的配置（全部字段必有值）。 */
export interface Settings {
  javaBaseUrl: string
  internalToken: string
  /** 存储根绝对路径（省略时用 `fallbackStorageRoot`）。 */
  storageRoot: string
  maxOutputBytes: number
  maxReferenceBytes: number
}

/**
 * 把用户配置收敛成运行时值。
 * @param config - 插件配置（可能全空）
 * @param fallbackStorageRoot - 未配 `storageRoot` 时的推导值（见 index.ts：按会话 cwd 反推）
 * @returns 解析后的配置
 * @throws TypeError 数值非法
 */
export function resolveSettings(config: Config, fallbackStorageRoot: string): Settings {
  const javaBaseUrl = (config.javaBaseUrl ?? DEFAULT_JAVA_BASE_URL).trim().replace(/\/+$/, '')
  const internalToken = (config.internalToken ?? '').trim()
  const storageRoot = (config.storageRoot ?? '').trim()
  const maxOutputBytes = config.maxOutputBytes ?? DEFAULT_MAX_OUTPUT_BYTES
  const maxReferenceBytes = config.maxReferenceBytes ?? DEFAULT_MAX_REFERENCE_BYTES
  assertPositive('maxOutputBytes', maxOutputBytes)
  assertPositive('maxReferenceBytes', maxReferenceBytes)
  return {
    javaBaseUrl,
    internalToken,
    storageRoot: resolve(storageRoot.length > 0 ? storageRoot : fallbackStorageRoot),
    maxOutputBytes,
    maxReferenceBytes,
  }
}

/** 断言是正整数。 */
function assertPositive(field: string, value: number): void {
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new TypeError(`workbench: ${field} 必须是正整数，收到 ${String(value)}`)
  }
}
