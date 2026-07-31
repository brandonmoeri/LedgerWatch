import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { accountsApi } from '../api/accounts';
import type { Account } from '../types/account';

export const fetchAccounts = createAsyncThunk(
    'accounts/fetchAll',
    async () => accountsApi.getAll()
);

interface AccountsState {
    items: Account[];
    status: 'idle' | 'loading' | 'succeeded' | 'failed';
    error: string | null;
}

const initialState: AccountsState = {
    items: [],
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
            });
    },
});

export default accountsSlice.reducer;