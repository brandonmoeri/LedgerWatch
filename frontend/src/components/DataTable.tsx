import type { ReactNode } from 'react';
import Skeleton from './Skeleton';

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
  onRetry?: () => void;
  emptyMessage?: string;
}

const SKELETON_ROWS = 5;

export default function DataTable<T>({
  columns,
  items,
  getRowKey,
  status,
  error,
  page,
  totalPages,
  onPageChange,
  onRetry,
  emptyMessage = 'No results found.',
}: DataTableProps<T>) {
  if (status === 'loading') {
    return (
      <div aria-busy="true" aria-live="polite">
        <span className="sr-only">Loading…</span>
        <div className="skeleton-table">
          {Array.from({ length: SKELETON_ROWS }).map((_, rowIdx) => (
            <div className="skeleton-row" key={rowIdx}>
              {columns.map((col) => (
                <Skeleton key={col.key} />
              ))}
            </div>
          ))}
        </div>
      </div>
    );
  }

  if (status === 'failed') {
    return (
      <div role="alert" className="state-error">
        <p>Error: {error}</p>
        {onRetry && <button onClick={onRetry}>Retry</button>}
      </div>
    );
  }

  if (status !== 'succeeded') {
    return null;
  }

  if (items.length === 0) {
    return <p className="state-empty">{emptyMessage}</p>;
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
