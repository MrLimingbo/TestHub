import { describe, expect, it } from 'vitest'
import {
  apiTreeDisplayText,
  availableCatalogMoveTargets,
  buildApiManagementTreeRows,
  catalogDescendantIds,
  interfaceRouteQuery,
  nextApiCopyPath,
  nextWorkspaceTabAfterClose,
} from '@/utils/apiManagementTree'

const catalogs = [
  { id: 'root', parentId: null, name: '订单服务', sortOrder: 0 },
  { id: 'child', parentId: 'root', name: '查询接口', sortOrder: 0 },
  { id: 'other', parentId: null, name: '库存服务', sortOrder: 1 },
]
const definitions = [
  { id: 'create', catalogId: 'root', method: 'POST', path: '/orders', name: '创建订单', status: 'PUBLISHED' },
  { id: 'detail', catalogId: 'child', method: 'GET', path: '/orders/{id}', name: '订单详情', status: 'DRAFT' },
  { id: 'stock', catalogId: 'other', method: 'GET', path: '/stocks', name: '库存列表', status: 'PUBLISHED' },
]

describe('api management tree', () => {
  it('mixes nested catalogs and interfaces while honoring expansion state', () => {
    const rows = buildApiManagementTreeRows(catalogs, definitions, new Set(['root', 'child']))
    expect(rows.map((row) => row.key)).toEqual([
      'catalog:root',
      'catalog:child',
      'api:detail',
      'api:create',
      'catalog:other',
    ])
    expect(rows[0]).toMatchObject({ kind: 'catalog', count: 2, expanded: true })
  })

  it('keeps ancestor catalogs visible and expanded when searching an interface', () => {
    const rows = buildApiManagementTreeRows(catalogs, definitions, new Set(), '详情')
    expect(rows.map((row) => row.key)).toEqual(['catalog:root', 'catalog:child', 'api:detail'])
    expect(rows.filter((row) => row.kind === 'catalog').every((row) => row.expanded)).toBe(true)
  })

  it('supports published and draft filters', () => {
    const published = buildApiManagementTreeRows(catalogs, definitions, new Set(['root', 'other']), '', 'PUBLISHED')
    const draft = buildApiManagementTreeRows(catalogs, definitions, new Set(), '', 'DRAFT')
    expect(published.filter((row) => row.kind === 'api').map((row) => row.api.id)).toEqual(['create', 'stock'])
    expect(draft.filter((row) => row.kind === 'api').map((row) => row.api.id)).toEqual(['detail'])
  })
})

describe('workspace tabs', () => {
  it('selects the neighboring tab when the active tab closes', () => {
    expect(nextWorkspaceTabAfterClose(['one', 'two', 'three'], 'two', 'two')).toEqual({
      openIds: ['one', 'three'],
      activeId: 'three',
    })
    expect(nextWorkspaceTabAfterClose(['one', 'two'], 'one', 'two')).toEqual({
      openIds: ['one'],
      activeId: 'one',
    })
  })

  it('removes quick-request state while preserving interface route signals', () => {
    expect(interfaceRouteQuery({
      draft: 'draft-1',
      newRequest: '123',
      folderId: 'folder-1',
      source: 'interface-menu',
      apiId: 'old-api',
      caseRequest: '7',
      view: 'document',
    }, 'api-2')).toEqual({
      caseRequest: '7',
      view: 'document',
      apiId: 'api-2',
    })
  })
})

describe('api tree actions', () => {
  it('switches interface labels between name and URL', () => {
    expect(apiTreeDisplayText(definitions[0], 'NAME')).toBe('创建订单')
    expect(apiTreeDisplayText(definitions[0], 'URL')).toBe('/orders')
  })

  it('excludes the current catalog and its descendants from move targets', () => {
    expect([...catalogDescendantIds(catalogs, 'root')]).toEqual(['child'])
    expect(availableCatalogMoveTargets(catalogs, 'root').map((catalog) => catalog.id)).toEqual(['other'])
  })

  it('generates a conflict-free path for copied interfaces', () => {
    const existing = [
      ...definitions,
      { ...definitions[0], id: 'copy-1', path: '/orders-copy' },
      { ...definitions[0], id: 'copy-2', path: '/orders-copy-2' },
    ]
    expect(nextApiCopyPath('POST', '/orders', existing)).toBe('/orders-copy-3')
    expect(nextApiCopyPath('GET', '/orders', definitions)).toBe('/orders-copy')
  })
})
