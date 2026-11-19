import { resolve } from 'node:path'
import type { SkillCandidate, SkillProvider } from '@deepseek-ai/dsh-skill'
import type { BackendClient, NodeContext } from './backend.ts'

const PROVIDER = 'workbench-node-skills'

/** 节点技能的配置序号与调用名，保留无版本项的位置以便提示。 */
export function nodeSkillRefs(context: NodeContext) {
  if (context.requirementStatus !== 'in_progress' || context.nodeStatus !== 'current') return []
  return context.skills.map((skill, index) => ({
    name: skill.name,
    versionNo: skill.versionNo,
    position: index + 1,
    alias: typeof skill.versionNo === 'number' && Number.isSafeInteger(skill.versionNo) && skill.versionNo > 0
      ? `workbench-node-${context.nodeInstanceId}-skill-${index + 1}-v${skill.versionNo}`
      : undefined,
  }))
}

/** 目录只提供快照元数据，正文由 DSH 的 skill 工具首次调用时读取。 */
export function nodeSkillProvider(client: BackendClient, context: NodeContext): SkillProvider {
  const candidates = new Map<string, { position: number; versionNo: number; candidate: SkillCandidate }>()
  const workspace = resolve(context.workspacePath)
  for (const skill of nodeSkillRefs(context)) {
    if (skill.alias === undefined || typeof skill.versionNo !== 'number') continue
    candidates.set(skill.alias, {
      position: skill.position,
      versionNo: skill.versionNo,
      candidate: {
        name: skill.alias,
        description: `当前节点第 ${skill.position} 项技能「${skill.name}」v${skill.versionNo}`,
        invocation: { modelInvocable: true, userInvocable: false },
        source: 'custom',
        provider: PROVIDER,
        rank: 250,
        locator: skill.alias,
      },
    })
  }

  return {
    name: PROVIDER,
    async list({ cwd }) {
      return cwd !== undefined && resolve(cwd) === workspace
        ? [...candidates.values()].map(({ candidate }) => candidate)
        : []
    },
    async get(candidate, { cwd, signal }) {
      if (cwd === undefined || resolve(cwd) !== workspace) return undefined
      const item = candidates.get(candidate.name)
      if (item === undefined) return undefined
      const { content } = await client.nodeSkillEntry(
        context.requirementId, context.nodeInstanceId, item.position, item.versionNo, signal,
      )
      // 技能上传支持普通 Markdown，也支持带 YAML frontmatter 的技能。
      const body = content.replace(/^\uFEFF?---[ \t]*\r?\n[\s\S]*?^---[ \t]*\r?$(?:\n|$)/m, (match, offset) =>
        offset === 0 ? '' : match).trim()
      if (!body) throw new Error(`节点技能 ${candidate.name} 的 SKILL.md 正文为空。`)
      return { ...item.candidate, content: body }
    },
  }
}
