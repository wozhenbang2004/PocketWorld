export type TreeKeyboardItem = {
    id: string
    parentId?: string
    expanded?: boolean
    disabled?: boolean
}

export type TreeKeyboardAction =
    | { type: 'focus'; id: string }
    | { type: 'expand'; expanded: boolean }
    | { type: 'activate' }
    | { type: 'none' }

/** Rows are in DOM/preorder order. Closing animations may still contain hidden descendants. */
export function visibleTreeItems(items: readonly TreeKeyboardItem[]): TreeKeyboardItem[] {
    const visible = new Map<string, TreeKeyboardItem>()
    for (const item of items) {
        if (item.parentId === undefined || visible.get(item.parentId)?.expanded === true) {
            visible.set(item.id, item)
        }
    }
    return [...visible.values()]
}

export function treeTabStop(
    items: readonly TreeKeyboardItem[], focusedId?: string, selectedId?: string
): string | undefined {
    const visible = visibleTreeItems(items)
    const visibleIds = new Set(visible.map(item => item.id))
    let focused = items.find(item => item.id === focusedId)
    while (focused) {
        if (visibleIds.has(focused.id)) return focused.id
        focused = items.find(item => item.id === focused?.parentId)
    }
    return visibleIds.has(selectedId ?? '') ? selectedId : visible[0]?.id
}

/** Navigation never selects or opens a document; activation goes through the original click. */
export function treeKeyboardAction(
    items: readonly TreeKeyboardItem[], currentId: string, key: string
): TreeKeyboardAction | undefined {
    const visible = visibleTreeItems(items)
    const index = visible.findIndex(item => item.id === currentId)
    if (index < 0) return undefined
    const current = visible[index]
    const focus = (item?: TreeKeyboardItem): TreeKeyboardAction => item
        ? { type: 'focus', id: item.id } : { type: 'none' }
    switch (key) {
        case 'ArrowDown': return focus(visible[index + 1])
        case 'ArrowUp': return focus(visible[index - 1])
        case 'Home': return focus(visible[0])
        case 'End': return focus(visible.at(-1))
        case 'ArrowRight':
            if (current.expanded === false && !current.disabled) return { type: 'expand', expanded: true }
            return focus(visible.find(item => item.parentId === current.id))
        case 'ArrowLeft':
            if (current.expanded === true && !current.disabled) return { type: 'expand', expanded: false }
            return focus(visible.find(item => item.id === current.parentId))
        case 'Enter':
        case ' ':
        case 'Spacebar': return { type: current.disabled ? 'none' : 'activate' }
        default: return undefined
    }
}
