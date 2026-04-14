export function formatTimestamp(timestamp?: string): string {
  if (!timestamp) {
    return "--";
  }
  const parsed = new Date(timestamp);
  if (Number.isNaN(parsed.getTime())) {
    return "--";
  }
  return parsed.toLocaleString();
}

export function formatNumber(value: number): string {
  if (!Number.isFinite(value)) {
    return "--";
  }
  return value.toLocaleString(undefined, { maximumFractionDigits: 2 });
}

export function formatCurrency(value: number): string {
  if (!Number.isFinite(value)) {
    return "--";
  }
  return value.toLocaleString(undefined, { style: "currency", currency: "INR", maximumFractionDigits: 2 });
}

export function getOrCreateClientUserId(): string {
  const storageKey = "trade-dashboard-user-id";
  const existingId = window.localStorage.getItem(storageKey);
  if (existingId && existingId.trim()) {
    return existingId;
  }

  const generated = window.crypto?.randomUUID
    ? `USR-${window.crypto.randomUUID()}`
    : `USR-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;

  window.localStorage.setItem(storageKey, generated);
  return generated;
}

export async function parseError(response: Response): Promise<string> {
  const fallbackResponse = response.clone();
  try {
    const body = await response.json();
    if (typeof body?.error === "string" && body.error.trim()) {
      return body.error;
    }
    if (typeof body?.message === "string" && body.message.trim()) {
      return body.message;
    }
    return `Request failed (${response.status})`;
  } catch {
    const text = await fallbackResponse.text();
    return text || `Request failed (${response.status})`;
  }
}
