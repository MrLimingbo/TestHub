export interface ApiErrorPayload {
  code?: string
  message?: string
  details?: Record<string, unknown>
}

export class ApiRequestError extends Error {
  constructor(
    readonly status: number,
    readonly code: string | undefined,
    readonly details: Record<string, string>,
    message: string,
  ) {
    super(message)
    this.name = 'ApiRequestError'
  }
}

export function toApiRequestError(response: Response, body: ApiErrorPayload): ApiRequestError {
  const details = Object.fromEntries(
    Object.entries(body.details ?? {}).flatMap(([field, value]) => {
      const message = typeof value === 'string' ? value : ''
      return message ? [[field, message]] : []
    }),
  )
  return new ApiRequestError(response.status, body.code, details, body.message ?? '请求失败，请稍后重试')
}

export function formErrorsFrom(error: unknown): Record<string, string> {
  return error instanceof ApiRequestError ? error.details : {}
}

export function clearFormErrors(errors: Record<string, string>) {
  Object.keys(errors).forEach((field) => delete errors[field])
}
