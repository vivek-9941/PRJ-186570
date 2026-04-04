import { useState, useEffect, useMemo, FormEvent } from "react";
import { OrderDetails, TrackerStep } from "../types";
import { formatTimestamp, parseError } from "../utils";

const ORDER_ENDPOINT = "/api/orders/api/v1/orders";
const TERMINAL_STATUSES = new Set(["EXECUTED", "REJECTED", "FAILED"]);

const statusPaths: Record<string, string[]> = {
  PENDING: ["PENDING"],
  VALIDATING: ["PENDING", "VALIDATING"],
  APPROVED: ["PENDING", "VALIDATING", "APPROVED"],
  REJECTED: ["PENDING", "VALIDATING", "REJECTED"],
  ROUTED: ["PENDING", "VALIDATING", "APPROVED", "ROUTED"],
  EXECUTED: ["PENDING", "VALIDATING", "APPROVED", "ROUTED", "EXECUTED"],
  FAILED: ["PENDING", "VALIDATING", "FAILED"]
};

const colorClasses: Record<TrackerStep["color"], { dot: string; text: string }> = {
  green: {
    dot: "bg-emerald-500 border-emerald-500",
    text: "text-emerald-700"
  },
  amber: {
    dot: "bg-amber-500 border-amber-500",
    text: "text-amber-700"
  },
  red: {
    dot: "bg-rose-500 border-rose-500",
    text: "text-rose-700"
  },
  gray: {
    dot: "bg-slate-300 border-slate-300",
    text: "text-slate-500"
  }
};

function statusIndex(status: string, timeline: Record<string, string>): number {
  if (status === "FAILED") {
    if (timeline.EXECUTED) return 4;
    if (timeline.ROUTED) return 3;
    if (timeline.APPROVED || timeline.REJECTED || timeline.FAILED) return 2;
    if (timeline.VALIDATING) return 1;
    return 0;
  }
  switch (status) {
    case "PENDING": return 0;
    case "VALIDATING": return 1;
    case "APPROVED":
    case "REJECTED": return 2;
    case "ROUTED": return 3;
    case "EXECUTED": return 4;
    default: return 0;
  }
}

function buildTrackerSteps(currentStatus: string, timeline: Record<string, string>): TrackerStep[] {
  const activeStatus = currentStatus.toUpperCase();
  const currentStep = statusIndex(activeStatus, timeline);

  return [
    { key: "PENDING", defaultLabel: "PENDING" },
    { key: "VALIDATING", defaultLabel: "VALIDATING" },
    { key: "DECISION", defaultLabel: "APPROVED / REJECTED" },
    { key: "ROUTED", defaultLabel: "ROUTED" },
    { key: "EXECUTED", defaultLabel: "EXECUTED" }
  ].map(({ key, defaultLabel }, index) => {
    let color: TrackerStep["color"] = "gray";
    if (activeStatus === "EXECUTED") {
      color = "green";
    } else if (activeStatus === "REJECTED") {
      if (index < 2) color = "green";
      if (index === 2) color = "red";
    } else if (activeStatus === "FAILED") {
      if (index < currentStep) color = "green";
      if (index === currentStep) color = "red";
    } else {
      if (index < currentStep) color = "green";
      if (index === currentStep) color = "amber";
    }

    let timestamp: string | undefined;
    let label = defaultLabel;

    if (key === "DECISION") {
      if (activeStatus === "REJECTED" || timeline.REJECTED) {
        label = "REJECTED";
        timestamp = timeline.REJECTED;
      } else if (activeStatus === "FAILED" && currentStep === 2 && timeline.FAILED) {
        label = "FAILED";
        timestamp = timeline.FAILED;
      } else if (activeStatus === "APPROVED" || activeStatus === "ROUTED" || activeStatus === "EXECUTED" || timeline.APPROVED) {
        label = "APPROVED";
        timestamp = timeline.APPROVED;
      } else if (timeline.FAILED) {
        label = "FAILED";
        timestamp = timeline.FAILED;
      }
    } else if (key === "PENDING") {
      timestamp = timeline.PENDING;
    } else if (key === "VALIDATING") {
      timestamp = timeline.VALIDATING;
    } else if (key === "ROUTED") {
      timestamp = timeline.ROUTED;
    } else if (key === "EXECUTED") {
      timestamp = timeline.EXECUTED;
    }

    return { key, label, timestamp, color };
  });
}

export default function OrderTracker() {
  const [trackInput, setTrackInput] = useState("");
  const [activeOrderId, setActiveOrderId] = useState("");
  const [trackSequence, setTrackSequence] = useState(0);
  const [trackerLoading, setTrackerLoading] = useState(false);
  const [trackerError, setTrackerError] = useState("");
  const [trackedOrder, setTrackedOrder] = useState<OrderDetails | null>(null);
  const [statusTimeline, setStatusTimeline] = useState<Record<string, string>>({});

  const trackerSteps = useMemo(
    () => buildTrackerSteps(trackedOrder?.status ?? "PENDING", statusTimeline),
    [trackedOrder?.status, statusTimeline]
  );

  useEffect(() => {
    if (!activeOrderId) return;
    let alive = true;
    let intervalId = 0;

    const poll = async () => {
      try {
        const response = await fetch(`${ORDER_ENDPOINT}/${activeOrderId}`);
        if (!response.ok) throw new Error(await parseError(response));
        const order = (await response.json()) as OrderDetails;
        const normalizedStatus = order.status?.toUpperCase?.() ?? "PENDING";
        if (!alive) return;
        setTrackedOrder({ ...order, status: normalizedStatus });
        setTrackerError("");
        setStatusTimeline((previous) => {
          const next = { ...previous };
          const observedAt = order.updatedAt ?? new Date().toISOString();
          const path = statusPaths[normalizedStatus] ?? [normalizedStatus];
          for (const stepStatus of path) {
            if (!next[stepStatus]) {
              next[stepStatus] = stepStatus === "PENDING" ? order.createdAt ?? observedAt : observedAt;
            }
          }
          if (!next[normalizedStatus]) next[normalizedStatus] = observedAt;
          return next;
        });
        if (TERMINAL_STATUSES.has(normalizedStatus)) window.clearInterval(intervalId);
      } catch (error) {
        if (!alive) return;
        setTrackerError(error instanceof Error ? error.message : "Unable to fetch order status.");
      } finally {
        if (alive) setTrackerLoading(false);
      }
    };

    setTrackerLoading(true);
    poll();
    intervalId = window.setInterval(poll, 1000);
    return () => { alive = false; window.clearInterval(intervalId); };
  }, [activeOrderId, trackSequence]);

  function startTracking(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const trimmedOrderId = trackInput.trim();
    if (!trimmedOrderId) return;
    setTrackerError("");
    setTrackedOrder(null);
    setStatusTimeline({});
    setActiveOrderId(trimmedOrderId);
    setTrackSequence((previous) => previous + 1);
  }

  const trackingState = trackedOrder?.status?.toUpperCase() ?? "";
  const trackingLive = activeOrderId && (!trackingState || !TERMINAL_STATUSES.has(trackingState.toUpperCase()));

  return (
    <section className="rounded-2xl border border-slate-200 bg-white/95 p-5 shadow-sm">
      <h2 className="text-lg font-semibold text-slate-800">LIVE ORDER TRACKER</h2>
      <p className="mt-1 text-sm text-slate-500">Track order progression in real time.</p>

      <form className="mt-5 flex gap-2" onSubmit={startTracking}>
        <input
          className="flex-1 rounded-lg border border-slate-300 px-3 py-2 text-sm outline-none transition focus:border-emerald-500 focus:ring-2 focus:ring-emerald-100"
          placeholder="Enter orderId"
          value={trackInput}
          onChange={(event) => setTrackInput(event.target.value)}
        />
        <button
          type="submit"
          className="rounded-lg bg-slate-800 px-4 py-2 text-sm font-semibold text-white transition hover:bg-slate-900"
        >
          Track
        </button>
      </form>

      {trackerLoading && <p className="mt-4 text-sm font-medium text-slate-500">Loading latest status...</p>}
      {trackerError && <p className="mt-4 rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-sm text-rose-700">{trackerError}</p>}
      {activeOrderId && !trackerError && (
        <div className="mt-4 rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-700">
          <p><span className="font-semibold">Tracking:</span> {activeOrderId}</p>
          <p className={trackingLive ? "text-amber-700" : "text-emerald-700"}>
            {trackingLive ? "Polling every 1 second" : "Polling stopped (terminal status)."}
          </p>
        </div>
      )}

      <ol className="mt-6 border-l-2 border-slate-200 pl-5">
        {trackerSteps.map((step) => (
          <li key={step.key} className="relative mb-6 last:mb-0">
            <span className={`absolute -left-[31px] top-1 h-4 w-4 rounded-full border-2 ${colorClasses[step.color].dot}`} />
            <p className={`text-sm font-semibold ${colorClasses[step.color].text}`}>{step.label}</p>
            <p className="text-xs text-slate-500">{formatTimestamp(step.timestamp)}</p>
          </li>
        ))}
      </ol>
    </section>
  );
}
