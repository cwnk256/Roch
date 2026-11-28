export interface DocumentMeta {
    documentId: string;
    fileName: string;
    fileType?: string;
    md5?: string;
    fileSize?: number;
    chunkCount?: number;
    version?: string;
    status?: string;
    createdAt?: string
}

export interface DocumentImportResult {
    chunkCount: number;
    md5: string;
    duplicated: boolean;
    message: string
}

export interface PdfImportJob {
    jobId: string;
    documentId: string;
    status: 'QUEUED' | 'RUNNING' | 'INDEXING' | 'SUCCEEDED' | 'FAILED';
    currentPage: number;
    totalPages: number;
    failedPage?: number | null;
    message?: string | null;
    result?: DocumentImportResult | null
}

export interface PageResult<T> {
    content: T[];
    page: number;
    size: number;
    total: number
}

export interface Task {
    taskId: string;
    type: 'SUMMARY' | 'REPORT' | 'COMPARE';
    status: string;
    title?: string;
    resultFileName?: string;
    downloadUrl?: string;
    errorMessage?: string;
    progress: number;
    currentStep: string;
    errorCode?: string | null;
    documentIds?: string[]
}

export interface Artifact {
    artifactId: string;
    fileName: string;
    contentType: string;
    fileSize: number;
    createdAt: string;
    downloadUrl: string
}

export interface PageResponse {
    url: string;
    title: string;
    fileName: string;
    filePath: string;
    fetchedAt: string;
    length: number;
    markdown: string;
}

export interface SearchResponse {
    query?: string
    documents?: {
        documentId?: string
        fileName?: string
        score?: number
        hitCount?: number
        matchedChunks?: {
            chunkId?: string
            content?: string
            section?: string
            chunkIndex?: number
            score?: number
        }[]
    }[]
}
