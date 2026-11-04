import test from 'node:test'
import assert from 'node:assert/strict'
import { treeKeyboardAction, treeTabStop, visibleTreeItems, type TreeKeyboardItem } from '../src/shell-library/adapted/tree/src/tree-keyboard.ts'

const rows: TreeKeyboardItem[] = [
    { id: 'src', expanded: true },
    { id: 'src/lib', parentId: 'src', expanded: false },
    { id: 'src/lib/hidden.ts', parentId: 'src/lib' },
    { id: 'src/main.ts', parentId: 'src' },
    { id: 'README.md' },
    { id: 'disabled.ts', disabled: true },
]

test('tree navigation follows visible rows across nested folders without activation', () => {
    assert.deepEqual(visibleTreeItems(rows).map(item => item.id), ['src', 'src/lib', 'src/main.ts', 'README.md', 'disabled.ts'])
    assert.deepEqual(treeKeyboardAction(rows, 'src/lib', 'ArrowDown'), { type: 'focus', id: 'src/main.ts' })
    assert.deepEqual(treeKeyboardAction(rows, 'src/main.ts', 'ArrowUp'), { type: 'focus', id: 'src/lib' })
    assert.deepEqual(treeKeyboardAction(rows, 'README.md', 'Home'), { type: 'focus', id: 'src' })
    assert.deepEqual(treeKeyboardAction(rows, 'src', 'End'), { type: 'focus', id: 'disabled.ts' })
    assert.deepEqual(treeKeyboardAction(rows, 'src', 'ArrowUp'), { type: 'none' })
    assert.deepEqual(treeKeyboardAction(rows, 'disabled.ts', 'ArrowDown'), { type: 'none' })
})

test('right expands once, then enters the first child; left collapses or goes to the parent', () => {
    assert.deepEqual(treeKeyboardAction(rows, 'src/lib', 'ArrowRight'), { type: 'expand', expanded: true })
    const opened = rows.map(item => item.id === 'src/lib' ? { ...item, expanded: true } : item)
    assert.deepEqual(treeKeyboardAction(opened, 'src/lib', 'ArrowRight'), { type: 'focus', id: 'src/lib/hidden.ts' })
    assert.deepEqual(treeKeyboardAction(opened, 'src/lib/hidden.ts', 'ArrowLeft'), { type: 'focus', id: 'src/lib' })
    assert.deepEqual(treeKeyboardAction(opened, 'src/lib', 'ArrowLeft'), { type: 'expand', expanded: false })
    assert.deepEqual(treeKeyboardAction(rows, 'src/lib', 'ArrowLeft'), { type: 'focus', id: 'src' })
    assert.deepEqual(treeKeyboardAction(rows, 'README.md', 'ArrowLeft'), { type: 'none' })
    assert.deepEqual(treeKeyboardAction(rows, 'README.md', 'ArrowRight'), { type: 'none' })
})

test('Enter and Space activate the focused file, while disabled items remain discoverable', () => {
    for (const key of ['Enter', ' ', 'Spacebar']) {
        assert.deepEqual(treeKeyboardAction(rows, 'src/main.ts', key), { type: 'activate' })
        assert.deepEqual(treeKeyboardAction(rows, 'disabled.ts', key), { type: 'none' })
    }
    assert.equal(treeKeyboardAction(rows, 'README.md', 'Tab'), undefined)
    assert.equal(treeKeyboardAction(rows, 'README.md', 'F10'), undefined, 'context-menu shortcuts keep their original path')
    assert.equal(treeKeyboardAction(rows, 'src/lib/hidden.ts', 'Enter'), undefined)
})

test('one tab stop follows focus, selected entry, or a visible ancestor after a collapse', () => {
    assert.equal(treeTabStop(rows, undefined, 'src/main.ts'), 'src/main.ts')
    assert.equal(treeTabStop(rows, 'README.md', 'src/main.ts'), 'README.md', 'selection must not steal keyboard focus')
    assert.equal(treeTabStop(rows, 'src/lib/hidden.ts', 'README.md'), 'src/lib')
    const closed = rows.map(item => item.id === 'src' ? { ...item, expanded: false } : item)
    assert.deepEqual(visibleTreeItems(closed).map(item => item.id), ['src', 'README.md', 'disabled.ts'])
    assert.equal(treeTabStop(closed, 'src/lib/hidden.ts'), 'src')
    assert.equal(treeTabStop(rows, 'removed.ts', 'src/main.ts'), 'src/main.ts')
    assert.equal(treeTabStop(rows, 'removed.ts', 'removed.ts'), 'src')
    assert.equal(treeTabStop([], 'removed.ts'), undefined)
})
