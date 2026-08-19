import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { auth } from './auth';

export default function RequireAuth() {
    const location = useLocation();

    if (!auth.isAuthenticated()) {
        return <Navigate to="/login" replace state={{ from: location.pathname }} />;
    }

    return <Outlet />;
}
