/**
 * 在真实 DSH 运行时里验证插件注册（不是替身）。
 *
 * 为什么不是跑 `dsh web` 再点界面：DSH 的 RPC 走 WebSocket + Typert 协议，用 curl 探不了；
 * 而这里只需要三件"真"的东西——真的 `ToolsService`、真的 `SkillRegistry`、
 * 真的 `SystemPrompt`——就能确认「工具被接受注册」「技能能在目录里查到」
 * 「prompt 段按会话 scope 生效」。因此本脚本用 DSH 安装目录里已构建的 lib 起一个最小内核，
 * 再 `import` 我们自己的 `lib/index.js`（**构建产物**，就是插件行加载的那份）并 apply。
 *
 * 用法（本仓库内）：
 *   node scripts/verify-runtime.mjs
 */

import { Context } from '@deepseek-ai/cordis'
import ToolsService from '@deepseek-ai/dsh-tools'
import { createScope } from '@deepseek-ai/dsh-scope'
import { SkillRegistry } from '@deepseek-ai/dsh-skill'
import SystemPrompt from '@deepseek-ai/dsh-system-prompt'

const results = { passed: 0, failed: 0 }

/**
 * 记录一条断言。
 * @param {boolean} ok - 是否通过
 * @param {string} label - 说明
 * @param {unknown} [detail] - 额外打印的值
 */
function check(ok, label, detail) {
  if (ok) {
    results.passed += 1
    console.log(`✓ ${label}${detail === undefined ? '' : `：${JSON.stringify(detail)}`}`)
  } else {
    results.failed += 1
    console.log(`✗ ${label}${detail === undefined ? '' : `：${JSON.stringify(detail)}`}`)
  }
}

const ctx = new Context()
// 真实服务：工具注册表 / 技能注册表 / 系统提示
ctx.plugin(ToolsService)
ctx.plugin(SkillRegistry)
ctx.plugin(SystemPrompt)

// 插件依赖的另外两个服务在最小内核里不存在，用最小桩补上（它们不是本次验证对象）
// workingDirectory：返回固定 cwd，模拟"会话在需求 workspace 里"
const cwd = { value: process.env.FDE_VERIFY_CWD ?? '/nonexistent/plain-dir' }
ctx.provide('workingDirectory', { ensure: async () => cwd.value })
ctx.provide('agents', { list: () => [] })
// cordis 的插件初始化是异步的：不先等一个 tick，`ctx.tools` / `ctx.skills` 还没挂上
await new Promise(resolve => setTimeout(resolve, 50))

const plugin = await import(new URL('../lib/index.js', import.meta.url).href)
plugin.apply(ctx)
plugin.apply(ctx) // 第二次调用应当被幂等守卫挡掉

console.log('\n=== 1. 工具注册（真实 ToolsService）===')
const definitions = ctx.tools.definitions()
const names = definitions.map(definition => definition.name).sort()
console.log(`    ToolsService 里可见的工具：${names.join(', ')}`)
const ours = ['node_context_get', 'node_output_commit', 'node_output_write', 'node_reference_read']
check(ours.every(name => names.includes(name)), '四个工具都在 ToolsService 里', ours)
check(names.filter(name => ours.includes(name)).length === 4, '每个工具只注册一次（apply 幂等）')
for (const name of ours) {
  const definition = definitions.find(item => item.name === name)
  const parameters = definition?.parameters ?? {}
  const required = parameters.required ?? []
  const properties = Object.keys(parameters.properties ?? {})
  console.log(`    ${name}: 参数 ${properties.join(',')} | 必填 ${required.length === 0 ? '(无)' : required.join(',')}`)
  check(definitions.filter(item => item.name === name).length === 1, `${name} 唯一`)
}

console.log('\n=== 2. 模型可见的工具 schema（ToolsService.schemas）===')
const schemas = ctx.tools.schemas().filter(schema => ours.includes(schema.name))
check(schemas.length === 4, '四个工具都投影到模型 schema', schemas.map(schema => schema.name).sort())
for (const schema of schemas) {
  console.log(`    ${schema.name}: ${String(schema.description).slice(0, 70)}…`)
}

console.log('\n=== 3. 技能注册（真实 SkillRegistry）===')
const skills = await ctx.skills.list()
const skillNames = skills.map(skill => skill.name)
console.log(`    技能目录：${skillNames.join(', ')}`)
check(skillNames.includes('workbench-node-delivery'), '内置技能出现在目录里', skillNames)
const loaded = await ctx.skills.get('workbench-node-delivery')
check(loaded !== undefined, '能按名称加载技能正文')
check(loaded?.source === 'bundled', '技能来源 = bundled', loaded?.source)
check((loaded?.content ?? '').includes('node_output_commit'), '正文含提交流程', true)
console.log(`    description: ${loaded?.description}`)
console.log(`    正文字符数: ${loaded?.content.length}`)

console.log('\n=== 4. prompt 段（真实 SystemPrompt，按会话 scope）===')
// 插件注册 prompt 段用的是"会话的 agent 上下文"（agent.ctx）。
// 这里用 dsh-scope 的 createScope 建两个会话 scope，等价地验证同一个机制：
// 一段只挂在一个会话上，读另一个会话时看不到。
// scope key 是不透明身份对象：读的时候必须传同一个对象（不是它的 id 字符串）
const outsideKey = { id: 'session-outside' }
const workspaceKey = { id: 'session-workspace' }
const outside = createScope(ctx, outsideKey)
const workspaceScope = createScope(ctx, workspaceKey)
await new Promise(resolve => setTimeout(resolve, 30))
for (const [scope, name] of [[outside, 'probe:outside'], [workspaceScope, 'probe:workspace']]) {
  // ctx.inject(deps, cb) 是"带依赖的子插件"：服务访问必须在 cb 收到的上下文里做，
  // 注册就落在该 scope 的层里（这正是插件在 agent.ctx 上注册时走的同一条路径）。
  scope.ctx.inject(['systemPrompt'], (injectedCtx) => {
    injectedCtx.systemPrompt.section({ name, order: 1, text: name })
  })
}
await new Promise(resolve => setTimeout(resolve, 30))
// 不走 assemble()（它需要一个工具执行器，与本次验证无关），直接读按 scope 合并后的注册表
const sp = ctx.systemPrompt
const sectionNamesOf = (scopeKey) => {
  const layers = [sp.layers.global, ...sp.layers.chainLayers(scopeKey)]
  const names = new Set()
  for (const layer of layers) for (const [name] of layer.sections.entries()) names.add(name)
  return [...names]
}
const outsideSections = sectionNamesOf(outsideKey)
const workspaceSections = sectionNamesOf(workspaceKey)
console.log(`    会话 session-outside 可见的段：${outsideSections.join(', ') || '(空)'}`)
console.log(`    会话 session-workspace 可见的段：${workspaceSections.join(', ') || '(空)'}`)
check(outsideSections.includes('probe:outside'), '会话 scope 里的段只对自身生效')
check(!outsideSections.includes('probe:workspace'), '另一会话的段不会串进来')
// 插件只会在"cwd 属于需求 workspace"的会话里注册 workbench:node-context；
// 这里的验证内核用桩 workingDirectory 返回普通目录，所以两个会话都不该有它。
check(!outsideSections.includes('workbench:node-context') && !workspaceSections.includes('workbench:node-context'),
  '普通目录会话没有 workbench:node-context 段')
// 反向确认：真的注册进 scope 后能被读到（证明读取口径正确，而不是"怎么读都空"）
check(workspaceSections.includes('probe:workspace'), '另一会话自己的段存在（读取口径有效）')

console.log(`\n结果：通过 ${results.passed} 项，失败 ${results.failed} 项`)
void cwd
process.exit(results.failed === 0 ? 0 : 1)
