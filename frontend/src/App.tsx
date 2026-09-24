import { useEffect, useRef } from 'react';
import { Provider } from 'react-redux';
import { BrowserRouter, Routes, Route, Navigate, useLocation } from 'react-router-dom';
import { store } from './store/store';
import AccountDetailPage from './pages/AccountDetailPage';
import CreateAccountPage from './pages/CreateAccountPage';
import AccountsDashboardPage from './pages/AccountsDashboardPage';
import TransactionsPage from './pages/TransactionsPage';
import LoginPage from './pages/LoginPage';
import RequireAuth from './auth/RequireAuth';

function AppRoutes() {
  const mainRef = useRef<HTMLElement>(null);
  const location = useLocation();
  const isFirstRender = useRef(true);

  useEffect(() => {
    if (isFirstRender.current) {
      isFirstRender.current = false;
      return;
    }
    mainRef.current?.focus();
  }, [location.pathname]);

  return (
    <main ref={mainRef} tabIndex={-1}>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route element={<RequireAuth />}>
          <Route path="/" element={<Navigate to="/accounts" replace />} />
          <Route path="/accounts" element={<AccountsDashboardPage />} />
          <Route path="/accounts/new" element={<CreateAccountPage />} />
          <Route path="/accounts/:id" element={<AccountDetailPage />} />
          <Route path="/transactions" element={<TransactionsPage />} />
        </Route>
      </Routes>
    </main>
  );
}

export default function App() {
  return (
    <Provider store={store}>
      <BrowserRouter>
        <AppRoutes />
      </BrowserRouter>
    </Provider>
  )
}
