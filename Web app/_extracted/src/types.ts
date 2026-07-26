export interface Item {
  id: string;
  name: string; // हिंदी नाम, e.g. "चीनी"
  altName: string; // English alias for matching, e.g. "sugar"
  unit: Unit;
  stock: number;
  costPrice: number; // per unit (खरीद भाव)
  sellPrice: number; // per unit (बिक्री भाव)
}

export interface Sale {
  id: string;
  itemId: string;
  itemName: string;
  unitLabel: string;
  qty: number;
  amount: number;
  profit: number;
  time: number; // epoch ms
}

export type Unit = "kg" | "g" | "L" | "ml" | "pc" | "pkt" | "btl" | "dozen" | "bag";

export interface ParsedUtterance {
  qty: number;
  qtyFound: boolean;
  unit: Unit | null;
  rawName: string;
}

export interface PendingSale {
  item: Item;
  qty: number;
  unit: Unit;
  amount: number;
  profit: number;
  source: string; // the utterance that produced it
}

export interface MatchResult {
  item: Item | null;
  suggestions: Item[];
  confidence: "exact" | "fuzzy" | "none";
}
