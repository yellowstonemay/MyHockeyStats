import React, { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Button } from '../components/Button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../components/Card'
import { Input } from '../components/Input'
import { Trophy, ArrowLeft } from 'lucide-react'

export default function Profile() {
  const navigate = useNavigate()
  const [fullName, setFullName] = useState('')
  const [birthdate, setBirthdate] = useState('')
  const [location, setLocation] = useState('')
  const [position, setPosition] = useState('')
  const [saved, setSaved] = useState(false)

  const handleSave = async (e) => {
    e.preventDefault()
    // TODO: Call API to save profile
    setSaved(true)
    setTimeout(() => setSaved(false), 3000)
  }

  return (
    <div className="min-h-screen bg-slate-50">
      {/* Navigation */}
      <nav className="border-b border-slate-200 bg-white shadow-sm sticky top-0 z-50">
        <div className="container flex items-center justify-between h-16">
          <div className="flex items-center space-x-2">
            <Trophy className="w-8 h-8 text-primary-600" />
            <h1 className="text-2xl font-bold text-slate-900">MyHockeyStats</h1>
          </div>
          <button
            onClick={() => navigate('/dashboard')}
            className="flex items-center space-x-2 text-slate-600 hover:text-slate-900"
          >
            <ArrowLeft className="w-5 h-5" />
            <span>Back to Dashboard</span>
          </button>
        </div>
      </nav>

      {/* Content */}
      <div className="container py-12 max-w-2xl">
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

            <form onSubmit={handleSave} className="space-y-6">
              <div>
                <label className="label">Full Name</label>
                <Input
                  type="text"
                  placeholder="Your full name"
                  value={fullName}
                  onChange={(e) => setFullName(e.target.value)}
                />
              </div>

              <div>
                <label className="label">Date of Birth</label>
                <Input
                  type="date"
                  value={birthdate}
                  onChange={(e) => setBirthdate(e.target.value)}
                />
              </div>

              <div>
                <label className="label">Location</label>
                <Input
                  type="text"
                  placeholder="City, State"
                  value={location}
                  onChange={(e) => setLocation(e.target.value)}
                />
              </div>

              <div>
                <label className="label">Position</label>
                <select
                  value={position}
                  onChange={(e) => setPosition(e.target.value)}
                  className="input appearance-none"
                >
                  <option value="">Select a position</option>
                  <option value="forward">Forward</option>
                  <option value="defense">Defense</option>
                  <option value="goaltender">Goaltender</option>
                </select>
              </div>

              <div className="flex gap-4">
                <Button type="submit" className="flex-1">
                  Save Changes
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  className="flex-1"
                  onClick={() => navigate('/dashboard')}
                >
                  Cancel
                </Button>
              </div>
            </form>
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
