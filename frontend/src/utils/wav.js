/**
 * 最小 WAV 编码：Float32 PCM → 16kHz 单声道 16bit WAV。
 * 抽成独立模块是为了能脱离浏览器单独验证（云 ASR 对格式很挑，头部错一个字节就整段失败）。
 */

export const TARGET_RATE = 16000

export function encodeWav(samples, sampleRate = TARGET_RATE) {
  const buffer = new ArrayBuffer(44 + samples.length * 2)
  const view = new DataView(buffer)
  const writeStr = (offset, str) => {
    for (let i = 0; i < str.length; i++) view.setUint8(offset + i, str.charCodeAt(i))
  }

  writeStr(0, 'RIFF')
  view.setUint32(4, 36 + samples.length * 2, true)   // 文件长度 - 8
  writeStr(8, 'WAVE')
  writeStr(12, 'fmt ')
  view.setUint32(16, 16, true)                       // fmt 块长度
  view.setUint16(20, 1, true)                        // PCM
  view.setUint16(22, 1, true)                        // 单声道
  view.setUint32(24, sampleRate, true)
  view.setUint32(28, sampleRate * 2, true)           // 字节率
  view.setUint16(32, 2, true)                        // 块对齐
  view.setUint16(34, 16, true)                       // 位深
  writeStr(36, 'data')
  view.setUint32(40, samples.length * 2, true)

  let offset = 44
  for (let i = 0; i < samples.length; i++) {
    const s = Math.max(-1, Math.min(1, samples[i]))
    view.setInt16(offset, s < 0 ? s * 0x8000 : s * 0x7fff, true)
    offset += 2
  }
  return new Blob([view], { type: 'audio/wav' })
}

/** 取窗口平均值降采样，相当于一个简单低通，语音场景够用 */
export function downsample(input, fromRate, toRate = TARGET_RATE) {
  if (fromRate === toRate) return input
  const ratio = fromRate / toRate
  const outLen = Math.floor(input.length / ratio)
  const out = new Float32Array(outLen)
  for (let i = 0; i < outLen; i++) {
    const start = Math.floor(i * ratio)
    const end = Math.min(Math.floor((i + 1) * ratio), input.length)
    let sum = 0
    for (let j = start; j < end; j++) sum += input[j]
    out[i] = sum / Math.max(1, end - start)
  }
  return out
}

/** 拼多段 Float32 */
export function concatFloat32(chunks) {
  const total = chunks.reduce((n, c) => n + c.length, 0)
  const merged = new Float32Array(total)
  let offset = 0
  for (const c of chunks) {
    merged.set(c, offset)
    offset += c.length
  }
  return merged
}
