/**
 * 「节点信息」视图（PRD AC-F4.2~F4.7、AC-F5.1）。
 *
 * 视图内容自上而下严格按 AC-F4.2 的顺序：
 *   1. 节点名称（含序号、状态、技能）
 *   2. 上一节点输出（文件列表，可下载，AC-F4.7）
 *   3. 节点输入（自由输入：标题右侧一个上传按钮，AC-F4.3；固定输入：逐项各一个上传按钮，AC-F4.4）
 *   4. 输出项（逐项：状态 + 已完成时显示版本 / 提交人 / 下载按钮，AC-F4.5）
 *   5. 说明
 *
 * 只读态（AC-F5.1）：`readonly=true` 时不显示任何上传入口，下载仍然可用。
 *
 * 「全部输出完成后自动刷新」是**上层**（`RequirementPage`）的轮询 + 重取职责，
 * 本组件只消费传入的节点数据与文件变更回调，保持纯展示 + 少量交互。
 */
import { useState } from 'react'
import { messageOf } from '../../api/client.ts'
import type { FileVO, RequirementNodeVO } from '../../api/types.ts'
import { useWorkbench } from '../../store/context.tsx'
import { FileUploadButton } from '../../ui/FileUploadButton.tsx'
import { ErrorState, StatusBadge } from '../../ui/components.tsx'
import { buttonBase, buttonLink, card, cardHeader, cardRow, color, mono, muted } from '../../ui/styles.ts'
import type { WorkbenchTranslate } from '../../session/NodeSessionView.tsx'

/**
 * 节点信息视图。
 * @param props - 节点数据、当前用户、文件变更回调与只读标记。
 */
export function NodeInfoPanel({ requirementId, node, filesVersion, onChange }: {
  /** 需求 ID（节点接口路径参数；节点 VO 本身不带需求 ID） */
  readonly requirementId: number
  readonly node: RequirementNodeVO
  /** 文件 / 输出变化计数：变化时本组件重新挂载以刷新内部状态（轮询到推进时会变） */
  readonly filesVersion: number
  /** 上传 / 删除输入之后通知上层刷新节点数据 */
  readonly onChange: () => void
}) {
  const { api, t, toast, currentUsername } = useWorkbench()
  // 正在上传的项目：key = 输入项 ID 或 'free'（自由输入）
  const [uploading, setUploading] = useState<string | undefined>(undefined)
  // 本区块的上传 / 删除错误（上传失败必须可见，不能只在 toast 里一闪而过）
  const [error, setError] = useState<string | undefined>(undefined)

  const readonly = node.readonly
  const free = node.inputType === 'free'
  // 自由输入节点：后端返回**一个** itemId=null 的输入项，承载全部自由输入文件
  const freeInput = node.inputs[0]
  const fixedInputs = free ? [] : node.inputs

  /** 上传一个文件到指定输入项（自由输入传 null）。 */
  const upload = async (file: File, inputItemId: number | null): Promise<void> => {
    const key = inputItemId === null ? 'free' : String(inputItemId)
    setUploading(key)
    setError(undefined)
    try {
      await api.node.uploadInput(requirementId, node.id, file, inputItemId)
      toast(t('node.info.uploaded', { name: file.name }))
      onChange()
    } catch (uploadError) {
      setError(t('node.info.uploadFailed', { message: messageOf(uploadError) }))
    } finally {
      setUploading(undefined)
    }
  }

  /** 删除一个已上传的输入文件。 */
  const removeInput = async (recordId: number): Promise<void> => {
    setError(undefined)
    try {
      await api.node.deleteInput(requirementId, node.id, recordId)
      toast(t('node.info.inputDeleted'))
      onChange()
    } catch (deleteError) {
      setError(t('node.info.deleteInputFailed', { message: messageOf(deleteError) }))
    }
  }

  /** 下载任意文件（带 JWT 的 fetch → blob）。 */
  const download = async (fileId: number, name: string): Promise<void> => {
    setError(undefined)
    try {
      await api.node.downloadFile(fileId, name)
    } catch (downloadError) {
      setError(t('node.info.downloadFailed', { message: messageOf(downloadError) }))
    }
  }

  const statusTone = node.status === 'done' ? 'success' : node.status === 'current' ? 'info' : 'neutral'
  const statusLabel = node.status === 'done'
    ? t('node.info.status.done')
    : node.status === 'current' ? t('node.info.status.current') : t('node.info.status.waiting')

  return (
    <div data-fde-view="info" data-fde-files-version={filesVersion} style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
      {error === undefined ? null : <ErrorState message={error} />}

      {/* 1. 节点名称 */}
      <div style={card} data-fde-section="node-heading">
        <div style={{ ...cardHeader, fontSize: '14px' }}>
          <span style={{ display: 'flex', alignItems: 'center', gap: '8px', minWidth: 0 }}>
            <span style={{ ...mono, color: color.textMuted }}>{t('node.info.seq', { seq: node.seq })}</span>
            <span data-fde-node-name="" style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>{node.name}</span>
          </span>
          <StatusBadge testId="node-status" label={statusLabel} tone={statusTone} />
        </div>
        <div style={{ ...cardRow, borderBottom: 'none', gap: '12px', flexWrap: 'wrap' }}>
          <span style={{ ...muted, flex: '0 0 auto' }}>{t('node.info.skills')}</span>
          <span data-fde-node-skills="" style={{ flex: '1 1 auto', minWidth: 0 }}>
            {node.skills.length === 0
              ? <span style={muted}>{t('node.info.skillsEmpty')}</span>
              : node.skills.map(skill => (
                <span key={skill.skillId} style={{ ...mono, marginRight: '10px' }}>
                  {skill.name}{skill.versionNo === null ? '' : ` v${skill.versionNo}`}
                </span>
              ))}
          </span>
        </div>
        {readonly
          ? (
            <div data-fde-readonly="true" style={{ ...cardRow, borderBottom: 'none', color: color.textMuted, fontSize: '12px' }}>
              {t('node.info.readonlyNotice')}
            </div>
          )
          : null}
      </div>

      {/* 2. 上一节点输出 */}
      <div style={card} data-fde-section="previous">
        <div style={cardHeader}>
          <span>{t('node.info.previous')}</span>
          {node.previousNode === null ? null : <span style={muted}>{node.previousNode.name}</span>}
        </div>
        {node.previousNode === null
          ? <div style={{ padding: '10px 12px' }}><span style={muted}>{t('node.info.previousEmpty')}</span></div>
          : (
            <div data-fde-previous-list="">
              {node.previousNode.outputs.map(item => (
                <div key={item.itemKey} style={cardRow}>
                  <span style={{ flex: '1 1 auto', minWidth: 0 }}>{item.name}</span>
                  {item.file === null
                    ? <span style={muted}>{t('node.info.previousNotSubmitted')}</span>
                    : (
                      <FileChip
                        file={item.file}
                        onDownload={() => {
                          const file = item.file
                          if (file !== null) void download(file.fileId, file.originalName)
                        }}
                        downloadLabel={t('common.download')}
                      />
                    )}
                </div>
              ))}
            </div>
          )}
      </div>

      {/* 3. 节点输入 */}
      <div style={card} data-fde-section="inputs">
        <div style={cardHeader}>
          <span style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            {t('node.info.inputs')}
            <span style={muted}>{free ? t('node.info.inputsFreeHint') : t('node.info.inputsFixedHint')}</span>
          </span>
          {/* AC-F4.3：自由输入时，「节点输入」标题右侧只有一个上传按钮 */}
          {free && !readonly
            ? (
              <FileUploadButton
                testId="free"
                label={t('common.upload')}
                busyLabel={t('node.info.uploading')}
                busy={uploading === 'free'}
                disabled={uploading !== undefined}
                onPick={(file) => { void upload(file, null) }}
              />
            )
            : null}
        </div>

        {free
          ? (
            <div data-fde-input-item="free">
              {(freeInput?.files.length ?? 0) === 0
                ? <div style={{ padding: '10px 12px' }}><span style={muted}>{t('node.info.inputsEmpty')}</span></div>
                : freeInput?.files.map(file => (
                  <div key={file.fileId} style={cardRow} data-fde-input-file={file.fileId}>
                    <span style={{ flex: '1 1 auto', minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      {file.originalName}
                    </span>
                    <span style={muted}>{formatSize(file.sizeBytes)}</span>
                    <button
                      type="button"
                      style={buttonBase}
                      data-fde-download-input={file.fileId}
                      onClick={() => { void download(file.fileId, file.originalName) }}
                    >
                      {t('common.download')}
                    </button>
                    {readonly || file.inputRecordId === null
                      ? null
                      : (
                        <button
                          type="button"
                          style={buttonLink}
                          data-fde-delete-input={file.fileId}
                          onClick={() => {
                            const recordId = file.inputRecordId
                            if (recordId !== null) void removeInput(recordId)
                          }}
                        >
                          {t('node.info.deleteInput')}
                        </button>
                      )}
                  </div>
                ))}
            </div>
          )
          : fixedInputs.map(item => (
            <div key={item.itemId ?? 'fixed'} data-fde-input-item={item.itemId ?? undefined}>
              <div style={{ ...cardRow, background: color.bgSubtle }}>
                <span style={{ fontWeight: 600, flex: '1 1 auto', minWidth: 0 }}>
                  {item.name ?? t('node.info.inputs')}
                  <span style={{ ...muted, marginLeft: '6px' }}>
                    {fileKindLabel(t, item.fileKind)}
                    {' · '}
                    {item.required ? t('common.required') : t('common.optional')}
                  </span>
                </span>
                {/* AC-F4.4：固定输入逐项各一个上传按钮 */}
                {readonly
                  ? null
                  : (
                    <FileUploadButton
                      testId={`input-${item.itemId}`}
                      label={t('node.info.uploadItem')}
                      busyLabel={t('node.info.uploading')}
                      busy={uploading === String(item.itemId)}
                      disabled={uploading !== undefined}
                      onPick={(file) => { void upload(file, item.itemId) }}
                    />
                  )}
              </div>
              {item.files.length === 0
                ? <div style={{ padding: '8px 12px' }}><span style={muted}>{t('node.info.inputsEmpty')}</span></div>
                : item.files.map(file => (
                  <div key={file.fileId} style={cardRow} data-fde-input-file={file.fileId}>
                    <span style={{ flex: '1 1 auto', minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      {file.originalName}
                    </span>
                    <span style={muted}>{formatSize(file.sizeBytes)}</span>
                    <button
                      type="button"
                      style={buttonBase}
                      data-fde-download-input={file.fileId}
                      onClick={() => { void download(file.fileId, file.originalName) }}
                    >
                      {t('common.download')}
                    </button>
                    {readonly || file.inputRecordId === null
                      ? null
                      : (
                        <button
                          type="button"
                          style={buttonLink}
                          data-fde-delete-input={file.fileId}
                          onClick={() => {
                            const recordId = file.inputRecordId
                            if (recordId !== null) void removeInput(recordId)
                          }}
                        >
                          {t('node.info.deleteInput')}
                        </button>
                      )}
                  </div>
                ))}
            </div>
          ))}
        {readonly
          ? <div style={{ ...cardRow, borderBottom: 'none' }}><span style={muted}>{t('node.info.inputsReadonly')}</span></div>
          : null}
      </div>

      {/* 4. 输出项 */}
      <div style={card} data-fde-section="outputs">
        <div style={cardHeader}>
          <span>{t('node.info.outputs')}</span>
          <span style={muted}>{currentUsername ?? ''}</span>
        </div>
        {node.outputs.length === 0
          ? <div style={{ padding: '10px 12px' }}><span style={muted}>{t('node.info.outputsEmpty')}</span></div>
          : (
            <div data-fde-output-list="">
              {node.outputs.map(item => (
                <div key={item.itemKey} data-fde-output-item={item.itemKey} style={{ ...cardRow, alignItems: 'flex-start' }}>
                  <div style={{ flex: '1 1 auto', minWidth: 0 }}>
                    <div style={{ fontWeight: 600 }}>
                      {item.name}
                      <span style={{ ...muted, marginLeft: '6px' }}>
                        {fileKindLabel(t, item.fileKind)}
                        {' · '}
                        {item.required ? t('common.required') : t('common.optional')}
                      </span>
                    </div>
                    {item.submitted && item.file !== null
                      ? (
                        <div data-fde-output-meta="" style={{ ...muted, marginTop: '2px' }}>
                          {item.versionNo === null ? null : <span style={{ ...mono, marginRight: '8px' }}>{t('node.info.outputVersion', { version: item.versionNo })}</span>}
                          <span style={{ marginRight: '8px' }}>{t('node.info.outputBy', { name: currentUsername ?? t('common.unknown') })}</span>
                          {item.committedAt === null ? null : <span>{t('node.info.outputCommittedAt', { time: formatTime(item.committedAt) })}</span>}
                        </div>
                      )
                      : null}
                  </div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flex: '0 0 auto' }}>
                    <StatusBadge
                      testId={`output-status-${item.itemKey}`}
                      label={item.submitted ? t('node.info.outputDone') : t('node.info.outputPending')}
                      tone={item.submitted ? 'success' : 'neutral'}
                    />
                    {/* AC-F4.5：只有已完成（且有真实文件）才出现下载按钮 */}
                    {item.submitted && item.file !== null
                      ? (
                        <button
                          type="button"
                          style={buttonBase}
                          data-fde-download-output={item.itemKey}
                          onClick={() => {
                            const file = item.file
                            if (file !== null) void download(file.fileId, file.originalName)
                          }}
                        >
                          {t('common.download')}
                        </button>
                      )
                      : null}
                  </div>
                </div>
              ))}
            </div>
          )}
      </div>

      {/* 5. 说明 */}
      <div style={card} data-fde-section="description">
        <div style={cardHeader}><span>{t('node.info.description')}</span></div>
        <div style={{ padding: '10px 12px', fontSize: '13px', lineHeight: 1.7, whiteSpace: 'pre-wrap' }}>
          {node.description === null || node.description === ''
            ? <span style={muted}>{t('node.info.descriptionEmpty')}</span>
            : node.description}
        </div>
      </div>
    </div>
  )
}

/** 文件信息块（名称 + 大小 + 下载）。 */
function FileChip({ file, onDownload, downloadLabel }: {
  readonly file: FileVO
  readonly onDownload: () => void
  readonly downloadLabel: string
}) {
  return (
    <span style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
      <span style={{ ...mono, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: '260px' }}>
        {file.originalName}
      </span>
      <span style={muted}>{formatSize(file.sizeBytes)}</span>
      <button type="button" style={buttonBase} data-fde-download-previous={file.fileId} onClick={onDownload}>
        {downloadLabel}
      </button>
    </span>
  )
}

/**
 * 文件类型文案：text / document / any，未知类型原样展示。
 * @param t - 文案函数
 * @param kind - 后端的 fileKind
 */
export function fileKindLabel(t: WorkbenchTranslate, kind: string): string {
  if (kind === 'text') return t('node.info.fileKind.text')
  if (kind === 'document') return t('node.info.fileKind.document')
  if (kind === 'any') return t('node.info.fileKind.any')
  return kind
}

/**
 * 文件大小格式化（B / KB / MB）。
 * @param bytes - 字节数
 */
export function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

/**
 * 时间格式化：把后端 `2026-11-19T10:00:00+08:00` 显示成 `2026-11-19 10:00`。
 * @param iso - 后端时间串
 */
export function formatTime(iso: string): string {
  const matched = /^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})/.exec(iso)
  return matched === null ? iso : `${matched[1]} ${matched[2]}`
}
