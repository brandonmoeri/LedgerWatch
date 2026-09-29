import type { FormEvent } from 'react';
import type { TransactionStatus, TransactionType } from '../types/transaction';

export interface TransactionSortOption {
  value: string;
  label: string;
}

export interface TransactionFilterFormProps {
  typeFilter: TransactionType | '';
  onTypeFilterChange: (value: TransactionType | '') => void;
  statusFilter: TransactionStatus | '';
  onStatusFilterChange: (value: TransactionStatus | '') => void;
  fromDate: string;
  onFromDateChange: (value: string) => void;
  toDate: string;
  onToDateChange: (value: string) => void;
  sort: string;
  onSortChange: (value: string) => void;
  sortOptions: TransactionSortOption[];
  onSubmit: (e: FormEvent) => void;
  /** Disables all controls, e.g. while a search is already in flight. */
  disabled?: boolean;
}

export default function TransactionFilterForm({
  typeFilter,
  onTypeFilterChange,
  statusFilter,
  onStatusFilterChange,
  fromDate,
  onFromDateChange,
  toDate,
  onToDateChange,
  sort,
  onSortChange,
  sortOptions,
  onSubmit,
  disabled = false,
}: TransactionFilterFormProps) {
  return (
    <form onSubmit={onSubmit}>
      <label>
        Type
        <select
          value={typeFilter}
          disabled={disabled}
          onChange={(e) => onTypeFilterChange(e.target.value as TransactionType | '')}
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
          disabled={disabled}
          onChange={(e) => onStatusFilterChange(e.target.value as TransactionStatus | '')}
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
          disabled={disabled}
          onChange={(e) => onFromDateChange(e.target.value)}
        />
      </label>
      <label>
        To
        <input
          type="date"
          value={toDate}
          disabled={disabled}
          onChange={(e) => onToDateChange(e.target.value)}
        />
      </label>
      <label>
        Sort
        <select value={sort} disabled={disabled} onChange={(e) => onSortChange(e.target.value)}>
          {sortOptions.map((opt) => (
            <option key={opt.value} value={opt.value}>{opt.label}</option>
          ))}
        </select>
      </label>
      <button type="submit" disabled={disabled}>Search</button>
    </form>
  );
}
