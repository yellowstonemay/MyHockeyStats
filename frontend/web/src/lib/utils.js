import { clsx } from 'clsx'
import { twMerge } from 'tailwind-merge'

export function cn(...inputs) {
  return twMerge(clsx(inputs))
}

export const api = {
  // Use a relative path so it works through nginx/Cloudflare without CORS.
  // nginx proxies /api -> backend:8080. For local dev, Vite can set VITE_API_URL.
  baseUrl: process.env.VITE_API_URL || '/api',
  
  async signup(email, password, fullName) {
    const res = await fetch(`${this.baseUrl}/auth/signup`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password, fullName, role: 'PLAYER' }),
    })
    if (!res.ok) {
      const err = await res.json()
      throw new Error(err.error || 'Signup failed')
    }
    return res.json()
  },

  async login(email, password) {
    const res = await fetch(`${this.baseUrl}/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password }),
    })
    if (!res.ok) {
      const err = await res.json()
      throw new Error(err.error || 'Login failed')
    }
    const data = await res.json()
    localStorage.setItem('token', data.token || data.accessToken)
    return data
  },

  async logout() {
    localStorage.removeItem('token')
  },

  getToken() {
    return localStorage.getItem('token')
  },

  async fetchWithAuth(url, options = {}) {
    const token = this.getToken()
    const headers = { ...options.headers }
    if (token) {
      headers['Authorization'] = `Bearer ${token}`
    }
    const res = await fetch(`${this.baseUrl}${url}`, { ...options, headers })
    if (!res.ok) {
      let msg = `HTTP ${res.status}: ${res.statusText}`
      try {
        const err = await res.json()
        if (err && err.error) msg = err.error
      } catch (e) {
        /* non-JSON error body */
      }
      const error = new Error(msg)
      error.status = res.status
      throw error
    }
    return res.json()
  },

  // Player Profile endpoints
  async getProfile(playerId) {
    return this.fetchWithAuth(`/players/${playerId}`)
  },

  async createProfile(fullName, birthMonthYear, location, position) {
    const token = this.getToken()
    const res = await fetch(`${this.baseUrl}/players`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`,
      },
      body: JSON.stringify({ fullName, birthMonthYear, location, position }),
    })
    if (!res.ok) throw new Error('Failed to create profile')
    return res.json()
  },

  async updateProfile(playerId, fullName, birthMonthYear, location, position, photoUrl) {
    const token = this.getToken()
    const res = await fetch(`${this.baseUrl}/players/${playerId}`, {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`,
      },
      body: JSON.stringify({ fullName, birthMonthYear, location, position, photoUrl }),
    })
    if (!res.ok) throw new Error('Failed to update profile')
    return res.json()
  },

  // Season endpoints
  async getSeasons(playerId) {
    return this.fetchWithAuth(`/players/${playerId}/seasons`)
  },

  async getSeason(seasonId) {
    return this.fetchWithAuth(`/seasons/${seasonId}`)
  },

  async getGamesBySeason(seasonId) {
    return this.fetchWithAuth(`/seasons/${seasonId}/games`)
  },

  // Game endpoints
  async getGame(gameId) {
    return this.fetchWithAuth(`/games/${gameId}`)
  },

  // Export endpoints
  async exportSeason(playerId, seasonId) {
    return this.fetchWithAuth(`/players/${playerId}/seasons/${seasonId}/export`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ format: 'pdf' }),
    })
  },

  async getExportStatus(jobId) {
    return this.fetchWithAuth(`/exports/${jobId}`)
  },
}
