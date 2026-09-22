import { configureStore } from '@reduxjs/toolkit';
import accountsReducer from './accountsSlice';
import transactionsReducer from './transactionsSlice';

export const store = configureStore({
    reducer: {
        accounts: accountsReducer,
        transactions: transactionsReducer,
    },
});

export type RootState = ReturnType<typeof store.getState>;
export type AppDispatch = typeof store.dispatch;