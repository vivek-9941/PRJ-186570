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

export async function parseError(response: Response): Promise<string> {
  const fallbackResponse = response.clone();
  try {
    const body = await response.json();
    if (typeof body?.message === "string" && body.message.trim()) {
      return body.message;
    }
    return `Request failed (${response.status})`;
  } catch {
    const text = await fallbackResponse.text();
    return text || `Request failed (${response.status})`;
  }
}
