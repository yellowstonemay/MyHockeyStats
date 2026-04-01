import React from 'react'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { BrowserRouter } from 'react-router-dom'
import '@testing-library/jest-dom'
import Profile from './Profile'
import * as AuthContext from '../lib/AuthContext'
import * as integrationsApi from '../lib/integrationsApi'

jest.mock('../lib/AuthContext')
jest.mock('../lib/integrationsApi')

const mockUser = {
  id: 'user-123',
  email: 'john@example.com',
  fullName: 'John Doe',
}

const mockSeasonsResponse = {
  playerId: 'user-123',
  playerName: 'John Doe',
  records: [
    {
      source: 'AYHL',
      sourcePlayerId: 'ayhl-1',
      season: 2025,
      club: 'North Stars',
      team: 'U18 AA',
      jerseyNumber: 12,
      gamesPlayed: 20,
      goals: 5,
      assists: 8,
      points: 13,
      penalties: 4,
      pim: 8,
    },
    {
      source: 'THF',
      sourcePlayerId: 'thf-1',
      season: 2024,
      club: 'Minnesota Thunder',
      team: 'U17 AA',
      jerseyNumber: 14,
      gamesPlayed: 18,
      goals: 3,
      assists: 6,
      points: 9,
      penalties: 2,
      pim: 4,
    },
  ],
  hasAmbiguity: false,
  availableSources: ['AYHL', 'THF'],
  emptySources: [],
}

describe('Profile Page Integration Tests', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    AuthContext.useAuth.mockReturnValue({
      user: mockUser,
      logout: jest.fn(),
    })
    integrationsApi.integrationsApi.fetchPlayerSeasons.mockResolvedValue(mockSeasonsResponse)
  })

  const renderProfile = () => {
    return render(
      <BrowserRouter>
        <Profile />
      </BrowserRouter>
    )
  }

  it('renders both profile settings and seasons tabs', () => {
    renderProfile()
    expect(screen.getByText('Profile Settings')).toBeInTheDocument()
    expect(screen.getByText('Game History & Stats')).toBeInTheDocument()
  })

  it('displays profile settings tab by default', () => {
    renderProfile()
    expect(screen.getByText('Edit Your Profile')).toBeInTheDocument()
  })

  it('switches to seasons tab when clicked', async () => {
    renderProfile()
    const seasonsTab = screen.getByRole('button', { name: /game history & stats/i })
    fireEvent.click(seasonsTab)

    await waitFor(() => {
      expect(screen.getByText(/game history & career statistics/i)).toBeInTheDocument()
    })
  })

  it('calls fetchPlayerSeasons with correct playerId', async () => {
    renderProfile()
    const seasonsTab = screen.getByRole('button', { name: /game history & stats/i })
    fireEvent.click(seasonsTab)

    await waitFor(() => {
      expect(integrationsApi.integrationsApi.fetchPlayerSeasons).toHaveBeenCalledWith(
        mockUser.id,
        ''
      )
    })
  })

  it('displays career records after loading', async () => {
    renderProfile()
    const seasonsTab = screen.getByRole('button', { name: /game history & stats/i })
    fireEvent.click(seasonsTab)

    await waitFor(() => {
      expect(screen.getByText('2025 Season')).toBeInTheDocument()
      expect(screen.getByText('AYHL')).toBeInTheDocument()
      expect(screen.getByText('North Stars')).toBeInTheDocument()
    })
  })

  it('handles API errors gracefully', async () => {
    const errorMessage = 'Failed to fetch seasons'
    integrationsApi.integrationsApi.fetchPlayerSeasons.mockRejectedValueOnce({
      message: errorMessage,
    })

    renderProfile()
    const seasonsTab = screen.getByRole('button', { name: /game history & stats/i })
    fireEvent.click(seasonsTab)

    await waitFor(() => {
      expect(screen.getByText(errorMessage)).toBeInTheDocument()
    })
  })

  it('maintains tab state when switching between tabs', async () => {
    renderProfile()

    // Start on profile tab
    expect(screen.getByText('Edit Your Profile')).toBeInTheDocument()

    // Switch to seasons tab
    const seasonsTab = screen.getByRole('button', { name: /game history & stats/i })
    fireEvent.click(seasonsTab)

    await waitFor(() => {
      expect(screen.getByText(/game history & career statistics/i)).toBeInTheDocument()
    })

    // Switch back to profile tab
    const profileTab = screen.getByRole('button', { name: /profile settings/i })
    fireEvent.click(profileTab)

    await waitFor(() => {
      expect(screen.getByText('Edit Your Profile')).toBeInTheDocument()
    })
  })

  it('displays full name in profile form', () => {
    renderProfile()
    const fullNameInput = screen.getByDisplayValue(mockUser.fullName)
    expect(fullNameInput).toBeInTheDocument()
  })

  it('displays email as disabled in profile form', () => {
    renderProfile()
    const emailInput = screen.getByDisplayValue(mockUser.email)
    expect(emailInput).toBeDisabled()
  })

  it('renders save button in profile tab', () => {
    renderProfile()
    expect(screen.getByRole('button', { name: /save changes/i })).toBeInTheDocument()
  })

  it('renders cancel button that navigates back', () => {
    renderProfile()
    expect(screen.getByRole('button', { name: /cancel/i })).toBeInTheDocument()
  })
})
