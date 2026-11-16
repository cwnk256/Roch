type DownloadableTask = { status: string; downloadUrl?: string }
type DownloadableArtifact = { downloadUrl: string }

export function getTaskDownloadUrl(task: DownloadableTask, artifacts: DownloadableArtifact[]): string | undefined {
    if (task.status !== 'COMPLETED') return undefined
    return task.downloadUrl || artifacts[artifacts.length - 1]?.downloadUrl
}
