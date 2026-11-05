// CPU percentages use a single logical core as 100%; RSS tree sums include shared pages.
export function cpuSeconds(value) {
  const match = /^(?:(\d+)-)?(?:(\d+):)?(\d+):(\d+(?:\.\d+)?)$/.exec(value)
  if (!match) throw new Error('Unrecognized cumulative CPU time')
  return Number(match[1] || 0) * 86400 + Number(match[2] || 0) * 3600 + Number(match[3]) * 60 + Number(match[4])
}
export function parsePs(text) {
  return text.trim().split('\n').filter(Boolean).map(line => {
    const m = /^\s*(\d+)\s+(\d+)\s+(\d+)\s+(\S+)\s+(.+)$/.exec(line)
    if (!m) throw new Error('Unrecognized process metric row')
    return { pid: +m[1], ppid: +m[2], rssKiB: +m[3], cpuSeconds: cpuSeconds(m[4]), executable: m[5] }
  })
}
export function ownedTree(rows, root) {
  const ids = new Set([root]); let changed = true
  while (changed) { changed = false; for (const p of rows) if (ids.has(p.ppid) && !ids.has(p.pid)) { ids.add(p.pid); changed = true } }
  return rows.filter(p => ids.has(p.pid))
}
export function intervalCpu(current, previous, elapsedSeconds) {
  if (!previous || elapsedSeconds <= 0 || current.cpuSeconds < previous.cpuSeconds) return null
  return (current.cpuSeconds - previous.cpuSeconds) / elapsedSeconds * 100
}
export function nearestRank(values, p) {
  const sorted = values.filter(Number.isFinite).sort((a, b) => a - b)
  return sorted.length ? sorted[Math.max(0, Math.ceil(p * sorted.length) - 1)] : null
}
export function distribution(values) {
  const n = values.filter(Number.isFinite).length
  return { n, p50: nearestRank(values, .5), p95: nearestRank(values, .95), p99: nearestRank(values, .99), max: n ? Math.max(...values) : null,
    p95SampleSufficient: n >= 100, p99SampleSufficient: n >= 1000, complianceAssessed: false }
}
export function trend(points) {
  const p = points.filter(([x, y]) => Number.isFinite(x) && Number.isFinite(y))
  if (!p.length) return { n: 0, median: null, peak: null, slopePerMinute: null }
  const meanX = p.reduce((a, b) => a + b[0], 0) / p.length, meanY = p.reduce((a, b) => a + b[1], 0) / p.length
  const denominator = p.reduce((a, b) => a + (b[0] - meanX) ** 2, 0)
  return { n: p.length, median: nearestRank(p.map(x => x[1]), .5), peak: Math.max(...p.map(x => x[1])),
    slopePerMinute: denominator ? p.reduce((a, b) => a + (b[0] - meanX) * (b[1] - meanY), 0) / denominator * 60 : null }
}
export function completeness(samples, durationSeconds, stepSeconds = 5) {
  const expected = Math.floor(durationSeconds / stepSeconds)
  // One delayed sample cannot compensate for another missed scheduled slot.
  const slots = new Set(samples.filter(x => x.mandatoryValid && x.elapsedSeconds > 0 && x.elapsedSeconds <= durationSeconds + .5)
    .map(x => Math.round(x.elapsedSeconds / stepSeconds)).filter(x => x >= 1 && x <= expected))
  let longestGap = 0, last = 0
  for (const slot of [...slots].sort((a, b) => a - b)) { longestGap = Math.max(longestGap, (slot - last) * stepSeconds); last = slot }
  longestGap = Math.max(longestGap, durationSeconds - last * stepSeconds)
  const ratio = slots.size / expected
  return { expected, present: slots.size, ratio, longestGapSeconds: longestGap, valid: ratio >= .99 && longestGap < 60 }
}
// A pause or missing observation must not count towards continuous low-interference time.
export class QuietGate {
  constructor() { this.since = null; this.previous = null }
  observe(atSeconds, busyCapacityPercent) {
    const continuous = this.previous !== null && atSeconds > this.previous && atSeconds - this.previous <= 10
    if (!continuous || !Number.isFinite(busyCapacityPercent) || busyCapacityPercent >= 25) this.since = null
    if (Number.isFinite(busyCapacityPercent) && busyCapacityPercent < 25) this.since ??= atSeconds
    this.previous = atSeconds
    const quietSeconds = this.since === null ? 0 : atSeconds - this.since
    return { quietSeconds, ready: quietSeconds >= 120 }
  }
}
export const sequence = Object.freeze([
  ...Array(40).fill('taskList'), ...Array(20).fill('planDetail'), ...Array(20).fill('qualityReport'),
  ...Array(10).fill('taskUpdate'), ...Array(5).fill('planCreate'), ...Array(5).fill('planDelete'),
])
