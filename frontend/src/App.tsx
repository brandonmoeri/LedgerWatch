import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import AccountDetailPage from './pages/AccountDetailPage';
import CreateAccountPage from './pages/CreateAccountPage';

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<Navigate to="/accounts/new" replace />} />
        <Route path="/accounts/new" element={<CreateAccountPage />} />
        <Route path="/accounts/:id" element={<AccountDetailPage />} />
      </Routes>
    </BrowserRouter>
  )
}