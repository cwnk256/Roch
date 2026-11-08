import assert from 'node:assert/strict'
import test from 'node:test'
import {getPaginationState, paginateItems} from './pagination.js'

test('clamps the current page and exposes the visible item range', () => {
    assert.deepEqual(getPaginationState(23, 10, 9), {
        page: 2,
        pageCount: 3,
        from: 21,
        to: 23
    })
})

test('returns an empty range when there are no records', () => {
    assert.deepEqual(getPaginationState(0, 10, 0), {
        page: 0,
        pageCount: 0,
        from: 0,
        to: 0
    })
})

test('returns ten items for the requested file-selection page', () => {
    const files = Array.from({length: 23}, (_, index) => `file-${index + 1}`)

    assert.deepEqual(paginateItems(files, 10, 1), [
        'file-11', 'file-12', 'file-13', 'file-14', 'file-15',
        'file-16', 'file-17', 'file-18', 'file-19', 'file-20'
    ])
})
