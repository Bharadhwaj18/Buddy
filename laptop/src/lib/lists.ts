// Which of Buddy's lists (plans) this laptop is about. By default only "Work": the pill shows those,
// only their reminders pop up here, and anything added on the laptop is filed there.

export interface PlanInfo {
  id: string;
  name: string;
  /** The list's first column, where new things go. */
  bucketId: string | null;
}

const PLANS = "buddy-plans";

/** The lists as last read from the server (so the laptop knows them offline, straight after a restart). */
export function cachedPlans(): PlanInfo[] {
  try {
    const p = JSON.parse(localStorage.getItem(PLANS) ?? "[]");
    return Array.isArray(p) ? (p as PlanInfo[]) : [];
  } catch {
    return [];
  }
}

export function cachePlans(plans: PlanInfo[]): void {
  try {
    localStorage.setItem(PLANS, JSON.stringify(plans));
  } catch {
    /* ignore */
  }
}

/**
 * The lists this laptop shows, in the server's order: the ones picked in settings, else Work, else all.
 * Null when the lists aren't known yet (then nothing is hidden).
 */
export function chosenLists(plans: PlanInfo[], picked: string[] | null): string[] | null {
  if (plans.length === 0) return null;
  if (picked) {
    const kept = plans.filter((p) => picked.includes(p.id)).map((p) => p.id);
    if (kept.length > 0) return kept;
  }
  const work = plans.filter((p) => p.name.trim().toLowerCase() === "work");
  return (work.length > 0 ? work : plans).map((p) => p.id);
}
