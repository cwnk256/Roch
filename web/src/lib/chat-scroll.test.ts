import assert from 'node:assert/strict'
import test from 'node:test'
import {scrollMessagesToBottom} from './chat-scroll.js'

test('scrolls the message pane to its latest content', () => {
    const pane = {scrollTop: 0, scrollHeight: 640}

    scrollMessagesToBottom(pane)

    assert.equal(pane.scrollTop, 640)
})
