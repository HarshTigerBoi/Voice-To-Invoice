import { useState } from "react";
import type { Sale } from "../types";
import { formatQty, formatTime, inr, isToday } from "../lib/format";
import { BookIcon, TrashIcon } from "./icons";

interface Props {
  sales: Sale[];
  onDelete: (id: string) => void;
  toast: (msg: string, kind?: "ok" | "warn" | "err") => void;
}

export default function LedgerView({ sales, onDelete, toast }: Props) {
  const [filter, setFilter] = useState<"today" | "all">("today");

  const visible = filter === "today" ? sales.filter((s) => isToday(s.time)) : sales;
  const revenue = visible.reduce((a, s) => a + s.amount, 0);
  const profit = visible.reduce((a, s) => a + s.profit, 0);

  return (
    <section className="overflow-hidden rounded-2xl border border-stone-200 bg-white shadow-sm">
      {/* Ledger header with tabs */}
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-stone-100 px-5 py-4">
        <div className="flex items-center gap-2.5">
          <BookIcon className="h-5 w-5 text-emerald-700" />
          <h2 className="font-display text-lg font-bold text-stone-900">बिक्री खाता</h2>
        </div>
        <div className="flex rounded-lg bg-stone-100 p-0.5">
          {(["today", "all"] as const).map((f) => (
            <button
              key={f}
              onClick={() => setFilter(f)}
              className={`rounded-md px-3 py-1 text-xs font-bold transition ${
                filter === f
                  ? "bg-white text-emerald-800 shadow-sm"
                  : "text-stone-500 hover:text-stone-700"
              }`}
            >
              {f === "today" ? "आज" : "सभी"}
            </button>
          ))}
        </div>
      </div>

      {/* Totals */}
      <div className="grid grid-cols-3 divide-x divide-stone-100 border-b border-stone-100 bg-stone-50/60">
        <div className="px-5 py-3">
          <p className="text-[11px] font-semibold tracking-wide text-stone-500 uppercase">
            Entries
          </p>
          <p className="font-display text-lg font-bold text-stone-900">{visible.length}</p>
        </div>
        <div className="px-5 py-3">
          <p className="text-[11px] font-semibold tracking-wide text-stone-500 uppercase">
            बिक्री
          </p>
          <p className="font-display text-lg font-bold text-stone-900">{inr(revenue)}</p>
        </div>
        <div className="px-5 py-3">
          <p className="text-[11px] font-semibold tracking-wide text-stone-500 uppercase">
            मुनाफ़ा
          </p>
          <p
            className={`font-display text-lg font-bold ${
              profit >= 0 ? "text-emerald-700" : "text-red-600"
            }`}
          >
            {inr(profit)}
          </p>
        </div>
      </div>

      {/* Entries — bahi-khata style with red margin rule */}
      <div className="max-h-[430px] overflow-y-auto">
        {visible.length === 0 ? (
          <div className="px-5 py-12 text-center">
            <p className="text-3xl">📒</p>
            <p className="mt-2 text-sm font-medium text-stone-500">
              {filter === "today"
                ? "आज अभी कोई बिक्री नहीं हुई"
                : "No sales recorded yet"}
            </p>
            <p className="mt-1 text-xs text-stone-400">
              Speak or type an item above to make your first entry.
            </p>
          </div>
        ) : (
          <ul>
            {visible.map((s) => (
              <li
                key={s.id}
                className="group relative flex items-center gap-4 border-b border-stone-100 py-3 pl-8 pr-4 last:border-0 hover:bg-emerald-50/40"
              >
                {/* red margin rule */}
                <span className="absolute top-0 bottom-0 left-5 w-px bg-red-300/70" />
                <span className="absolute top-1/2 left-[13px] h-2 w-2 -translate-y-1/2 rounded-full border-2 border-white bg-emerald-500 shadow" />
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-bold text-stone-900">
                    {s.itemName}
                    <span className="ml-2 font-medium text-stone-500">
                      × {formatQty(s.qty)} {s.unitLabel}
                    </span>
                  </p>
                  <p className="text-[11px] text-stone-400">{formatTime(s.time)}</p>
                </div>
                <div className="text-right">
                  <p className="text-sm font-bold text-stone-900">{inr(s.amount)}</p>
                  <p
                    className={`text-[11px] font-semibold ${
                      s.profit >= 0 ? "text-emerald-700" : "text-red-600"
                    }`}
                  >
                    +{inr(s.profit)}
                  </p>
                </div>
                <button
                  onClick={() => {
                    onDelete(s.id);
                    toast(`Entry removed: ${s.itemName}`, "warn");
                  }}
                  aria-label={`Delete ${s.itemName} entry`}
                  className="rounded-lg p-2 text-stone-300 opacity-0 transition group-hover:opacity-100 hover:bg-red-50 hover:text-red-600 focus:opacity-100"
                >
                  <TrashIcon className="h-4 w-4" />
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  );
}
