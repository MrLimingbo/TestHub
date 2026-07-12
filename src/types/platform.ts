export interface MetricCard {
  label: string
  value: string
  delta: string
  tone: 'blue' | 'green' | 'orange' | 'violet'
}

export interface TableColumn {
  title: string
  dataIndex: string
  width?: number
}

export interface ModuleDefinition {
  key: string
  title: string
  eyebrow: string
  description: string
  action: string
  metrics: MetricCard[]
  columns: TableColumn[]
  rows: Record<string, string | number>[]
}
