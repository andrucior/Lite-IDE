import { describe, it, expect } from 'vitest'
import { toMonacoMarkers } from './diagnostics.js'

// Minimal Monaco stub: only the severity enum the mapper reads.
const monaco = {
  MarkerSeverity: { Error: 8, Warning: 4, Info: 2 },
}

describe('toMonacoMarkers', () => {
  it('returns an empty array for no diagnostics', () => {
    expect(toMonacoMarkers([], monaco)).toEqual([])
    expect(toMonacoMarkers(undefined, monaco)).toEqual([])
  })

  it('maps positions straight through (server is already 1-based)', () => {
    const markers = toMonacoMarkers(
      [{ severity: 'error', message: 'boom', startLine: 2, startCol: 3, endLine: 2, endCol: 8 }],
      monaco,
    )
    expect(markers).toEqual([
      {
        severity: 8,
        message: 'boom',
        startLineNumber: 2,
        startColumn: 3,
        endLineNumber: 2,
        endColumn: 8,
      },
    ])
  })

  it('maps each severity to the matching Monaco level', () => {
    const diags = [
      { severity: 'error', message: 'e', startLine: 1, startCol: 1, endLine: 1, endCol: 1 },
      { severity: 'warning', message: 'w', startLine: 1, startCol: 1, endLine: 1, endCol: 1 },
      { severity: 'info', message: 'i', startLine: 1, startCol: 1, endLine: 1, endCol: 1 },
    ]
    expect(toMonacoMarkers(diags, monaco).map((m) => m.severity)).toEqual([8, 4, 2])
  })

  it('falls back to Info for an unknown severity', () => {
    const markers = toMonacoMarkers(
      [{ severity: 'mystery', message: '?', startLine: 1, startCol: 1, endLine: 1, endCol: 1 }],
      monaco,
    )
    expect(markers[0].severity).toBe(monaco.MarkerSeverity.Info)
  })
})
