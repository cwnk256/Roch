import assert from 'node:assert/strict'
import test from 'node:test'
import {normalizeSearchResults} from './fileSearch.js'

test('normalizes document search hits with readable chunk previews', () => {
    const results = normalizeSearchResults({
        query: 'Maintenance safety',
        documents: [{
            documentId: 'document-1',
            fileName: 'manual.txt',
            score: 0.84,
            hitCount: 2,
            matchedChunks: [{
                chunkId: 'chunk-1',
                content: 'Inspect equipment daily.',
                section: 'Maintenance',
                chunkIndex: 3,
                score: 0.81
            }]
        }]
    })

    assert.deepEqual(results, [{
        documentId: 'document-1',
        fileName: 'manual.txt',
        score: 0.84,
        hitCount: 2,
        chunks: [{
            chunkId: 'chunk-1',
            content: 'Inspect equipment daily.',
            section: 'Maintenance',
            chunkIndex: 3,
            score: 0.81
        }]
    }])
})
