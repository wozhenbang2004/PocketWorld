'use client'

import React from 'react'
import * as AccordionPrimitive from '@radix-ui/react-accordion'
import { IconChevronRight } from '../../../../icons';
import { cva } from 'class-variance-authority'
import { cn } from './lib/utils'
import { treeKeyboardAction, treeTabStop, type TreeKeyboardItem } from './tree-keyboard'

const treeVariants = cva(
    'group hover:before:opacity-100 before:absolute before:rounded-lg before:left-0 px-2 before:w-full before:opacity-0 before:bg-accent/70 before:h-[2rem] before:-z-10 focus-visible:outline focus-visible:outline-2 focus-visible:outline-current focus-visible:-outline-offset-2'
)

const selectedTreeVariants = cva(
    'before:opacity-100 before:bg-accent/70 text-accent-foreground'
)

const dragOverVariants = cva(
    'before:opacity-100 before:bg-primary/20 text-primary-foreground'
)

interface TreeDataItem {
    id: string
    name: string
    icon?: React.ComponentType<{ className?: string }>
    selectedIcon?: React.ComponentType<{ className?: string }>
    openIcon?: React.ComponentType<{ className?: string }>
    children?: TreeDataItem[]
    actions?: React.ReactNode
    onClick?: () => void
    draggable?: boolean
    droppable?: boolean
    disabled?: boolean
    className?: string
}

type TreeRenderItemParams = {
    item: TreeDataItem
    level: number
    isLeaf: boolean
    isSelected: boolean
    isOpen?: boolean
    hasChildren: boolean
}

const TreeExpansionContext = React.createContext<{ ids: string[]; change: (id: string, open: boolean) => void } | null>(null)

type TreeProps = React.HTMLAttributes<HTMLDivElement> & {
    expandedIds?: string[]
    onExpandedChange?: (ids: string[]) => void
    data: TreeDataItem[] | TreeDataItem
    initialSelectedItemId?: string
    selectedItemId?: string
    onSelectChange?: (item: TreeDataItem | undefined) => void
    expandAll?: boolean
    defaultNodeIcon?: React.ComponentType<{ className?: string }>
    defaultLeafIcon?: React.ComponentType<{ className?: string }>
    onDocumentDrag?: (sourceItem: TreeDataItem, targetItem: TreeDataItem) => void
    renderItem?: (params: TreeRenderItemParams) => React.ReactNode
}

function treeRows(root: HTMLElement): { element: HTMLElement; item: TreeKeyboardItem }[] {
    return Array.from(root.querySelectorAll<HTMLElement>('[data-tree-item]'))
        .filter(element => element.closest('[role="tree"]') === root)
        .map(element => ({
            element,
            item: {
                id: element.dataset.treeItem!,
                parentId: element.dataset.treeParent,
                expanded: element.hasAttribute('aria-expanded') ? element.getAttribute('aria-expanded') === 'true' : undefined,
                disabled: element.getAttribute('aria-disabled') === 'true',
            },
        }))
}

function handleTreeKeyDown(event: React.KeyboardEvent<HTMLElement>, setExpanded?: (open: boolean) => void) {
    // Controls supplied by renderItem/actions retain their own keyboard and context-menu behavior.
    if (event.target !== event.currentTarget || event.altKey || event.ctrlKey || event.metaKey || event.nativeEvent.isComposing) return
    const root = event.currentTarget.closest<HTMLElement>('[role="tree"]')
    if (!root) return
    const rows = treeRows(root)
    const action = treeKeyboardAction(rows.map(row => row.item), event.currentTarget.dataset.treeItem!, event.key)
    if (!action) return
    event.preventDefault()
    event.stopPropagation() // Nested Radix accordions must not apply a second, local navigation step.
    if (action.type === 'focus') rows.find(row => row.item.id === action.id)?.element.focus()
    else if (action.type === 'expand') setExpanded?.(action.expanded)
    else if (action.type === 'activate' && !event.repeat) event.currentTarget.click()
}

function focusClickedRow(event: React.MouseEvent<HTMLElement>) {
    const control = event.target instanceof Element
        ? event.target.closest('button, a, input, select, textarea, [tabindex], [contenteditable="true"]') : null
    if (!control || control === event.currentTarget) event.currentTarget.focus()
}

const TreeView = React.forwardRef<HTMLDivElement, TreeProps>(
    (
        {
            data,
            initialSelectedItemId,
            selectedItemId: controlledSelectedItemId,
            expandedIds: controlledExpandedIds,
            onExpandedChange,
            onSelectChange,
            expandAll,
            defaultLeafIcon,
            defaultNodeIcon,
            className,
            onDocumentDrag,
            renderItem,
            ...props
        },
        ref
    ) => {
        const [internalSelectedItemId, setSelectedItemId] = React.useState<
            string | undefined
        >(initialSelectedItemId)

        const selectedItemId = controlledSelectedItemId ?? internalSelectedItemId
        const rootRef = React.useRef<HTMLDivElement>(null)
        React.useImperativeHandle(ref, () => rootRef.current!, [])
        const [focusedItemId, setFocusedItemId] = React.useState(selectedItemId)
        React.useLayoutEffect(() => {
            if (!rootRef.current) return
            const next = treeTabStop(treeRows(rootRef.current).map(row => row.item), focusedItemId, selectedItemId)
            if (next !== focusedItemId) setFocusedItemId(next)
        }, [data, focusedItemId, selectedItemId])

        const [draggedItem, setDraggedItem] = React.useState<TreeDataItem | null>(null)

        const handleSelectChange = React.useCallback(
            (item: TreeDataItem | undefined) => {
                setSelectedItemId(item?.id)
                if (onSelectChange) {
                    onSelectChange(item)
                }
            },
            [onSelectChange]
        )

        const handleDragStart = React.useCallback((item: TreeDataItem) => {
            setDraggedItem(item)
        }, [])

        const handleDrop = React.useCallback((targetItem: TreeDataItem) => {
            if (draggedItem && onDocumentDrag && draggedItem.id !== targetItem.id) {
                onDocumentDrag(draggedItem, targetItem)
            }
            setDraggedItem(null)
        }, [draggedItem, onDocumentDrag])

        const expandedItemIds = React.useMemo(() => {
            if (!initialSelectedItemId) {
                return [] as string[]
            }

            const ids: string[] = []

            function walkTreeItems(
                items: TreeDataItem[] | TreeDataItem,
                targetId: string
            ) {
                if (Array.isArray(items)) {
                    for (let i = 0; i < items.length; i++) {
                        ids.push(items[i].id)
                        if (walkTreeItems(items[i], targetId) && !expandAll) {
                            return true
                        }
                        if (!expandAll) ids.pop()
                    }
                } else if (!expandAll && items.id === targetId) {
                    return true
                } else if (items.children) {
                    return walkTreeItems(items.children, targetId)
                }
            }

            walkTreeItems(data, initialSelectedItemId)
            return ids
        }, [data, expandAll, initialSelectedItemId])

        return (
            <TreeExpansionContext.Provider value={controlledExpandedIds === undefined ? null : { ids: controlledExpandedIds, change: (id, open) => onExpandedChange?.(open ? [...new Set([...controlledExpandedIds, id])] : controlledExpandedIds.filter(value => value !== id)) }}>
            <div className={cn('overflow-hidden relative p-2', className)}>
                <TreeItem
                    data={data}
                    ref={rootRef}
                    selectedItemId={selectedItemId}
                    focusedItemId={focusedItemId}
                    onItemFocus={setFocusedItemId}
                    handleSelectChange={handleSelectChange}
                    expandedItemIds={expandedItemIds}
                    defaultLeafIcon={defaultLeafIcon}
                    defaultNodeIcon={defaultNodeIcon}
                    handleDragStart={handleDragStart}
                    handleDrop={handleDrop}
                    draggedItem={draggedItem}
                    renderItem={renderItem}
                    level={0}
                    {...props}
                />
                <div
                    className='w-full h-[48px]'
                    onDrop={() => { handleDrop({id: '', name: 'parent_div'})}}>
                </div>
            </div>
            </TreeExpansionContext.Provider>
        )
    }
)
TreeView.displayName = 'TreeView'

type TreeItemProps = TreeProps & {
    selectedItemId?: string
    focusedItemId?: string
    onItemFocus?: (id: string) => void
    parentId?: string
    handleSelectChange: (item: TreeDataItem | undefined) => void
    expandedItemIds: string[]
    defaultNodeIcon?: React.ComponentType<{ className?: string }>
    defaultLeafIcon?: React.ComponentType<{ className?: string }>
    handleDragStart?: (item: TreeDataItem) => void
    handleDrop?: (item: TreeDataItem) => void
    draggedItem: TreeDataItem | null
    level?: number
}

const TreeItem = React.forwardRef<HTMLDivElement, TreeItemProps>(
    (
        {
            className,
            data,
            selectedItemId,
            focusedItemId,
            onItemFocus,
            parentId,
            handleSelectChange,
            expandedItemIds,
            defaultNodeIcon,
            defaultLeafIcon,
            handleDragStart,
            handleDrop,
            draggedItem,
            renderItem,
            level,
            onSelectChange,
            expandAll,
            initialSelectedItemId,
            selectedItemId: controlledSelectedItemId,
            onDocumentDrag,
            ...props
        },
        ref
    ) => {
        const items = Array.isArray(data) ? data : [data]
        return (
            <div ref={ref} role={level ? 'group' : 'tree'} className={className} {...props}>
                <ul role="none">
                    {items.map((item, index) => (
                        <li key={item.id} role="none">
                            {item.children ? (
                                <TreeNode
                                    item={item}
                                    level={level ?? 0}
                                    selectedItemId={selectedItemId}
                                    focusedItemId={focusedItemId}
                                    onItemFocus={onItemFocus}
                                    parentId={parentId}
                                    position={index + 1}
                                    setSize={items.length}
                                    expandedItemIds={expandedItemIds}
                                    handleSelectChange={handleSelectChange}
                                    defaultNodeIcon={defaultNodeIcon}
                                    defaultLeafIcon={defaultLeafIcon}
                                    handleDragStart={handleDragStart}
                                    handleDrop={handleDrop}
                                    draggedItem={draggedItem}
                                    renderItem={renderItem}
                                />
                            ) : (
                                <TreeLeaf
                                    item={item}
                                    level={level ?? 0}
                                    selectedItemId={selectedItemId}
                                    focusedItemId={focusedItemId}
                                    onItemFocus={onItemFocus}
                                    parentId={parentId}
                                    position={index + 1}
                                    setSize={items.length}
                                    handleSelectChange={handleSelectChange}
                                    defaultLeafIcon={defaultLeafIcon}
                                    handleDragStart={handleDragStart}
                                    handleDrop={handleDrop}
                                    draggedItem={draggedItem}
                                    renderItem={renderItem}
                                />
                            )}
                        </li>
                    ))}
                </ul>
            </div>
        )
    }
)
TreeItem.displayName = 'TreeItem'

const TreeNode = ({
    item,
    handleSelectChange,
    expandedItemIds,
    selectedItemId,
    focusedItemId,
    onItemFocus,
    parentId,
    position,
    setSize,
    defaultNodeIcon,
    defaultLeafIcon,
    handleDragStart,
    handleDrop,
    draggedItem,
    renderItem,
    level = 0,
}: {
    item: TreeDataItem
    handleSelectChange: (item: TreeDataItem | undefined) => void
    expandedItemIds: string[]
    selectedItemId?: string
    focusedItemId?: string
    onItemFocus?: (id: string) => void
    parentId?: string
    position?: number
    setSize?: number
    defaultNodeIcon?: React.ComponentType<{ className?: string }>
    defaultLeafIcon?: React.ComponentType<{ className?: string }>
    handleDragStart?: (item: TreeDataItem) => void
    handleDrop?: (item: TreeDataItem) => void
    draggedItem: TreeDataItem | null
    renderItem?: (params: TreeRenderItemParams) => React.ReactNode
    level?: number
}) => {
    const expansion = React.useContext(TreeExpansionContext)
    const [localValue, setLocalValue] = React.useState(
        expandedItemIds.includes(item.id) ? [item.id] : []
    )
    const value = expansion ? (expansion.ids.includes(item.id) ? [item.id] : []) : localValue
    const setValue = (next: string[]) => expansion ? expansion.change(item.id, next.includes(item.id)) : setLocalValue(next)
    const [isDragOver, setIsDragOver] = React.useState(false)
    const hasChildren = !!item.children?.length
    const isSelected = selectedItemId === item.id
    const isOpen = value.includes(item.id)
    const childrenId = React.useId()

    const onDragStart = (e: React.DragEvent) => {
        if (!item.draggable || item.disabled) {
            e.preventDefault()
            return
        }
        e.dataTransfer.setData('text/plain', item.id)
        handleDragStart?.(item)
    }

    const onDragOver = (e: React.DragEvent) => {
        if (item.droppable !== false && !item.disabled && draggedItem && draggedItem.id !== item.id) {
            e.preventDefault()
            setIsDragOver(true)
        }
    }

    const onDragLeave = () => {
        setIsDragOver(false)
    }

    const onDrop = (e: React.DragEvent) => {
        if (item.disabled) return
        e.preventDefault()
        setIsDragOver(false)
        handleDrop?.(item)
    }

    return (
        <AccordionPrimitive.Root
            type="multiple"
            value={value}
            onValueChange={(s) => { if (!item.disabled) setValue(s) }}
        >
            <AccordionPrimitive.Item value={item.id}>
                <AccordionTrigger
                    role="treeitem"
                    aria-label={item.name}
                    aria-selected={isSelected}
                    aria-expanded={hasChildren ? isOpen : undefined}
                    aria-disabled={item.disabled || undefined}
                    aria-level={level + 1}
                    aria-posinset={position}
                    aria-setsize={setSize}
                    aria-owns={isOpen && hasChildren ? childrenId : undefined}
                    data-tree-item={item.id}
                    data-tree-parent={parentId}
                    tabIndex={focusedItemId === item.id ? 0 : -1}
                    onFocus={event => { if (event.target === event.currentTarget) onItemFocus?.(item.id) }}
                    onKeyDown={event => handleTreeKeyDown(event, open => setValue(open ? [item.id] : []))}
                    className={cn(
                        treeVariants(),
                        isSelected && selectedTreeVariants(),
                        isDragOver && dragOverVariants(),
                        item.className
                    )}
                    onClick={event => {
                        if (item.disabled) return
                        focusClickedRow(event)
                        handleSelectChange(item)
                        item.onClick?.()
                    }}
                    draggable={!!item.draggable && !item.disabled}
                    onDragStart={onDragStart}
                    onDragOver={onDragOver}
                    onDragLeave={onDragLeave}
                    onDrop={onDrop}
                >
                    {renderItem ? (
                        renderItem({
                            item,
                            level,
                            isLeaf: false,
                            isSelected,
                            isOpen,
                            hasChildren,
                        })
                    ) : (
                        <>
                            <TreeIcon
                                item={item}
                                isSelected={isSelected}
                                isOpen={isOpen}
                                default={defaultNodeIcon}
                            />
                            <span className="text-sm truncate">{item.name}</span>
                            <TreeActions isSelected={isSelected}>
                                {item.actions}
                            </TreeActions>
                        </>
                    )}
                </AccordionTrigger>
                <AccordionContent className="ml-4 pl-1 border-l" role="presentation" aria-hidden={!isOpen} inert={!isOpen}>
                    <TreeItem
                        id={childrenId}
                        data={item.children ? item.children : item}
                        selectedItemId={selectedItemId}
                        focusedItemId={focusedItemId}
                        onItemFocus={onItemFocus}
                        parentId={item.id}
                        handleSelectChange={handleSelectChange}
                        expandedItemIds={expandedItemIds}
                        defaultLeafIcon={defaultLeafIcon}
                        defaultNodeIcon={defaultNodeIcon}
                        handleDragStart={handleDragStart}
                        handleDrop={handleDrop}
                        draggedItem={draggedItem}
                        renderItem={renderItem}
                        level={level + 1}
                    />
                </AccordionContent>
            </AccordionPrimitive.Item>
        </AccordionPrimitive.Root>
    )
}

const TreeLeaf = React.forwardRef<
    HTMLDivElement,
    React.HTMLAttributes<HTMLDivElement> & {
        item: TreeDataItem
        level: number
        selectedItemId?: string
        focusedItemId?: string
        onItemFocus?: (id: string) => void
        parentId?: string
        position?: number
        setSize?: number
        handleSelectChange: (item: TreeDataItem | undefined) => void
        defaultLeafIcon?: React.ComponentType<{ className?: string }>
        handleDragStart?: (item: TreeDataItem) => void
        handleDrop?: (item: TreeDataItem) => void
        draggedItem: TreeDataItem | null
        renderItem?: (params: TreeRenderItemParams) => React.ReactNode
    }
>(
    (
        {
            className,
            item,
            level,
            selectedItemId,
            focusedItemId,
            onItemFocus,
            parentId,
            position,
            setSize,
            handleSelectChange,
            defaultLeafIcon,
            handleDragStart,
            handleDrop,
            draggedItem,
            renderItem,
            ...props
        },
        ref
    ) => {
        const [isDragOver, setIsDragOver] = React.useState(false)
        const isSelected = selectedItemId === item.id

        const onDragStart = (e: React.DragEvent) => {
            if (!item.draggable || item.disabled) {
                e.preventDefault()
                return
            }
            e.dataTransfer.setData('text/plain', item.id)
            handleDragStart?.(item)
        }

        const onDragOver = (e: React.DragEvent) => {
            if (item.droppable !== false && !item.disabled && draggedItem && draggedItem.id !== item.id) {
                e.preventDefault()
                setIsDragOver(true)
            }
        }

        const onDragLeave = () => {
            setIsDragOver(false)
        }

        const onDrop = (e: React.DragEvent) => {
            if (item.disabled) return
            e.preventDefault()
            setIsDragOver(false)
            handleDrop?.(item)
        }

        return (
            <div
                ref={ref}
                role="treeitem"
                aria-label={item.name}
                aria-selected={isSelected}
                aria-disabled={item.disabled || undefined}
                aria-level={level + 1}
                aria-posinset={position}
                aria-setsize={setSize}
                data-tree-item={item.id}
                data-tree-parent={parentId}
                tabIndex={focusedItemId === item.id ? 0 : -1}
                onFocus={event => { if (event.target === event.currentTarget) onItemFocus?.(item.id) }}
                onKeyDown={handleTreeKeyDown}
                className={cn(
                    'ml-5 flex text-left items-center py-2 cursor-pointer before:right-1',
                    treeVariants(),
                    className,
                    isSelected && selectedTreeVariants(),
                    isDragOver && dragOverVariants(),
                    item.disabled && 'opacity-50 cursor-not-allowed pointer-events-none',
                    item.className
                )}
                onClick={event => {
                    if (item.disabled) return
                    focusClickedRow(event)
                    handleSelectChange(item)
                    item.onClick?.()
                }}
                draggable={!!item.draggable && !item.disabled}
                onDragStart={onDragStart}
                onDragOver={onDragOver}
                onDragLeave={onDragLeave}
                onDrop={onDrop}
                {...props}
            >
                {renderItem ? (
                    <>
                        <div className="h-4 w-4 shrink-0 mr-1" />
                        {renderItem({
                            item,
                            level,
                            isLeaf: true,
                            isSelected,
                            hasChildren: false,
                        })}
                    </>
                ) : (
                    <>
                        <TreeIcon
                            item={item}
                            isSelected={isSelected}
                            default={defaultLeafIcon}
                        />
                        <span className="flex-grow text-sm truncate">{item.name}</span>
                        <TreeActions isSelected={isSelected && !item.disabled}>
                            {item.actions}
                        </TreeActions>
                    </>
                )}
            </div>
        )
    }
)
TreeLeaf.displayName = 'TreeLeaf'

const AccordionTrigger = React.forwardRef<
    React.ElementRef<typeof AccordionPrimitive.Trigger>,
    React.ComponentPropsWithoutRef<typeof AccordionPrimitive.Trigger>
>(({ className, children, ...props }, ref) => (
    <AccordionPrimitive.Header role="presentation">
        <AccordionPrimitive.Trigger
            ref={ref}
            className={cn(
                'flex flex-1 w-full items-center py-2 transition-all first:[&[data-state=open]>svg]:first-of-type:rotate-90',
                className
            )}
            {...props}
        >
            <IconChevronRight className="h-4 w-4 shrink-0 transition-transform duration-200 text-accent-foreground/50 mr-1" />
            {children}
        </AccordionPrimitive.Trigger>
    </AccordionPrimitive.Header>
))
AccordionTrigger.displayName = AccordionPrimitive.Trigger.displayName

const AccordionContent = React.forwardRef<
    React.ElementRef<typeof AccordionPrimitive.Content>,
    React.ComponentPropsWithoutRef<typeof AccordionPrimitive.Content>
>(({ className, children, ...props }, ref) => (
    <AccordionPrimitive.Content
        ref={ref}
        className={cn(
            'overflow-hidden text-sm transition-all data-[state=closed]:animate-accordion-up data-[state=open]:animate-accordion-down',
            className
        )}
        {...props}
    >
        <div className="pb-1 pt-0">{children}</div>
    </AccordionPrimitive.Content>
))
AccordionContent.displayName = AccordionPrimitive.Content.displayName

const TreeIcon = ({
    item,
    isOpen,
    isSelected,
    default: defaultIcon
}: {
    item: TreeDataItem
    isOpen?: boolean
    isSelected?: boolean
    default?: React.ComponentType<{ className?: string }>
}) => {
    let Icon: React.ComponentType<{ className?: string }> | undefined = defaultIcon
    if (isSelected && item.selectedIcon) {
        Icon = item.selectedIcon
    } else if (isOpen && item.openIcon) {
        Icon = item.openIcon
    } else if (item.icon) {
        Icon = item.icon
    }
    return Icon ? (
        <Icon className="h-4 w-4 shrink-0 mr-2" />
    ) : (
        <></>
    )
}

const TreeActions = ({
    children,
    isSelected
}: {
    children: React.ReactNode
    isSelected: boolean
}) => {
    return (
        <div
            className={cn(
                isSelected ? 'block' : 'hidden',
                'absolute right-3 group-hover:block'
            )}
        >
            {children}
        </div>
    )
}

export { 
    TreeView, 
    type TreeDataItem, 
    type TreeRenderItemParams,
    AccordionTrigger,
    AccordionContent,
    TreeLeaf,
    TreeNode,
    TreeItem
}
