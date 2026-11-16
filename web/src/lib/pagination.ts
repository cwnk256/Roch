export type PaginationState = {
    page: number
    pageCount: number
    from: number
    to: number
}

export function getPaginationState(total: number, size: number, page: number): PaginationState {
    const pageCount = total === 0 ? 0 : Math.ceil(total / size)
    const currentPage = pageCount === 0 ? 0 : Math.min(Math.max(page, 0), pageCount - 1)
    const from = total === 0 ? 0 : currentPage * size + 1

    return {
        page: currentPage,
        pageCount,
        from,
        to: total === 0 ? 0 : Math.min(from + size - 1, total)
    }
}

export function paginateItems<T>(items: T[], size: number, page: number): T[] {
    const {page: currentPage} = getPaginationState(items.length, size, page)
    return items.slice(currentPage * size, currentPage * size + size)
}
