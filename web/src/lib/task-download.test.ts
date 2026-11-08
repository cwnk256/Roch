import assert from 'node:assert/strict'
import test from 'node:test'
import {getTaskDownloadUrl} from './task-download.js'

test('prefers a completed task download URL', () => {
    assert.equal(
        getTaskDownloadUrl({status: 'COMPLETED', downloadUrl: '/files/report.pdf'}, []),
        '/files/report.pdf'
    )
})

test('falls back to the newest task artifact download URL', () => {
    assert.equal(
        getTaskDownloadUrl({status: 'COMPLETED'}, [
            {downloadUrl: '/files/older.pdf'},
            {downloadUrl: '/files/newer.pdf'}
        ]),
        '/files/newer.pdf'
    )
})

test('does not expose a download URL for incomplete tasks', () => {
    assert.equal(getTaskDownloadUrl({status: 'RUNNING', downloadUrl: '/files/report.pdf'}, []), undefined)
})
