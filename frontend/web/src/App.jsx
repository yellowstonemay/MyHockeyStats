import React from 'react'
import { BrowserRouter as Router, Routes, Route, Navigate, useLocation } from 'react-router-dom'
import { AuthProvider } from './lib/AuthContext'
import Navigation from './components/Navigation'
import NotificationBanner from './components/NotificationBanner'
import { ProtectedRoute } from './components/ProtectedRoute'
import Home from './pages/Home'
import SignUp from './pages/SignUp'
import SignIn from './pages/SignIn'
import Dashboard from './pages/Dashboard'
import Profile from './pages/Profile'
import Players from './pages/Players'
import OAuthCallback from './pages/OAuthCallback'
import VerifyEmail from './pages/VerifyEmail'
import ForgotPassword from './pages/ForgotPassword'
import ResetPassword from './pages/ResetPassword'
import ParentPlayerHistoryPage from './pages/ParentPlayerHistoryPage'
import IntegrationAdminPage from './pages/IntegrationAdminPage'
import AdminPlayersPage from './pages/AdminPlayersPage'

function AppRoutes() {
  const location = useLocation()
  const hideGlobalNavigation = [
    '/', '/signin', '/signup', '/oauth/callback', '/verify-email',
    '/forgot-password', '/reset-password',
  ].includes(location.pathname)

  return (
    <>
      {!hideGlobalNavigation && <Navigation />}
      {!hideGlobalNavigation && <NotificationBanner />}
      <Routes>
        <Route path="/" element={<Home />} />
        <Route path="/signup" element={<SignUp />} />
        <Route path="/signin" element={<SignIn />} />
        <Route path="/oauth/callback" element={<OAuthCallback />} />
        <Route path="/verify-email" element={<VerifyEmail />} />
        <Route path="/forgot-password" element={<ForgotPassword />} />
        <Route path="/reset-password" element={<ResetPassword />} />
        <Route
          path="/players"
          element={
            <ProtectedRoute>
              <Players />
            </ProtectedRoute>
          }
        />
        <Route
          path="/dashboard"
          element={
            <ProtectedRoute>
              <Dashboard />
            </ProtectedRoute>
          }
        />
        <Route
          path="/profile"
          element={
            <ProtectedRoute>
              <Profile />
            </ProtectedRoute>
          }
        />
        <Route
          path="/parent-player-history"
          element={
            <ProtectedRoute>
              <ParentPlayerHistoryPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/integration-admin"
          element={
            <ProtectedRoute>
              <IntegrationAdminPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/admin"
          element={
            <ProtectedRoute>
              <AdminPlayersPage />
            </ProtectedRoute>
          }
        />
        <Route path="*" element={<Navigate to="/" />} />
      </Routes>
    </>
  )
}

export default function App() {
  return (
    <Router>
      <AuthProvider>
        <AppRoutes />
      </AuthProvider>
    </Router>
  )
}
