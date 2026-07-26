import { useState } from "react";
import type { Item, PendingSale, Sale } from "./types";
import { useLocalStorage } from "./hooks/useLocalStorage";
import { SEED_ITEMS } from "./lib/seed";
import { formatQty, inr, todayString, unitLabel } from "./lib/format";
import VoiceSale from "./components/VoiceSale";
import LedgerView from "./components/LedgerView";
import InventoryView from "./components/InventoryView";
import StatsStrip from "./components/StatsStrip";
import { BookIcon, MicIcon } from "./components/icons";

interface Toast {
  id: number;
  msg: string;
  kind: "ok" | "warn" | "err";
}

const EXAMPLES = [
  "चीनी दो किलो",
  "दूध तीन पैकेट",
  "rice 5 kg",
  "चाय पत्ती एक पैकेट",
  "अंडे दो दर्जन",
  "तेल डेढ़ बोतल",
];

export default function App() {
  const [items, setItems] = useLocalStorage<Item[]>("bolokhata.items", SEED_ITEMS);
  const [sales, setSales] = useLocalStorage<Sale[]>("bolokhata.sales", []);
  const [pending, setPending] = useState<PendingSale | null>(null);
  const [toast, setToast] = useState<Toast | null>(null);
  const [command, setCommand] = useState<{ text: string; n: number } | null>(null);

  const showToast = (msg: string, kind: "ok" | "warn" | "err" = "ok") => {
    const id = Date.now();
    setToast({ id, msg, kind });
    setTimeout(() => setToast((t) => (t && t.id === id ? null : t)), 3200);
  };

  const confirmSale = (p: PendingSale) => {
    const sale: Sale = {
      id: "s" + Date.now(),
      itemId: p.item.id,
      itemName: p.item.name,
      unitLabel: unitLabel(p.unit),
      qty: p.qty,
      amount: p.amount,
      profit: p.profit,
      time: Date.now(),
    };
    setSales((prev) => [sale, ...prev]);
    setItems((prev) =>
      prev.map((it) =>
        it.id === p.item.id
          ? { ...it, stock: Math.round((it.stock - p.qty) * 100) / 100 }
          : it
      )
    );
    showToast(
      `✔ ${p.item.name} ${formatQty(p.qty)} ${unitLabel(p.unit)} — ${inr(p.amount)} दर्ज हुई`,
      "ok"
    );
  };

  const deleteSale = (id: string) => {
    const sale = sales.find((s) => s.id === id);
    if (!sale) return;
    setSales((prev) => prev.filter((s) => s.id !== id));
    // Restore stock
    setItems((prev) =>
      prev.map((it) =>
        it.id === sale.itemId
          ? { ...it, stock: Math.round((it.stock + sale.qty) * 100) / 100 }
          : it
      )
    );
  };

  const addItem = (item: Item) => setItems((prev) => [...prev, item]);

  const deleteItem = (id: string) => setItems((prev) => prev.filter((i) => i.id !== id));

  const restock = (id: string, amount: number) =>
    setItems((prev) =>
      prev.map((it) =>
        it.id === id ? { ...it, stock: Math.round((it.stock + amount) * 100) / 100 } : it
      )
    );

  return (
    <div className="min-h-screen">
      {/* Top bar */}
      <header className="sticky top-0 z-20 border-b border-stone-200/80 bg-[#f7f4ee]/90 backdrop-blur">
        <div className="mx-auto flex max-w-6xl items-center justify-between px-4 py-3 sm:px-6">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-gradient-to-br from-emerald-700 to-emerald-900 text-white shadow-md shadow-emerald-900/20">
              <BookIcon className="h-5 w-5" />
            </div>
            <div>
              <h1 className="font-display text-xl leading-tight font-bold text-stone-900">
                बोलो खाता
                <span className="ml-2 text-sm font-semibold text-emerald-700">BoloKhata</span>
              </h1>
              <p className="text-[11px] leading-tight text-stone-500">
                बोलो → बिक्री दर्ज → stock व मुनाफ़ा अपने-आप
              </p>
            </div>
          </div>
          <p className="hidden text-right text-xs font-semibold text-stone-500 sm:block">
            {todayString()}
          </p>
        </div>
      </header>

      <main className="mx-auto max-w-6xl px-4 py-6 sm:px-6">
        <StatsStrip sales={sales} items={items} />

        <div className="mt-6 grid gap-6 lg:grid-cols-5">
          <div className="space-y-6 lg:col-span-3">
            <VoiceSale
              items={items}
              pending={pending}
              setPending={setPending}
              onConfirm={confirmSale}
              onAddItem={addItem}
              toast={showToast}
              command={command}
            />

            {/* Example phrases */}
            <div className="rounded-2xl border border-dashed border-stone-300 bg-white/60 p-4">
              <p className="text-xs font-bold tracking-wide text-stone-500 uppercase">
                Try saying / tap to test
              </p>
              <div className="mt-2 flex flex-wrap gap-2">
                {EXAMPLES.map((ex) => (
                  <button
                    key={ex}
                    onClick={() => setCommand({ text: ex, n: Date.now() })}
                    className="inline-flex items-center gap-1.5 rounded-full border border-stone-200 bg-white px-3 py-1.5 text-xs font-semibold text-stone-700 transition hover:border-emerald-400 hover:bg-emerald-50 hover:text-emerald-800"
                  >
                    <MicIcon className="h-3 w-3 text-emerald-600" />
                    {ex}
                  </button>
                ))}
              </div>
            </div>

            <LedgerView sales={sales} onDelete={deleteSale} toast={showToast} />
          </div>

          <div className="lg:col-span-2">
            <InventoryView
              items={items}
              onAdd={addItem}
              onDelete={deleteItem}
              onRestock={restock}
              toast={showToast}
            />

            <div className="mt-6 rounded-2xl border border-stone-200 bg-white p-5 shadow-sm">
              <h3 className="font-display text-sm font-bold text-stone-900">
                कैसे काम करता है?
              </h3>
              <ol className="mt-3 space-y-2.5 text-xs text-stone-600">
                {[
                  ["Mic दबाएँ", "और item + मात्रा बोलें — Hindi या English में"],
                  ["App समझती है", "नाम, quantity (दो / 2 / डेढ़) और unit (किलो, पैकेट...)"],
                  ["Confirm करें", "बिक्री खाते में जाती है, stock घटता है, मुनाफ़ा जुड़ता है"],
                ].map(([t, d], i) => (
                  <li key={t} className="flex gap-3">
                    <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-emerald-100 text-[11px] font-bold text-emerald-800">
                      {i + 1}
                    </span>
                    <span>
                      <span className="font-bold text-stone-800">{t}</span> — {d}
                    </span>
                  </li>
                ))}
              </ol>
              <p className="mt-4 border-t border-stone-100 pt-3 text-[11px] leading-relaxed text-stone-400">
                सब कुछ आपके browser में ही save होता है (localStorage) — कोई server नहीं,
                data आपका ही रहता है. Voice recognition के लिए internet चाहिए (Chrome/Edge में
                सबसे बढ़िया).
              </p>
            </div>
          </div>
        </div>

        <footer className="mt-10 border-t border-stone-200 pt-5 pb-2 text-center text-[11px] text-stone-400">
          छोटे दुकानदारों के लिए — voice-powered sales, stock & profit tracking
        </footer>
      </main>

      {/* Toast */}
      {toast && (
        <div
          role="status"
          className={`fixed bottom-6 left-1/2 z-50 -translate-x-1/2 rounded-xl px-4 py-2.5 text-sm font-semibold text-white shadow-lg ${
            toast.kind === "ok"
              ? "bg-emerald-700"
              : toast.kind === "warn"
              ? "bg-amber-600"
              : "bg-red-600"
          }`}
        >
          {toast.msg}
        </div>
      )}
    </div>
  );
}
