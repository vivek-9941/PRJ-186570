import { useEffect, useMemo, useState } from "react";
import { MarginSnapshot } from "../types";
import { formatCurrency, parseError } from "../utils";

const MARGIN_ENDPOINT_BASE = "/api/margin/api/v1/margin";

interface FundsConsoleProps {
  userId: string;
}

export default function FundsConsole({ userId }: FundsConsoleProps) {
  const [amount, setAmount] = useState("10000");
  const [snapshot, setSnapshot] = useState<MarginSnapshot | null>(null);
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");

  const encodedUserId = useMemo(() => encodeURIComponent(userId), [userId]);

  useEffect(() => {
    let alive = true;

    const fetchSnapshot = async () => {
      try {
        const response = await fetch(`${MARGIN_ENDPOINT_BASE}/${encodedUserId}`);
        if (!response.ok) {
          throw new Error(await parseError(response));
        }
        const payload = (await response.json()) as MarginSnapshot;
        if (!alive) return;
        setSnapshot(payload);
        setError("");
      } catch (fetchError) {
        if (!alive) return;
        setError(fetchError instanceof Error ? fetchError.message : "Unable to load margin snapshot.");
      } finally {
        if (alive) setLoading(false);
      }
    };

    fetchSnapshot();
    const intervalId = window.setInterval(fetchSnapshot, 5000);
    return () => {
      alive = false;
      window.clearInterval(intervalId);
    };
  }, [encodedUserId]);

  async function submitFundsAction(action: "deposit" | "withdraw") {
    setError("");
    setMessage("");

    const parsedAmount = Number(amount);
    if (!Number.isFinite(parsedAmount) || parsedAmount <= 0) {
      setError("Amount must be a positive number.");
      return;
    }

    setActionLoading(true);

    try {
      const response = await fetch(`${MARGIN_ENDPOINT_BASE}/${encodedUserId}/${action}?amount=${parsedAmount}`, {
        method: "PUT"
      });
      if (!response.ok) {
        throw new Error(await parseError(response));
      }
      const updatedSnapshot = (await response.json()) as MarginSnapshot;
      setSnapshot(updatedSnapshot);
      setMessage(action === "deposit" ? "Funds added successfully." : "Funds withdrawn successfully.");
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Unable to update funds.");
    } finally {
      setActionLoading(false);
    }
  }

  return (
    <section className="panel">
      <h2 className="panel-title">FUNDS CONSOLE</h2>
      <p className="panel-subtitle">Manage cash balance for your auto-assigned user.</p>

      <div className="mt-4 rounded-lg border border-[#434656]/70 bg-[#0b1326]/70 px-3 py-2 text-sm text-[#c3c5d9]">
        <span className="font-semibold text-[#b7c4ff]">userId:</span> {userId}
      </div>

      <div className="mt-4 grid gap-2 rounded-lg border border-[#434656]/70 bg-[#0b1326]/70 p-3 text-sm text-[#c3c5d9]">
        <p><span className="font-semibold text-[#dae2fd]">Cash:</span> {formatCurrency(snapshot?.cashBalance ?? NaN)}</p>
        <p><span className="font-semibold text-[#dae2fd]">Holdings:</span> {formatCurrency(snapshot?.holdingsValue ?? NaN)}</p>
        <p><span className="font-semibold text-[#dae2fd]">Reserved:</span> {formatCurrency(snapshot?.reservedMargin ?? NaN)}</p>
        <p><span className="font-semibold text-[#84f5e8]">Available:</span> {formatCurrency(snapshot?.availableMargin ?? NaN)}</p>
      </div>

      <form
        className="mt-4 space-y-3"
        onSubmit={(event) => {
          event.preventDefault();
          void submitFundsAction("deposit");
        }}
      >
        <label className="block text-sm font-medium text-[#c3c5d9]">
          amount
          <input
            type="number"
            min="0.01"
            step="0.01"
            className="input-dark"
            value={amount}
            onChange={(event) => setAmount(event.target.value)}
            required
          />
        </label>

        <div className="grid grid-cols-2 gap-2">
          <button
            type="submit"
            className="btn-primary disabled:cursor-not-allowed disabled:opacity-60"
            disabled={actionLoading}
          >
            {actionLoading ? "Updating..." : "Add Funds"}
          </button>
          <button
            type="button"
            onClick={() => void submitFundsAction("withdraw")}
            className="rounded-lg border border-[#ffb870]/50 bg-[#2a1f10] px-4 py-2.5 text-sm font-semibold text-[#ffdcbe] transition hover:brightness-110 disabled:cursor-not-allowed disabled:opacity-60"
            disabled={actionLoading}
          >
            Withdraw Funds
          </button>
        </div>
      </form>

      {loading && <p className="mt-4 text-sm font-medium text-[#c3c5d9]">Loading margin snapshot...</p>}
      {error && <p className="mt-4 rounded-lg border border-[#ffb4ab]/35 bg-[#93000a]/35 px-3 py-2 text-sm text-[#ffdad6]">{error}</p>}
      {message && <p className="mt-4 rounded-lg border border-[#66d9cc]/35 bg-[#1ea296]/20 px-3 py-2 text-sm text-[#84f5e8]">{message}</p>}
    </section>
  );
}
