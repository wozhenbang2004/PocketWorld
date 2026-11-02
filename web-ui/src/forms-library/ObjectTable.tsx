import { useMemo, useState, type ReactNode } from 'react';
import { flexRender, getCoreRowModel, getSortedRowModel, useReactTable, type ColumnDef, type SortingState } from '@tanstack/react-table';
import { RiArrowDownSLine } from '@remixicon/react';
import { Table, TableBody, TableCell, TableColumn, TableHeader, TableRow } from './boardui/components/base/table/table';

/** PW-35. Product-controlled adaptation of BoardUI DataTableExample.
 * Retains TanStack sorting + original React Aria Table collection primitives,
 * column/header/row/cell structure and compact visual recipe. The entire
 * seeded customer catalogue, purchase editing and cosmetic invoice actions
 * were removed. Supplied rows are the only records rendered.
 */
export interface ObjectColumn<T> { id: string; label: string; render: (row: T) => ReactNode; sortValue?: (row: T) => string | number; secondary?: boolean; }
export function ObjectTable<T extends object>({ label, rows, columns, getRowId, onRowOpen, empty = '尚无可显示的记录' }: {
  label: string; rows: T[]; columns: ObjectColumn<T>[]; getRowId: (row: T) => string; onRowOpen?: (row: T) => void; empty?: string;
}) {
  const [sorting, setSorting] = useState<SortingState>([]);
  const definitions = useMemo<ColumnDef<T>[]>(() => columns.map(column => ({
    id: column.id, header: column.label, accessorFn: row => column.sortValue?.(row) ?? '',
    enableSorting: Boolean(column.sortValue), cell: info => column.render(info.row.original),
  })), [columns]);
  const table = useReactTable({ data: rows, columns: definitions, state: { sorting }, onSortingChange: setSorting, getRowId, getCoreRowModel: getCoreRowModel(), getSortedRowModel: getSortedRowModel() });
  return <section className="pw-object-table" aria-label={label}>
    <Table aria-label={label} size="sm" selectionMode="none" onRowAction={onRowOpen ? key => { const row = table.getRowModel().rows.find(x => x.id === String(key)); if (row) onRowOpen(row.original); } : undefined}>
      <TableHeader>{table.getFlatHeaders().map(header => <TableColumn key={header.id} id={header.id} isRowHeader={header.index === 0} className={columns[header.index]?.secondary ? 'pw-secondary-column' : undefined}>
        {header.column.getCanSort() ? <button type="button" className="pw-table-sort" onClick={header.column.getToggleSortingHandler()}>
          {flexRender(header.column.columnDef.header, header.getContext())}<RiArrowDownSLine size={16} aria-hidden className={header.column.getIsSorted() === 'asc' ? 'pw-sort-ascending' : ''} />
        </button> : flexRender(header.column.columnDef.header, header.getContext())}
      </TableColumn>)}</TableHeader>
      <TableBody renderEmptyState={() => <p className="pw-table-empty">{empty}</p>}>
        {table.getRowModel().rows.map(row => <TableRow key={row.id} id={row.id} className={onRowOpen ? 'pw-action-row' : undefined}>
          {row.getVisibleCells().map((cell, index) => <TableCell key={cell.id} className={columns[index]?.secondary ? 'pw-secondary-column' : undefined}>{flexRender(cell.column.columnDef.cell, cell.getContext())}</TableCell>)}
        </TableRow>)}
      </TableBody>
    </Table>
  </section>;
}
