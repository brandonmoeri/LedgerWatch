import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { accountsApi } from '../api/accounts';
import type { Account, UpdateAccountRequest } from '../types/account';

export const fetchAccounts = createAsyncThunk(
    'accounts/fetchAll',
    async () => accountsApi.getAll()
);

export const fetchAccountById = createAsyncThunk(
    'accounts/fetchById',
    async (id: string) => accountsApi.getById(id)
);

export const updateAccount = createAsyncThunk(
    'accounts/update',
    async ({ id, body }: { id: string; body: UpdateAccountRequest }) =>
        accountsApi.update(id, body)
);

interface AccountsState {
    items: Account[];
    selectedAccount: Account | null;
    selectedStatus: 'idle' | 'loading' | 'succeeded' | 'failed';
    status: 'idle' | 'loading' | 'succeeded' | 'failed';
    error: string | null;
}

const initialState: AccountsState = {
    items: [],
    selectedAccount: null,
    selectedStatus: 'idle',
    status: 'idle',
    error: null
};

const accountsSlice = createSlice({
    name: 'accounts',
    initialState,
    reducers: {},
    extraReducers: (builder) => {
        builder
            .addCase(fetchAccounts.pending, (state) => {
                state.status = 'loading';
                state.error = null;
            })
            .addCase(fetchAccounts.fulfilled, (state, action) => {
                state.status = 'succeeded';
                state.items = action.payload;
            })
            .addCase(fetchAccounts.rejected, (state, actions) => {
                state.status = 'failed';
                state.error = actions.error.message ?? 'Failed to load accounts';
            })
            .addCase(fetchAccountById.pending, (state) => {
                state.selectedStatus = 'loading';
                state.error = null;
            })
            .addCase(fetchAccountById.fulfilled, (state, action) => {
                state.selectedStatus = 'succeeded';
                state.selectedAccount = action.payload;
            })
            .addCase(fetchAccountById.rejected, (state, action) => {
                state.selectedStatus = 'failed';
                state.error = action.error.message ?? 'Failed to load account';
            })
            .addCase(updateAccount.fulfilled, (state, action) => {
                state.selectedAccount = action.payload;
                const idx = state.items.findIndex((a) => a.id === action.payload.id);
                if (idx !== -1) state.items[idx] = action.payload;
            })
    },
});

export default accountsSlice.reducer;