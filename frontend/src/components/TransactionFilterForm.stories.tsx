import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { fn } from 'storybook/test';
import TransactionFilterForm from './TransactionFilterForm';
import type { TransactionFilterFormProps } from './TransactionFilterForm';
import type { TransactionStatus, TransactionType } from '../types/transaction';

const SORT_OPTIONS = [
  { value: 'createdAt,desc', label: 'Newest first' },
  { value: 'createdAt,asc', label: 'Oldest first' },
  { value: 'amount,desc', label: 'Amount (high to low)' },
  { value: 'amount,asc', label: 'Amount (low to high)' },
];

const meta = {
  title: 'Components/TransactionFilterForm',
  component: TransactionFilterForm,
  parameters: {
    layout: 'padded',
  },
  args: {
    typeFilter: '',
    onTypeFilterChange: fn(),
    statusFilter: '',
    onStatusFilterChange: fn(),
    fromDate: '',
    onFromDateChange: fn(),
    toDate: '',
    onToDateChange: fn(),
    sort: 'createdAt,desc',
    onSortChange: fn(),
    sortOptions: SORT_OPTIONS,
    onSubmit: fn((e) => e.preventDefault()),
  },
} satisfies Meta<typeof TransactionFilterForm>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Empty: Story = {};

export const WithActiveFilters: Story = {
  args: {
    typeFilter: 'DEBIT',
    statusFilter: 'PENDING',
    fromDate: '2026-08-01',
    toDate: '2026-08-31',
    sort: 'amount,desc',
  },
};

export const InvertedDateRange: Story = {
  name: 'From date after To date',
  args: {
    fromDate: '2026-09-15',
    toDate: '2026-09-01',
  },
};

export const Disabled: Story = {
  name: 'Disabled while a search is in flight',
  args: {
    disabled: true,
    typeFilter: 'CREDIT',
    statusFilter: 'POSTED',
  },
};

export const NoSortOptions: Story = {
  name: 'No sort options available',
  args: {
    sortOptions: [],
    sort: '',
  },
};

/**
 * A fully interactive version backed by local component state, so filters
 * can actually be changed in the Storybook canvas.
 */
export const Interactive: Story = {
  render: (args) => <InteractiveFilterForm {...args} />,
};

function InteractiveFilterForm(args: TransactionFilterFormProps) {
  const [typeFilter, setTypeFilter] = useState<TransactionType | ''>(args.typeFilter);
  const [statusFilter, setStatusFilter] = useState<TransactionStatus | ''>(args.statusFilter);
  const [fromDate, setFromDate] = useState(args.fromDate);
  const [toDate, setToDate] = useState(args.toDate);
  const [sort, setSort] = useState(args.sort);

  return (
    <TransactionFilterForm
      {...args}
      typeFilter={typeFilter}
      onTypeFilterChange={setTypeFilter}
      statusFilter={statusFilter}
      onStatusFilterChange={setStatusFilter}
      fromDate={fromDate}
      onFromDateChange={setFromDate}
      toDate={toDate}
      onToDateChange={setToDate}
      sort={sort}
      onSortChange={setSort}
    />
  );
}
