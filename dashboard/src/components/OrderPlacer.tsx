import { useEffect, useState, FormEvent } from "react";
import { SymbolOption, Side, PlaceOrderResponse, MarketDataBySymbol, OrderDetails } from "../types";
import { parseError } from "../utils";

const ORDER_ENDPOINT = "/api/orders/api/v1/orders";
const SYMBOLS = ["INFY", "TCS", "RELIANCE", "HDFC"] as const;
const TERMINAL_STATUSES = new Set(["EXECUTED", "FULLY_FILLED", "REJECTED", "FAILED", "CANCELLED"]);

interface OrderPlacerProps {
  latestTicks: MarketDataBySymbol;
  userId: string;
}

export default function OrderPlacer({ latestTicks, userId }: OrderPlacerProps) {
  const [symbol, setSymbol] = useState<SymbolOption>("INFY");
  const [side, setSide] = useState<Side>("BUY");
  const [quantity, setQuantity] = useState("1");
  const [price, setPrice] = useState("0");
  const [priceManuallyEdited, setPriceManuallyEdited] = useState(false);
  const [placeOrderLoading, setPlaceOrderLoading] = useState(false);
  const [placeOrderError, setPlaceOrderError] = useState("");
  const [placeOrderResult, setPlaceOrderResult] = useState<PlaceOrderResponse | null>(null);
  const [liveOrderStatus, setLiveOrderStatus] = useState("");
  const [liveOrderReason, setLiveOrderReason] = useState("");

  useEffect(() => {
    setPriceManuallyEdited(false);
  }, [symbol]);

  useEffect(() => {
    if (priceManuallyEdited) {
      return;
    }
    const currentTick = latestTicks[symbol];
    if (!currentTick) {
      return;
    }
    setPrice(currentTick.price.toFixed(2));
  }, [symbol, latestTicks, priceManuallyEdited]);

  useEffect(() => {
    const orderId = placeOrderResult?.orderId;
    if (!orderId) {
      return;
    }

    let alive = true;
    let intervalId = 0;

    const pollOrder = async () => {
      try {
        const response = await fetch(`${ORDER_ENDPOINT}/${orderId}`, { cache: "no-store" });
        if (!response.ok) {
          throw new Error(await parseError(response));
        }
        const order = (await response.json()) as OrderDetails;
        if (!alive) return;

        const nextStatus = order.status?.toUpperCase?.() ?? "PENDING";
        setLiveOrderStatus(nextStatus);
        if (nextStatus === "REJECTED" || nextStatus === "FAILED") {
          setLiveOrderReason(order.rejectionReason ?? "Order failed during validation.");
        } else {
          setLiveOrderReason("");
        }

        if (TERMINAL_STATUSES.has(nextStatus)) {
          window.clearInterval(intervalId);
        }
      } catch {
        if (!alive) return;
      }
    };

    pollOrder();
    intervalId = window.setInterval(pollOrder, 500);

    return () => {
      alive = false;
      window.clearInterval(intervalId);
    };
  }, [placeOrderResult?.orderId]);

  async function handlePlaceOrder(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPlaceOrderError("");
    setPlaceOrderResult(null);
    setLiveOrderStatus("");
    setLiveOrderReason("");

    const parsedQuantity = Number(quantity);
    const parsedPrice = Number(price);

    if (!Number.isFinite(parsedQuantity) || parsedQuantity <= 0) {
      setPlaceOrderError("quantity must be a positive number.");
      return;
    }
    if (!Number.isFinite(parsedPrice) || parsedPrice <= 0) {
      setPlaceOrderError("price must be a positive number.");
      return;
    }

    setPlaceOrderLoading(true);

    try {
      const response = await fetch(ORDER_ENDPOINT, {
        method: "POST",
        headers: {
          "Content-Type": "application/json"
        },
        body: JSON.stringify({
          userId: userId.trim(),
          symbol,
          side,
          orderType: "LIMIT",
          quantity: parsedQuantity,
          price: parsedPrice
        })
      });

      if (!response.ok) {
        setPlaceOrderError(await parseError(response));
        return;
      }

      const data = (await response.json()) as PlaceOrderResponse;
      setPlaceOrderResult(data);
      setLiveOrderStatus(data.status?.toUpperCase?.() ?? "PENDING");
    } catch {
      setPlaceOrderError("Could not place order. Check service connectivity.");
    } finally {
      setPlaceOrderLoading(false);
    }
  }

  return (
    <section className="panel">
      <h2 className="panel-title">ORDER PLACER</h2>
      <p className="panel-subtitle">Submit a new order to the order service.</p>

      <form className="mt-5 space-y-4" onSubmit={handlePlaceOrder}>
        <div className="rounded-lg border border-[#434656]/70 bg-[#0b1326]/70 px-3 py-2 text-sm text-[#c3c5d9]">
          <span className="font-semibold text-[#b7c4ff]">userId:</span> {userId}
        </div>

        <label className="block text-sm font-medium text-[#c3c5d9]">
          symbol
          <select
            className="input-dark"
            value={symbol}
            onChange={(event) => setSymbol(event.target.value as SymbolOption)}
          >
            {SYMBOLS.map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </select>
        </label>

        <div>
          <p className="text-sm font-medium text-[#c3c5d9]">side</p>
          <div className="mt-1 grid grid-cols-2 gap-2">
            {(["BUY", "SELL"] as const).map((option) => (
              <button
                key={option}
                type="button"
                onClick={() => setSide(option)}
                className={`rounded-lg border px-3 py-2 text-sm font-semibold transition ${
                  side === option
                    ? "border-[#66d9cc] bg-[#66d9cc]/20 text-[#84f5e8]"
                    : "border-[#434656]/85 bg-[#0b1326]/70 text-[#c3c5d9] hover:bg-[#171f33]"
                }`}
              >
                {option}
              </button>
            ))}
          </div>
        </div>

        <label className="block text-sm font-medium text-[#c3c5d9]">
          quantity
          <input
            type="number"
            min="1"
            step="1"
            className="input-dark"
            value={quantity}
            onChange={(event) => setQuantity(event.target.value)}
            required
          />
        </label>

        <label className="block text-sm font-medium text-[#c3c5d9]">
          price
          <input
            type="number"
            min="0.01"
            step="0.01"
            className="input-dark"
            value={price}
            onChange={(event) => {
              setPrice(event.target.value);
              setPriceManuallyEdited(true);
            }}
            required
          />
        </label>

        <button
          type="submit"
          className="btn-primary w-full disabled:cursor-not-allowed disabled:opacity-60"
          disabled={placeOrderLoading}
        >
          {placeOrderLoading ? "Placing..." : "Place Order"}
        </button>
      </form>

      {placeOrderError && (
        <p className="mt-4 rounded-lg border border-[#ffb4ab]/35 bg-[#93000a]/35 px-3 py-2 text-sm text-[#ffdad6]">
          {placeOrderError}
        </p>
      )}

      {placeOrderResult && (
        <div className="mt-4 rounded-lg border border-[#66d9cc]/35 bg-[#1ea296]/20 px-3 py-2 text-sm text-[#84f5e8]">
          <p>
            <span className="font-semibold">orderId:</span> {placeOrderResult.orderId}
          </p>
          <p>
            <span className="font-semibold">status:</span> {liveOrderStatus || placeOrderResult.status}
          </p>
        </div>
      )}

      {placeOrderResult && liveOrderStatus && !TERMINAL_STATUSES.has(liveOrderStatus) && (
        <p className="mt-4 rounded-lg border border-[#ffb870]/35 bg-[#2a1f10] px-3 py-2 text-sm text-[#ffdcbe]">
          Validating order... waiting for risk/margin/compliance result.
        </p>
      )}

      {liveOrderReason && (
        <p className="mt-4 rounded-lg border border-[#ffb4ab]/35 bg-[#93000a]/35 px-3 py-2 text-sm text-[#ffdad6]">
          {liveOrderReason}
        </p>
      )}
    </section>
  );
}
