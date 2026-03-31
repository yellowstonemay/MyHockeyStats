import React, { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../lib/AuthContext'
import { Button } from '../components/Button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../components/Card'
import { Input } from '../components/Input'
import { ArrowLeft } from 'lucide-react'
import SeasonsTab from '../components/PlayerProfile/SeasonsTab'

export default function Profile() {
  const BIRTH_MONTH_YEAR_REGEX = /^(0[1-9]|1[0-2])\/\d{4}$/

  const navigate = useNavigate()
  const { user } = useAuth()
  const [activeTab, setActiveTab] = useState('profile')
  const [fullName, setFullName] = useState(user?.fullName || '')
  const [birthMonthYear, setBirthMonthYear] = useState('')
  const [location, setLocation] = useState('')
  const [position, setPosition] = useState('')
  const [saved, setSaved] = useState(false)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const handleSave = async (e) => {
    e.preventDefault()
    setError('')
    setLoading(true)
    
    try {
      if (!BIRTH_MONTH_YEAR_REGEX.test(birthMonthYear)) {
        throw new Error('Birth month/year must be in MM/YYYY format (example: 05/2011)')
      }

      // TODO: Once API is ready, send profile update
      // const response = await api.updateProfile({ fullName, birthMonthYear, location, position })
      setSaved(true)
      setTimeout(() => setSaved(false), 3000)
    } catch (err) {
      setError(err.message || 'Failed to save profile')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen bg-slate-50">
      {/* Content */}
      <div className="container py-12 max-w-4xl">
        <div className="flex items-center mb-6">
          <button
            onClick={() => navigate('/dashboard')}
            className="flex items-center space-x-2 text-primary-600 hover:text-primary-700 transition-colors"
          >
            <ArrowLeft className="w-5 h-5" />
            <span>Back to Dashboard</span>
          </button>
        </div>

        {/* Tab Navigation */}
        <div className="flex gap-4 mb-6 border-b border-slate-200">
          <button
            onClick={() => setActiveTab('profile')}
            className={`px-4 py-2 font-medium border-b-2 transition-colors ${
              activeTab === 'profile'
                ? 'border-primary-600 text-primary-600'
                : 'border-transparent text-slate-600 hover:text-slate-900'
            }`}
          >
            Profile Settings
          </button>
          <button
            onClick={() => setActiveTab('seasons')}
            className={`px-4 py-2 font-medium border-b-2 transition-colors ${
              activeTab === 'seasons'
                ? 'border-primary-600 text-primary-600'
                : 'border-transparent text-slate-600 hover:text-slate-900'
            }`}
          >
            Game History & Stats
          </button>
        </div>

        {/* Profile Settings Tab */}
        {activeTab === 'profile' && (
          <Card>
            <CardHeader>
              <CardTitle>Edit Your Profile</CardTitle>
              <CardDescription>Keep your information up to date</CardDescription>
            </CardHeader>
            <CardContent>
              {saved && (
                <div className="p-3 bg-green-100 border border-green-400 text-green-700 rounded-md mb-6 text-sm">
                  ✓ Profile saved successfully
                </div>
              )}
              
              {error && (
                <div className="p-3 bg-red-100 border border-red-400 text-red-700 rounded-md mb-6 text-sm">
                  {error}
                </div>
              )}

              <form onSubmit={handleSave} className="space-y-6">
                <div>
                  <label className="label">Email</label>
                  <Input
                    type="email"
                    value={user?.email || ''}
                    disabled
                    className="bg-slate-100"
                  />
                  <p className="text-xs text-slate-500 mt-1">Email cannot be changed</p>
                </div>

                <div>
                  <label className="label">Full Name</label>
                  <Input
                    type="text"
                    placeholder="Your full name"
                    value={fullName}
                    onChange={(e) => setFullName(e.target.value)}
                    disabled={loading}
                  />
                </div>

                <div>
                  <label className="label">Birth Month/Year</label>
                  <Input
                    type="text"
                    placeholder="MM/YYYY (example: 05/2011)"
                    value={birthMonthYear}
                    onChange={(e) => setBirthMonthYear(e.target.value)}
                    disabled={loading}
                  />
                  <p className="text-xs text-slate-500 mt-1">Use month/year only. Day is not accepted.</p>
                </div>

                <div>
                  <label className="label">Location</label>
                  <Input
                    type="text"
                    placeholder="City, State"
                    value={location}
                    onChange={(e) => setLocation(e.target.value)}
                    disabled={loading}
                  />
                </div>

                <div>
                  <label className="label">Position</label>
                  <select
                    value={position}
                    onChange={(e) => setPosition(e.target.value)}
                    disabled={loading}
                    className="input appearance-none"
                  >
                    <option value="">Select a position</option>
                    <option value="forward">Forward</option>
                    <option value="defense">Defense</option>
                    <option value="goaltender">Goaltender</option>
                  </select>
                </div>

                <div className="flex gap-4">
                  <Button 
                    type="submit" 
                    className="flex-1"
                    disabled={loading}
                  >
                    {loading ? 'Saving...' : 'Save Changes'}
                  </Button>
                  <Button
                    type="button"
                    variant="outline"
                    className="flex-1"
                    onClick={() => navigate('/dashboard')}
                    disabled={loading}
                  >
                    Cancel
                  </Button>
                </div>
              </form>
            </CardContent>
          </Card>
        )}

        {/* Seasons Tab */}
        {activeTab === 'seasons' && (
          <SeasonsTab playerId={user?.id} />
        )}
      </div>
    </div>
  )
}
