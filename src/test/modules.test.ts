import { describe, expect, it } from 'vitest'
import { modules } from '@/mock/modules'

describe('module catalogue', () => {
  it('provides usable high-fidelity data for every generic module', () => {
    expect(Object.keys(modules)).toHaveLength(11)
    for (const module of Object.values(modules)) {
      expect(module.title).toBeTruthy()
      expect(module.metrics).toHaveLength(4)
      expect(module.rows.length).toBeGreaterThan(2)
    }
  })
})
