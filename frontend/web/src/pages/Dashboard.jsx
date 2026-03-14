import React, { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Button } from '../components/Button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../components/Card'
import { BarChart3 } from 'lucide-react'

export default function Dashboard() {
  const [activeTab, setActiveTab] = useState('overview')
  const navigate = useNavigate()

  return (
    <div className="min-h-screen bg-slate-50">
      {/* Sidebar & Content */}
      <div className="container py-8">
        <div className="grid grid-cols-1 lg:grid-cols-4 gap-8">
          {/* Sidebar */}
          <div className="lg:col-span-1">
            <Card>
              <CardContent className="p-0">
                <nav className="flex flex-col">
                  {[
                    { id: 'overview', label: 'Overview', icon: '📊' },
                    { id: 'seasons', label: 'Seasons', icon: '🏒' },
                    { id: 'games', label: 'Game History', icon: '📝' },
                    { id: 'stats', label: 'Statistics', icon: '📈' },
                    { id: 'export', label: 'Export', icon: '📄' },
                  ].map((item) => (
                    <button
                      key={item.id}
                      onClick={() => setActiveTab(item.id)}
                      className={`flex items-center space-x-3 px-4 py-3 border-b border-slate-200 last:border-b-0 text-left transition-colors ${
                        activeTab === item.id
                          ? 'bg-primary-50 text-primary-600 font-medium border-l-4 border-primary-600'
                          : 'text-slate-600 hover:bg-slate-50'
                      }`}
                    >
                      <span>{item.icon}</span>
                      <span>{item.label}</span>
                    </button>
                  ))}
                  <button
                    onClick={() => navigate('/integrated-history')}
                    className="flex items-center space-x-3 px-4 py-3 border-t border-slate-200 text-left text-slate-600 hover:bg-slate-50 transition-colors"
                  >
                    <span>🔗</span>
                    <span>Integrated History</span>
                  </button>
                </nav>
              </CardContent>
            </Card>
          </div>

          {/* Main Content */}
          <div className="lg:col-span-3">
            {activeTab === 'overview' && (
              <div className="space-y-6">
                <Card>
                  <CardHeader>
                    <CardTitle>Welcome Back!</CardTitle>
                    <CardDescription>Here's your hockey stats overview</CardDescription>
                  </CardHeader>
                  <CardContent>
                    <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
                      <div className="bg-gradient-to-br from-primary-50 to-primary-100 rounded-lg p-4">
                        <div className="text-3xl font-bold text-primary-600">0</div>
                        <p className="text-sm text-slate-600 mt-1">Total Games</p>
                      </div>
                      <div className="bg-gradient-to-br from-secondary-50 to-secondary-100 rounded-lg p-4">
                        <div className="text-3xl font-bold text-secondary-600">0</div>
                        <p className="text-sm text-slate-600 mt-1">Total Goals</p>
                      </div>
                      <div className="bg-gradient-to-br from-amber-50 to-amber-100 rounded-lg p-4">
                        <div className="text-3xl font-bold text-amber-600">0</div>
                        <p className="text-sm text-slate-600 mt-1">Total Assists</p>
                      </div>
                    </div>
                  </CardContent>
                </Card>

                <Card>
                  <CardHeader>
                    <CardTitle>Quick Actions</CardTitle>
                  </CardHeader>
                  <CardContent className="flex flex-col sm:flex-row gap-4">
                    <Button variant="primary" className="flex-1">
                      Add Season
                    </Button>
                    <Button variant="secondary" className="flex-1">
                      Record Game
                    </Button>
                    <Button variant="outline" className="flex-1">
                      Export Stats
                    </Button>
                  </CardContent>
                </Card>
              </div>
            )}

            {activeTab === 'seasons' && (
              <Card>
                <CardHeader>
                  <CardTitle>Your Seasons</CardTitle>
                  <CardDescription>Manage and view your hockey seasons</CardDescription>
                </CardHeader>
                <CardContent>
                  <p className="text-slate-600 text-center py-8">
                    No seasons yet. <button className="text-primary-600 hover:underline">Add your first season</button> to get started.
                  </p>
                </CardContent>
              </Card>
            )}

            {activeTab === 'games' && (
              <Card>
                <CardHeader>
                  <CardTitle>Game History</CardTitle>
                  <CardDescription>All your games across all seasons</CardDescription>
                </CardHeader>
                <CardContent>
                  <p className="text-slate-600 text-center py-8">
                    No games recorded yet. Start tracking your performance!
                  </p>
                </CardContent>
              </Card>
            )}

            {activeTab === 'stats' && (
              <Card>
                <CardHeader>
                  <CardTitle>Statistics</CardTitle>
                  <CardDescription>Your career statistics and trends</CardDescription>
                </CardHeader>
                <CardContent>
                  <p className="text-slate-600 text-center py-8">
                    Add some games to see your statistics and performance trends.
                  </p>
                </CardContent>
              </Card>
            )}

            {activeTab === 'export' && (
              <Card>
                <CardHeader>
                  <CardTitle>Export Your Stats</CardTitle>
                  <CardDescription>Download your stats as PDFs to share</CardDescription>
                </CardHeader>
                <CardContent>
                  <p className="text-slate-600 mb-4">
                    Export your game history and season summaries in PDF format.
                  </p>
                  <Button disabled>
                    Export as PDF
                  </Button>
                </CardContent>
              </Card>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}
