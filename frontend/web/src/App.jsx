import React from 'react'
import { BrowserRouter as Router, Routes, Route, Navigate, useLocation } from 'react-router-dom'
import { AuthProvider } from './lib/AuthContext'
import Navigation from './components/Navigation'
import { ProtectedRoute } from './components/ProtectedRoute'
import Home from './pages/Home'
import SignUp from './pages/SignUp'
import SignIn from './pages/SignIn'
import Dashboard from './pages/Dashboard'
import Profile from './pages/Profile'
import IntegrationHistoryPage from './pages/IntegrationHistoryPage'
import ParentPlayerHistoryPage from './pages/ParentPlayerHistoryPage'
import IntegrationAdminPage from './pages/IntegrationAdminPage'

function AppRoutes() {
  const location = useLocation()
  const hideGlobalNavigation = ['/', '/signin', '/signup'].includes(location.pathname)

  return (
    <>
      {!hideGlobalNavigation && <Navigation />}
      <Routes>
        <Route path="/" element={<Home />} />
        <Route path="/signup" element={<SignUp />} />
        <Route path="/signin" element={<SignIn />} />
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
          path="/integrated-history"
          element={
            <ProtectedRoute>
              <IntegrationHistoryPage />
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
