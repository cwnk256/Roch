import {http} from './http'
import type {Artifact, DocumentMeta, PageResponse, PageResult, SearchResponse, Task} from '../types'

export type Conversation = {
    conversationId: string
    title: string
    createdAt: string
    updatedAt: string
}

export const api = {
    chat: (message: string) => http.post<{ reply: string }>('/chat', {message}).then(r => r.data),
    createConversation: () => http.post<Conversation>('/chat/conversations').then(r => r.data),
    listConversations: () => http.get<Conversation[]>('/chat/conversations').then(r => r.data),
    clearConversation: (id: string) => http.delete('/chat/conversations/' + id).then(r => r.data),
    deleteConversation: (id: string) => http.delete('/chat/conversations/' + id + '/delete').then(r => r.data),
    history: (id: string) => http.get<{
        messages: { role: string; content: string }[]
    }>(`/chat/conversations/${id}/messages`).then(r => r.data),
    multiTurn: (payload: Record<string, unknown>) => http.post('/chat/multi-turn', payload).then(r => r.data),
    /**
     * 流式多轮问答（SSE over POST，EventSource 不支持 POST，故用 fetch 读取）。
     * 事件协议：conversation(会话ID) → rag(true/false) → content(增量文本) 多次 → 结束；
     * 异常时后端发 error 事件。
     */
    async multiTurnStream(
        payload: Record<string, unknown>,
        handlers: {
            onConversation?: (id: string) => void
            onRag?: (used: boolean) => void
            onContent?: (delta: string) => void
            onErrorEvent?: (message: string) => void
            signal?: AbortSignal
        }
    ): Promise<void> {
        const response = await fetch('/api/chat/multi-turn/stream', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(payload),
            signal: handlers.signal
        });
        if (!response.ok || !response.body) {
            let detail = '';
            try {
                detail = ((await response.json()) as { error?: string })?.error || '';
            } catch {
                /* 非 JSON 错误体，忽略 */
            }
            throw new Error(detail || `流式请求失败（HTTP ${response.status}）`);
        }
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        // 按 SSE 规范解析：事件以空行分隔；event: 指定名称，多行 data: 用 \n 拼接
        const dispatch = (rawEvent: string) => {
            let event = 'message';
            const dataLines: string[] = [];
            for (const line of rawEvent.split('\n')) {
                if (line.startsWith('event:')) event = line.slice(6).trim();
                else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''));
            }
            const data = dataLines.join('\n');
            switch (event) {
                case 'conversation':
                    handlers.onConversation?.(data);
                    break;
                case 'rag':
                    handlers.onRag?.(data === 'true');
                    break;
                case 'content':
                    handlers.onContent?.(data);
                    break;
                case 'error':
                    handlers.onErrorEvent?.(data);
                    break;
            }
        };
        while (true) {
            const {done, value} = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, {stream: true});
            // 兼容 \r\n 与 \n 行尾
            buffer = buffer.replace(/\r\n/g, '\n');
            let separator: number;
            while ((separator = buffer.indexOf('\n\n')) >= 0) {
                dispatch(buffer.slice(0, separator));
                buffer = buffer.slice(separator + 2);
            }
        }
        if (buffer.trim()) dispatch(buffer);
    },
    listDocuments: (params: Record<string, unknown> = {}) => http.get<PageResult<DocumentMeta>>('/documents', {params}).then(r => r.data),
    document: (id: string) => http.get<DocumentMeta>(`/documents/${id}`).then(r => r.data),
    chunks: (id: string, params = {}) => http.get<PageResult<Record<string, unknown>>>(`/documents/${id}/chunks`, {params}).then(r => r.data),
    search: (payload: Record<string, unknown>) => http.post<SearchResponse>('/documents/search', payload).then(r => r.data),
    upload: (file: File, kind: string) => {
        const form = new FormData();
        form.append('file', file);
        return http.post(`/documents${kind ? `/${kind}` : ''}`, form).then(r => r.data)
    },
    deleteDocument: (id: string) => http.delete(`/documents/${id}`).then(r => r.data),
    reindex: (id: string, file: File) => {
        const form = new FormData();
        form.append('file', file);
        return http.post(`/documents/${id}/reindex`, form).then(r => r.data)
    },
    listTasks: (params: Record<string, unknown> = {}) => http.get<PageResult<Task>>('/document-tasks', {params}).then(r => r.data),
    createTask: (payload: Record<string, unknown>) => http.post<Task>('/document-tasks', payload).then(r => r.data),
    task: (id: string) => http.get<Task>(`/document-tasks/${id}`).then(r => r.data),
    result: (id: string) => http.get(`/document-tasks/${id}/result`).then(r => r.data),
    artifacts: (id: string) => http.get<Artifact[]>(`/document-tasks/${id}/artifacts`).then(r => r.data),
    retry: (id: string) => http.post<Task>(`/document-tasks/${id}/retry`).then(r => r.data),
    reExport: (id: string) => http.post<Artifact>(`/document-tasks/${id}/artifacts/pdf`).then(r => r.data),
    fetchPage: (url: string, engine: string = 'auto') =>
        http.post<PageResponse>('/curl/page', {url, engine}).then(r => r.data),
}
