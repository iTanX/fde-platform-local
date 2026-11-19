/**
 * 内置指导技能 `workbench-node-delivery` 的注册。
 *
 * 正文放在 `skills/workbench-node-delivery/SKILL.md`（与官方技能同样的 frontmatter 格式：
 * `---\nname: ...\ndescription: ...\n---`），而不是写死在 TS 里：这样技能可以被
 * 直接阅读、对比、评审，也能被 `scripts/verify-host.ts` 用与 DSH `skill-filesystem`
 * 相同的方式解析。
 *
 * 注册用官方的 `ctx.skills.register(SkillRegistration)`：字段取
 * `{ name, description, whenToUse?, source, content }`（`invocation` / `provider` 省略时
 * 取官方默认：模型与用户都能调用、provider 记作 runtime）。
 */

import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import type { SkillRegistration } from '@deepseek-ai/dsh-skill'

/** 内置技能名（kebab-case，与目录名一致）。 */
export const SKILL_NAME = 'workbench-node-delivery'

/** 技能文件相对插件包根目录的位置（源文件与构建产物共用这一份）。 */
const SKILL_RELATIVE = 'skills/workbench-node-delivery/SKILL.md'

/**
 * `skills/workbench-node-delivery/SKILL.md` 的绝对路径。
 *
 * 同一个模块会被两种方式加载：运行期是从 `lib/index.js` 起（上一级就是插件包根），
 * 验证脚本则直接从 `src/host/skill.ts` 起（上两级才是包根）。这里按"谁存在用谁"解析，
 * 避免两套路径写死导致其中一种失效。
 */
export const SKILL_FILE = fileURLToPath(import.meta.url).includes('/lib/')
  ? fileURLToPath(new URL(`../${SKILL_RELATIVE}`, import.meta.url))
  : fileURLToPath(new URL(`../../${SKILL_RELATIVE}`, import.meta.url))

/** 解析后的技能定义（`register` 的入参）。 */
export type ParsedSkill = SkillRegistration

/**
 * 解析官方技能的 frontmatter 格式：首行 `---`、中间 `key: value`、再一行 `---`、正文随后。
 * 只支持本包用到的两个字段（name / description），与官方 `skill-filesystem` 的宽松程度相比更严格，
 * 目的是让格式错误在这里就暴露，而不是注册出一个空描述的技能。
 * @param markdown - 技能文件全文
 * @returns `{ name, description, body }`
 * @throws Error frontmatter 缺失或字段不合法
 */
export function parseSkillMarkdown(markdown: string): { name: string, description: string, body: string } {
  // 兼容 CRLF：先统一成 \n，避免 Windows 编辑过的文件解析失败
  const text = markdown.replace(/\r\n/g, '\n')
  if (!text.startsWith('---\n')) throw new Error('SKILL.md 必须以 `---` frontmatter 开头')
  const end = text.indexOf('\n---', 3)
  if (end < 0) throw new Error('SKILL.md 的 frontmatter 没有结束行 `---`')
  const front = text.slice(4, end)
  const body = text.slice(end + 4).replace(/^\n+/, '')
  const fields = new Map<string, string>()
  for (const line of front.split('\n')) {
    if (line.trim().length === 0) continue
    const index = line.indexOf(':')
    if (index <= 0) throw new Error(`SKILL.md frontmatter 无法解析的行：${line}`)
    fields.set(line.slice(0, index).trim(), line.slice(index + 1).trim())
  }
  const name = fields.get('name')
  const description = fields.get('description')
  if (name === undefined || name.length === 0) throw new Error('SKILL.md frontmatter 缺少 name')
  if (description === undefined || description.length === 0) throw new Error('SKILL.md frontmatter 缺少 description')
  if (body.trim().length === 0) throw new Error('SKILL.md 正文为空')
  return { name, description, body }
}

/**
 * 读取并解析内置技能文件，组装成 `ctx.skills.register` 的入参。
 * @param file - 技能文件路径（默认 `skills/workbench-node-delivery/SKILL.md`）
 * @returns 可注册的技能定义
 */
export function loadBundledSkill(file: string = SKILL_FILE): ParsedSkill {
  const parsed = parseSkillMarkdown(readFileSync(file, 'utf8'))
  if (parsed.name !== SKILL_NAME) {
    throw new Error(`内置技能名不一致：文件里是 ${parsed.name}，代码里期望 ${SKILL_NAME}`)
  }
  return {
    name: parsed.name,
    description: parsed.description,
    // whenToUse 与 description 同源：DSH 的技能目录对两者都会展示，正文里的触发条件单独写会漂移
    whenToUse: '会话工作目录形如 <存储根>/ws/<需求ID>，或用户要求产出节点交付物、写节点输出、把某个节点做完。',
    source: 'bundled',
    content: parsed.body,
  }
}
