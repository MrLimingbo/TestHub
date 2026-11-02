import { expect, it } from 'vitest'
import { mockIntegerInput, mockIntegerError } from '@/utils/mockIntegerInput'
it('TC-D16MOCK-37: exact decimals and exponents respect each Mock integer range', () => {
  for (const raw of ['10', '10.0', '1e1', '100e-1']) expect(mockIntegerInput(raw, 0, 1000, 'priority')).toBe(10)
  expect(mockIntegerInput('-0', 0, 1000, 'priority')).toBe(0)
  expect(mockIntegerInput('2e2', 200, 599, 'responseStatus')).toBe(200)
  expect(mockIntegerInput('365.0', 1, 365, 'expiresInDays')).toBe(365)
})
it('TC-D16MOCK-35/36: invalid text is rejected before numeric rounding or bounds clipping', () => {
  for (const raw of ['1.5', '1.0000000000000001', '1001', '-1', '1e10000', '1e-10000', 'NaN', '"10"', '']) expect(mockIntegerError(raw, 0, 1000, 'priority')).toContain('priority')
  expect(() => mockIntegerInput('0', 1, 365, 'expiresInDays')).toThrow('expiresInDays')
  expect(() => mockIntegerInput('199', 200, 599, 'responseStatus')).toThrow('responseStatus')
})
