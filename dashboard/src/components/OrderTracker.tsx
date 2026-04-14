import { useState, useEffect, useMemo, FormEvent } from "react";
import { OrderDetails, TrackerStep } from "../types";
import { formatTimestamp, parseError } from "../utils";

const ORDER_ENDPOINT = "/api/orders/api/v1/orders";
const TERMINAL_STATUSES = new Set(["EXECUTED", "FULLY_FILLED", "REJECTED", "FAILED", "CANCELLED"]);
const CANCELLABLE_STATUSES = new Set(["PENDING", "PARTIALLY_FILLED"]);

const statusPaths: Record<string, string[]> = {
  PENDING: ["PENDING"],
  VALIDATING: ["PENDING", "VALIDATING"],
  APPROVED: ["PENDING", "VALIDATING", "APPROVED"],
  REJECTED: ["PENDING", "VALIDATING", "REJECTED"],
  ROUTED: ["PENDING", "VALIDATING", "APPROVED", "ROUTED"],
  PARTIALLY_FILLED: ["PENDING", "VALIDATING", "APPROVED", "ROUTED", "PARTIALLY_FILLED"],
  FULLY_FILLED: ["PENDING", "VALIDATING", "APPROVED", "ROUTED", "EXECUTED", "FULLY_FILLED"],
  CANCELLED: ["PENDING", "CANCELLED"],
  EXECUTED: ["PENDING", "VALIDATING", "APPROVED", "ROUTED", "EXECUTED"],
  FAILED: ["PENDING", "VALIDATING", "FAILED"]
};

const colorClasses: Record<TrackerStep["color"], { dot: string; text: string }> = {
  green: {
    dot: "bg-[#66d9cc] border-[#66d9cc]",
    text: "text-[#84f5e8]"
  },
  amber: {
    dot: "bg-[#ffb870] border-[#ffb870]",
    text: "text-[#ffdcbe]"
  },
  red: {
    dot: "bg-[#ffb4ab] border-[#ffb4ab]",
    text: "text-[#ffdad6]"
  },
  gray: {
    dot: "bg-[#8d90a2] border-[#8d90a2]",
    text: "text-[#8d90a2]"
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
  if (status === "CANCELLED") {
    if (timeline.PARTIALLY_FILLED || timeline.ROUTED) return 3;
    if (timeline.APPROVED || timeline.REJECTED) return 2;
    if (timeline.VALIDATING) return 1;
    return 0;
  }
  switch (status) {
    case "PENDING": return 0;
    case "VALIDATING": return 1;
    case "APPROVED":
    case "REJECTED": return 2;
    case "ROUTED": return 3;
    case "PARTIALLY_FILLED": return 3;
    case "FULLY_FILLED":
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
    } else if (activeStatus === "FULLY_FILLED") {
      color = "green";
    } else if (activeStatus === "CANCELLED") {
      if (index < currentStep) color = "green";
      if (index === currentStep) color = "red";
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
      if (activeStatus === "CANCELLED" && index === currentStep) {
        label = "CANCELLED";
        timestamp = timeline.CANCELLED;
      } else if (activeStatus === "REJECTED" || timeline.REJECTED) {
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
      if (activeStatus === "CANCELLED" && currentStep === 0) {
        label = "CANCELLED";
        timestamp = timeline.CANCELLED;
      } else {
        timestamp = timeline.PENDING;
      }
    } else if (key === "VALIDATING") {
      if (activeStatus === "CANCELLED" && currentStep === 1) {
        label = "CANCELLED";
        timestamp = timeline.CANCELLED;
      } else {
        timestamp = timeline.VALIDATING;
      }
    } else if (key === "ROUTED") {
      if (activeStatus === "PARTIALLY_FILLED") {
        label = "PARTIALLY FILLED";
        timestamp = timeline.PARTIALLY_FILLED ?? timeline.ROUTED;
      } else if (activeStatus === "CANCELLED" && currentStep === 3) {
        label = "CANCELLED";
        timestamp = timeline.CANCELLED;
      } else {
        timestamp = timeline.ROUTED;
      }
    } else if (key === "EXECUTED") {
      if (activeStatus === "FULLY_FILLED") {
        label = "FULLY FILLED";
        timestamp = timeline.FULLY_FILLED ?? timeline.EXECUTED;
      } else {
        timestamp = timeline.EXECUTED;
      }
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
  const [cancelLoading, setCancelLoading] = useState(false);
  const [cancelMessage, setCancelMessage] = useState("");
  const [shouldPoll, setShouldPoll] = useState(false);
  const [trackedOrder, setTrackedOrder] = useState<OrderDetails | null>(null);
  const [statusTimeline, setStatusTimeline] = useState<Record<string, string>>({});

  const trackerSteps = useMemo(
    () => buildTrackerSteps(trackedOrder?.status ?? "PENDING", statusTimeline),
    [trackedOrder?.status, statusTimeline]
  );

  useEffect(() => {
    if (!activeOrderId || !shouldPoll) return;
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
        if (TERMINAL_STATUSES.has(normalizedStatus)) {
          setShouldPoll(false);
          window.clearInterval(intervalId);
        }
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
  }, [activeOrderId, shouldPoll, trackSequence]);

  function startTracking(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const trimmedOrderId = trackInput.trim();
    if (!trimmedOrderId) return;
    setTrackerError("");
    setCancelMessage("");
    setCancelLoading(false);
    setTrackedOrder(null);
    setStatusTimeline({});
    setActiveOrderId(trimmedOrderId);
    setShouldPoll(true);
    setTrackSequence((previous) => previous + 1);
  }

  const trackingState = trackedOrder?.status?.toUpperCase() ?? "";
  const canCancel = trackedOrder ? CANCELLABLE_STATUSES.has(trackedOrder.status.toUpperCase()) : false;
  const trackingLive = Boolean(activeOrderId) && shouldPoll && (!trackingState || !TERMINAL_STATUSES.has(trackingState.toUpperCase()));

  async function cancelOrder() {
    if (!trackedOrder) return;
    setCancelLoading(true);
    setTrackerError("");
    setCancelMessage("");

    try {
      const response = await fetch(`${ORDER_ENDPOINT}/${trackedOrder.orderId}`, { method: "DELETE" });
      if (!response.ok) throw new Error(await parseError(response));
      const cancelledAt = new Date().toISOString();
      setTrackedOrder({ ...trackedOrder, status: "CANCELLED", updatedAt: cancelledAt });
      setStatusTimeline((previous) => ({ ...previous, CANCELLED: cancelledAt }));
      setShouldPoll(false);
      setCancelMessage("Order cancelled");
    } catch (error) {
      setTrackerError(error instanceof Error ? error.message : "Unable to cancel order.");
    } finally {
      setCancelLoading(false);
    }
  }

  return (
    <section className="panel">
      <h2 className="panel-title">LIVE ORDER TRACKER</h2>
      <p className="panel-subtitle">Track order progression in real time.</p>

      <form className="mt-5 flex gap-2" onSubmit={startTracking}>
        <input
          className="input-dark flex-1"
          placeholder="Enter orderId"
          value={trackInput}
          onChange={(event) => setTrackInput(event.target.value)}
        />
        <button
          type="submit"
          className="btn-primary px-4 py-2"
        >
          Track
        </button>
      </form>

      {trackerLoading && <p className="mt-4 text-sm font-medium text-[#c3c5d9]">Loading latest status...</p>}
      {trackerError && <p className="mt-4 rounded-lg border border-[#ffb4ab]/35 bg-[#93000a]/35 px-3 py-2 text-sm text-[#ffdad6]">{trackerError}</p>}
      {cancelMessage && <p className="mt-4 rounded-lg border border-[#66d9cc]/35 bg-[#1ea296]/20 px-3 py-2 text-sm text-[#84f5e8]">{cancelMessage}</p>}
      {activeOrderId && !trackerError && (
        <div className="mt-4 rounded-lg border border-[#434656]/85 bg-[#0b1326]/70 px-3 py-2 text-sm text-[#c3c5d9]">
          <p><span className="font-semibold">Tracking:</span> {activeOrderId}</p>
          <p className={trackingLive ? "text-[#ffdcbe]" : "text-[#84f5e8]"}>
            {trackingLive ? "Polling every 1 second" : "Polling stopped (terminal status)."}
          </p>
        </div>
      )}

      {canCancel && (
        <button
          type="button"
          onClick={cancelOrder}
          disabled={cancelLoading}
          className="mt-4 rounded-lg bg-[#93000a] px-4 py-2 text-sm font-semibold text-[#ffdad6] transition hover:brightness-110 disabled:cursor-not-allowed disabled:opacity-60"
        >
          {cancelLoading ? "Cancelling..." : "Cancel order"}
        </button>
      )}

      <ol className="mt-6 border-l-2 border-[#434656]/80 pl-5">
        {trackerSteps.map((step) => (
          <li key={step.key} className="relative mb-6 last:mb-0">
            <span className={`absolute -left-[31px] top-1 h-4 w-4 rounded-full border-2 ${colorClasses[step.color].dot}`} />
            <p className={`text-sm font-semibold ${colorClasses[step.color].text}`}>{step.label}</p>
            <p className="text-xs text-[#8d90a2]">{formatTimestamp(step.timestamp)}</p>
          </li>
        ))}
      </ol>
    </section>
  );
}
