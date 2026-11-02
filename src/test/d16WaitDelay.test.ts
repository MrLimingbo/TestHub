import { expect, it } from 'vitest'
import { parseWaitDelayInput, validateWaitSteps } from '@/utils/waitDelay'
it('TC-D16API-24: decimal and exponent input keeps exact bounded integer semantics', () => {
  for (const [raw, value] of [['10', 10], ['10.0', 10], ['1e1', 10], ['0.1e1', 1], ['10e-1', 1], ['-0', 0], ['0e10000', 0], ['600000.0', 600000]] as const) expect(parseWaitDelayInput(raw)).toEqual({ value })
})
it('TC-D16API-25: tiny fractions, range errors and legacy string values are never rounded away', () => {
  for (const raw of ['1.0000000000000001', '0.00001', '0.1e0', '-0.5', '600001', '1e10000', '1e-10000', 'NaN', '"10"', '']) expect(parseWaitDelayInput(raw).error).toContain('delayMillis')
  for (const delayMillis of ['10', 1.5, null, Infinity]) expect(() => validateWaitSteps([{ id: 'wait', type: 'WAIT', config: { delayMillis } }])).toThrow('delayMillis')
})
