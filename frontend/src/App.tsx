import { Provider } from 'react-redux';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { store } from './store/store';
import AccountDetailPage from './pages/AccountDetailPage';
import CreateAccountPage from './pages/CreateAccountPage';
import AccountsDashboardPage from './pages/AccountsDashboardPage';

export default function App() {
  return (
    <Provider store={store}>
      <BrowserRouter>
        <Routes>
          <Route path="/" element={<Navigate to="/accounts" replace />} />
          <Route path="/accounts" element={<AccountsDashboardPage />} />
          <Route path="/accounts/new" element={<CreateAccountPage />} />
          <Route path="/accounts/:id" element={<AccountDetailPage />} />
        </Routes>
      </BrowserRouter>
    </Provider>
  )
}