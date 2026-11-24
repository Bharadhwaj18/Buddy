// Small display helpers: "today 4:00 pm", "Tomorrow", "Mon 23 Nov".

const DAY = (d: Date) => d.toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short" });

export function timeOf(ms: number): string {
  return new Date(ms).toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit" }).toLowerCase();
}

/** "today 4:00 pm", "tomorrow", "Mon 23 Nov 9:00 am". */
export function whenText(ms: number, withTime = true): string {
  const d = new Date(ms);
  const now = new Date();
  const sameDay = (a: Date, b: Date) => a.toDateString() === b.toDateString();
  const tomorrow = new Date(now);
  tomorrow.setDate(now.getDate() + 1);
  const day = sameDay(d, now) ? "today" : sameDay(d, tomorrow) ? "tomorrow" : DAY(d);
  return withTime ? `${day} ${timeOf(ms)}` : day;
}

export function capitalise(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1);
}

export function greeting(now = new Date()): string {
  const h = now.getHours();
  if (h >= 5 && h < 12) return "Good morning";
  if (h >= 12 && h < 17) return "Good afternoon";
  return "Good evening";
}

export function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!);
}
