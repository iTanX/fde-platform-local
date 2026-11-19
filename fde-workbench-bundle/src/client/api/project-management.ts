import { ApiClient, ApiError } from './client.ts'
import type { MemberVO, ProjectVO, SkillVO } from './types.ts'

interface SkillPackageFile {
  readonly path: string
  readonly size: number
  readonly sha256: string
}

interface SkillVersionVO {
  readonly id: number
  readonly versionNo: number
  readonly entryPath: string
  readonly checksum: string
  readonly uploadedBy: number
  readonly createdAt: string
  readonly files: readonly SkillPackageFile[]
}

export function projectMemberActions(client: ApiClient, projectId: number) {
  const url = `/projects/${projectId}/members`
  return {
    add: (username: string) => client.json<MemberVO>('POST', url, { username }),
    remove: (userId: number, revision: number) =>
      client.json<null>('DELETE', `${url}/${userId}${ApiClient.query({ revision })}`),
    // 与移除一样，转让携带调用方看到的项目版本号。
    transfer: (userId: number, revision: number) =>
      client.json<ProjectVO>('POST', `${url}/${userId}/transfer-owner${ApiClient.query({ revision })}`),
  }
}

export function projectSkillActions(client: ApiClient) {
  return {
    upload: (projectId: number, file: File) => {
      const form = new FormData()
      form.append('file', file)
      return client.upload<SkillVO>(`/projects/${projectId}/skills`, form)
    },
    versions: (skillId: number) => client.json<SkillVersionVO[]>('GET', `/skills/${skillId}/versions`),
    preview: (skillId: number, versionNo: number, path: string) =>
      client.json<SkillPackageFile & { readonly content: string }>('GET',
        `/skills/${skillId}/files${ApiClient.query({ path, versionNo })}`),
    remove: (skillId: number) => client.json<null>('DELETE', `/skills/${skillId}`),
  }
}

/** 同时支持带来源的新响应和早期仅包含需求字段的响应。 */
export function skillConflictReferences(error: unknown) {
  if (!(error instanceof ApiError) || error.code !== 10403) return []
  const references = (error.data as { references?: unknown } | null | undefined)?.references
  if (!Array.isArray(references)) return []
  return references.flatMap<{ kind: 'workflow' | 'requirement'; name: string; node: string }>((reference: unknown) => {
    if (typeof reference !== 'object' || reference === null || !('nodeName' in reference)
      || typeof reference.nodeName !== 'string') return []
    if ('workflowName' in reference && typeof reference.workflowName === 'string') {
      return [{ kind: 'workflow' as const, name: reference.workflowName, node: reference.nodeName }]
    }
    if ('requirementTitle' in reference && typeof reference.requirementTitle === 'string') {
      return [{ kind: 'requirement' as const, name: reference.requirementTitle, node: reference.nodeName }]
    }
    return []
  })
}
