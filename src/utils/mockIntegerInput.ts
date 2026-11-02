/** Check decimal text before Number conversion can round away a fractional part. */
export function mockIntegerInput(raw: string, minimum: number, maximum: number, field: string): number {
  const invalid = () => new Error(`${field} 必须是 ${minimum} 到 ${maximum} 之间的精确整数数值`)
  const match = /^(-?)(0|[1-9]\d*)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(raw.trim())
  if (!match) throw invalid()
  const fraction = match[3] ?? ''
  let digits = (match[2]! + fraction).replace(/^0+/, '')
  if (!digits) { if (minimum === 0) return 0; throw invalid() }
  if (match[1] === '-') throw invalid()
  const scale = BigInt(fraction.length) - BigInt(match[4] ?? '0')
  if (scale > 0n) {
    if (scale >= BigInt(digits.length)) throw invalid()
    const removed = Number(scale)
    if (!/^0+$/.test(digits.slice(-removed))) throw invalid()
    digits = digits.slice(0, -removed)
  } else if (scale < 0n) {
    if (BigInt(digits.length) - scale > BigInt(String(maximum).length)) throw invalid()
    digits += '0'.repeat(Number(-scale))
  }
  if (digits.length > String(maximum).length) throw invalid()
  const value = Number(digits)
  if (value < minimum || value > maximum) throw invalid()
  return value
}

export function mockIntegerError(raw: string, minimum: number, maximum: number, field: string): string {
  try { mockIntegerInput(raw, minimum, maximum, field); return '' }
  catch (error) { return error instanceof Error ? error.message : `${field} 不合法` }
}
