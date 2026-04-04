import { useState, FormEvent } from "react";
import { SymbolOption, Side, PlaceOrderResponse } from "../types";
import { parseError } from "../utils";

const ORDER_ENDPOINT = "/api/orders/api/v1/orders";
const SYMBOLS = ["INFY", "TCS", "RELIANCE", "HDFC"] as const;

export default function OrderPlacer() {
  const [userId, setUserId] = useState("");
  const [symbol, setSymbol] = useState<SymbolOption>("INFY");
  const [side, setSide] = useState<Side>("BUY");
  const [quantity, setQuantity] = useState("1");
  const [price, setPrice] = useState("0");
  const [placeOrderLoading, setPlaceOrderLoading] = useState(false);
  const [placeOrderError, setPlaceOrderError] = useState("");
  const [placeOrderResult, setPlaceOrderResult] = useState<PlaceOrderResponse | null>(null);

  async function handlePlaceOrder(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPlaceOrderError("");
    setPlaceOrderResult(null);

    const normalizedUserId = userId.trim();
    const parsedQuantity = Number(quantity);
    const parsedPrice = Number(price);

    if (!normalizedUserId) {
      setPlaceOrderError("userId is required.");
      return;
    }
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
          userId: normalizedUserId,
          symbol,
          side,
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
    } catch {
      setPlaceOrderError("Could not place order. Check service connectivity.");
    } finally {
      setPlaceOrderLoading(false);
    }
  }

  return (
    <section className="rounded-2xl border border-slate-200 bg-white/95 p-5 shadow-sm">
      <h2 className="text-lg font-semibold text-slate-800">ORDER PLACER</h2>
      <p className="mt-1 text-sm text-slate-500">Submit a new order to the order service.</p>

      <form className="mt-5 space-y-4" onSubmit={handlePlaceOrder}>
        <label className="block text-sm font-medium text-slate-700">
          userId
          <input
            className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm outline-none transition focus:border-emerald-500 focus:ring-2 focus:ring-emerald-100"
            value={userId}
            onChange={(event) => setUserId(event.target.value)}
            placeholder="e.g. USER_001"
            required
          />
        </label>

        <label className="block text-sm font-medium text-slate-700">
          symbol
          <select
            className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm outline-none transition focus:border-emerald-500 focus:ring-2 focus:ring-emerald-100"
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
          <p className="text-sm font-medium text-slate-700">side</p>
          <div className="mt-1 grid grid-cols-2 gap-2">
            {(["BUY", "SELL"] as const).map((option) => (
              <button
                key={option}
                type="button"
                onClick={() => setSide(option)}
                className={`rounded-lg border px-3 py-2 text-sm font-semibold transition ${
                  side === option
                    ? "border-emerald-600 bg-emerald-100 text-emerald-800"
                    : "border-slate-300 bg-white text-slate-600 hover:bg-slate-50"
                }`}
              >
                {option}
              </button>
            ))}
          </div>
        </div>

        <label className="block text-sm font-medium text-slate-700">
          quantity
          <input
            type="number"
            min="1"
            step="1"
            className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm outline-none transition focus:border-emerald-500 focus:ring-2 focus:ring-emerald-100"
            value={quantity}
            onChange={(event) => setQuantity(event.target.value)}
            required
          />
        </label>

        <label className="block text-sm font-medium text-slate-700">
          price
          <input
            type="number"
            min="0.01"
            step="0.01"
            className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm outline-none transition focus:border-emerald-500 focus:ring-2 focus:ring-emerald-100"
            value={price}
            onChange={(event) => setPrice(event.target.value)}
            required
          />
        </label>

        <button
          type="submit"
          className="w-full rounded-lg bg-emerald-600 px-4 py-2.5 text-sm font-semibold text-white transition hover:bg-emerald-700 disabled:cursor-not-allowed disabled:bg-emerald-300"
          disabled={placeOrderLoading}
        >
          {placeOrderLoading ? "Placing..." : "Place Order"}
        </button>
      </form>

      {placeOrderError && (
        <p className="mt-4 rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-sm text-rose-700">
          {placeOrderError}
        </p>
      )}

      {placeOrderResult && (
        <div className="mt-4 rounded-lg border border-emerald-200 bg-emerald-50 px-3 py-2 text-sm text-emerald-800">
          <p>
            <span className="font-semibold">orderId:</span> {placeOrderResult.orderId}
          </p>
          <p>
            <span className="font-semibold">status:</span> {placeOrderResult.status}
          </p>
        </div>
      )}
    </section>
  );
}
