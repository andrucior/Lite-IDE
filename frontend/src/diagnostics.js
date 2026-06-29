/**
 * Map server [[Diagnostic]] objects onto Monaco editor markers.
 *
 * Kept as a standalone pure function (no React, no Monaco import) so it is trivially unit-testable
 * with a stubbed `monaco`. The server already emits 1-based line/column positions, so they map
 * straight onto Monaco's `startLineNumber` / `startColumn` / … without any offset arithmetic.
 *
 * @param {Array<{severity:string,message:string,startLine:number,startCol:number,endLine:number,endCol:number}>} diagnostics
 * @param {{MarkerSeverity:{Error:number,Warning:number,Info:number}}} monaco
 * @returns {Array<object>} Monaco `IMarkerData` objects (empty array for no diagnostics)
 */
export function toMonacoMarkers(diagnostics, monaco) {
  if (!diagnostics?.length) return []
  const severityOf = {
    error:   monaco.MarkerSeverity.Error,
    warning: monaco.MarkerSeverity.Warning,
    info:    monaco.MarkerSeverity.Info,
  }
  return diagnostics.map((d) => ({
    severity:        severityOf[d.severity] ?? monaco.MarkerSeverity.Info,
    message:         d.message,
    startLineNumber: d.startLine,
    startColumn:     d.startCol,
    endLineNumber:   d.endLine,
    endColumn:       d.endCol,
  }))
}
