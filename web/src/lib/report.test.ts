import assert from 'node:assert/strict'
import test from 'node:test'
import {createSSRApp, h} from 'vue'
import {renderToString} from 'vue/server-renderer'
import {createServer} from 'vite'

test('comparison reader distinguishes facts on the same page and flags unverified fields in a found cell', async () => {
    const report = {
        schemaVersion: 'v2', summary: '', title: '对比',
        configuration: {sections: [{sectionId: 'matrix'}], exportOptions: {citationStyle: 'FILE_LOCATION'}},
        sections: [{title: '矩阵', content: '', claims: [
            {claimId: 'c1', text: '周期为30天', evidenceIds: ['e1'], supportStatus: 'SUPPORTED'},
            {claimId: 'c2', text: '目标为60天', evidenceIds: ['e2'], supportStatus: 'SUPPORTED'}
        ]}],
        evidenceSnapshots: ['e1', 'e2'].map(evidenceId => ({
            evidenceId, documentId: 'a b', fileName: 'A.pdf', chunkId: 'chunk', pageStart: 7, section: '附件'
        })),
        currentSources: {e1: {originalAvailable: true}, e2: {originalAvailable: false}},
        comparison: {documents: [{documentId: 'a b', fileName: 'A.pdf'}], conclusions: [], rows: [
            {dimension: '周期', cells: [{documentId: 'a b', status: 'FOUND', facts: [
                {claimId: 'c1', rawValue: '30', unit: '天', normalizedValue: '720', normalizedUnit: '小时', verified: true},
                {claimId: 'c2', rawValue: '60', unit: '天', verified: false}
            ]}]}
        ]}
    }
    const server = await createServer({server: {middlewareMode: true, hmr: false}})
    try {
        const reader = (await server.ssrLoadModule('/src/components/ReportReader.vue')).default
        const html = await renderToString(createSSRApp({render: () => h(reader, {report})}))
        for (const fragment of ['换算值：720 小时', '比较字段未核验', '[A.pdf · 第 7 页 · 附件] [1]',
            '[A.pdf · 第 7 页 · 附件] [2]', '/api/documents/a%20b/file#page=7', '原文件当前不可用']) {
            assert.ok(html.includes(fragment), fragment)
        }
    } finally {
        await server.close()
    }
})
