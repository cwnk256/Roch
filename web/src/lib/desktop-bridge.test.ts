import assert from 'node:assert/strict'
import test from 'node:test'
import {createDesktopBridge, getDesktopWebView, type DesktopWebView} from './desktop-bridge.js'

class TestWebView extends EventTarget implements DesktopWebView {
    requests: Record<string, unknown>[] = []

    postMessage(message: Record<string, unknown>) {
        this.requests.push(message)
    }

    respond(index: number, result: Record<string, unknown>) {
        this.dispatchEvent(new MessageEvent('message', {data: {id: this.requests[index].id, ...result}}))
    }
}

test('requires the WebView desktop message channel', () => {
    const webview = new TestWebView()
    assert.equal(getDesktopWebView({chrome: {}}), undefined)
    assert.equal(getDesktopWebView({chrome: {webview}}), webview)
})

test('matches concurrent native replies to the calling action', async () => {
    const webview = new TestWebView()
    const bridge = createDesktopBridge(webview)
    const directory = bridge.chooseDirectory()
    const statuses = bridge.credentialStatus()

    assert.equal(webview.requests[0].action, 'chooseDirectory')
    assert.equal(webview.requests[1].action, 'credentialStatus')
    assert.notEqual(webview.requests[0].id, webview.requests[1].id)

    const credentialStatus = {firecrawl: true, amapGeo: false, amapWeather: false}
    webview.respond(1, {ok: true, value: credentialStatus})
    webview.respond(0, {ok: true, value: null})
    assert.equal(await directory, null)
    assert.deepEqual(await statuses, credentialStatus)
})

test('propagates native restart failures', async () => {
    const webview = new TestWebView()
    const restart = createDesktopBridge(webview).restart()
    assert.equal(webview.requests[0].action, 'restart')
    webview.respond(0, {ok: false, error: '报告任务正在运行'})
    await assert.rejects(restart, {message: '报告任务正在运行'})
})

test('sends only the credentials selected for update', async () => {
    const webview = new TestWebView()
    const save = createDesktopBridge(webview).saveCredentials({firecrawl: '', amapGeo: 'test-key'})
    assert.deepEqual(webview.requests[0].credentials, {firecrawl: '', amapGeo: 'test-key'})
    webview.respond(0, {ok: true})
    await save
})
