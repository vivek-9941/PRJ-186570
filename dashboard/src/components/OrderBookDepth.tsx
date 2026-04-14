import { useEffect, useMemo, useState } from "react";
import { OrderBookDepth as OrderBookDepthType, OrderBookDepthLevel, SymbolOption } from "../types";

const MATCHING_ENDPOINT = "/api/matching/api/v1/orderbook";

interface OrderBookDepthProps {
  symbol: SymbolOption;
  onSymbolChange: (symbol: SymbolOption) => void;
  symbols: SymbolOption[];
}

function formatPrice(value: number): string {
  return `Rs ${value.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

function formatQty(value: number): string {
  return value.toLocaleString(undefined, { maximumFractionDigits: 2 });
}

function DepthRow({
  level,
  maxQty,
  tintClass
}: {
  level: OrderBookDepthLevel;
  maxQty: number;
  tintClass: string;
}) {
  const widthPercent = maxQty > 0 ? Math.max(4, (level.quantity / maxQty) * 100) : 4;

  return (
    <div className="relative grid grid-cols-[1.2fr_1fr_1.6fr] items-center gap-3 rounded-md px-2 py-1.5 text-sm">
      <div className="font-semibold">{formatPrice(level.price)}</div>
      <div>{formatQty(level.quantity)}</div>
      <div className="relative h-2 overflow-hidden rounded-full bg-[#2d3449]">
        <div className={`h-full rounded-full ${tintClass}`} style={{ width: `${widthPercent}%` }} />
      </div>
    </div>
  );
}

export default function OrderBookDepth({ symbol, onSymbolChange, symbols }: OrderBookDepthProps) {
  const [depth, setDepth] = useState<OrderBookDepthType | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    let alive = true;

    const loadDepth = async () => {
      try {
        if (!alive) return;
        setLoading(true);
        const response = await fetch(`${MATCHING_ENDPOINT}/${symbol}/depth`);
        if (!response.ok) {
          throw new Error("Unable to load order book depth.");
        }
        const payload = (await response.json()) as OrderBookDepthType;
        if (!alive) return;
        setDepth(payload);
        setError("");
      } catch (loadError) {
        if (!alive) return;
        setError(loadError instanceof Error ? loadError.message : "Unable to load order book depth.");
      } finally {
        if (alive) setLoading(false);
      }
    };

    loadDepth();
    const intervalId = window.setInterval(loadDepth, 2000);
    return () => {
      alive = false;
      window.clearInterval(intervalId);
    };
  }, [symbol]);

  const maxSellQty = useMemo(
    () => Math.max(1, ...(depth?.asks ?? []).map((level) => level.quantity)),
    [depth?.asks]
  );
  const maxBuyQty = useMemo(
    () => Math.max(1, ...(depth?.bids ?? []).map((level) => level.quantity)),
    [depth?.bids]
  );

  return (
    <section className="panel">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="panel-title">ORDER BOOK DEPTH</h2>
          <p className="panel-subtitle">Top 5 levels per side. Refreshes every 2 seconds.</p>
        </div>
        <label className="text-sm font-medium text-[#c3c5d9]">
          Symbol
          <select
            className="input-dark ml-2 !mt-0 w-auto py-1.5"
            value={symbol}
            onChange={(event) => onSymbolChange(event.target.value as SymbolOption)}
          >
            {symbols.map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </select>
        </label>
      </div>

      {loading && !depth && <p className="mt-4 text-sm font-medium text-[#c3c5d9]">Loading depth...</p>}
      {error && <p className="mt-4 rounded-lg border border-[#ffb4ab]/35 bg-[#93000a]/35 px-3 py-2 text-sm text-[#ffdad6]">{error}</p>}

      <div className="mt-4 rounded-lg border border-[#434656]/75 bg-[#0b1326]/70 p-3">
        <div className="grid grid-cols-[1.2fr_1fr_1.6fr] gap-3 px-2 text-xs font-semibold uppercase tracking-wide text-[#8d90a2]">
          <div>Price</div>
          <div>Qty</div>
          <div>Depth</div>
        </div>

        <div className="mt-2 space-y-1 rounded-lg border border-[#93000a]/45 bg-[#93000a]/15 p-2">
          {(depth?.asks ?? []).map((level, index) => (
            <DepthRow key={`ask-${index}-${level.price}`} level={level} maxQty={maxSellQty} tintClass="bg-[#ffb4ab]/80" />
          ))}
          {(depth?.asks ?? []).length === 0 && <p className="px-2 py-1 text-sm text-[#8d90a2]">No asks</p>}
        </div>

        <div className="my-3 rounded-md border border-[#66d9cc]/40 bg-[#1ea296]/15 px-3 py-2 text-center text-sm font-semibold text-[#84f5e8]">
          Spread: {depth?.spread != null ? formatPrice(depth.spread) : "--"}
        </div>

        <div className="space-y-1 rounded-lg border border-[#1ea296]/50 bg-[#1ea296]/18 p-2">
          {(depth?.bids ?? []).map((level, index) => (
            <DepthRow key={`bid-${index}-${level.price}`} level={level} maxQty={maxBuyQty} tintClass="bg-[#66d9cc]/85" />
          ))}
          {(depth?.bids ?? []).length === 0 && <p className="px-2 py-1 text-sm text-[#8d90a2]">No bids</p>}
        </div>
      </div>
    </section>
  );
}
