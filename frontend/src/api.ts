/** A failed request, with the server's own explanation when it sent one. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly fields: Record<string, string> = {},
  ) {
    super(message);
  }
}

export async function api<T>(path: string, init: { method?: string; body?: unknown } = {}): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, {
      method: init.method ?? 'GET',
      headers: init.body !== undefined ? { 'Content-Type': 'application/json' } : {},
      body: init.body !== undefined ? JSON.stringify(init.body) : undefined,
    });
  } catch {
    throw new ApiError(0, 'The server could not be reached.');
  }
  if (!response.ok) {
    // Errors arrive as RFC 9457 problem documents.
    const problem = await response.json().catch(() => null);
    const fields: Record<string, string> = problem?.fields ?? {};
    const detail = Object.entries(fields)
      .map(([field, message]) => `${field} ${message}`)
      .join('; ');
    throw new ApiError(response.status, detail || problem?.detail || `The request failed (${response.status}).`, fields);
  }
  return response.status === 204 ? (undefined as T) : ((await response.json()) as T);
}
