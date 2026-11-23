export interface DesktopWebView extends EventTarget {
    postMessage(message: Record<string, unknown>): void
}

export interface CredentialStatus {
    firecrawl: boolean
    amapGeo: boolean
    amapWeather: boolean
}

export type CredentialUpdates = Partial<Record<keyof CredentialStatus, string>>

export function getDesktopWebView(host: {chrome?: {webview?: DesktopWebView}}): DesktopWebView | undefined {
    const channel = host.chrome?.webview
    return typeof channel?.postMessage === 'function' ? channel : undefined
}

let nextRequestId = 0

export function createDesktopBridge(webview: DesktopWebView) {
    function request<T>(action: string, details: Record<string, unknown> = {}): Promise<T> {
        const id = String(++nextRequestId)
        return new Promise((resolve, reject) => {
            function finish() {
                clearTimeout(timeout)
                webview.removeEventListener('message', receive)
            }

            function receive(event: Event) {
                const message = (event as MessageEvent).data
                if (message?.id !== id || typeof message.ok !== 'boolean') return
                finish()
                if (message.ok) resolve(message.value as T)
                else reject(new Error(message.error || 'Windows 操作失败。'))
            }

            const timeout = setTimeout(() => {
                finish()
                reject(new Error('Windows 操作超时，请重试。'))
            }, 180_000)
            webview.addEventListener('message', receive)
            try {
                webview.postMessage({id, action, ...details})
            } catch (error) {
                finish()
                reject(error)
            }
        })
    }

    return {
        chooseDirectory: () => request<string | null>('chooseDirectory'),
        credentialStatus: () => request<CredentialStatus>('credentialStatus'),
        saveCredentials: (credentials: CredentialUpdates) => request<void>('saveCredentials', {credentials}),
        restart: () => request<void>('restart')
    }
}

export function desktopBridge() {
    if (typeof window === 'undefined') return undefined
    const webview = getDesktopWebView(window as Window & {chrome?: {webview?: DesktopWebView}})
    return webview ? createDesktopBridge(webview) : undefined
}
