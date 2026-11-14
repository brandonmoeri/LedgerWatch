import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { fraudFlagsApi } from '../api/fraudFlags';
import type { FraudFlag } from '../types/fraud';

export const fetchAccountFlags = createAsyncThunk(
    'accountFlags/fetch',
    async ({ accountId, page = 0 }: { accountId: string; page?: number }) =>
        fraudFlagsApi.getByAccount(accountId, { page })
);

export interface AccountFlagsState {
    /** The account the rest of this state belongs to. */
    accountId: string | null;
    items: FraudFlag[];
    page: number;
    totalPages: number;
    status: 'idle' | 'loading' | 'succeeded' | 'failed';
    error: string | null;
    /** The latest request. Answers to earlier ones (another account or page) are dropped. */
    requestId: string | null;
}

const initialState: AccountFlagsState = {
    accountId: null,
    items: [],
    page: 0,
    totalPages: 0,
    status: 'idle',
    error: null,
    requestId: null,
};

const accountFlagsSlice = createSlice({
    name: 'accountFlags',
    initialState,
    reducers: {},
    extraReducers: (builder) => {
        builder
            .addCase(fetchAccountFlags.pending, (state, action) => {
                if (state.accountId !== action.meta.arg.accountId) {
                    state.items = [];
                    state.page = 0;
                    state.totalPages = 0;
                }
                state.accountId = action.meta.arg.accountId;
                state.requestId = action.meta.requestId;
                state.status = 'loading';
                state.error = null;
            })
            .addCase(fetchAccountFlags.fulfilled, (state, action) => {
                if (action.meta.requestId !== state.requestId) return;
                state.status = 'succeeded';
                state.items = action.payload.content;
                state.page = action.payload.page.number;
                state.totalPages = action.payload.page.totalPages;
            })
            .addCase(fetchAccountFlags.rejected, (state, action) => {
                if (action.meta.requestId !== state.requestId) return;
                state.status = 'failed';
                state.error = action.error.message ?? 'Failed to load fraud flags';
            });
    },
});

export default accountFlagsSlice.reducer;
