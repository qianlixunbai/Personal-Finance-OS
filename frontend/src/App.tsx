import { Routes, Route, Navigate } from 'react-router-dom';
import Login from './pages/Login';
import Register from './pages/Register';
import Dashboard from './pages/Dashboard';
import Accounts from './pages/Accounts';
import Assets from './pages/Assets';
import Transactions from './pages/Transactions';
import TransactionImport from './pages/TransactionImport';
import Investments from './pages/Investments';
import Layout from './components/Layout';

function PrivateRoute({ children }: { children: React.ReactNode }) {
    const token = localStorage.getItem('token');
    return token ? <>{children}</> : <Navigate to="/login" />;
}

export default function App() {
    return (
        <Routes>
            <Route path="/login" element={<Login />} />
            <Route path="/register" element={<Register />} />
            <Route path="/" element={<PrivateRoute><Layout /></PrivateRoute>}>
                <Route index element={<Dashboard />} />
                <Route path="accounts" element={<Accounts />} />
                <Route path="assets" element={<Assets />} />
                <Route path="transactions" element={<Transactions />} />
                <Route path="transactions/import" element={<TransactionImport />} />
                <Route path="investments" element={<Investments />} />
            </Route>
        </Routes>
    );
}
