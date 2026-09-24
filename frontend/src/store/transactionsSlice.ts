import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { transactionsApi } from '../api/transactions';
import type { Transaction, TransactionsQuery } from '../types/transaction';

export const fetchTransactions = createAsyncThunk(
    'transactions/fetchAll',
    async (query: TransactionsQuery = {}) => transactionsApi.getAll(query)
);

interface TransactionsState {
    items: Transaction[];
    page: number;
    size: number;
    totalElements: number;
    totalPages: number;
    status: 'idle' | 'loading' | 'succeeded' | 'failed';
    error: string | null;
}

const initialState: TransactionsState = {
    items: [],
    page: 0,
    size: 20,
    totalElements: 0,
    totalPages: 0,
    status: 'idle',
    error: null
};

const transactionsSlice = createSlice({
    name: 'transactions',
    initialState,
    reducers: {},
    extraReducers: (builder) => {
        builder
            .addCase(fetchTransactions.pending, (state) => {
                state.status = 'loading';
                state.error = null;
            })
            .addCase(fetchTransactions.fulfilled, (state, action) => {
                state.status = 'succeeded';
                state.items = action.payload.content;
                state.page = action.payload.page.number;
                state.size = action.payload.page.size;
                state.totalElements = action.payload.page.totalElements;
                state.totalPages = action.payload.page.totalPages;
            })
            .addCase(fetchTransactions.rejected, (state, action) => {
                state.status = 'failed';
                state.error = action.error.message ?? 'Failed to load transactions';
            })
    },
});

export default transactionsSlice.reducer;
