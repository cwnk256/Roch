import assert from 'node:assert/strict'
import test from 'node:test'
import type {AxiosRequestConfig} from 'axios'
import {createServer} from 'vite'

test('PDF import distinguishes queued OCR work without changing native or text uploads', async () => {
    const server = await createServer({optimizeDeps: {noDiscovery: true}, server: {middlewareMode: true, ws: false}})
    try {
        const {api} = await server.ssrLoadModule('/src/api/index.ts')
        const {http} = await server.ssrLoadModule('/src/api/http.ts')
        const requests: AxiosRequestConfig[] = []
        const native = {chunkCount: 2, md5: 'hash', duplicated: false, message: '导入成功'}
        const queued = {jobId: 'job #1', documentId: 'doc', status: 'QUEUED', currentPage: 0, totalPages: 3}
        const failed = {...queued, status: 'FAILED', failedPage: 2, message: '识别失败'}
        const responses = [
            {status: 200, data: native}, {status: 202, data: queued},
            {status: 200, data: failed}, {status: 202, data: queued},
            {status: 200, data: native}
        ]
        http.defaults.adapter = async (config: AxiosRequestConfig) => {
            requests.push(config)
            return {...responses[requests.length - 1], config, statusText: '', headers: {}}
        }

        const pdf = new File(['pdf'], 'sample.pdf')
        assert.deepEqual(await api.importPdf(pdf), {kind: 'completed', result: native})
        assert.deepEqual(await api.importPdf(pdf), {kind: 'ocr', job: queued})
        assert.deepEqual(await api.pdfImport(queued.jobId), failed)
        assert.deepEqual(await api.retryPdfImport(queued.jobId), queued)
        assert.deepEqual(await api.upload(new File(['text'], 'sample.txt'), ''), native)
        assert.deepEqual(requests.map(({method, url, params}) => ({method, url, params})), [
            {method: 'post', url: '/documents/pdf', params: {ocr: 'auto'}},
            {method: 'post', url: '/documents/pdf', params: {ocr: 'auto'}},
            {method: 'get', url: '/documents/pdf-imports/job%20%231', params: undefined},
            {method: 'post', url: '/documents/pdf-imports/job%20%231/retry', params: undefined},
            {method: 'post', url: '/documents', params: undefined}
        ])
        assert.equal(requests[0].data.get('file').name, 'sample.pdf')
    } finally {
        await server.close()
    }
})
