import { clsx } from 'clsx'
import { twMerge } from 'tailwind-merge'

export function cn(...inputs) {
  return twMerge(clsx(inputs))
}

export const api = {
  baseUrl: process.env.REACT_APP_API_URL || 'http://localhost:8080/api',
  
  async signup(email, password) {
    const res = await fetch(`${this.baseUrl}/auth/signup`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password, role: 'PLAYER' }),
    })
    if (!res.ok) throw new Error('Signup failed')
    return res.json()
  },

  async login(email, password) {
    const res = await fetch(`${this.baseUrl}/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password }),
    })
    if (!res.ok) throw new Error('Login failed')
    const data = await res.json()
    localStorage.setItem('token', data.accessToken)
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
    return fetch(`${this.baseUrl}${url}`, { ...options, headers })
  },
}
