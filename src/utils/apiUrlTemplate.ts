// Keep runtime placeholders visible to the executor; encode only literal text.
export function encodeApiUrlComponent(value: string): string {
  return value.split(/(\{\{\s*[^{}\s]+\s*}})/g)
    .map((part, index) => index % 2 ? part : encodeURIComponent(part))
    .join('')
}
