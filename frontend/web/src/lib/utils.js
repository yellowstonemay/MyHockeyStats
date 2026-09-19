import { clsx } from 'clsx'
import { twMerge } from 'tailwind-merge'

export function cn(...inputs) {
  return twMerge(clsx(inputs))
}

export const api = {
  // Use a relative path so it works through nginx/Cloudflare without CORS.
  // nginx proxies /api -> backend:8080. For local dev, Vite can set VITE_API_URL.
  baseUrl: process.env.VITE_API_URL || '/api',
  
  async signup(email, password) {
    const res = await fetch(`${this.baseUrl}/auth/signup`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password }),
    })
    if (!res.ok) {
      const err = await res.json().catch(() => ({}))
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
      const err = await res.json().catch(() => ({}))
      const error = new Error(err.error || 'Login failed')
      // e.g. EMAIL_NOT_VERIFIED — the UI offers to resend the link.
      error.code = err.code
      throw error
    }
    const data = await res.json()
    localStorage.setItem('token', data.token || data.accessToken)
    return data
  },

  async resendVerification(email) {
    const res = await fetch(`${this.baseUrl}/auth/resend-verification`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email }),
    })
    if (!res.ok) throw new Error('Could not send a new confirmation link')
    return res.json()
  },

  // Always resolves, even for unknown addresses (no account enumeration).
  async forgotPassword(email) {
    const res = await fetch(`${this.baseUrl}/auth/forgot-password`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email }),
    })
    if (!res.ok) throw new Error('Could not send the reset email')
    return res.json()
  },

  async resetPassword(token, newPassword) {
    const res = await fetch(`${this.baseUrl}/auth/reset-password`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token, newPassword }),
    })
    const data = await res.json().catch(() => ({}))
    if (!res.ok) throw new Error(data.error || 'Could not reset your password')
    return data
  },

  async logout() {
    localStorage.removeItem('token')
  },

  // ── Social sign-in (Google / Facebook) ────────────────────────────────
  // Full-page redirect: the backend owns the OAuth handshake and sends the
  // browser back to /oauth/callback with a one-time code.
  oauthStartUrl(provider) {
    return `${this.baseUrl}/auth/oauth2/${provider}/start`
  },

  async fetchOAuthProviders() {
    const res = await fetch(`${this.baseUrl}/auth/oauth2/providers`)
    if (!res.ok) throw new Error('Failed to load sign-in providers')
    return res.json()
  },

  async exchangeOAuthCode(code) {
    const res = await fetch(`${this.baseUrl}/auth/oauth2/exchange`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code }),
    })
    if (!res.ok) {
      const err = await res.json().catch(() => ({}))
      throw new Error(err.error || 'Sign-in failed')
    }
    return res.json()
  },

  // Confirm ownership of an existing email/password account so the social
  // login can be attached to it (the link is never made silently).
  async completeOAuthLink(code, password) {
    const res = await fetch(`${this.baseUrl}/auth/oauth2/complete-link`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code, password }),
    })
    if (!res.ok) {
      const err = await res.json().catch(() => ({}))
      throw new Error(err.error || 'Could not link your account')
    }
    return res.json()
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
