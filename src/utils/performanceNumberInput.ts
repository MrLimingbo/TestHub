const integerFields = ['threadCount', 'rampUpSeconds', 'durationSeconds', 'stepCount', 'stepIntervalSeconds', 'targetTps', 'p95ThresholdMillis', 'expectedStatusCode', 'rendezvousUsers'] as const
export type PerformanceNumberField = typeof integerFields[number] | 'errorThresholdPercent'
export type PerformanceNumberText = Record<PerformanceNumberField, string>
const fields: PerformanceNumberField[] = [...integerFields, 'errorThresholdPercent']
const parts = (raw: string, field: string) => {
  const match = /^(-?)(0|[1-9]\d*)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(raw.trim())
  if (!match) throw new Error(`${field} 必须是精确数值`)
  return { negative: match[1] === '-', digits: (match[2]! + (match[3] ?? '')).replace(/^0+/, '') || '0', scale: BigInt((match[3] ?? '').length) - BigInt(match[4] ?? '0') }
}
export function performanceIntegerInput(raw: string, minimum: number, maximum: number, field: string): number {
  const fail = () => new Error(`${field} 必须是 ${minimum} 到 ${maximum} 之间的精确整数数值`)
  const value = parts(raw, field); let digits = value.digits
  if (digits === '0') { if (minimum === 0) return 0; throw fail() }
  if (value.negative) throw fail()
  if (value.scale > 0n) {
    if (value.scale >= BigInt(digits.length)) throw fail()
    const removed = Number(value.scale)
    if (!/^0+$/.test(digits.slice(-removed))) throw fail()
    digits = digits.slice(0, -removed)
  } else if (value.scale < 0n) {
    if (BigInt(digits.length) - value.scale > BigInt(String(maximum).length)) throw fail()
    digits += '0'.repeat(Number(-value.scale))
  }
  if (digits.length > String(maximum).length) throw fail()
  const result = Number(digits)
  if (result < minimum || result > maximum) throw fail()
  return result
}
export function performancePercentInput(raw: string): number {
  const fail = () => new Error('errorThresholdPercent 必须是 0 到 100 之间、最多两位小数的数值')
  const value = parts(raw, 'errorThresholdPercent')
  if (value.scale > 2n || (value.negative && value.digits !== '0')) throw fail()
  if (value.digits === '0') return 0
  const zeros = 2n - value.scale
  if (BigInt(value.digits.length) + zeros > 5n) throw fail()
  const scaled = BigInt(value.digits + '0'.repeat(Number(zeros)))
  if (scaled > 10000n) throw fail()
  return Number(scaled) / 100
}
export function performanceNumberText(source: Partial<Record<PerformanceNumberField, number | null>>): PerformanceNumberText {
  return Object.fromEntries(fields.map(field => [field, source[field] == null ? '' : String(source[field])])) as PerformanceNumberText
}
function range(raw: PerformanceNumberText, field: PerformanceNumberField): [number, number] {
  const bounded = (value: string, fallback: number) => { try { return performanceIntegerInput(value, 1, 100000, field) } catch { return fallback } }
  switch (field) {
    case 'threadCount': return [1, 100000]
    case 'rampUpSeconds': return [0, 86400]
    case 'durationSeconds': return [10, 86400]
    case 'stepCount': return [2, Math.min(bounded(raw.threadCount, 100000), 20)]
    case 'stepIntervalSeconds': return [1, 86400]
    case 'targetTps': return [1, 1000000]
    case 'p95ThresholdMillis': return [1, 600000]
    case 'expectedStatusCode': return [100, 599]
    case 'rendezvousUsers': return [2, bounded(raw.threadCount, 100000)]
    case 'errorThresholdPercent': return [0, 100]
  }
}
function value(raw: PerformanceNumberText, field: PerformanceNumberField, model: string): number | undefined {
  if ((field === 'stepCount' || field === 'stepIntervalSeconds') && model !== 'STEPPED_RAMP') return undefined
  if (field === 'rendezvousUsers' && model === 'STEPPED_RAMP') return undefined
  const required = ['threadCount', 'rampUpSeconds', 'durationSeconds', ...(model === 'STEPPED_RAMP' ? ['stepCount', 'stepIntervalSeconds'] : [])].includes(field)
  if (!raw[field].trim() && !required) return undefined
  if (field === 'errorThresholdPercent') return performancePercentInput(raw[field])
  const [minimum, maximum] = range(raw, field)
  return performanceIntegerInput(raw[field], minimum, maximum, field)
}
export function performanceNumberError(raw: PerformanceNumberText, field: PerformanceNumberField, model: string): string {
  try { value(raw, field, model); return '' }
  catch (error) { return error instanceof Error ? error.message : `${field} 不合法` }
}
export function performanceGroupNumbers(raw: PerformanceNumberText, model: string, index: number) {
  const result: Partial<Record<PerformanceNumberField, number>> = {}
  for (const field of fields) {
    try { result[field] = value(raw, field, model) }
    catch (error) { throw new Error(`线程组 ${index + 1}：${error instanceof Error ? error.message : field + ' 不合法'}`) }
  }
  return result
}
