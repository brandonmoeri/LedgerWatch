import { memo } from 'react';
import type { ReactNode } from 'react';
import Skeleton from './Skeleton';

export type SortDirection = 'asc' | 'desc';

export interface DataTableSort {
  field: string;
  direction: SortDirection;
}

export interface DataTableColumn<T> {
  key: string;
  header: ReactNode;
  render: (item: T) => ReactNode;
  /** API field this column sorts by. Omit to leave the column unsortable. */
  sortField?: string;
}

export type DataTableStatus = 'idle' | 'loading' | 'succeeded' | 'failed';

interface DataTableProps<T> {
  /** Accessible name for the table, used as a visually-hidden <caption>. */
  caption: string;
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
  sort?: DataTableSort;
  onSortChange?: (field: string) => void;
}

const SKELETON_ROWS = 5;

function ariaSortFor(sortField: string | undefined, sort?: DataTableSort): 'ascending' | 'descending' | 'none' | undefined {
  if (!sortField) {
    return undefined;
  }
  if (sort?.field !== sortField) {
    return 'none';
  }
  return sort.direction === 'asc' ? 'ascending' : 'descending';
}

function DataTableInner<T>({
  caption,
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
  sort,
  onSortChange,
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
        <caption className="sr-only">{caption}</caption>
        <thead>
          <tr>
            {columns.map((col) => {
              const sortable = Boolean(col.sortField && onSortChange);
              const ariaSort = ariaSortFor(col.sortField, sort);
              return (
                <th key={col.key} scope="col" aria-sort={ariaSort}>
                  {sortable ? (
                    <button
                      type="button"
                      className="sort-button"
                      onClick={() => onSortChange!(col.sortField!)}
                    >
                      {col.header}
                      <span aria-hidden="true" className="sort-indicator">
                        {ariaSort === 'ascending' ? ' ▲' : ariaSort === 'descending' ? ' ▼' : ''}
                      </span>
                    </button>
                  ) : (
                    col.header
                  )}
                </th>
              );
            })}
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

      <nav className="pagination" aria-label="Pagination">
        <button onClick={() => onPageChange(Math.max(page - 1, 0))} disabled={page === 0}>
          Previous
        </button>
        <span aria-live="polite" aria-atomic="true">Page {page + 1} of {Math.max(totalPages, 1)}</span>
        <button onClick={() => onPageChange(page + 1)} disabled={page + 1 >= totalPages}>
          Next
        </button>
      </nav>
    </>
  );
}

// memo() erases the generic signature, so it's cast back to DataTableInner's
// type. Consumers still get a properly generic <DataTable<T> /> component.
const DataTable = memo(DataTableInner) as typeof DataTableInner;

export default DataTable;
