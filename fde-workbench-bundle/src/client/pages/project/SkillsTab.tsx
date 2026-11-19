import { useRef, useState } from 'react'
import { messageOf } from '../../api/client.ts'
import { projectSkillActions, skillConflictReferences } from '../../api/project-management.ts'
import type { SkillVO } from '../../api/types.ts'
import { useWorkbench } from '../../store/context.tsx'
import { ConfirmDialog, EmptyState, ErrorState, Loading, Modal, StatusBadge } from '../../ui/components.tsx'
import { useLoadable } from '../../ui/use-loadable.ts'
import { buttonBase, buttonDanger, buttonPrimary, card, cardHeader, cardRow, inputBase, muted } from '../../ui/styles.ts'
import { zipSkillDirectory } from './skill-directory.ts'

/** 项目技能上传同名包时由后端递增版本。保留旧版入口，供按版本下载和预览。 */
export function SkillsTab({ projectId, currentUserId, isOwner }: {
  readonly projectId: number
  readonly currentUserId: number | undefined
  readonly isOwner: boolean
}) {
  const { api, t, toast } = useWorkbench()
  const actions = projectSkillActions(api.client)
  const skills = useLoadable(() => api.skill.list(projectId), [projectId])
  const zipInput = useRef<HTMLInputElement>(null)
  const directoryInput = useRef<HTMLInputElement>(null)
  const [viewing, setViewing] = useState<SkillVO>()
  const [removing, setRemoving] = useState<SkillVO>()
  const [busy, setBusy] = useState(false)
  const [uploadError, setUploadError] = useState<string>()
  const [deleteError, setDeleteError] = useState<{ message: string; references: ReturnType<typeof skillConflictReferences> }>()

  const upload = async (files: File[], directory: boolean) => {
    if (files.length === 0 || busy) return
    setBusy(true)
    setUploadError(undefined)
    try {
      let file = files[0]!
      if (directory) {
        try {
          file = await zipSkillDirectory(files)
        } catch {
          setUploadError(t('skills.directoryFailed'))
          return
        }
      }
      const uploaded = await actions.upload(projectId, file)
      toast(t('skills.uploaded', { name: uploaded.name, version: uploaded.currentVersionNo ?? 1 }), 'success')
      skills.reload()
    } catch (error) {
      setUploadError(messageOf(error))
    } finally {
      setBusy(false)
    }
  }

  const remove = async () => {
    if (removing === undefined || busy) return
    setBusy(true)
    setDeleteError(undefined)
    try {
      await actions.remove(removing.id)
      setRemoving(undefined)
      skills.reload()
    } catch (error) {
      setDeleteError({ message: messageOf(error), references: skillConflictReferences(error) })
    } finally {
      setBusy(false)
    }
  }

  return (
    <div style={card}>
      <div style={cardHeader}>
        <span>{t('skills.heading')}</span>
        <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap' }}>
          <input ref={zipInput} type="file" accept=".zip,application/zip" hidden onChange={event => {
            const files = Array.from(event.currentTarget.files ?? [])
            event.currentTarget.value = '' // 支持再次选择同一个文件以上传新版本。
            void upload(files, false)
          }} />
          <input ref={directoryInput} type="file" {...{ webkitdirectory: '' }} multiple hidden onChange={event => {
            const files = Array.from(event.currentTarget.files ?? [])
            event.currentTarget.value = ''
            void upload(files, true)
          }} />
          <button type="button" style={buttonPrimary} disabled={busy} data-fde-action="upload-skill-zip" onClick={() => zipInput.current?.click()}>
            {busy && removing === undefined ? t('common.uploading') : t('skills.uploadZip')}
          </button>
          <button type="button" style={buttonBase} disabled={busy} data-fde-action="upload-skill-directory" onClick={() => directoryInput.current?.click()}>
            {t('skills.uploadDirectory')}
          </button>
        </div>
      </div>
      {uploadError === undefined ? null : <div style={{ padding: '12px' }}><ErrorState message={uploadError} /></div>}
      {skills.state.kind === 'loading' ? <Loading label={t('common.loading')} /> : null}
      {skills.state.kind === 'failed' ? (
        <div style={{ padding: '12px' }}><ErrorState message={skills.state.message} onRetry={skills.reload} retryLabel={t('common.retry')} /></div>
      ) : null}
      {skills.state.kind === 'ready' && skills.state.data.length === 0
        ? <div style={{ padding: '12px' }}><EmptyState title={t('skills.empty')} hint={t('skills.uploadHint')} /></div>
        : null}
      {skills.state.kind === 'ready' && skills.state.data.length > 0 ? (
        <div data-fde-skill-list="">
          {skills.state.data.map(skill => (
            <div key={skill.id} data-fde-skill-row={skill.id} style={{ ...cardRow, justifyContent: 'space-between', flexWrap: 'wrap' }}>
              <div style={{ minWidth: 0 }}>
                <div style={{ fontWeight: 600 }}>{skill.name}</div>
                {skill.description === null ? null : <div style={muted}>{skill.description}</div>}
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
                <span style={muted}>{t('skills.counts', { count: skill.usageCount })}</span>
                {skill.currentVersionNo === null ? null : <StatusBadge label={t('skills.version', { version: skill.currentVersionNo })} tone="info" />}
                <button type="button" style={buttonBase} data-fde-skill-versions={skill.id} onClick={() => setViewing(skill)}>{t('skills.versions')}</button>
                {isOwner || skill.createdBy === currentUserId ? (
                  <button type="button" style={buttonDanger} disabled={busy} data-fde-skill-delete={skill.id} onClick={() => {
                    setDeleteError(undefined)
                    setRemoving(skill)
                  }}>{t('common.delete')}</button>
                ) : null}
              </div>
            </div>
          ))}
        </div>
      ) : null}
      {viewing === undefined ? null : <SkillVersions skill={viewing} onClose={() => setViewing(undefined)} />}
      {removing === undefined ? null : (
        <ConfirmDialog
          title={t('skills.deleteTitle')}
          body={<>
            <div>{t('skills.deleteBody', { name: removing.name })}</div>
            {deleteError === undefined ? null : (
              <div style={{ marginTop: '8px' }}>
                <ErrorState message={deleteError.message} />
                {deleteError.references.length === 0 ? null : (
                  <ul data-fde-skill-references="">
                    {deleteError.references.map((ref, index) => (
                      <li key={index}>{t(ref.kind === 'workflow' ? 'skills.reference.workflow' : 'skills.reference.requirement', { name: ref.name, node: ref.node })}</li>
                    ))}
                  </ul>
                )}
              </div>
            )}
          </>}
          confirmLabel={t('common.delete')}
          danger
          busy={busy}
          onConfirm={() => { void remove() }}
          onCancel={() => { if (!busy) setRemoving(undefined) }}
        />
      )}
    </div>
  )
}

function SkillVersions({ skill, onClose }: { readonly skill: SkillVO; readonly onClose: () => void }) {
  const { api, t } = useWorkbench()
  const versions = useLoadable(() => projectSkillActions(api.client).versions(skill.id), [skill.id])
  const [selectedVersion, setSelectedVersion] = useState<number>()
  const [selectedPath, setSelectedPath] = useState<string>()
  const [downloadError, setDownloadError] = useState<string>()
  const [downloading, setDownloading] = useState(false)
  const latestFirst = versions.state.kind === 'ready' ? [...versions.state.data].reverse() : []
  const version = latestFirst.find(item => item.versionNo === selectedVersion) ?? latestFirst[0]

  const download = async () => {
    if (version === undefined || downloading) return
    setDownloading(true)
    setDownloadError(undefined)
    try {
      await api.skill.downloadVersion(skill.id, version.versionNo, `${skill.name}-v${version.versionNo}.zip`)
    } catch (error) {
      setDownloadError(messageOf(error))
    } finally {
      setDownloading(false)
    }
  }

  return (
    <Modal title={t('skills.versionTitle', { name: skill.name })} onClose={onClose} testId="skill-versions">
      {versions.state.kind === 'loading' ? <Loading label={t('common.loading')} /> : null}
      {versions.state.kind === 'failed' ? <ErrorState message={versions.state.message} onRetry={versions.reload} retryLabel={t('common.retry')} /> : null}
      {versions.state.kind === 'ready' && version === undefined ? <EmptyState title={t('skills.versionsEmpty')} /> : null}
      {version === undefined ? null : (
        <div style={{ padding: '14px', display: 'grid', gap: '12px' }}>
          <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
            <select style={inputBase} aria-label={t('skills.versions')} data-fde-skill-version="" value={version.versionNo} disabled={downloading} onChange={event => {
              setSelectedVersion(Number(event.currentTarget.value))
              setSelectedPath(undefined)
              setDownloadError(undefined)
            }}>
              {latestFirst.map(item => (
                <option key={item.id} value={item.versionNo}>{item.versionNo === skill.currentVersionNo ? t('skills.version', { version: item.versionNo }) : `v${item.versionNo}`}</option>
              ))}
            </select>
            <button type="button" style={buttonPrimary} disabled={downloading} data-fde-action="download-skill-version" onClick={() => { void download() }}>
              {downloading ? t('common.downloading') : t('common.download')}
            </button>
          </div>
          {downloadError === undefined ? null : <ErrorState message={downloadError} />}
          <ul style={{ margin: 0, paddingInlineStart: '20px' }}>
            {version.files.map(file => (
              <li key={file.path} style={{ marginBlock: '4px' }}>
                <button type="button" style={buttonBase} aria-pressed={selectedPath === file.path} data-fde-skill-preview={file.path} onClick={() => setSelectedPath(file.path)}>{file.path}</button>
              </li>
            ))}
          </ul>
          {selectedPath === undefined ? null : (
            <SkillFilePreview key={`${version.versionNo}:${selectedPath}`} skillId={skill.id} versionNo={version.versionNo} path={selectedPath} />
          )}
        </div>
      )}
    </Modal>
  )
}

function SkillFilePreview({ skillId, versionNo, path }: { readonly skillId: number; readonly versionNo: number; readonly path: string }) {
  const { api, t } = useWorkbench()
  const preview = useLoadable(() => projectSkillActions(api.client).preview(skillId, versionNo, path), [skillId, versionNo, path])
  if (preview.state.kind === 'loading') return <Loading label={t('common.loading')} />
  if (preview.state.kind === 'failed') return <ErrorState message={preview.state.message} onRetry={preview.reload} retryLabel={t('common.retry')} />
  // 预览内容是项目上传的用户数据，以纯文本渲染。
  return <pre data-fde-skill-file-content="" style={{ margin: 0, padding: '10px', maxHeight: '300px', overflow: 'auto', whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{preview.state.data.content}</pre>
}
