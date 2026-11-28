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
            evidenceId, documentId: 'a b', fileName: 'A.pdf', chunkId: 'chunk', pageStart: 7, section: '附件',
            nativeLocation: evidenceId === 'e1' ? '页内位置 16–31' : undefined
        })),
        currentSources: {e1: {originalAvailable: true}, e2: {originalAvailable: false}},
        comparison: {documents: [{documentId: 'a b', fileName: 'A.pdf'}], conclusions: [], rows: [
            {dimension: '周期', cells: [{documentId: 'a b', status: 'FOUND', facts: [
                {claimId: 'c1', rawValue: '30', unit: '天', normalizedValue: '720', normalizedUnit: '小时', verified: true},
                {claimId: 'c2', rawValue: '60', unit: '天', verified: false}
            ]}]}
        ]}
    }
    const server = await createServer({optimizeDeps: {noDiscovery: true}, server: {middlewareMode: true, ws: false}})
    try {
        const reader = (await server.ssrLoadModule('/src/components/ReportReader.vue')).default
        const html = await renderToString(createSSRApp({render: () => h(reader, {report})}))
        for (const fragment of ['换算值：720 小时', '比较字段未核验', '[A.pdf · 第 7 页 · 附件 · 页内位置 16–31] [1]',
            '[A.pdf · 第 7 页 · 附件] [2]', '/api/documents/a%20b/file#page=7', '原文件当前不可用']) {
            assert.ok(html.includes(fragment), fragment)
        }
    } finally {
        await server.close()
    }
})

test('OCR evidence labels the scan, offers a page preview, and links to the original physical PDF page', async () => {
    const server = await createServer({optimizeDeps: {noDiscovery: true}, server: {middlewareMode: true, ws: false}})
    try {
        const reader = (await server.ssrLoadModule('/src/components/ReportReader.vue')).default
        const {ocrPagePreview} = await server.ssrLoadModule('/src/lib/report.ts')
        const snapshot = {
            evidenceId: 'e1', documentId: 'a b', fileName: '扫描.pdf', chunkId: 'chunk', pageStart: 4,
            ocr: {region: [0.125, 0.25, 0.625, 0.5], table: {row: 3, column: 2}, reviewRequired: true}
        }
        const report = {
            schemaVersion: 'v2', summary: '', title: '扫描件报告', sections: [],
            summaryClaims: [{claimId: 'c1', text: '表格金额', evidenceIds: ['e1'], supportStatus: 'SUPPORTED'}],
            evidenceSnapshots: [snapshot],
            currentSources: {e1: {originalAvailable: true}}
        }
        const html = await renderToString(createSSRApp({render: () => h(reader, {report})}))
        for (const fragment of ['OCR 识别', '表格第 3 行第 2 列', '请核对扫描原页',
            '查看第 4 页定位', '/api/documents/a%20b/file#page=4']) {
            assert.ok(html.includes(fragment), fragment)
        }
        assert.deepEqual(ocrPagePreview(snapshot), {
            url: '/api/documents/a%20b/pdf-pages/4',
            highlight: {left: '12.5%', top: '25%', width: '50%', height: '25%'}
        })
        assert.equal(ocrPagePreview({...snapshot, ocr: undefined}), undefined)
        assert.equal(ocrPagePreview({...snapshot, ocr: {...snapshot.ocr, region: [1, 0, 0, 1]}}), undefined)
    } finally {
        await server.close()
    }
})
