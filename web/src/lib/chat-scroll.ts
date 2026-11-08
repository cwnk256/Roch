export type ScrollableMessagePane = {
    scrollTop: number
    scrollHeight: number
}

export function scrollMessagesToBottom(pane: ScrollableMessagePane | null): void {
    if (pane) pane.scrollTop = pane.scrollHeight
}
