/**
 * 流程节点编辑器（T3-3）：节点列表 + 上下拖动排序 + 点击展开 + 输入 / 输出项增删 + 技能多选 + 整体保存。
 *
 * 关键契约（docs/api.md §6 `PUT /api/workflows/{id}/nodes`）：
 * 1. **整体覆盖**：每次保存都提交完整节点列表（**包括未改动的节点**），后端按数组顺序重排 `seq`，
 *    所以这里的编辑态是「整条流程的一份副本」，保存时把副本原样映射成请求体。
 * 2. **revision 是不透明值**：它是 `updated_at` 的毫秒时间戳（long），原样放进请求体，
 *    不解析、不展示、不自己 +1；保存冲突（10402）时提示重新加载。
 * 3. **校验文案直接展示**：后端已定位到「第 N 个节点『名称』的第 M 项」，前端不再二次包装。
 *
 * 排序同时提供两种方式：
 * - 拖动：HTML5 Drag & Drop（按住节点标题拖动，放到目标节点上即插入到该位置）；
 * - 上 / 下按钮：键盘可达性更好，也方便自动化测试（不依赖真实鼠标轨迹）。
 * 两者改的都是同一个数组顺序，执行顺序即列表顺序（AC-E2.1）。
 */
import { useMemo, useState } from 'react'
import { messageOf } from '../../api/client.ts'
import type { SkillVO, WorkflowNodeWrite, WorkflowVO } from '../../api/types.ts'
import { useNavigation, type AppStore } from '../../store/app-store.ts'
import { useWorkbench } from '../../store/context.tsx'
import { ErrorState, Loading } from '../../ui/components.tsx'
import { PageShell } from '../../ui/PageShell.tsx'
import { useLoadable } from '../../ui/use-loadable.ts'
import {
  buttonBase, buttonDanger, buttonDisabled, buttonLink, buttonPrimary, card, cardRow, color, formLabel, inputBase, mono, muted,
} from '../../ui/styles.ts'

/** 输入项 / 输出项的编辑态。 */
interface ItemDraft {
  /** 前端本地键：仅用于 React key（后端不关心） */
  readonly uid: string
  /** 输出项稳定键（输入项忽略） */
  readonly itemKey: string
  readonly name: string
  readonly description: string
  readonly fileKind: string
  readonly required: boolean
}

/** 节点的编辑态。 */
interface NodeDraft {
  /** 前端本地键：仅用于 React key（新增节点还没有后端 id） */
  readonly uid: string
  /** 已有节点带后端 id；新增节点为 null */
  readonly nodeId: number | null
  readonly name: string
  readonly description: string
  readonly inputType: 'free' | 'fixed'
  readonly skillIds: number[]
  readonly inputs: ItemDraft[]
  readonly outputs: ItemDraft[]
}

/** 可选择的文件类型（与后端 fileKind 取值一致）。 */
const FILE_KINDS = ['text', 'document', 'any'] as const

/** 本地自增键生成器（只在前端用于 React key）。 */
let draftSeq = 0
function nextUid(prefix: string): string {
  draftSeq += 1
  return `${prefix}-${draftSeq}`
}

/**
 * 把后端节点映射成编辑态。
 * @param workflow - 流程详情
 */
function toDrafts(workflow: WorkflowVO): NodeDraft[] {
  return workflow.nodes.map(node => ({
    uid: nextUid('node'),
    nodeId: node.id,
    name: node.name,
    description: node.description ?? '',
    inputType: node.inputType === 'fixed' ? 'fixed' : 'free',
    skillIds: node.skills.map(skill => skill.skillId),
    inputs: node.inputs.map(item => ({
      uid: nextUid('in'),
      itemKey: '',
      name: item.name,
      description: item.description ?? '',
      fileKind: item.fileKind,
      required: item.required,
    })),
    outputs: node.outputs.map(item => ({
      uid: nextUid('out'),
      itemKey: item.itemKey,
      name: item.name,
      description: item.description ?? '',
      fileKind: item.fileKind,
      required: item.required,
    })),
  }))
}

/** 空节点骨架（新增节点）。 */
function emptyNode(index: number, label: string): NodeDraft {
  return {
    uid: nextUid('node'),
    nodeId: null,
    name: `${label} ${index}`,
    description: '',
    inputType: 'free',
    skillIds: [],
    inputs: [],
    outputs: [{ uid: nextUid('out'), itemKey: '', name: '', description: '', fileKind: 'text', required: true }],
  }
}

/** 空输入 / 输出项。 */
function emptyItem(): ItemDraft {
  return { uid: nextUid('item'), itemKey: '', name: '', description: '', fileKind: 'text', required: true }
}

/**
 * 流程编辑器页面。
 * @param props - 页面栈 store、项目 ID 与流程 ID。
 */
export function WorkflowEditorPage({ store, projectId, workflowId }: {
  readonly store: AppStore
  readonly projectId: number
  readonly workflowId: number
}) {
  const { api, t, toast } = useWorkbench()
  const nav = useNavigation(store)
  const workflow = useLoadable(() => api.workflow.detail(workflowId), [workflowId])
  const skills = useLoadable(() => api.skill.list(projectId), [projectId])

  return (
    <PageShell
      page="workflow-editor"
      back={{ label: t('editor.back'), onClick: () => { nav.back() } }}
      title={workflow.state.kind === 'ready' ? workflow.state.data.name : `#${workflowId}`}
      subtitle={t('editor.dragHint')}
    >
      {workflow.state.kind === 'loading' ? <Loading label={t('common.loading')} /> : null}
      {workflow.state.kind === 'failed'
        ? <ErrorState message={workflow.state.message} onRetry={workflow.reload} retryLabel={t('common.retry')} />
        : null}
      {workflow.state.kind === 'ready'
        ? (
          <EditorBody
            // revision 变化（重新加载后）时重建编辑态，避免拿着旧副本继续保存
            key={String(workflow.state.data.revision)}
            workflow={workflow.state.data}
            skillList={skills.state.kind === 'ready' ? skills.state.data : []}
            skillsError={skills.state.kind === 'failed' ? skills.state.message : undefined}
            onSaved={() => { workflow.reload(); toast(t('editor.saved')) }}
            onReload={workflow.reload}
          />
        )
        : null}
    </PageShell>
  )
}

/**
 * 编辑器主体：持有编辑态副本（`useState(toDrafts(workflow))`），保存成功后才丢弃。
 */
function EditorBody({ workflow, skillList, skillsError, onSaved, onReload }: {
  readonly workflow: WorkflowVO
  readonly skillList: readonly SkillVO[]
  readonly skillsError?: string | undefined
  readonly onSaved: () => void
  readonly onReload: () => void
}) {
  const { api, t } = useWorkbench()
  const [nodes, setNodes] = useState<NodeDraft[]>(() => toDrafts(workflow))
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set(nodes[0] === undefined ? [] : [nodes[0].uid]))
  const [dragging, setDragging] = useState<string | undefined>(undefined)
  const [dropTarget, setDropTarget] = useState<string | undefined>(undefined)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | undefined>(undefined)

  /** 更新某个节点。 */
  const patchNode = (uid: string, patch: Partial<NodeDraft>): void => {
    setNodes(current => current.map(node => (node.uid === uid ? { ...node, ...patch } : node)))
  }

  /** 更新节点里的某一项（输入 / 输出）。 */
  const patchItem = (nodeUid: string, kind: 'inputs' | 'outputs', itemUid: string, patch: Partial<ItemDraft>): void => {
    setNodes(current => current.map(node => node.uid === nodeUid
      ? { ...node, [kind]: node[kind].map(item => (item.uid === itemUid ? { ...item, ...patch } : item)) }
      : node))
  }

  /** 移动节点到新下标（拖动与上移 / 下移共用）。 */
  const move = (uid: string, targetIndex: number): void => {
    setNodes((current) => {
      const from = current.findIndex(node => node.uid === uid)
      if (from < 0) return current
      const clamped = Math.max(0, Math.min(current.length - 1, targetIndex))
      if (clamped === from) return current
      const copy = [...current]
      const [moved] = copy.splice(from, 1)
      if (moved === undefined) return current
      copy.splice(clamped, 0, moved)
      return copy
    })
  }

  /** 切换节点展开 / 收起（AC-E2.2）。 */
  const toggle = (uid: string): void => {
    setExpanded((current) => {
      const copy = new Set(current)
      if (copy.has(uid)) copy.delete(uid)
      else copy.add(uid)
      return copy
    })
  }

  /** 提交前把编辑态映射成请求体（完整节点列表，整体覆盖）。 */
  const body = useMemo(() => ({
    revision: workflow.revision,
    nodes: nodes.map((node): WorkflowNodeWrite => ({
      nodeId: node.nodeId,
      name: node.name,
      description: node.description === '' ? undefined : node.description,
      inputType: node.inputType,
      skillIds: node.skillIds,
      // 自由输入节点不保存固定输入项（后端也会忽略，这里主动清空，避免脏数据）
      inputs: node.inputType === 'fixed'
        ? node.inputs.map(item => ({
          name: item.name,
          description: item.description === '' ? undefined : item.description,
          fileKind: item.fileKind,
          required: item.required,
        }))
        : [],
      outputs: node.outputs.map(item => ({
        itemKey: item.itemKey,
        name: item.name,
        description: item.description === '' ? undefined : item.description,
        fileKind: item.fileKind,
        required: item.required,
      })),
    })),
  }), [nodes, workflow.revision])

  /** 保存：成功用返回的流程刷新（拿到新 revision），失败展示后端文案。 */
  const save = async (): Promise<void> => {
    setBusy(true)
    setError(undefined)
    try {
      await api.workflow.saveNodes(workflow.id, body)
      onSaved()
    } catch (saveError) {
      setError(t('editor.saveFailed', { message: messageOf(saveError) }))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
      {error === undefined
        ? null
        : (
          <ErrorState message={error} onRetry={() => { void save() }} retryLabel={t('common.retry')} />
        )}
      {skillsError === undefined
        ? null
        : <ErrorState message={t('editor.skillsLoadFailed', { message: skillsError })} />}

      <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
        <button
          type="button"
          data-fde-action="add-node"
          style={buttonBase}
          onClick={() => {
            const created = emptyNode(nodes.length + 1, t('editor.newNode'))
            setNodes(current => [...current, created])
            setExpanded(current => new Set([...current, created.uid]))
          }}
        >
          {t('editor.addNode')}
        </button>
        <span style={muted}>{t('editor.dragHint')}</span>
        <span style={{ flex: '1 1 auto' }} />
        <button type="button" style={buttonBase} onClick={onReload}>{t('editor.reload')}</button>
        <button
          type="button"
          data-fde-action="save-nodes"
          style={{ ...buttonPrimary, ...(busy ? buttonDisabled : {}) }}
          disabled={busy}
          onClick={() => { void save() }}
        >
          {busy ? t('common.saving') : t('editor.save')}
        </button>
      </div>

      {nodes.length === 0
        ? <div style={{ padding: '20px 0', ...muted }} data-fde-editor-empty="">{t('editor.noNodes')}</div>
        : null}

      <div data-fde-node-editor="" style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
        {nodes.map((node, index) => (
          <NodeCard
            key={node.uid}
            node={node}
            index={index}
            total={nodes.length}
            expanded={expanded.has(node.uid)}
            dragging={dragging === node.uid}
            dropTarget={dropTarget === node.uid}
            skills={skillList}
            onToggle={() => { toggle(node.uid) }}
            onPatch={(patch) => { patchNode(node.uid, patch) }}
            onPatchItem={(kind, itemUid, patch) => { patchItem(node.uid, kind, itemUid, patch) }}
            onMoveUp={() => { move(node.uid, index - 1) }}
            onMoveDown={() => { move(node.uid, index + 1) }}
            onRemove={() => { setNodes(current => current.filter(item => item.uid !== node.uid)) }}
            onDragStart={() => { setDragging(node.uid) }}
            onDragEnd={() => { setDragging(undefined); setDropTarget(undefined) }}
            onDragOver={(event) => {
              if (dragging === undefined || dragging === node.uid) return
              event.preventDefault()
              setDropTarget(node.uid)
            }}
            onDrop={() => {
              if (dragging === undefined) return
              move(dragging, index)
              setDragging(undefined)
              setDropTarget(undefined)
            }}
            t={t}
          />
        ))}
      </div>
    </div>
  )
}

/** 单个节点的编辑卡片。 */
function NodeCard(props: {
  readonly node: NodeDraft
  readonly index: number
  readonly total: number
  readonly expanded: boolean
  readonly dragging: boolean
  readonly dropTarget: boolean
  readonly skills: readonly SkillVO[]
  readonly onToggle: () => void
  readonly onPatch: (patch: Partial<NodeDraft>) => void
  readonly onPatchItem: (kind: 'inputs' | 'outputs', itemUid: string, patch: Partial<ItemDraft>) => void
  readonly onMoveUp: () => void
  readonly onMoveDown: () => void
  readonly onRemove: () => void
  readonly onDragStart: () => void
  readonly onDragEnd: () => void
  readonly onDragOver: (event: React.DragEvent) => void
  readonly onDrop: () => void
  readonly t: ReturnType<typeof useWorkbench>['t']
}) {
  const { node, index, total, expanded, dragging, dropTarget, skills, t } = props
  return (
    <div
      data-fde-node-card={node.nodeId ?? `new-${index}`}
      data-fde-node-index={index}
      style={{
        ...card,
        opacity: dragging ? 0.45 : 1,
        borderColor: dropTarget ? color.accent : color.border,
        borderStyle: dropTarget ? 'dashed' : 'solid',
      }}
      onDragOver={props.onDragOver}
      onDrop={(event) => { event.preventDefault(); props.onDrop() }}
    >
      <div
        style={{ ...cardRow, background: color.bgSubtle, cursor: 'grab', userSelect: 'none' }}
        // 拖动把手放在整行标题上（draggable 的必须是节点标题，避免拖不了输入框里的文字）
        draggable
        onDragStart={(event) => {
          event.dataTransfer.effectAllowed = 'move'
          // Firefox 需要 setData 才会触发 drag 事件
          event.dataTransfer.setData('text/plain', node.uid)
          props.onDragStart()
        }}
        onDragEnd={props.onDragEnd}
        data-fde-node-drag={node.nodeId ?? `new-${index}`}
      >
        <span style={{ ...muted, width: '20px', flex: '0 0 auto', textAlign: 'right' }}>{index + 1}</span>
        <button
          type="button"
          onClick={props.onToggle}
          data-fde-node-toggle={node.nodeId ?? `new-${index}`}
          style={{
            appearance: 'none', border: 'none', background: 'transparent', padding: 0, cursor: 'pointer',
            color: 'inherit', font: 'inherit', fontSize: '13px', fontWeight: 600, textAlign: 'left',
            flex: '1 1 auto', minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
          }}
        >
          {node.name === '' ? t('editor.emptyNode') : node.name}
          <span style={{ ...muted, fontWeight: 400, marginLeft: '8px' }}>
            {node.inputType === 'fixed' ? t('editor.inputType.fixed') : t('editor.inputType.free')}
            {' · '}
            {t('workflows.nodes', { count: node.outputs.length })}
          </span>
        </button>
        <button type="button" style={buttonLink} data-fde-node-up={node.nodeId ?? `new-${index}`} disabled={index === 0} onClick={props.onMoveUp} title={t('editor.moveUp')}>
          ↑
        </button>
        <button type="button" style={buttonLink} data-fde-node-down={node.nodeId ?? `new-${index}`} disabled={index === total - 1} onClick={props.onMoveDown} title={t('editor.moveDown')}>
          ↓
        </button>
        <button type="button" style={buttonBase} onClick={props.onToggle}>{expanded ? t('editor.collapse') : t('editor.expand')}</button>
        <button type="button" style={buttonDanger} data-fde-node-remove={node.nodeId ?? `new-${index}`} onClick={props.onRemove}>{t('editor.removeNode')}</button>
      </div>

      {expanded
        ? (
          <div style={{ padding: '10px 12px', display: 'flex', flexDirection: 'column', gap: '10px' }}>
            <div style={{ display: 'grid', gridTemplateColumns: '160px 1fr', gap: '8px', alignItems: 'center' }}>
              <label style={formLabel}>{t('editor.nodeName')}</label>
              <input
                data-fde-node-name={node.nodeId ?? `new-${index}`}
                value={node.name}
                onChange={(event) => { props.onPatch({ name: event.target.value }) }}
                style={inputBase}
              />
              <label style={formLabel}>{t('editor.inputType')}</label>
              <div style={{ display: 'flex', alignItems: 'center', gap: '12px', flexWrap: 'wrap' }}>
                <label style={{ display: 'flex', alignItems: 'center', gap: '4px', fontSize: '13px' }}>
                  <input
                    type="radio"
                    name={`inputType-${node.uid}`}
                    data-fde-input-type-free={node.nodeId ?? `new-${index}`}
                    checked={node.inputType === 'free'}
                    onChange={() => { props.onPatch({ inputType: 'free' }) }}
                  />
                  {t('editor.inputType.free')}
                </label>
                <label style={{ display: 'flex', alignItems: 'center', gap: '4px', fontSize: '13px' }}>
                  <input
                    type="radio"
                    name={`inputType-${node.uid}`}
                    data-fde-input-type-fixed={node.nodeId ?? `new-${index}`}
                    checked={node.inputType === 'fixed'}
                    onChange={() => { props.onPatch({ inputType: 'fixed' }) }}
                  />
                  {t('editor.inputType.fixed')}
                </label>
                <span style={muted}>{node.inputType === 'free' ? t('editor.inputTypeFreeHint') : t('editor.inputTypeFixedHint')}</span>
              </div>
              <label style={formLabel}>{t('editor.nodeDescription')}</label>
              <textarea
                data-fde-node-description={node.nodeId ?? `new-${index}`}
                rows={2}
                value={node.description}
                onChange={(event) => { props.onPatch({ description: event.target.value }) }}
                style={{ ...inputBase, resize: 'vertical' }}
              />
            </div>

            {/* 输入项：仅固定输入 */}
            <div data-fde-editor-inputs={node.nodeId ?? `new-${index}`}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '4px' }}>
                <span style={{ ...formLabel, fontSize: '13px' }}>{t('editor.inputs')}</span>
                <button
                  type="button"
                  style={buttonBase}
                  data-fde-add-input={node.nodeId ?? `new-${index}`}
                  disabled={node.inputType !== 'fixed'}
                  onClick={() => { props.onPatch({ inputs: [...node.inputs, emptyItem()] }) }}
                >
                  {t('editor.addInput')}
                </button>
                {node.inputType === 'free' ? <span style={muted}>{t('editor.inputsIgnored')}</span> : null}
              </div>
              {node.inputs.map((item, itemIndex) => (
                <ItemRow
                  key={item.uid}
                  kind="inputs"
                  item={item}
                  index={itemIndex}
                  disabled={node.inputType !== 'fixed'}
                  t={t}
                  onPatch={(patch) => { props.onPatchItem('inputs', item.uid, patch) }}
                  onRemove={() => { props.onPatch({ inputs: node.inputs.filter(entry => entry.uid !== item.uid) }) }}
                />
              ))}
            </div>

            {/* 输出项 */}
            <div data-fde-editor-outputs={node.nodeId ?? `new-${index}`}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '4px' }}>
                <span style={{ ...formLabel, fontSize: '13px' }}>{t('editor.outputs')}</span>
                <button
                  type="button"
                  style={buttonBase}
                  data-fde-add-output={node.nodeId ?? `new-${index}`}
                  onClick={() => { props.onPatch({ outputs: [...node.outputs, emptyItem()] }) }}
                >
                  {t('editor.addOutput')}
                </button>
                <span style={muted}>{t('editor.itemKeyHint')}</span>
              </div>
              {node.outputs.map((item, itemIndex) => (
                <ItemRow
                  key={item.uid}
                  kind="outputs"
                  item={item}
                  index={itemIndex}
                  t={t}
                  onPatch={(patch) => { props.onPatchItem('outputs', item.uid, patch) }}
                  onRemove={() => { props.onPatch({ outputs: node.outputs.filter(entry => entry.uid !== item.uid) }) }}
                />
              ))}
            </div>

            {/* 技能多选（AC-E5.1）：列本项目全部技能，勾选顺序即加载顺序 */}
            <div data-fde-editor-skills={node.nodeId ?? `new-${index}`}>
              <div style={{ ...formLabel, fontSize: '13px', marginBottom: '4px' }}>{t('editor.skills')}</div>
              {skills.length === 0
                ? <span style={muted}>{t('editor.skillsEmpty')}</span>
                : (
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: '10px' }}>
                    {skills.map(skill => {
                      const checked = node.skillIds.includes(skill.id)
                      return (
                        <label key={skill.id} style={{ display: 'flex', alignItems: 'center', gap: '4px', fontSize: '13px' }} data-fde-skill-option={skill.id}>
                          <input
                            type="checkbox"
                            data-fde-node-skill={`${node.nodeId ?? `new-${index}`}:${skill.id}`}
                            checked={checked}
                            onChange={(event) => {
                              // 勾选追加到末尾（保持选择顺序），取消则移除
                              props.onPatch({
                                skillIds: event.target.checked
                                  ? [...node.skillIds, skill.id]
                                  : node.skillIds.filter(id => id !== skill.id),
                              })
                            }}
                          />
                          <span style={mono}>{skill.name}</span>
                        </label>
                      )
                    })}
                  </div>
                )}
            </div>
          </div>
        )
        : null}
    </div>
  )
}

/** 输入 / 输出项的一行编辑。 */
function ItemRow({ kind, item, index, disabled, onPatch, onRemove, t }: {
  readonly kind: 'inputs' | 'outputs'
  readonly item: ItemDraft
  readonly index: number
  readonly disabled?: boolean
  readonly onPatch: (patch: Partial<ItemDraft>) => void
  readonly onRemove: () => void
  readonly t: ReturnType<typeof useWorkbench>['t']
}) {
  return (
    <div
      data-fde-item-row={`${kind}-${index}`}
      style={{
        display: 'grid',
        gridTemplateColumns: kind === 'outputs' ? '1fr 140px 1fr 110px auto auto' : '1fr 1fr 110px auto auto',
        gap: '6px',
        alignItems: 'center',
        padding: '6px 0',
        borderTop: `1px solid ${color.border}`,
        opacity: disabled === true ? 0.5 : 1,
      }}
    >
      <input
        placeholder={t('editor.itemName')}
        value={item.name}
        disabled={disabled}
        onChange={(event) => { onPatch({ name: event.target.value }) }}
        style={inputBase}
        data-fde-item-name={`${kind}-${index}`}
      />
      {kind === 'outputs'
        ? (
          <input
            placeholder={t('editor.itemKey')}
            value={item.itemKey}
            onChange={(event) => { onPatch({ itemKey: event.target.value }) }}
            style={{ ...inputBase, ...mono }}
            data-fde-item-key={`outputs-${index}`}
          />
        )
        : null}
      <input
        placeholder={t('editor.itemDescription')}
        value={item.description}
        disabled={disabled}
        onChange={(event) => { onPatch({ description: event.target.value }) }}
        style={inputBase}
        data-fde-item-description={`${kind}-${index}`}
      />
      <select
        aria-label={t('editor.fileKind')}
        value={item.fileKind}
        disabled={disabled}
        onChange={(event) => { onPatch({ fileKind: event.target.value }) }}
        style={inputBase}
        data-fde-item-kind={`${kind}-${index}`}
      >
        {FILE_KINDS.map(value => <option key={value} value={value}>{value}</option>)}
      </select>
      <label style={{ display: 'flex', alignItems: 'center', gap: '4px', fontSize: '12px', ...muted }}>
        <input
          type="checkbox"
          checked={item.required}
          disabled={disabled}
          onChange={(event) => { onPatch({ required: event.target.checked }) }}
          data-fde-item-required={`${kind}-${index}`}
        />
        {t('editor.required')}
      </label>
      <button type="button" style={buttonLink} onClick={onRemove} disabled={disabled} data-fde-item-remove={`${kind}-${index}`} title={t('editor.removeItem')}>
        ✕
      </button>
    </div>
  )
}
