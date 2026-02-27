import React, { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Button } from '../components/Button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../components/Card'
import { Trophy } from 'lucide-react'

export default function Home() {
  const navigate = useNavigate()

  return (
    <div className="min-h-screen bg-gradient-to-b from-primary-50 to-slate-50">
      {/* Navigation */}
      <nav className="border-b border-slate-200 bg-white shadow-sm sticky top-0 z-50">
        <div className="container flex items-center justify-between h-16">
          <div className="flex items-center space-x-2">
            <Trophy className="w-8 h-8 text-primary-600" />
            <h1 className="text-2xl font-bold text-slate-900">MyHockeyStats</h1>
          </div>
          <div className="flex items-center space-x-4">
            <button 
              onClick={() => navigate('/signin')}
              className="text-slate-600 hover:text-slate-900 font-medium"
            >
              Sign In
            </button>
            <Button onClick={() => navigate('/signup')}>
              Get Started
            </Button>
          </div>
        </div>
      </nav>

      {/* Hero Section */}
      <div className="container py-20 text-center">
        <h2 className="text-5xl font-bold text-slate-900 mb-6">
          Track Your Hockey Performance
        </h2>
        <p className="text-xl text-slate-600 mb-8 max-w-2xl mx-auto">
          The only platform built for youth hockey players to track stats, analyze performance, and share achievements with family.
        </p>
        <div className="flex gap-4 justify-center">
          <Button size="lg" onClick={() => navigate('/signup')}>
            Create Free Account
          </Button>
          <Button size="lg" variant="outline">
            Learn More
          </Button>
        </div>
      </div>

      {/* Features Section */}
      <div className="container py-20">
        <h3 className="text-3xl font-bold text-slate-900 mb-12 text-center">
          Everything You Need
        </h3>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-8">
          <Card>
            <CardHeader>
              <div className="w-12 h-12 bg-primary-100 rounded-lg flex items-center justify-center mb-4">
                <span className="text-2xl">📊</span>
              </div>
              <CardTitle>Game History</CardTitle>
              <CardDescription>View all your games by season</CardDescription>
            </CardHeader>
            <CardContent>
              <p className="text-slate-600">
                Access complete game-by-game performance details including goals, assists, and stats across all seasons.
              </p>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <div className="w-12 h-12 bg-secondary-100 rounded-lg flex items-center justify-center mb-4">
                <span className="text-2xl">🎯</span>
              </div>
              <CardTitle>Performance Analytics</CardTitle>
              <CardDescription>Track your improvements over time</CardDescription>
            </CardHeader>
            <CardContent>
              <p className="text-slate-600">
                Get insights into your performance with visual charts and stats summaries for each season.
              </p>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <div className="w-12 h-12 bg-primary-100 rounded-lg flex items-center justify-center mb-4">
                <span className="text-2xl">📄</span>
              </div>
              <CardTitle>Export Reports</CardTitle>
              <CardDescription>Share your stats with family</CardDescription>
            </CardHeader>
            <CardContent>
              <p className="text-slate-600">
                Download PDF reports of your season performance to share with parents, coaches, and scouts.
              </p>
            </CardContent>
          </Card>
        </div>
      </div>

      {/* CTA Section */}
      <div className="bg-primary-600 text-white py-16">
        <div className="container text-center">
          <h3 className="text-3xl font-bold mb-4">
            Ready to start tracking your hockey stats?
          </h3>
          <p className="text-primary-100 mb-8 text-lg">
            Join hundreds of youth hockey players already using MyHockeyStats.
          </p>
          <Button 
            size="lg" 
            variant="secondary"
            onClick={() => navigate('/signup')}
          >
            Create Your Account Today
          </Button>
        </div>
      </div>

      {/* Footer */}
      <footer className="bg-slate-900 text-slate-300 py-8">
        <div className="container text-center">
          <p>&copy; 2026 MyHockeyStats. Track. Analyze. Improve.</p>
        </div>
      </footer>
    </div>
  )
}
