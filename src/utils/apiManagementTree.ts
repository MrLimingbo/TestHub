export interface ApiTreeCatalog {
  id: string
  parentId: string | null
  name: string
  sortOrder: number
}

export interface ApiTreeDefinition {
  id: string
  catalogId: string
  method: string
  path: string
  name: string
  status: string
}

export type ApiManagementTreeRow<C extends ApiTreeCatalog, A extends ApiTreeDefinition> =
  | { kind: 'catalog'; key: string; depth: number; catalog: C; count: number; expanded: boolean }
  | { kind: 'api'; key: string; depth: number; api: A }

export type ApiTreeStatusFilter = 'ALL' | 'PUBLISHED' | 'DRAFT'
export type ApiTreeDisplayMode = 'NAME' | 'URL'
export type ApiRouteQuery = Record<string, string | null | (string | null)[]>

const QUICK_REQUEST_QUERY_KEYS = new Set(['draft', 'newRequest', 'folderId', 'source'])

function matchesStatus(api: ApiTreeDefinition, status: ApiTreeStatusFilter) {
  if (status === 'ALL') return true
  return status === 'PUBLISHED' ? api.status === 'PUBLISHED' : api.status !== 'PUBLISHED'
}

function matchesKeyword(api: ApiTreeDefinition, keyword: string) {
  if (!keyword) return true
  return `${api.name} ${api.method} ${api.path}`.toLocaleLowerCase().includes(keyword)
}

export function buildApiManagementTreeRows<C extends ApiTreeCatalog, A extends ApiTreeDefinition>(
  catalogs: C[],
  definitions: A[],
  expandedCatalogIds: ReadonlySet<string>,
  rawKeyword = '',
  status: ApiTreeStatusFilter = 'ALL',
): ApiManagementTreeRow<C, A>[] {
  const keyword = rawKeyword.trim().toLocaleLowerCase()
  const sortedCatalogs = [...catalogs].sort(
    (left, right) => left.sortOrder - right.sortOrder || left.name.localeCompare(right.name),
  )
  const visibleDefinitions = definitions.filter(
    (api) => matchesStatus(api, status) && matchesKeyword(api, keyword),
  )
  const definitionsByCatalog = new Map<string, A[]>()
  visibleDefinitions.forEach((api) => {
    definitionsByCatalog.set(api.catalogId, [...(definitionsByCatalog.get(api.catalogId) ?? []), api])
  })
  definitionsByCatalog.forEach((items) => items.sort(
    (left, right) => left.name.localeCompare(right.name) || left.method.localeCompare(right.method),
  ))

  const catalogsByParent = new Map<string | null, C[]>()
  sortedCatalogs.forEach((catalog) => {
    catalogsByParent.set(catalog.parentId, [...(catalogsByParent.get(catalog.parentId) ?? []), catalog])
  })
  const countCache = new Map<string, number>()
  const visiting = new Set<string>()
  const countDefinitions = (catalogId: string): number => {
    const cached = countCache.get(catalogId)
    if (cached !== undefined) return cached
    if (visiting.has(catalogId)) return 0
    visiting.add(catalogId)
    const count = (definitionsByCatalog.get(catalogId)?.length ?? 0)
      + (catalogsByParent.get(catalogId) ?? []).reduce(
        (total, child) => total + countDefinitions(child.id),
        0,
      )
    visiting.delete(catalogId)
    countCache.set(catalogId, count)
    return count
  }

  const rows: ApiManagementTreeRow<C, A>[] = []
  const rendered = new Set<string>()
  const visit = (parentId: string | null, depth: number) => {
    for (const catalog of catalogsByParent.get(parentId) ?? []) {
      if (rendered.has(catalog.id)) continue
      rendered.add(catalog.id)
      const count = countDefinitions(catalog.id)
      const nameMatches = keyword && catalog.name.toLocaleLowerCase().includes(keyword)
      if ((keyword || status !== 'ALL') && !count && !nameMatches) continue
      const expanded = Boolean(keyword || status !== 'ALL') || expandedCatalogIds.has(catalog.id)
      rows.push({ kind: 'catalog', key: `catalog:${catalog.id}`, depth, catalog, count, expanded })
      if (!expanded) continue
      visit(catalog.id, depth + 1)
      for (const api of definitionsByCatalog.get(catalog.id) ?? []) {
        rows.push({ kind: 'api', key: `api:${api.id}`, depth: depth + 1, api })
      }
    }
  }
  visit(null, 0)

  for (const api of visibleDefinitions) {
    if (!catalogs.some((catalog) => catalog.id === api.catalogId)) {
      rows.push({ kind: 'api', key: `api:${api.id}`, depth: 0, api })
    }
  }
  return rows
}

export function nextWorkspaceTabAfterClose(openIds: string[], activeId: string, closingId: string) {
  const closingIndex = openIds.indexOf(closingId)
  const remaining = openIds.filter((id) => id !== closingId)
  if (activeId !== closingId) return { openIds: remaining, activeId }
  return {
    openIds: remaining,
    activeId: remaining[Math.min(Math.max(closingIndex, 0), remaining.length - 1)] ?? '',
  }
}

export function interfaceRouteQuery(query: ApiRouteQuery, apiId: string): ApiRouteQuery {
  const nextQuery = Object.fromEntries(
    Object.entries(query).filter(([key]) => key !== 'apiId' && !QUICK_REQUEST_QUERY_KEYS.has(key)),
  ) as ApiRouteQuery
  if (apiId) nextQuery.apiId = apiId
  return nextQuery
}

export function apiTreeDisplayText(api: ApiTreeDefinition, mode: ApiTreeDisplayMode) {
  return mode === 'URL' ? api.path : api.name
}

export function catalogDescendantIds(catalogs: ApiTreeCatalog[], catalogId: string) {
  const descendants = new Set<string>()
  let found = true
  while (found) {
    found = false
    for (const catalog of catalogs) {
      if (catalog.parentId && (catalog.parentId === catalogId || descendants.has(catalog.parentId)) && !descendants.has(catalog.id)) {
        descendants.add(catalog.id)
        found = true
      }
    }
  }
  return descendants
}

export function availableCatalogMoveTargets(catalogs: ApiTreeCatalog[], catalogId: string) {
  const excluded = catalogDescendantIds(catalogs, catalogId)
  excluded.add(catalogId)
  return catalogs.filter((catalog) => !excluded.has(catalog.id))
}

export function nextApiCopyPath(method: string, path: string, definitions: ApiTreeDefinition[]) {
  const normalizedMethod = method.toUpperCase()
  const existing = new Set(definitions
    .filter((api) => api.method.toUpperCase() === normalizedMethod)
    .map((api) => api.path))
  const base = `${path.replace(/\/+$/, '') || '/'}-copy`
  let candidate = base
  let suffix = 2
  while (existing.has(candidate)) candidate = `${base}-${suffix++}`
  return candidate
}
