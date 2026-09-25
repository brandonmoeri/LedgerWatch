import { useCallback, useEffect, useMemo, useState } from 'react';
import type { FormEvent } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import { fetchTransactions } from '../store/transactionsSlice';
import type { AppDispatch, RootState } from '../store/store';
import type { Transaction, TransactionStatus, TransactionType } from '../types/transaction';
import DataTable from '../components/DataTable';
import type { DataTableColumn, DataTableSort } from '../components/DataTable';
import { useStableCallback } from '../hooks/useStableCallback';

const COLUMNS: DataTableColumn<Transaction>[] = [
  { key: 'date', header: 'Date', render: (tx) => new Date(tx.createdAt).toLocaleDateString(), sortField: 'createdAt' },
  { key: 'type', header: 'Type', render: (tx) => tx.type },
  { key: 'status', header: 'Status', render: (tx) => tx.status },
  { key: 'amount', header: 'Amount', render: (tx) => tx.amount, sortField: 'amount' },
  { key: 'description', header: 'Description', render: (tx) => tx.description },
];

const SORT_OPTIONS = [
  { value: 'createdAt,desc', label: 'Newest first' },
  { value: 'createdAt,asc', label: 'Oldest first' },
  { value: 'amount,desc', label: 'Amount (high to low)' },
  { value: 'amount,asc', label: 'Amount (low to high)' },
];

function toStartOfDayInstant(date: string): string {
  return `${date}T00:00:00.000Z`;
}

function toEndOfDayInstant(date: string): string {
  return `${date}T23:59:59.999Z`;
}

export default function TransactionsPage() {
  const dispatch = useDispatch<AppDispatch>();
  const { items, status, error, page, totalPages } = useSelector((s: RootState) => s.transactions);

  const [typeFilter, setTypeFilter] = useState<TransactionType | ''>('');
  const [statusFilter, setStatusFilter] = useState<TransactionStatus | ''>('');
  const [fromDate, setFromDate] = useState('');
  const [toDate, setToDate] = useState('');
  const [sort, setSort] = useState('createdAt,desc');

  useEffect(() => {
    if (status === 'idle') {
      dispatch(fetchTransactions({ page: 0, sort }));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status, dispatch]);

  const runSearch = (nextPage: number, nextSort: string) => {
    dispatch(fetchTransactions({
      page: nextPage,
      sort: nextSort,
      type: typeFilter || undefined,
      status: statusFilter || undefined,
      createdFrom: fromDate ? toStartOfDayInstant(fromDate) : undefined,
      createdTo: toDate ? toEndOfDayInstant(toDate) : undefined,
    }));
  };

  const handleSearchSubmit = (e: FormEvent) => {
    e.preventDefault();
    runSearch(0, sort);
  };

  const handleSortChange = (value: string) => {
    setSort(value);
    runSearch(0, value);
  };

  const [sortField, sortDirectionRaw] = sort.split(',');
  const sortDirection = sortDirectionRaw === 'asc' ? 'asc' : 'desc';

  const handleHeaderSort = useStableCallback((field: string) => {
    const nextDirection = field === sortField && sortDirection === 'asc' ? 'desc' : 'asc';
    handleSortChange(`${field},${nextDirection}`);
  });

  // Stable identities so DataTable (React.memo'd) doesn't re-render its rows
  // whenever unrelated page state (e.g. filter text) changes.
  const handlePageChange = useStableCallback((nextPage: number) => runSearch(nextPage, sort));
  const handleRetry = useStableCallback(() => runSearch(page, sort));
  const getRowKey = useCallback((tx: Transaction) => tx.id, []);
  const tableSort = useMemo<DataTableSort>(
    () => ({ field: sortField, direction: sortDirection }),
    [sortField, sortDirection]
  );

  return (
    <div>
      <h1>Transactions</h1>

      <form onSubmit={handleSearchSubmit}>
        <label>
          Type
          <select
            value={typeFilter}
            onChange={(e) => setTypeFilter(e.target.value as TransactionType | '')}
          >
            <option value="">All</option>
            <option value="CREDIT">Credit</option>
            <option value="DEBIT">Debit</option>
          </select>
        </label>
        <label>
          Status
          <select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value as TransactionStatus | '')}
          >
            <option value="">All</option>
            <option value="PENDING">Pending</option>
            <option value="POSTED">Posted</option>
            <option value="VOIDED">Voided</option>
          </select>
        </label>
        <label>
          From
          <input
            type="date"
            value={fromDate}
            onChange={(e) => setFromDate(e.target.value)}
          />
        </label>
        <label>
          To
          <input
            type="date"
            value={toDate}
            onChange={(e) => setToDate(e.target.value)}
          />
        </label>
        <label>
          Sort
          <select value={sort} onChange={(e) => handleSortChange(e.target.value)}>
            {SORT_OPTIONS.map((opt) => (
              <option key={opt.value} value={opt.value}>{opt.label}</option>
            ))}
          </select>
        </label>
        <button type="submit">Search</button>
      </form>

      <DataTable
        caption="Transactions"
        columns={COLUMNS}
        items={items}
        getRowKey={getRowKey}
        status={status}
        error={error}
        page={page}
        totalPages={totalPages}
        onPageChange={handlePageChange}
        onRetry={handleRetry}
        emptyMessage="No transactions found."
        sort={tableSort}
        onSortChange={handleHeaderSort}
      />
    </div>
  );
}
