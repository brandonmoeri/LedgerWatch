import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { dashboardApi } from '../api/dashboard';
import type { DashboardQuery, DashboardSummary } from '../types/dashboard';

export const fetchDashboardSummary = createAsyncThunk(
    'dashboard/fetchSummary',
    async (query: DashboardQuery = {}) => dashboardApi.getSummary(query)
);

interface DashboardState {
    summary: DashboardSummary | null;
    status: 'idle' | 'loading' | 'succeeded' | 'failed';
    error: string | null;
}

const initialState: DashboardState = {
    summary: null,
    status: 'idle',
    error: null
};

const dashboardSlice = createSlice({
    name: 'dashboard',
    initialState,
    reducers: {},
    extraReducers: (builder) => {
        builder
            .addCase(fetchDashboardSummary.pending, (state) => {
                state.status = 'loading';
                state.error = null;
            })
            .addCase(fetchDashboardSummary.fulfilled, (state, action) => {
                state.status = 'succeeded';
                state.summary = action.payload;
            })
            .addCase(fetchDashboardSummary.rejected, (state, action) => {
                state.status = 'failed';
                state.error = action.error.message ?? 'Failed to load dashboard summary';
            })
    },
});

export default dashboardSlice.reducer;
