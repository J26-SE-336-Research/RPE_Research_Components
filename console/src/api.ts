// Thin client for the rpe-hub API (/api/hub/*). Every call acts on the real running ecosystem.
import { useCallback, useEffect, useRef, useState } from "react";

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

export async function api<T = unknown>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(`/api/hub${path}`, {
    method,
    credentials: "same-origin",
    headers: body !== undefined ? { "Content-Type": "application/json" } : undefined,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  let data: unknown = undefined;
  try {
    data = text ? JSON.parse(text) : undefined;
  } catch {
    data = text;
  }
  if (!res.ok) {
    const msg = (data as { error?: string } | undefined)?.error ?? `${res.status} ${res.statusText}`;
    if (res.status === 401) window.dispatchEvent(new Event("rpe:unauthorized"));
    throw new ApiError(res.status, msg);
  }
  return data as T;
}

/** Loads data on mount and optionally polls every `intervalMs`. */
export function useData<T>(path: string, intervalMs?: number) {
  const [data, setData] = useState<T | undefined>();
  const [error, setError] = useState<string>();
  const alive = useRef(true);
  const load = useCallback(async () => {
    try {
      const d = await api<T>("GET", path);
      if (alive.current) {
        setData(d);
        setError(undefined);
      }
    } catch (e) {
      if (alive.current) setError((e as Error).message);
    }
  }, [path]);
  useEffect(() => {
    alive.current = true;
    load();
    const id = intervalMs ? window.setInterval(load, intervalMs) : undefined;
    return () => {
      alive.current = false;
      if (id) window.clearInterval(id);
    };
  }, [load, intervalMs]);
  return { data, error, reload: load };
}

/** Runs an action (button) with busy/error/result state. */
export function useAction<A extends unknown[], R>(fn: (...args: A) => Promise<R>) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const [result, setResult] = useState<R>();
  const run = useCallback(
    async (...args: A) => {
      setBusy(true);
      setError(undefined);
      try {
        const r = await fn(...args);
        setResult(r);
        return r;
      } catch (e) {
        setError((e as Error).message);
        return undefined;
      } finally {
        setBusy(false);
      }
    },
    [fn],
  );
  return { run, busy, error, result, setResult };
}

export type User = { Name: string; Role: "admin" | "sre" | "viewer" };

export type Check = { rule: string; pass: boolean; detail: string };
export type Hop = {
  id: number;
  parent: number;
  depth: number;
  service: string;
  name: string;
  status: number;
  outcome: string;
  terminal: boolean;
  ms: number;
  context: string;
  fields?: Record<string, string>;
  checks: Check[];
};
export type ChainResult = {
  status: number;
  ms: number;
  result: Record<string, string> | null;
  resultRaw: string;
  hops: Hop[];
  error?: string;
};
