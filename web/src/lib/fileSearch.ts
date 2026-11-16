export interface SearchChunk {
    chunkId?: string
    content: string
    section?: string
    chunkIndex?: number
    score?: number
}

export interface SearchDocument {
    documentId?: string
    fileName?: string
    score?: number
    hitCount?: number
    chunks: SearchChunk[]
}

import type {SearchResponse} from '../types'

export function normalizeSearchResults(response: SearchResponse | null): SearchDocument[] {
    if (!response || !Array.isArray(response.documents)) return []

    return response.documents.map((document) => {
        const value = document as Record<string, unknown>
        const matchedChunks = Array.isArray(value.matchedChunks) ? value.matchedChunks : []
        return {
            documentId: stringValue(value.documentId),
            fileName: stringValue(value.fileName),
            score: numberValue(value.score),
            hitCount: numberValue(value.hitCount),
            chunks: matchedChunks.map((chunk) => {
                const item = chunk as Record<string, unknown>
                return {
                    chunkId: stringValue(item.chunkId),
                    content: stringValue(item.content) ?? '',
                    section: stringValue(item.section),
                    chunkIndex: numberValue(item.chunkIndex),
                    score: numberValue(item.score)
                }
            })
        }
    })
}

function stringValue(value: unknown): string | undefined {
    return typeof value === 'string' ? value : undefined
}

function numberValue(value: unknown): number | undefined {
    return typeof value === 'number' ? value : undefined
}
