import { useEffect, useRef, useState } from "react";
import type { Item, PendingSale, Unit } from "../types";
import { matchItem, parseUtterance } from "../lib/voiceParser";
import { formatQty, inr, unitLabel, UNIT_LABELS } from "../lib/format";
import {
  AlertIcon,
  CheckIcon,
  KeyboardIcon,
  MicIcon,
  PlusIcon,
  StopIcon,
  XIcon,
} from "./icons";

interface Props {
  items: Item[];
  pending: PendingSale | null;
  setPending: (p: PendingSale | null) => void;
  onConfirm: (p: PendingSale) => void;
  onAddItem: (item: Item) => void;
  toast: (msg: string, kind?: "ok" | "warn" | "err") => void;
  command?: { text: string; n: number } | null;
}

// ---- Web Speech API minimal typings ----
interface SRResultLike {
  isFinal: boolean;
  0: { transcript: string };
}
interface SREventLike {
  resultIndex: number;
  results: { length: number; [i: number]: SRResultLike };
}
interface SRLike {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  onresult: ((e: SREventLike) => void) | null;
  onerror: ((e: { error: string }) => void) | null;
  onend: (() => void) | null;
  start: () => void;
  stop: () => void;
  abort: () => void;
}

type Stage =
  | { kind: "idle" }
  | { kind: "listening" }
  | { kind: "unmatched"; rawName: string; qty: number; unit: Unit | null; suggestions: Item[] };

export default function VoiceSale({
  items,
  pending,
  setPending,
  onConfirm,
  onAddItem,
  toast,
  command,
}: Props) {
  const [supported, setSupported] = useState(true);
  const [stage, setStage] = useState<Stage>({ kind: "idle" });
  const [transcript, setTranscript] = useState("");
  const [lang, setLang] = useState<"hi-IN" | "en-IN">("hi-IN");
  const [manualText, setManualText] = useState("");
  const [showManual, setShowManual] = useState(false);
  const [newItem, setNewItem] = useState<{
    name: string; unit: Unit; cost: string; sell: string; stock: string;
  } | null>(null);
  const [newQty, setNewQty] = useState(1);
  const recogRef = useRef<SRLike | null>(null);

  useEffect(() => {
    const w = window as unknown as {
      SpeechRecognition?: new () => SRLike;
      webkitSpeechRecognition?: new () => SRLike;
    };
    if (!w.SpeechRecognition && !w.webkitSpeechRecognition) setSupported(false);
    return () => recogRef.current?.abort();
  }, []);

  // Example-phrase chips trigger parsing through the same pipeline as voice
  useEffect(() => {
    if (command) {
      setTranscript(command.text);
      setNewItem(null);
      processUtterance(command.text);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [command?.n]);

  const processUtterance = (text: string) => {
    const clean = text.trim();
    if (!clean) return;
    const parsed = parseUtterance(clean);
    if (!parsed.rawName) {
      toast("Could not detect an item name. Try: \"चीनी दो किलो\"", "err");
      return;
    }
    const match = matchItem(parsed.rawName, items);
    if (match.item) {
      const item = match.item;
      const unit = parsed.unit ?? item.unit;
      setPending(buildPending(item, parsed.qty, unit, clean));
      setNewQty(parsed.qty);
      if (match.confidence === "fuzzy")
        toast(`Matched "${parsed.rawName}" → ${item.name}`, "warn");
      if (!parsed.qtyFound) toast("No quantity heard — assuming 1", "warn");
    } else {
      setPending(null);
      setStage({
        kind: "unmatched",
        rawName: parsed.rawName,
        qty: parsed.qty,
        unit: parsed.unit,
        suggestions: match.suggestions,
      });
    }
  };

  const buildPending = (item: Item, qty: number, unit: Unit, source: string): PendingSale => ({
    item,
    qty,
    unit,
    amount: round2(qty * item.sellPrice),
    profit: round2(qty * (item.sellPrice - item.costPrice)),
    source,
  });

  const round2 = (n: number) => Math.round(n * 100) / 100;

  const startListening = () => {
    const w = window as unknown as {
      SpeechRecognition?: new () => SRLike;
      webkitSpeechRecognition?: new () => SRLike;
    };
    const SR = w.SpeechRecognition || w.webkitSpeechRecognition;
    if (!SR) return;
    const r = new SR();
    r.lang = lang;
    r.continuous = false;
    r.interimResults = true;
    r.onresult = (e: SREventLike) => {
      let final = "";
      let interim = "";
      for (let i = 0; i < e.results.length; i++) {
        const res = e.results[i];
        if (res.isFinal) final += res[0].transcript;
        else interim += res[0].transcript;
      }
      setTranscript(final || interim);
      if (final) {
        setStage({ kind: "idle" });
        processUtterance(final);
      }
    };
    r.onerror = (e) => {
      setStage({ kind: "idle" });
      if (e.error === "not-allowed" || e.error === "service-not-allowed")
        toast("Microphone permission denied — allow mic in browser settings", "err");
      else if (e.error === "no-speech")
        toast("Nothing heard — try again, speak close to the mic", "warn");
      else if (e.error === "network")
        toast("Speech service needs internet — use manual entry instead", "err");
    };
    r.onend = () => setStage((s) => (s.kind === "listening" ? { kind: "idle" } : s));
    try {
      r.start();
      recogRef.current = r;
      setTranscript("");
      setStage({ kind: "listening" });
    } catch {
      setStage({ kind: "idle" });
    }
  };

  const stopListening = () => {
    recogRef.current?.stop();
    setStage({ kind: "idle" });
  };

  const updateQty = (q: number) => {
    setNewQty(q);
    if (pending) setPending(buildPending(pending.item, q, pending.unit, pending.source));
  };

  const updateUnit = (u: Unit) => {
    if (pending) setPending(buildPending(pending.item, pending.qty, u, pending.source));
  };

  const confirm = () => {
    if (!pending) return;
    if (pending.qty <= 0) {
      toast("Quantity must be more than 0", "err");
      return;
    }
    onConfirm(pending);
    setPending(null);
    setStage({ kind: "idle" });
    setTranscript("");
  };

  const quickAddItem = () => {
    if (!stage || stage.kind !== "unmatched") return;
    setNewItem({
      name: stage.rawName,
      unit: stage.unit ?? "pc",
      cost: "",
      sell: "",
      stock: "",
    });
  };

  const saveNewItem = () => {
    if (!newItem) return;
    const cost = parseFloat(newItem.cost);
    const sell = parseFloat(newItem.sell);
    if (!newItem.name.trim()) {
      toast("Item name required", "err");
      return;
    }
    if (isNaN(sell)) {
      toast("Selling price required", "err");
      return;
    }
    const item: Item = {
      id: "u" + Date.now(),
      name: newItem.name.trim(),
      altName: "",
      unit: newItem.unit,
      stock: parseFloat(newItem.stock) || 0,
      costPrice: isNaN(cost) ? sell : cost,
      sellPrice: sell,
    };
    onAddItem(item);
    setPending(buildPending(item, newQty, newItem.unit, item.name));
    setNewItem(null);
    setStage({ kind: "idle" });
    toast(`Item "${item.name}" added to inventory`, "ok");
  };

  const listening = stage.kind === "listening";

  return (
    <section className="rounded-2xl border border-stone-200 bg-white shadow-sm">
      {/* Header */}
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-stone-100 px-5 py-4">
        <div>
          <h2 className="font-display text-lg font-bold text-stone-900">
            बोलकर बिक्री दर्ज करें
          </h2>
          <p className="text-xs text-stone-500">
            Say it like: <span className="font-semibold text-emerald-700">"चीनी दो किलो"</span> or{" "}
            <span className="font-semibold text-emerald-700">"rice 5 kg"</span>
          </p>
        </div>
        <div className="flex items-center gap-2">
          <select
            value={lang}
            onChange={(e) => setLang(e.target.value as "hi-IN" | "en-IN")}
            disabled={listening}
            className="rounded-lg border border-stone-200 bg-white px-2.5 py-1.5 text-xs font-semibold text-stone-700 disabled:opacity-50"
          >
            <option value="hi-IN">हिंदी</option>
            <option value="en-IN">English</option>
          </select>
          <button
            onClick={() => setShowManual((v) => !v)}
            className="inline-flex items-center gap-1.5 rounded-lg border border-stone-200 px-2.5 py-1.5 text-xs font-semibold text-stone-600 hover:bg-stone-50"
          >
            <KeyboardIcon className="h-3.5 w-3.5" /> Type
          </button>
        </div>
      </div>

      <div className="p-5">
        {/* Mic zone */}
        <div className="flex flex-col items-center gap-3">
          <button
            onClick={listening ? stopListening : startListening}
            disabled={!supported}
            aria-label={listening ? "Stop listening" : "Start listening"}
            className={`relative flex h-24 w-24 items-center justify-center rounded-full text-white shadow-lg transition-all ${
              listening
                ? "bg-red-600 shadow-red-200 hover:bg-red-700"
                : "bg-gradient-to-br from-emerald-600 to-emerald-800 shadow-emerald-200 hover:scale-105"
            } disabled:cursor-not-allowed disabled:opacity-50`}
          >
            {listening && (
              <span className="absolute inset-0 animate-ping rounded-full bg-red-500 opacity-30" />
            )}
            {listening ? (
              <StopIcon className="relative h-9 w-9" />
            ) : (
              <MicIcon className="relative h-9 w-9" />
            )}
          </button>

          {!supported ? (
            <p className="text-center text-sm text-red-600">
              Voice input needs Chrome or Edge. Use the Type option above.
            </p>
          ) : listening ? (
            <p className="text-center text-sm font-medium text-red-600">
              सुन रहा हूँ... speak the item and quantity
            </p>
          ) : (
            <p className="text-center text-sm text-stone-500">
              Tap the mic, then speak
            </p>
          )}

          {transcript && (
            <p className="max-w-full rounded-lg bg-stone-100 px-3 py-1.5 text-center text-sm text-stone-700">
              <span className="mr-1 text-stone-400">"</span>
              {transcript}
              <span className="ml-1 text-stone-400">"</span>
            </p>
          )}
        </div>

        {/* Manual entry fallback */}
        {showManual && (
          <form
            onSubmit={(e) => {
              e.preventDefault();
              if (manualText.trim()) {
                processUtterance(manualText);
                setManualText("");
              }
            }}
            className="mt-4 flex gap-2"
          >
            <input
              value={manualText}
              onChange={(e) => setManualText(e.target.value)}
              placeholder='Type e.g. "दूध तीन पैकेट"'
              className="min-w-0 flex-1 rounded-lg border border-stone-200 px-3 py-2 text-sm focus:border-emerald-500 focus:outline-none focus:ring-2 focus:ring-emerald-100"
            />
            <button
              type="submit"
              className="rounded-lg bg-stone-900 px-4 py-2 text-sm font-semibold text-white hover:bg-stone-800"
            >
              दर्ज करें
            </button>
          </form>
        )}

        {/* Pending sale confirmation */}
        {pending && (
          <div className="mt-5 rounded-xl border-2 border-emerald-500 bg-emerald-50/60 p-4">
            <div className="mb-3 flex items-center justify-between">
              <p className="text-xs font-bold tracking-wide text-emerald-700 uppercase">
                Sale ready — confirm to save
              </p>
              <span className="rounded-full bg-emerald-600 px-2 py-0.5 text-[10px] font-bold text-white">
                {pending.item.name}
              </span>
            </div>
            <div className="flex flex-wrap items-center gap-3">
              <div className="flex items-center gap-2">
                <input
                  type="number"
                  min={0.1}
                  step={0.25}
                  value={newQty}
                  onChange={(e) => updateQty(parseFloat(e.target.value) || 0)}
                  className="w-20 rounded-lg border border-emerald-300 bg-white px-2 py-1.5 text-center text-base font-bold text-stone-900 focus:border-emerald-500 focus:outline-none"
                  aria-label="Quantity"
                />
                <select
                  value={pending.unit}
                  onChange={(e) => updateUnit(e.target.value as Unit)}
                  className="rounded-lg border border-emerald-300 bg-white px-2 py-1.5 text-sm font-semibold text-stone-700 focus:outline-none"
                  aria-label="Unit"
                >
                  {Object.entries(UNIT_LABELS).map(([u, l]) => (
                    <option key={u} value={u}>
                      {l.hi} ({l.en})
                    </option>
                  ))}
                </select>
              </div>
              <div className="ml-auto text-right">
                <p className="text-lg font-bold text-stone-900">{inr(pending.amount)}</p>
                <p className="text-xs font-medium text-emerald-700">
                  मुनाफ़ा {inr(pending.profit)}
                </p>
              </div>
            </div>

            {pending.qty > pending.item.stock && (
              <p className="mt-3 flex items-center gap-1.5 rounded-lg bg-amber-100 px-3 py-2 text-xs font-semibold text-amber-800">
                <AlertIcon className="h-4 w-4 shrink-0" />
                Stock में सिर्फ़ {formatQty(pending.item.stock)} {unitLabel(pending.item.unit)} है —
                over-selling दर्ज हो जाएगी
              </p>
            )}

            <div className="mt-4 flex gap-2">
              <button
                onClick={confirm}
                className="inline-flex flex-1 items-center justify-center gap-2 rounded-xl bg-emerald-700 px-4 py-2.5 text-sm font-bold text-white shadow-md shadow-emerald-200 transition hover:bg-emerald-800"
              >
                <CheckIcon className="h-4 w-4" /> खाते में डालें
              </button>
              <button
                onClick={() => {
                  setPending(null);
                  setStage({ kind: "idle" });
                  setTranscript("");
                }}
                className="inline-flex items-center gap-2 rounded-xl border border-stone-200 bg-white px-4 py-2.5 text-sm font-semibold text-stone-600 hover:bg-stone-50"
              >
                <XIcon className="h-4 w-4" /> Cancel
              </button>
            </div>
          </div>
        )}

        {/* Unmatched item */}
        {stage.kind === "unmatched" && !pending && !newItem && (
          <div className="mt-5 rounded-xl border-2 border-amber-400 bg-amber-50/70 p-4">
            <p className="flex items-center gap-2 text-sm font-bold text-amber-800">
              <AlertIcon className="h-4 w-4" />
              "{stage.rawName}" inventory में नहीं मिला
            </p>
            {stage.suggestions.length > 0 && (
              <div className="mt-3">
                <p className="text-xs font-medium text-amber-700">
                  Did you mean one of these?
                </p>
                <div className="mt-2 flex flex-wrap gap-2">
                  {stage.suggestions.map((s) => (
                    <button
                      key={s.id}
                      onClick={() => {
                        const unit = stage.unit ?? s.unit;
                        setPending(buildPending(s, stage.qty, unit, stage.rawName));
                        setNewQty(stage.qty);
                        setStage({ kind: "idle" });
                      }}
                      className="rounded-lg border border-amber-300 bg-white px-3 py-1.5 text-sm font-semibold text-stone-800 hover:border-amber-500 hover:bg-amber-100"
                    >
                      {s.name}
                    </button>
                  ))}
                </div>
              </div>
            )}
            <button
              onClick={quickAddItem}
              className="mt-3 inline-flex items-center gap-2 rounded-xl bg-stone-900 px-4 py-2 text-sm font-semibold text-white hover:bg-stone-800"
            >
              <PlusIcon className="h-4 w-4" /> नया item बनाएँ
            </button>
          </div>
        )}

        {/* Inline new-item form */}
        {newItem && (
          <div className="mt-5 rounded-xl border border-stone-200 bg-stone-50 p-4">
            <p className="mb-3 text-sm font-bold text-stone-800">
              नया item — {newItem.name || "…"}
            </p>
            <div className="grid grid-cols-2 gap-3">
              <label className="col-span-2 text-xs font-semibold text-stone-600">
                नाम / Name
                <input
                  value={newItem.name}
                  onChange={(e) => setNewItem({ ...newItem, name: e.target.value })}
                  className="mt-1 w-full rounded-lg border border-stone-200 bg-white px-3 py-2 text-sm focus:border-emerald-500 focus:outline-none"
                />
              </label>
              <label className="text-xs font-semibold text-stone-600">
                Unit
                <select
                  value={newItem.unit}
                  onChange={(e) => setNewItem({ ...newItem, unit: e.target.value as Unit })}
                  className="mt-1 w-full rounded-lg border border-stone-200 bg-white px-2 py-2 text-sm focus:outline-none"
                >
                  {Object.entries(UNIT_LABELS).map(([u, l]) => (
                    <option key={u} value={u}>
                      {l.hi}
                    </option>
                  ))}
                </select>
              </label>
              <label className="text-xs font-semibold text-stone-600">
                Stock (मात्रा)
                <input
                  type="number"
                  min={0}
                  value={newItem.stock}
                  onChange={(e) => setNewItem({ ...newItem, stock: e.target.value })}
                  placeholder="0"
                  className="mt-1 w-full rounded-lg border border-stone-200 bg-white px-3 py-2 text-sm focus:border-emerald-500 focus:outline-none"
                />
              </label>
              <label className="text-xs font-semibold text-stone-600">
                खरीद भाव (Cost ₹)
                <input
                  type="number"
                  min={0}
                  value={newItem.cost}
                  onChange={(e) => setNewItem({ ...newItem, cost: e.target.value })}
                  placeholder="Optional"
                  className="mt-1 w-full rounded-lg border border-stone-200 bg-white px-3 py-2 text-sm focus:border-emerald-500 focus:outline-none"
                />
              </label>
              <label className="text-xs font-semibold text-stone-600">
                बिक्री भाव (Sell ₹) *
                <input
                  type="number"
                  min={0}
                  value={newItem.sell}
                  onChange={(e) => setNewItem({ ...newItem, sell: e.target.value })}
                  placeholder="Required"
                  className="mt-1 w-full rounded-lg border border-stone-200 bg-white px-3 py-2 text-sm focus:border-emerald-500 focus:outline-none"
                />
              </label>
            </div>
            <div className="mt-4 flex gap-2">
              <button
                onClick={saveNewItem}
                className="flex-1 rounded-xl bg-emerald-700 px-4 py-2.5 text-sm font-bold text-white hover:bg-emerald-800"
              >
                Save & create sale
              </button>
              <button
                onClick={() => {
                  setNewItem(null);
                  setStage({ kind: "idle" });
                }}
                className="rounded-xl border border-stone-200 bg-white px-4 py-2.5 text-sm font-semibold text-stone-600 hover:bg-stone-50"
              >
                Cancel
              </button>
            </div>
          </div>
        )}
      </div>
    </section>
  );
}
