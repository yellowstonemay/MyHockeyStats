import React from 'react'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import '@testing-library/jest-dom'
import SeasonsTab from './SeasonsTab'
import * as integrationsApi from '../../lib/integrationsApi'

jest.mock('../../lib/integrationsApi')

describe('SeasonsTab', () => {
  const mockPlayerId = 'player-123'
  const mockRecords = [
    {
      source: 'AYHL',
      sourcePlayerId: 'ayhl-1',
      playerName: 'John Doe',
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
      importedAt: '2026-03-31',
    },
    {
      source: 'THF',
      sourcePlayerId: 'thf-1',
      playerName: 'John Doe',
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
      importedAt: '2026-03-30',
    },
  ]

  beforeEach(() => {
    jest.clearAllMocks()
    integrationsApi.integrationsApi.fetchPlayerSeasons.mockResolvedValue({
      playerId: mockPlayerId,
      playerName: 'John Doe',
      records: mockRecords,
      hasAmbiguity: false,
      ambiguityNote: null,
      availableSources: ['AYHL', 'THF'],
      emptySources: ['AHF'],
      fetchedAt: '2026-03-31T10:00:00Z',
    })
  })

  it('renders loading state initially', () => {
    render(<SeasonsTab playerId={mockPlayerId} />)
    expect(screen.getByText(/loading career data/i)).toBeInTheDocument()
  })

  it('renders career records after loading', async () => {
    render(<SeasonsTab playerId={mockPlayerId} />)
    await waitFor(() => {
      expect(screen.getByText('2025 Season')).toBeInTheDocument()
      expect(screen.getByText('2024 Season')).toBeInTheDocument()
    })
  })

  it('displays all statistics fields correctly', async () => {
    render(<SeasonsTab playerId={mockPlayerId} />)
    await waitFor(() => {
      expect(screen.getByText('AYHL')).toBeInTheDocument()
      expect(screen.getByText('North Stars')).toBeInTheDocument()
      expect(screen.getByText('12')).toBeInTheDocument() // jersey number
      expect(screen.getByText('20')).toBeInTheDocument() // gamesPlayed
      expect(screen.getByText('5')).toBeInTheDocument() // goals
      expect(screen.getByText('8')).toBeInTheDocument() // assists
    })
  })

  it('displays source attribution for each record', async () => {
    render(<SeasonsTab playerId={mockPlayerId} />)
    await waitFor(() => {
      expect(screen.getByText('AYHL')).toBeInTheDocument()
      expect(screen.getByText('THF')).toBeInTheDocument()
    })
  })

  it('renders error state with retry button', async () => {
    const errorMessage = 'Failed to load season data'
    integrationsApi.integrationsApi.fetchPlayerSeasons.mockRejectedValueOnce({
      message: errorMessage,
    })

    render(<SeasonsTab playerId={mockPlayerId} />)
    await waitFor(() => {
      expect(screen.getByText(errorMessage)).toBeInTheDocument()
      expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument()
    })
  })

  it('displays ambiguity message when hasAmbiguity is true', async () => {
    const ambiguityNote = 'Multiple players found with this name'
    integrationsApi.integrationsApi.fetchPlayerSeasons.mockResolvedValueOnce({
      playerId: mockPlayerId,
      playerName: 'John Doe',
      records: mockRecords,
      hasAmbiguity: true,
      ambiguityNote: ambiguityNote,
      availableSources: ['AYHL', 'THF'],
      emptySources: ['AHF'],
      fetchedAt: '2026-03-31T10:00:00Z',
    })

    render(<SeasonsTab playerId={mockPlayerId} />)
    await waitFor(() => {
      expect(screen.getByText(ambiguityNote)).toBeInTheDocument()
    })
  })

  it('sorts records by season descending', async () => {
    render(<SeasonsTab playerId={mockPlayerId} />)
    await waitFor(() => {
      const seasonHeaders = screen.getAllByText(/\d{4} Season/)
      expect(seasonHeaders[0]).toHaveTextContent('2025')
      expect(seasonHeaders[1]).toHaveTextContent('2024')
    })
  })

  it('handles empty records gracefully', async () => {
    integrationsApi.integrationsApi.fetchPlayerSeasons.mockResolvedValueOnce({
      playerId: mockPlayerId,
      playerName: 'John Doe',
      records: [],
      hasAmbiguity: false,
      ambiguityNote: null,
      availableSources: [],
      emptySources: ['AYHL', 'THF', 'AHF'],
      fetchedAt: '2026-03-31T10:00:00Z',
    })

    render(<SeasonsTab playerId={mockPlayerId} />)
    await waitFor(() => {
      expect(screen.getByText(/no career records found/i)).toBeInTheDocument()
    })
  })

  it('calls retry when retry button is clicked', async () => {
    integrationsApi.integrationsApi.fetchPlayerSeasons
      .mockRejectedValueOnce({ message: 'Error' })
      .mockResolvedValueOnce({
        playerId: mockPlayerId,
        playerName: 'John Doe',
        records: mockRecords,
        hasAmbiguity: false,
        ambiguityNote: null,
        availableSources: ['AYHL', 'THF'],
        emptySources: ['AHF'],
        fetchedAt: '2026-03-31T10:00:00Z',
      })

    render(<SeasonsTab playerId={mockPlayerId} />)
    await waitFor(() => {
      const retryButton = screen.getByRole('button', { name: /retry/i })
      fireEvent.click(retryButton)
    })

    await waitFor(() => {
      expect(screen.getByText('2025 Season')).toBeInTheDocument()
    })
  })

  it('calls fetchPlayerSeasons with correct parameters', async () => {
    render(<SeasonsTab playerId={mockPlayerId} />)
    await waitFor(() => {
      expect(integrationsApi.integrationsApi.fetchPlayerSeasons).toHaveBeenCalledWith(
        mockPlayerId,
        ''
      )
    })
  })

  it('shows season filter when multiple seasons exist', async () => {
    render(<SeasonsTab playerId={mockPlayerId} />)
    await waitFor(() => {
      expect(screen.getByDisplayValue('All Seasons')).toBeInTheDocument()
      expect(screen.getByDisplayValue('2025 Season')).toBeInTheDocument()
      expect(screen.getByDisplayValue('2024 Season')).toBeInTheDocument()
    })
  })
})
