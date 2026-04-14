import { useEffect, useState } from "react";
import { CircuitBreakerHealthResponse } from "../types";
import { parseError } from "../utils";

const CIRCUIT_BREAKER_ENDPOINT = "/api/orders/api/v1/health/circuit-breakers";

const dotColor: Record<string, string> = {
  CLOSED: "bg-[#66d9cc]",
  OPEN: "bg-[#ffb4ab]",
  HALF_OPEN: "bg-[#ffb870]"
};

export default function CircuitBreakerPanel() {
  const [health, setHealth] = useState<CircuitBreakerHealthResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    let alive = true;

    const fetchHealth = async () => {
      try {
        const response = await fetch(CIRCUIT_BREAKER_ENDPOINT, { cache: "no-store" });
        if (!response.ok) throw new Error(await parseError(response));
        const payload = (await response.json()) as CircuitBreakerHealthResponse;
        if (!alive) return;
        setHealth(payload);
        setError("");
      } catch (fetchError) {
        if (!alive) return;
        setError(fetchError instanceof Error ? fetchError.message : "Unable to load circuit breaker health.");
      } finally {
        if (alive) setLoading(false);
      }
    };

    fetchHealth();
    const intervalId = window.setInterval(fetchHealth, 5000);
    return () => {
      alive = false;
      window.clearInterval(intervalId);
    };
  }, []);

  const rows = Object.entries(health ?? {});

  return (
    <section className="panel xl:col-span-3">
      <div className="flex items-center justify-between gap-3">
        <div>
          <h2 className="panel-title">CIRCUIT BREAKERS</h2>
          <p className="panel-subtitle">Live health of downstream validation services.</p>
        </div>
        <div className="rounded-full border border-[#434656]/70 bg-[#171f33] px-3 py-1 text-xs font-semibold text-[#c3c5d9]">
          Refreshes every 5s
        </div>
      </div>

      {loading && <p className="mt-4 text-sm font-medium text-[#c3c5d9]">Loading circuit breaker states...</p>}
      {error && <p className="mt-4 rounded-lg border border-[#ffb4ab]/35 bg-[#93000a]/35 px-3 py-2 text-sm text-[#ffdad6]">{error}</p>}

      <div className="mt-4 grid gap-3 md:grid-cols-3">
        {rows.length === 0 && !loading ? (
          <div className="rounded-xl border border-[#434656]/70 bg-[#0b1326]/70 px-4 py-5 text-sm text-[#8d90a2]">
            No circuit breaker data available yet.
          </div>
        ) : (
          rows.map(([name, details]) => (
            <article key={name} className="rounded-xl border border-[#434656]/70 bg-[#0b1326]/70 px-4 py-4">
              <div className="flex items-center justify-between gap-3">
                <h3 className="text-sm font-semibold text-[#dae2fd]">{name}</h3>
                <span className={`h-3 w-3 rounded-full ${dotColor[details.state] ?? "bg-[#8d90a2]"}`} />
              </div>
              <p className="mt-3 text-sm text-[#c3c5d9]">
                State: <span className="font-semibold">{details.state}</span>
              </p>
              <p className="mt-1 text-sm text-[#c3c5d9]">
                Failure rate: <span className="font-semibold">{details.failureRate}</span>
              </p>
              {typeof details.calls === "number" && (
                <p className="mt-1 text-sm text-[#c3c5d9]">
                  Probe calls: <span className="font-semibold">{details.calls}</span>
                </p>
              )}
            </article>
          ))
        )}
      </div>
    </section>
  );
}
