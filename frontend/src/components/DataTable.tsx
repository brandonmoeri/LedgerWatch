import type { ReactNode } from 'react';

export interface DataTableColumn<T> {
  key: string;
  header: ReactNode;
  render: (item: T) => ReactNode;
}

export type DataTableStatus = 'idle' | 'loading' | 'succeeded' | 'failed';

interface DataTableProps<T> {
  columns: DataTableColumn<T>[];
  items: T[];
  getRowKey: (item: T) => string;
  status: DataTableStatus;
  error?: string | null;
  page: number;
  totalPages: number;
  onPageChange: (page: number) => void;
}

export default function DataTable<T>({
  columns,
  items,
  getRowKey,
  status,
  error,
  page,
  totalPages,
  onPageChange,
}: DataTableProps<T>) {
  if (status === 'loading') {
    return <p>Loading…</p>;
  }

  if (status === 'failed') {
    return <p>Error: {error}</p>;
  }

  if (status !== 'succeeded') {
    return null;
  }

  return (
    <>
      <table>
        <thead>
          <tr>
            {columns.map((col) => (
              <th key={col.key}>{col.header}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {items.map((item) => (
            <tr key={getRowKey(item)}>
              {columns.map((col) => (
                <td key={col.key}>{col.render(item)}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>

      <div>
        <button onClick={() => onPageChange(Math.max(page - 1, 0))} disabled={page === 0}>
          Previous
        </button>
        <span>Page {page + 1} of {Math.max(totalPages, 1)}</span>
        <button onClick={() => onPageChange(page + 1)} disabled={page + 1 >= totalPages}>
          Next
        </button>
      </div>
    </>
  );
}
