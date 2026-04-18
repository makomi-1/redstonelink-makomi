export function formatStartedAt(epochMillis: number): string {
  if (!Number.isFinite(epochMillis) || epochMillis <= 0) {
    return '-';
  }
  return new Date(epochMillis).toLocaleString();
}

export function formatBytes(sizeBytes: number): string {
  if (!Number.isFinite(sizeBytes) || sizeBytes < 0) {
    return '-';
  }
  if (sizeBytes < 1024) {
    return `${sizeBytes} B`;
  }
  if (sizeBytes < 1024 * 1024) {
    return `${(sizeBytes / 1024).toFixed(1)} KB`;
  }
  return `${(sizeBytes / (1024 * 1024)).toFixed(2)} MB`;
}
