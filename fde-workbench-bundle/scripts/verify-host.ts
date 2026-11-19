/**
 * Host 半的离线验证脚本（不依赖模型、不依赖 DSH 进程）。
 *
 * 用法（在插件包根目录）：
 *   node --experimental-strip-types scripts/verify-host.ts                  # 纯函数 + 替身 HTTP 后端
 *   FDE_BASE_URL=http://127.0.0.1:8081 FDE_INTERNAL_TOKEN=... \
 *     node --experimental-strip-types scripts/verify-host.ts --live /path/to/ws # 另打真实后端
 *
 * 它验证三件事：
 *   A. 纯函数边界：cwd 解析、扩展名白名单、路径越界、截断、sha256、prompt 段渲染、技能解析；
 *   B. 工具逻辑：用**最小替身 ctx**（只实现工具会用到的 `workingDirectory.ensure`、`logger`，
 *      以及 `tools.register` 收集器）加载真实的 `apply`，再用替身后端（Node http）跑
 *      四个工具的 execute，断言结果文本与副作用（真实落盘 + 真实 sha256）；
 *   C. 可选 `--live`：对真实的 Java 后端跑 `GET /internal/nodes/{nid}/context`，
 *      用真实 workspace 跑一次写盘 + 读引用（**不提交**，不污染数据库）。
 *
 * 所有断言失败都会打印并让进程以非 0 退出。
 */

import { strict as assert } from 'node:assert'
import { createHash } from 'node:crypto'
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs'
import { createServer } from 'node:http'
import type { AddressInfo } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import {
  extensionAllowed,
  formatBytes,
  isInside,
  nodeOutputDir,
  normalizeWorkspaceRelative,
  parseWorkspaceCwd,
  toWorkspaceRelative,
} from '../src/host/paths.ts'
import {
  formatCommitResult,
  formatNodeContext,
  prepareOutputWrite,
  readReference,
  sha256,
  truncateText,
  writeOutputFile,
} from '../src/host/logic.ts'
import { loadBundledSkill, parseSkillMarkdown, SKILL_NAME } from '../src/host/skill.ts'
import { renderNodePromptLine, requirementIdOfCwd } from '../src/host/prompt.ts'
import { modelDirNames, resolveWorkspace } from '../src/host/workbench.ts'
import { defaultStorageRoot } from '../src/host/index.ts'

let passed = 0
let failed = 0

/**
 * 执行一个断言分组。
 * @param title - 分组标题
 * @param body - 断言体
 */
async function section(title: string, body: () => void | Promise<void>): Promise<void> {
  console.log(`\n=== ${title} ===`)
  try {
    await body()
  } catch (error) {
    failed += 1
    console.log(`✗ ${title}：${error instanceof Error ? (error.stack ?? error.message) : String(error)}`)
  }
}

/**
 * 断言并打印一行真实输出。
 * @param label - 断言说明
 * @param value - 实际值
 * @param expected - 期望值（浅比较，用 assert）
 */
function check<T>(label: string, value: T, expected?: T): void {
  const shown = JSON.stringify(value)
  if (expected !== undefined) {
    try {
      assert.deepEqual(value, expected)
    } catch (error) {
      failed += 1
      console.log(`✗ ${label}：期望 ${JSON.stringify(expected)}，实际 ${shown}`)
      return
    }
  }
  passed += 1
  console.log(`✓ ${label}${expected === undefined ? `：${shown}` : ''}`)
}

// ---------------------------------------------------------------------------
// A. 纯函数边界
// ---------------------------------------------------------------------------
const storageRoot = join(mkdtempSync(join(tmpdir(), 'fde-verify-')), 'storage')

await section('A1 cwd → 需求 workspace', () => {
  check('需求 workspace', parseWorkspaceCwd(`${storageRoot}/ws/42`, storageRoot), {
    requirementId: 42,
    workspacePath: `${storageRoot}/ws/42`,
  })
  check('非 ws 目录', requirementIdOfCwd(`${storageRoot}/other/42`, storageRoot))
  check('ws 下一层子目录不算（模型 cwd 必须是 workspace 根）', requirementIdOfCwd(`${storageRoot}/ws/42/sub`, storageRoot))
  check('需求 ID 非数字', requirementIdOfCwd(`${storageRoot}/ws/abc`, storageRoot))
  check('同前缀目录不被误判', requirementIdOfCwd(`/tmp/fde-verify/storage2/ws/42`, storageRoot))
  check('尾部斜杠仍识别', requirementIdOfCwd(`${storageRoot}/ws/42/`, storageRoot), 42)
})

await section('A2 storageRoot 推导（未配置时）', () => {
  check('从 /x/data/ws/7 推出 /x/data', defaultStorageRoot('/x/data/ws/7'), '/x/data')
  check('非 ws 形状退回 cwd 本身', defaultStorageRoot('/x/data'), '/x/data')
})

await section('A3 扩展名白名单（与后端 NodeStoragePaths 对齐）', () => {
  check('text + .md', extensionAllowed('text', 'a.md'), true)
  check('text + .markdown', extensionAllowed('text', 'a.markdown'), true)
  check('text + .docx', extensionAllowed('text', 'a.docx'), false)
  check('document + .docx', extensionAllowed('document', 'a.docx'), true)
  check('document + .md', extensionAllowed('document', 'a.md'), false)
  check('any + .zip', extensionAllowed('any', 'a.zip'), true)
  check('any + 无扩展名', extensionAllowed('any', 'noext'), false)
})

await section('A4 路径越界与相对化', () => {
  const ws = `${storageRoot}/ws/42`
  const out = nodeOutputDir(ws, 101)
  check('输出目录', out, `${ws}/.workbench/101`)
  check('同目录不算在内', isInside(out, out), false)
  check('输出目录内的文件', isInside(out, `${out}/requirement-items.md`), true)
  check('.. 越界被拒', isInside(out, `${out}/../../etc/passwd`), false)
  check('相对化', toWorkspaceRelative(ws, `${out}/requirement-items.md`), '.workbench/101/requirement-items.md')
  check('normalize 接受相对路径', normalizeWorkspaceRelative(ws, './.workbench/101/a.md'), '.workbench/101/a.md')
  check('normalize 接受 workspace 内绝对路径', normalizeWorkspaceRelative(ws, `${out}/a.md`), '.workbench/101/a.md')
  let rejected = ''
  try {
    normalizeWorkspaceRelative(ws, '/etc/passwd')
  } catch (error) {
    rejected = (error as Error).message
  }
  check('normalize 拒绝越界绝对路径', rejected.startsWith('relativePath 必须在需求 workspace 之内'), true)
})

await section('A5 截断与摘要', () => {
  check('sha256 与 node crypto 一致', sha256(Buffer.from('hello')), createHash('sha256').update('hello').digest('hex'))
  const text = '中文内容🙂'.repeat(1000)
  const cut = truncateText(text, 1000)
  check('截断后不超过上限', Buffer.byteLength(cut.body, 'utf8') <= 1000, true)
  check('截断字节数 = 原文 - 已给', cut.truncatedBytes, Buffer.byteLength(text, 'utf8') - Buffer.byteLength(cut.body, 'utf8'))
  check('未超限时不截断', truncateText('短', 1000).truncatedBytes, 0)
  check('字节格式化', formatBytes(10 * 1024 * 1024), '10.0 MiB')
})

await section('A6 写盘校验（prepareOutputWrite）', () => {
  const context = fixtureContext()
  const ws = context.workspacePath
  const ok = prepareOutputWrite(
    { context, workspacePath: ws, itemKey: 'requirement-items', maxOutputBytes: 1024 * 1024 },
    Buffer.from('# 标题\n'),
  )
  check('目标路径按 itemKey + fileKind 决定', ok.relativePath, '.workbench/101/requirement-items.md')
  check('sha256 与内容一致', ok.sha256, sha256(Buffer.from('# 标题\n')))

  const cases: { label: string, expect: string, run: () => unknown }[] = [
    { label: '未知 itemKey', expect: '不属于本节点', run: () => prepareOutputWrite({ context, workspacePath: ws, itemKey: 'nope', maxOutputBytes: 1e6 }, Buffer.from('x')) },
    { label: '空内容', expect: '内容为空', run: () => prepareOutputWrite({ context, workspacePath: ws, itemKey: 'requirement-items', maxOutputBytes: 1e6 }, Buffer.alloc(0)) },
    { label: '超大小', expect: '超过单文件上限', run: () => prepareOutputWrite({ context, workspacePath: ws, itemKey: 'requirement-items', maxOutputBytes: 4 }, Buffer.from('12345')) },
    // document 项复制一个 .md 源文件：扩展名跟随源文件，因此会被 fileKind 白名单拒绝
    { label: 'document 项复制 .md', expect: '不被接受', run: () => prepareOutputWrite({ context, workspacePath: ws, itemKey: 'design-doc', sourceName: '/tmp/notes.md', maxOutputBytes: 1e6 }, Buffer.from('x')) },
  ]
  for (const item of cases) {
    let message = ''
    try {
      item.run()
    } catch (error) {
      message = (error as Error).message
    }
    check(`拒绝：${item.label}`, message.includes(item.expect), true)
    console.log(`    → ${message}`)
  }
  const doc = prepareOutputWrite(
    { context, workspacePath: ws, itemKey: 'design-doc', maxOutputBytes: 1e6 },
    Buffer.from('docx-bytes'),
  )
  check('document 项默认 .docx', doc.relativePath, '.workbench/101/design-doc.docx')
})

await section('A7 上下文 / 提交结果排版', () => {
  const context = fixtureContext()
  const text = formatNodeContext(context)
  console.log(text.split('\n').map(line => `    │ ${line}`).join('\n'))
  check('含需求名', text.includes('《客户工单分诊需求》'), true)
  check('含节点序号', text.includes('节点 2/3「需求拆解」'), true)
  check('含 itemKey', text.includes('itemKey=requirement-items'), true)
  check('含建议路径', text.includes('.workbench/101/requirement-items.md'), true)
  check('输入未上传时明说', text.includes('尚未上传'), true)

  const blocked = formatCommitResult(
    { committed: true, versionNo: 1, advanced: false, nextNodeName: '验收', requirementCompleted: false, remainingRequiredOutputs: ['acceptance-criteria'], remainingRequiredInputs: [5] },
    'requirement-items',
  )
  console.log(blocked.split('\n').map(line => `    │ ${line}`).join('\n'))
  check('缺必填输入时明确"不要重试"', blocked.includes('输出已登记，但用户还没上传必填资料，请提示用户去节点信息页上传，不要重试'), true)
  check('remainingRequiredInputs 原样带出', blocked.includes('5'), true)
  check('remainingRequiredOutputs 原样带出', blocked.includes('acceptance-criteria'), true)
})

await section('A8 prompt 段单行渲染', () => {
  const line = renderNodePromptLine({
    requirementTitle: '客户工单分诊需求',
    nodeSeq: 2,
    nodeCount: 3,
    nodeName: '需求拆解',
    skillNames: ['需求拆解', '验收标准(v2)'],
    outputDir: '.workbench/101/',
  })
  console.log(`    │ ${line}`)
  check('是单行', line.includes('\n'), false)
  check('含需求名/节点/技能/目录/首个动作', ['客户工单分诊需求', '2/3', '需求拆解', '验收标准(v2)', '.workbench/101/', 'node_context_get'].every(part => line.includes(part)), true)
})

await section('A9 内置技能', () => {
  // 技能文件在插件包根目录的 skills/ 下（源文件与构建产物都在用这一份）
  const raw = readFileSync(new URL('../skills/workbench-node-delivery/SKILL.md', import.meta.url), 'utf8')
  const parsed = parseSkillMarkdown(raw)
  check('frontmatter name', parsed.name, SKILL_NAME)
  check('frontmatter description 非空', parsed.description.length > 0, true)
  const skill = loadBundledSkill()
  check('注册字段齐备', { name: skill.name, source: skill.source, hasWhenToUse: typeof skill.whenToUse === 'string' }, { name: SKILL_NAME, source: 'bundled', hasWhenToUse: true })
  check('正文覆盖六条要点', ['node_context_get', 'node_output_write', 'node_output_commit', 'node_reference_read', '追问', '下载'].every(word => skill.content.includes(word)), true)
  console.log(`    │ description: ${skill.description}`)
})

await section('A10 节点目录候选', () => {
  check('数字目录按新→旧', modelDirNames(['101', 'x', '200', '99']), [200, 101, 99])
  check('workspace 拼接', resolveWorkspace('/data/root', 42), '/data/root/ws/42')
})

// ---------------------------------------------------------------------------
// B. 工具逻辑：替身 ctx + 替身后端
// ---------------------------------------------------------------------------
await section('B1 工具注册到替身 ctx 并执行', async () => {
  const fake = await loadPluginWithFakes()
  const names = [...fake.tools.keys()]
  check('注册的工具名', names.sort(), ['node_context_get', 'node_output_commit', 'node_output_write', 'node_reference_read'])
  check('注册的技能名', [...fake.skills.keys()], [SKILL_NAME])

  // 1) node_context_get（无参数 → 从 cwd 解析需求 + .workbench 目录推断当前节点）
  const ctxResult = await fake.call('node_context_get', {})
  check('node_context_get ok', ctxResult.ok, true)
  check('node_context_get 用 cwd 找到节点 101', ctxResult.message.includes('节点 2/3「需求拆解」'), true)
  console.log(ctxResult.message.split('\n').map(line => `    │ ${line}`).join('\n'))

  // 2) node_output_write：真实落盘 + 真实 sha256
  const writeResult = await fake.call('node_output_write', { itemKey: 'requirement-items', content: '# 需求条目清单\n\n1. 工单自动分派\n' })
  check('node_output_write ok', writeResult.ok, true)
  const target = join(fake.workspace, '.workbench/101/requirement-items.md')
  check('文件真的落盘', existsSync(target), true)
  check('返回值 sha256 = 磁盘 sha256', writeResult.data.sha256, sha256(readFileSync(target)))
  check('返回值 relativePath', writeResult.data.relativePath, '.workbench/101/requirement-items.md')
  console.log(writeResult.message.split('\n').map(line => `    │ ${line}`).join('\n'))

  // 3) node_output_commit：走替身后端，原样回传 remaining
  const commitResult = await fake.call('node_output_commit', {
    itemKey: 'requirement-items',
    relativePath: writeResult.data.relativePath,
    sha256: writeResult.data.sha256,
  })
  check('node_output_commit ok', commitResult.ok, true)
  check('remaining 原样回传', commitResult.data, {
    committed: true,
    versionNo: 1,
    advanced: false,
    nextNodeName: null,
    requirementCompleted: false,
    remainingRequiredOutputs: ['acceptance-criteria'],
    remainingRequiredInputs: [5],
  })
  check('带"不要重试"提示', commitResult.message.includes('请提示用户去节点信息页上传，不要重试'), true)
  console.log(commitResult.message.split('\n').map(line => `    │ ${line}`).join('\n'))

  // 4) 服务端收到的请求体（字段名与 docs/api.md §11 逐字对齐）
  const commitCall = fake.requests.find(request => request.path.endsWith('/commit'))
  check('commit 请求路径', commitCall?.path, '/internal/nodes/101/outputs/requirement-items/commit')
  check('commit 请求体', commitCall?.body, {
    relativePath: '.workbench/101/requirement-items.md',
    sha256: writeResult.data.sha256,
    operator: 'agent',
  })
  check('请求头带 X-Internal-Token', commitCall?.token, 'test-token')

  // 5) node_reference_read：读输入并登记引用
  const refResult = await fake.call('node_reference_read', { kind: 'input', fileId: 9 })
  check('node_reference_read ok', refResult.ok, true)
  check('读到了正文', refResult.message.includes('客户说：系统老是掉单'), true)
  const refCall = fake.requests.find(request => request.path.endsWith('/reference'))
  check('reference 请求体', refCall?.body, { kind: 'input', fileId: 9, operator: 'agent' })
  console.log(refResult.message.split('\n').slice(0, 8).map(line => `    │ ${line}`).join('\n'))

  // 6) 失败路径：一律可读中文信封
  // 复制场景要有一个真实存在的源文件，否则测到的是"源文件不存在"而不是扩展名拒绝
  const markdownSource = join(fake.workspace, 'notes.md')
  writeFileSync(markdownSource, '# 笔记\n')
  const failures: [string, string, Record<string, unknown>, string][] = [
    ['sha256 不一致', 'node_output_commit', { itemKey: 'requirement-items', relativePath: '.workbench/101/requirement-items.md', sha256: 'deadbeef' }, 'sha256 与磁盘内容不一致'],
    ['提交越界路径', 'node_output_commit', { itemKey: 'requirement-items', relativePath: '/etc/passwd', sha256: 'x' }, 'relativePath 必须在需求 workspace 之内'],
    ['提交不存在的文件', 'node_output_commit', { itemKey: 'acceptance-criteria', relativePath: '.workbench/101/acceptance-criteria.md', sha256: 'x' }, '文件不存在'],
    ['提交未知输出项', 'node_output_commit', { itemKey: 'ghost', relativePath: '.workbench/101/a.md', sha256: 'x' }, '不属于本节点'],
    ['写入未知输出项', 'node_output_write', { itemKey: 'ghost', content: 'x' }, '不属于本节点'],
    ['写入扩展名不符', 'node_output_write', { itemKey: 'design-doc', sourcePath: markdownSource }, '不被接受'],
    ['写入源文件不存在', 'node_output_write', { itemKey: 'requirement-items', sourcePath: join(fake.workspace, 'nope.md') }, '读取 sourcePath 失败'],
    ['content 与 sourcePath 同给', 'node_output_write', { itemKey: 'requirement-items', content: 'x', sourcePath: '/tmp/x' }, '必须二选一'],
    ['引用文件找不到', 'node_reference_read', { kind: 'input', itemKey: '不存在的输入' }, '没有找到符合条件的引用文件'],
    ['kind 不一致', 'node_reference_read', { kind: 'ownOutput', fileId: 9 }, '与 kind=ownOutput 不一致'],
    ['itemKey/fileId 都没给', 'node_reference_read', { kind: 'input' }, '请至少提供 itemKey 或 fileId'],
  ]
  for (const [label, tool, args, expect] of failures) {
    // 单个用例断言失败不中断其余用例：逐个记录，最后统一汇总
    try {
      const result = await fake.call(tool, args)
      console.log(`[${label}] ${tool} → ok=${String(result.ok)}：${result.message}`)
      check(`失败：${label}（ok=false，中文）`, result.ok === false && result.message.includes(expect), true)
    } catch (error) {
      failed += 1
      console.log(`✗ 失败路径用例「${label}」抛出了异常（应当返回信封而不是抛错）：${(error as Error).message}`)
    }
  }
})

await section('B2 prompt 段：属于 / 不属于需求 workspace', async () => {
  const fake = await loadPluginWithFakes()
  // 属于需求 workspace：跑一次 pre-step 触发同步
  const inWorkspace = await fake.promptTextAfterSync(`${storageRoot}/ws/42`)
  console.log(`    │ 属于需求 workspace → ${JSON.stringify(inWorkspace)}`)
  check('属于需求 workspace 时非空且单行', inWorkspace.length > 0 && !inWorkspace.includes('\n'), true)
  check('含节点信息', inWorkspace.includes('节点 2/3「需求拆解」'), true)

  // 不属于：普通目录
  const outside = await fake.promptTextAfterSync('/tmp/fde-verify/plain-dir')
  console.log(`    │ 不属于需求 workspace → ${JSON.stringify(outside)}`)
  check('不属于时为空串', outside, '')
})

// ---------------------------------------------------------------------------
// C. 可选：真实后端
// ---------------------------------------------------------------------------
if (process.argv.includes('--live')) {
  await section('C1 真实后端 /context + 真实 workspace 读写', async () => {
    const baseUrl = process.env.FDE_BASE_URL ?? 'http://127.0.0.1:8081'
    const token = process.env.FDE_INTERNAL_TOKEN ?? ''
    const nodeInstanceId = Number(process.env.FDE_NODE_ID ?? '')
    assert.ok(Number.isInteger(nodeInstanceId) && nodeInstanceId > 0, '需要 FDE_NODE_ID')
    const response = await fetch(`${baseUrl}/internal/nodes/${nodeInstanceId}/context?operator=agent`, {
      headers: { 'X-Internal-Token': token },
    })
    const envelope = await response.json() as { code: number, message?: string, data?: Record<string, any> }
    check('HTTP 200', response.status, 200)
    check('业务码 0', envelope.code, 0)
    const context = envelope.data as any
    check('字段名逐字对齐', ['nodeInstanceId', 'requirementId', 'workspacePath', 'outputs', 'previousNode', 'outputDir'].every(key => key in context), true)
    check('workspace 形如 <storageRoot>/ws/<id>', /\/ws\/\d+$/.test(context.workspacePath), true)
    console.log(formatNodeContext(context).split('\n').map(line => `    │ ${line}`).join('\n'))
    // 读一下本节点第一个输入文件（不提交、不写盘，不留痕）
    const readBack = await readReference({
      absolutePath: join(context.workspacePath, context.freeInputFiles?.[0]?.relativePath ?? context.inputItems?.[0]?.files?.[0]?.relativePath ?? 'no-file'),
      relativePath: context.freeInputFiles?.[0]?.relativePath ?? context.inputItems?.[0]?.files?.[0]?.relativePath ?? 'no-file',
      maxBytes: 4096,
      info: {},
    }).catch(error => ({ text: `（没有可读输入文件：${(error as Error).message}）`, truncatedBytes: 0 }))
    console.log(readBack.text.split('\n').slice(0, 6).map(line => `    │ ${line}`).join('\n'))
    check('引用读取返回了元信息或明确的空提示', readBack.text.length > 0, true)
  })
}

console.log(`\n结果：通过 ${passed} 项，失败 ${failed} 项`)
process.exit(failed === 0 ? 0 : 1)

// ---------------------------------------------------------------------------
// 夹具与替身
// ---------------------------------------------------------------------------

/**
 * 构造一个与后端 `NodeContextVO` 同构的节点上下文（fixture 用，不命中真实后端）。
 * @returns 节点上下文
 */
function fixtureContext(): any {
  return {
    nodeInstanceId: 101,
    requirementId: 42,
    requirementTitle: '客户工单分诊需求',
    requirementStatus: 'in_progress',
    workspacePath: `${storageRoot}/ws/42`,
    nodeSeq: 2,
    nodeCount: 3,
    nodeName: '需求拆解',
    nodeDescription: '把客户原话拆成可交付的需求条目',
    nodeStatus: 'current',
    inputType: 'fixed',
    readonly: false,
    skills: [{ name: '需求拆解', versionNo: 2 }],
    inputItems: [
      {
        itemId: 5,
        name: '客户原话整理',
        fileKind: 'text',
        required: true,
        files: [{ fileId: 9, name: '客户原话.md', relativePath: 'inputs/2/9_客户原话.md', sizeBytes: 22, sha256: 'abc' }],
      },
      { itemId: 6, name: '补充说明', fileKind: 'text', required: true, files: [] },
    ],
    freeInputFiles: [],
    outputs: [
      { itemKey: 'requirement-items', name: '需求条目清单', fileKind: 'text', required: true, submitted: false, versionNo: null, fileName: null, relativePath: null, suggestPath: '.workbench/101/requirement-items.md' },
      { itemKey: 'acceptance-criteria', name: '验收标准', fileKind: 'text', required: true, submitted: false, versionNo: null, fileName: null, relativePath: null, suggestPath: '.workbench/101/acceptance-criteria.md' },
      { itemKey: 'design-doc', name: '设计文档', fileKind: 'document', required: false, submitted: false, versionNo: null, fileName: null, relativePath: null, suggestPath: '.workbench/101/design-doc.docx' },
    ],
    previousNode: {
      seq: 1,
      name: '需求澄清',
      outputs: [{ itemKey: 'clarify-notes', name: '澄清记录', relativePath: 'outputs/1/clarify-notes.v1.md', fileId: 7 }],
    },
    outputDir: '.workbench/101/',
    nextNodeName: '验收',
  }
}

/** 替身上下文暴露给脚本的部分。 */
interface FakePlugin {
  tools: Map<string, any>
  skills: Map<string, any>
  requests: { path: string, body: unknown, token: string }[]
  workspace: string
  /** 调用一个已注册工具（走真实的 execute + render）。 */
  call: (name: string, args: Record<string, unknown>) => Promise<any>
  /** 把会话 cwd 设成 cwd 后触发一次 pre-step，返回该会话最终注册的 prompt 段文本。 */
  promptTextAfterSync: (cwd: string) => Promise<string>
}

/**
 * 在临时目录里搭一个"需求 workspace"，用替身 ctx 加载真实的 `apply`。
 *
 * 替身只实现工具与 prompt 段真正用到的接口：
 * `workingDirectory.ensure` / `logger` / `tools.register` / `skills.register` /
 * `agents.list` / `on` / `effect` / `provide`，以及 agent 自己的 `ctx.systemPrompt.section`。
 * 四个工具的执行逻辑、写盘、sha256、后端调用全部是真实代码。
 * @returns 替身接口
 */
async function loadPluginWithFakes(): Promise<FakePlugin> {
  const requests: { path: string, body: unknown, token: string }[] = []
  const workspace = prepareWorkspace()
  const currentCwd = { value: `${storageRoot}/ws/42` }

  // ---- 替身后端：只实现 context / commit / reference 三个内部接口 ----
  const server = createServer((request, response) => {
    let raw = ''
    request.on('data', (chunk) => { raw += String(chunk) })
    request.on('end', () => {
      const path = request.url ?? ''
      const body = raw.length > 0 ? JSON.parse(raw) : undefined
      requests.push({ path, body, token: String(request.headers['x-internal-token'] ?? '') })
      const context = fixtureContext()
      // 用真实 workspace 覆盖 fixture 路径：读引用时能真的读到文件
      context.workspacePath = workspace
      response.setHeader('Content-Type', 'application/json')
      // 与真实后端一致：只认"本需求的节点 101"，其它 ID 一律 404（探测窗口时正好被跳过）
      const contextMatch = /^\/internal\/nodes\/(\d+)\/context/.exec(path)
      if (contextMatch !== null) {
        if (contextMatch[1] !== '101') {
          response.statusCode = 404
          response.end(JSON.stringify({ code: 10301, message: '节点不存在' }))
          return
        }
        response.end(JSON.stringify({ code: 0, message: 'ok', data: context }))
        return
      }
      if (path.endsWith('/commit')) {
        response.end(JSON.stringify({
          code: 0,
          message: 'ok',
          data: {
            committed: true,
            versionNo: 1,
            advanced: false,
            nextNodeName: null,
            requirementCompleted: false,
            remainingRequiredOutputs: ['acceptance-criteria'],
            remainingRequiredInputs: [5],
          },
        }))
        return
      }
      if (path.endsWith('/reference')) {
        response.end(JSON.stringify({ code: 0, message: 'ok', data: { recorded: true, fileId: 9, fileName: '客户原话.md', kind: 'input' } }))
        return
      }
      response.statusCode = 404
      response.end(JSON.stringify({ code: 10301, message: 'not found' }))
    })
  })
  await new Promise<void>((resolve) => { server.listen(0, '127.0.0.1', resolve) })
  const port = (server.address() as AddressInfo).port

  // ---- 替身 ctx ----
  const tools = new Map<string, any>()
  const skills = new Map<string, any>()
  const listener: { onStep?: (payload: { agent: unknown }, next: () => Promise<undefined>) => Promise<unknown> } = {}
  /** 本次 sync 后该会话注册的最近一条文本（同会话换文本会先 dispose 再注册）。 */
  let lastTextByAgent = new WeakMap<object, string>()

  // 会话上下文的替身：只实现插件真正用到的两个成员——
  // `inject(deps, cb)`（scoped 环境下取服务的唯一方式）与 `systemPrompt.section`
  const agentCtx = {
    systemPrompt: {
      section: (section: { text: string }) => {
        lastTextByAgent.set(agent, section.text)
        return () => {}
      },
    },
    inject: (_deps: string[], callback: (ctx: unknown) => void) => { callback(agentCtx) },
  }
  const agent: any = {
    id: 'sess-verify',
    session: { header: { cwd: `${storageRoot}/ws/42` } },
    ctx: agentCtx,
  }
  const ctx: any = {
    logger: {
      info: (message: string) => console.log(`    [plugin] ${message}`),
      warn: (message: string) => console.log(`    [plugin:warn] ${message}`),
    },
    tools: { register: (definition: any) => { tools.set(definition.name, definition); return () => {} } },
    skills: { register: (skill: any) => { skills.set(skill.name, skill); return () => {} } },
    workingDirectory: { ensure: async () => currentCwd.value },
    agents: { list: () => [agent] },
    on: (event: string, handler: any) => {
      if (event === 'agent/pre-step') listener.onStep = handler
      return () => {}
    },
    effect: (body: () => () => void) => { body() },
    provide: () => {},
  }

  const plugin = await import('../src/host/index.ts')
  plugin.apply(ctx, {
    javaBaseUrl: `http://127.0.0.1:${port}`,
    internalToken: 'test-token',
    storageRoot,
    maxOutputBytes: 1024 * 1024,
  })

  const call = async (name: string, args: Record<string, unknown>): Promise<any> => {
    const definition = tools.get(name)
    assert.ok(definition, `工具 ${name} 未注册`)
    const value = await definition.execute(args, {
      name,
      arguments: args,
      callId: 'call-1',
      rootCallId: 'call-1',
      signal: new AbortController().signal,
      agent,
      deferContext: () => {},
      concludeTurn: () => {},
      token: 'token-1',
    })
    // 顺带断言 render：模型看到的就是 message，失败时加【失败】前缀
    const rendered = definition.output.render(args, value)
    assert.equal(rendered.length, 1)
    assert.equal(rendered[0].text, value.ok ? value.message : `【失败】${value.message}`)
    return value
  }

  const promptTextAfterSync = async (cwd: string): Promise<string> => {
    currentCwd.value = cwd
    lastTextByAgent = new WeakMap<object, string>()
    await listener.onStep?.({ agent }, async () => undefined)
    // sync 是异步的（先算 cwd 再问后端）：等它把注册做完
    for (let i = 0; i < 40 && lastTextByAgent.get(agent) === undefined; i += 1) {
      await new Promise(resolve => setTimeout(resolve, 25))
    }
    return lastTextByAgent.get(agent) ?? ''
  }

  return { tools, skills, requests, workspace, call, promptTextAfterSync }
}

/**
 * 建一个临时需求 workspace：`<storageRoot>/ws/42`，含 `.workbench/101`（当前节点线索）
 * 与 `inputs/2/9_客户原话.md`（真实输入文件）。目录是本次运行专用的临时目录。
 * @returns workspace 绝对路径
 */
function prepareWorkspace(): string {
  const workspace = `${storageRoot}/ws/42`
  mkdirSync(`${workspace}/.workbench/101`, { recursive: true })
  mkdirSync(`${workspace}/inputs/2`, { recursive: true })
  writeFileSync(`${workspace}/inputs/2/9_客户原话.md`, '客户说：系统老是掉单，能不能自动分派？\n')
  return workspace
}
