import { api } from './utils'

// Admin-only: the private school assistant (grades/assignments). The backend gates these on the
// is_admin flag and proxies to a local-only service, so these calls 403 for normal accounts.
export const schoolApi = {
  status() {
    return api.fetchWithAuth('/admin/school/status')
  },

  ask(question) {
    return api.fetchWithAuth('/admin/school/ask', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question }),
    })
  },
}
