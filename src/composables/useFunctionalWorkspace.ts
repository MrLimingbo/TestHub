import type { ComputedRef, InjectionKey, Ref } from 'vue'
import { inject } from 'vue'

export interface FunctionalCatalog {
  id: string
  scope: 'REQUIREMENTS' | 'CASES' | 'PLANS' | 'GOVERNANCE' | 'DELIVERY'
  parentId: string | null
  name: string
  sortOrder: number
}

export interface FunctionalRequirement {
  id: string
  displayId: number
  catalogId: string
  requirementCode: string
  title: string
  priority: string
  status: string
  description: string
  updatedAt: string
}

export interface FunctionalCase {
  id: string
  displayId: number
  catalogId: string
  requirementId: string | null
  caseCode: string
  caseType: string
  title: string
  priority: string
  status: string
  precondition: string
  steps: string
  expectedResult: string
  createdBy: string
  createdByName: string
  createdAt: string
  executedBy: string | null
  executedByName: string | null
  executedAt: string | null
  updatedAt: string
}

export interface FunctionalDefectSummary {
  id: string
  catalogId: string | null
}

export interface FunctionalPlanSummary {
  id: string
  catalogId: string | null
}

export interface FunctionalWorkspace {
  catalogs: Ref<FunctionalCatalog[]>
  requirements: Ref<FunctionalRequirement[]>
  cases: Ref<FunctionalCase[]>
  defects: Ref<FunctionalDefectSummary[]>
  plans: Ref<FunctionalPlanSummary[]>
  selectedCatalogId: Ref<string>
  selectedCatalogName: ComputedRef<string>
  loading: Ref<boolean>
  reload: () => Promise<void>
}

export const functionalWorkspaceKey: InjectionKey<FunctionalWorkspace> = Symbol('functional-workspace')

export function useFunctionalWorkspace(): FunctionalWorkspace {
  const workspace = inject(functionalWorkspaceKey)
  if (!workspace) throw new Error('功能测试工作区上下文不可用')
  return workspace
}
