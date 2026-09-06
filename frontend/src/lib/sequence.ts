import { isLosslessNumber, parse } from 'lossless-json'

const LONG_MAX = 9223372036854775807n

/** REST and SSE share this parser; unsafe integer tokens never pass through a JS number. */
export function parseJson(text: string): unknown {
  return parse(text, (key, parsed) => {
    if (!isLosslessNumber(parsed)) return parsed
    const value = parsed.value
    // Protocol integers must retain their original decimal lexeme until validation.
    // Converting 1.0 / 1e0 to Number first would silently accept malformed cursors.
    if (['sequenceNo', 'lastEventSequence', 'lastSentSequence', 'expectedSequence', 'actualSequence', 'chunkIndex'].includes(key)) sequence(value)
    if (/^-?\d+$/.test(value)) {
      const integer = BigInt(value)
      return integer > BigInt(Number.MAX_SAFE_INTEGER) || integer < BigInt(Number.MIN_SAFE_INTEGER)
        ? value : Number(value)
    }
    const numeric = Number(value)
    if (!Number.isFinite(numeric)) throw new Error('JSON number is not finite')
    return numeric
  })
}

/** Canonical nonnegative Java long, stored and compared only as decimal strings/BigInt. */
export function sequence(value: unknown): string {
  if (typeof value === 'number') {
    if (!Number.isSafeInteger(value) || value < 0) throw new Error('Invalid event sequence')
    value = String(value)
  }
  if (typeof value !== 'string' || !/^(0|[1-9]\d*)$/.test(value) || value.length > 19 || BigInt(value) > LONG_MAX) {
    throw new Error('Invalid event sequence')
  }
  return value
}

export function resourceId(value: unknown): string {
  // Public IDs are strings; accepting already-rounded numbers would mask a broken wire contract.
  if (typeof value !== 'string' || sequence(value) === '0') throw new Error('Invalid resource ID')
  return value
}

export function compareSequence(a: string, b: string): number {
  const left = BigInt(sequence(a)), right = BigInt(sequence(b))
  return left < right ? -1 : left > right ? 1 : 0
}
export function nextSequence(value: string): string { return sequence(String(BigInt(sequence(value)) + 1n)) }
