import { ApiError, request } from './api'
import { resourceId, sequence } from './sequence'
import type { Page } from './types'

export interface KnowledgeBase {
  id: string; name: string; description: string | null; status: string
  embeddingProvider: string; embeddingModel: string; chunkSize: number; chunkOverlap: number
  embeddingProfileCode: string | null; chunkStrategyVersion: string | null
  createdAt: string; updatedAt: string
}
export const READINESS = ['NOT_READY', 'INDEXING', 'READY', 'DEGRADED', 'FAILED'] as const
export type Readiness = typeof READINESS[number]
export interface DocumentReceipt {
  id: string; knowledgeBaseId: string; fileName: string; fileType: string; fileSize: number | string
  parseStatus: string; createdAt: string; updatedAt: string
}
export interface KnowledgeDocument extends DocumentReceipt {
  vectorGeneration: string
  vectorization: { pending: string; processing: string; completed: string; failed: string }
  retrievalReadiness: Readiness
}
export interface BatchResult { discovered: number; claimed: number; completed: number; failed: number; skipped: number }

function checked<T>(value: T, validate: (value: T) => void, write = false): T {
  try { validate(value); return value } catch {
    throw new ApiError('服务器响应无法确认，请刷新查看最新记录', 0, 'INVALID_RESPONSE', write)
  }
}
function base(value: KnowledgeBase): void {
  resourceId(value.id)
  if (typeof value.name !== 'string' || typeof value.status !== 'string'
    || !(value.embeddingProfileCode === null || typeof value.embeddingProfileCode === 'string')
    || !(value.chunkStrategyVersion === null || typeof value.chunkStrategyVersion === 'string')) throw new Error('Invalid knowledge base')
}
function receipt(value: DocumentReceipt, kbId: string): void {
  resourceId(value.id)
  if (resourceId(value.knowledgeBaseId) !== kbId || typeof value.fileName !== 'string'
    || typeof value.parseStatus !== 'string') throw new Error('Invalid document')
}
function document(value: KnowledgeDocument, kbId: string): void {
  receipt(value, kbId)
  value.vectorGeneration = sequence(value.vectorGeneration)
  for (const key of ['pending', 'processing', 'completed', 'failed'] as const) value.vectorization[key] = sequence(value.vectorization[key])
  if (!READINESS.includes(value.retrievalReadiness)) throw new Error('Unknown readiness')
}
function page<T>(value: Page<T>, requestedPage: number, validate: (item: T) => void): void {
  if (!Array.isArray(value.items) || value.page !== requestedPage || value.pageSize !== 20
    || typeof value.hasNext !== 'boolean') throw new Error('Invalid page')
  sequence(value.total)
  value.items.forEach(validate)
}
function batch(value: BatchResult): void {
  for (const key of ['discovered', 'claimed', 'completed', 'failed', 'skipped'] as const) {
    if (!Number.isSafeInteger(value[key]) || value[key] < 0) throw new Error('Invalid batch')
  }
}
const kbPath = (id: string) => `/knowledge-bases/${resourceId(id)}`
export const knowledgeApi = {
  async listBases(index = 1, signal?: AbortSignal): Promise<Page<KnowledgeBase>> {
    return checked(await request('GET', `/knowledge-bases?page=${index}&pageSize=20`, undefined, { signal }),
      (value: Page<KnowledgeBase>) => page(value, index, base))
  },
  async getBase(id: string, signal?: AbortSignal): Promise<KnowledgeBase> {
    return checked(await request('GET', kbPath(id), undefined, { signal }), (value: KnowledgeBase) => {
      base(value); if (value.id !== id) throw new Error('Wrong knowledge base')
    })
  },
  async createBase(name: string, description: string, signal?: AbortSignal): Promise<KnowledgeBase> {
    return checked(await request('POST', '/knowledge-bases', { name, description }, { signal }), base, true)
  },
  async listDocuments(kbId: string, index = 1, signal?: AbortSignal): Promise<Page<KnowledgeDocument>> {
    return checked(await request('GET', `${kbPath(kbId)}/documents?page=${index}&pageSize=20`, undefined, { signal }),
      (value: Page<KnowledgeDocument>) => page(value, index, item => document(item, kbId)))
  },
  async getDocument(kbId: string, id: string, signal?: AbortSignal): Promise<KnowledgeDocument> {
    return checked(await request('GET', `/documents/${resourceId(id)}`, undefined, { signal }), (value: KnowledgeDocument) => {
      document(value, kbId); if (value.id !== id) throw new Error('Wrong document')
    })
  },
  async upload(kbId: string, file: File, signal?: AbortSignal): Promise<DocumentReceipt> {
    const form = new FormData()
    form.append('file', file)
    return checked(await request('POST', `${kbPath(kbId)}/documents`, form, { signal }),
      (value: DocumentReceipt) => receipt(value, kbId), true)
  },
  async process(kbId: string, signal?: AbortSignal): Promise<BatchResult> {
    return checked(await request('POST', `${kbPath(kbId)}/documents/process-pending`, undefined, { signal }), batch, true)
  },
  async vectorize(kbId: string, signal?: AbortSignal): Promise<BatchResult> {
    return checked(await request('POST', `${kbPath(kbId)}/chunks/vectorize-pending`, undefined, { signal }), batch, true)
  },
}

export const compatible = (kb: KnowledgeBase): boolean => kb.embeddingProfileCode === 'dashscope-te-v4-1024-cosine'
  && kb.chunkStrategyVersion === 'structured-token-v1'
export const observing = (doc: KnowledgeDocument): boolean => ['PROCESSING', 'REPROCESSING'].includes(doc.parseStatus)
  || doc.retrievalReadiness === 'INDEXING'
export function uploadError(file?: File): string {
  if (!file) return '请选择一个 TXT 或 MD 文件'
  if (!/\.(txt|md)$/i.test(file.name)) return '仅支持 TXT / MD 单文件上传'
  if (file.size === 0) return '文件不能为空'
  return ''
}
