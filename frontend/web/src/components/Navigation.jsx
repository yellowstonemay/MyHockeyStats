import React from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../lib/AuthContext'
import { Button } from './Button'
import { Trophy, Settings, LogOut } from 'lucide-react'

export default function Navigation() {
  const navigate = useNavigate()
  const { user, logout } = useAuth()

  const handleLogout = () => {
    logout()
    navigate('/')
  }

  return (
    <nav className="border-b border-slate-200 bg-white shadow-sm sticky top-0 z-50">
      <div className="container flex items-center justify-between h-16">
        <div 
          className="flex items-center space-x-2 cursor-pointer"
          onClick={() => navigate('/')}
        >
          <Trophy className="w-8 h-8 text-primary-600" />
          <h1 className="text-xl sm:text-2xl font-bold text-slate-900">MyHockeyStats</h1>
        </div>
        {user && (
          <div className="flex items-center space-x-2 sm:space-x-4">
            <span className="hidden sm:inline text-slate-600 truncate max-w-[180px]">Welcome, {user.email}</span>
            {user.isAdmin && (
              <button
                onClick={() => navigate('/admin')}
                title="Admin"
                aria-label="Admin"
                className="flex items-center space-x-2 text-slate-600 hover:text-slate-900 transition-colors"
              >
                <Trophy className="w-5 h-5" />
                <span className="hidden sm:inline">Admin</span>
              </button>
            )}
            <button 
              onClick={() => navigate('/profile')}
              title="Profile"
              aria-label="Profile"
              className="flex items-center space-x-2 text-slate-600 hover:text-slate-900 transition-colors"
            >
              <Settings className="w-5 h-5" />
              <span className="hidden sm:inline">Profile</span>
            </button>
            <Button 
              variant="ghost" 
              onClick={handleLogout}
              title="Sign Out"
              aria-label="Sign Out"
              className="flex items-center space-x-2"
            >
              <LogOut className="w-4 h-4" />
              <span className="hidden sm:inline">Sign Out</span>
            </Button>
          </div>
        )}
      </div>
    </nav>
  )
}