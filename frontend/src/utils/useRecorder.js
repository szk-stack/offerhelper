import { useCallback, useEffect, useRef, useState } from 'react'
import { encodeWav, downsample, concatFloat32, TARGET_RATE } from './wav'

/**
 * 麦克风录音 + 静音切句，输出 16kHz 单声道 WAV。
 *
 * 为什么不用 MediaRecorder：它在 Chrome 只出 webm/opus，而云 ASR 基本只收 wav/mp3，
 * 后端转码要么引 ffmpeg 要么引音频库，都不划算。直接用 Web Audio 取 PCM 自己编码最省。
 *
 * 切句：检测到说话后，停顿 silenceMs 毫秒视为一句结束立刻回调；
 * 单段超过 maxSegmentMs 强制切，避免一口气说太久。
 */
export function useRecorder({
  onSegment,
  silenceMs = 800,
  minSpeechMs = 350,
  maxSegmentMs = 45000,
  threshold = 0.012,
} = {}) {
  const [active, setActive] = useState(false)
  const [level, setLevel] = useState(0)
  const [error, setError] = useState(null)
  const supported = typeof navigator !== 'undefined'
    && !!navigator.mediaDevices?.getUserMedia
    && !!(window.AudioContext || window.webkitAudioContext)

  const ctxRef = useRef(null)
  const streamRef = useRef(null)
  const nodeRef = useRef(null)
  const onSegmentRef = useRef(onSegment)
  const activeRef = useRef(false)
  const bufRef = useRef([])          // 当前句已累积的片段
  const preRollRef = useRef([])      // 说话前的缓冲，避免切掉字头
  const speechRef = useRef(false)
  const lastVoiceRef = useRef(0)
  const bufStartRef = useRef(0)

  useEffect(() => { onSegmentRef.current = onSegment }, [onSegment])

  const flush = useCallback(() => {
    const chunks = bufRef.current
    const startedAt = bufStartRef.current
    bufRef.current = []
    preRollRef.current = []
    speechRef.current = false
    if (chunks.length === 0) return

    const merged = concatFloat32(chunks)
    if (merged.length === 0) return

    const rate = ctxRef.current?.sampleRate || TARGET_RATE
    // 太短的一段多半是咳嗽、键盘声，直接丢掉
    if (startedAt && Date.now() - startedAt < minSpeechMs) return
    if (merged.length / rate < 0.25) return

    const pcm = downsample(merged, rate)
    const blob = encodeWav(pcm, TARGET_RATE)
    onSegmentRef.current?.(blob, { seconds: merged.length / rate })
  }, [minSpeechMs])

  const teardown = useCallback(() => {
    activeRef.current = false
    if (nodeRef.current) {
      nodeRef.current.onaudioprocess = null
      try { nodeRef.current.disconnect() } catch (e) { /* 已断开 */ }
      nodeRef.current = null
    }
    streamRef.current?.getTracks().forEach(t => t.stop())
    streamRef.current = null
    if (ctxRef.current) {
      ctxRef.current.close().catch(() => {})
      ctxRef.current = null
    }
  }, [])

  const stop = useCallback(() => {
    // 停止时把还没说完的一小段也送出去，避免最后半句丢失
    flush()
    teardown()
    setActive(false)
    setLevel(0)
  }, [flush, teardown])

  const start = useCallback(async () => {
    if (!supported) {
      setError('当前浏览器不支持录音，请使用 Chrome / Edge')
      return
    }
    setError(null)
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          channelCount: 1,
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
        },
      })
      streamRef.current = stream
      const AudioCtx = window.AudioContext || window.webkitAudioContext
      const ctx = new AudioCtx()
      if (ctx.state === 'suspended') await ctx.resume()
      ctxRef.current = ctx

      const source = ctx.createMediaStreamSource(stream)
      const node = ctx.createScriptProcessor(4096, 1, 1)
      nodeRef.current = node

      bufRef.current = []
      preRollRef.current = []
      speechRef.current = false
      lastVoiceRef.current = 0
      bufStartRef.current = 0
      activeRef.current = true

      node.onaudioprocess = (event) => {
        if (!activeRef.current) return
        // inputBuffer 会被复用，必须复制
        const chunk = new Float32Array(event.inputBuffer.getChannelData(0))

        let sumSq = 0
        for (let i = 0; i < chunk.length; i++) sumSq += chunk[i] * chunk[i]
        const rms = Math.sqrt(sumSq / chunk.length)
        const now = Date.now()

        // UI 音量条，做一点平滑
        setLevel(prev => prev * 0.72 + rms * 0.28)

        if (rms >= threshold) {
          if (!speechRef.current) {
            speechRef.current = true
            bufStartRef.current = now
            // 把说话前的缓冲一起带上，避免第一个字被切
            bufRef.current = [...preRollRef.current]
            preRollRef.current = []
          }
          lastVoiceRef.current = now
          bufRef.current.push(chunk)
        } else if (speechRef.current) {
          bufRef.current.push(chunk)
          if (now - lastVoiceRef.current >= silenceMs) {
            flush()
            return
          }
        } else {
          // 还没开始说话，只保留最近几帧
          preRollRef.current.push(chunk)
          if (preRollRef.current.length > 3) preRollRef.current.shift()
        }

        // 说太久强制切段，既避开接口时长限制，也让文字早点出来
        if (speechRef.current && now - bufStartRef.current >= maxSegmentMs) {
          flush()
          bufStartRef.current = now
          speechRef.current = true
          lastVoiceRef.current = now
        }
      }

      source.connect(node)
      // ScriptProcessor 必须连到 destination 才会持续回调，用 0 增益避免回声
      const mute = ctx.createGain()
      mute.gain.value = 0
      node.connect(mute)
      mute.connect(ctx.destination)

      setActive(true)
    } catch (e) {
      const name = e?.name || ''
      if (name === 'NotAllowedError' || name === 'SecurityError') {
        setError('麦克风权限被拒绝，请在浏览器地址栏左侧的图标里允许麦克风')
      } else if (name === 'NotFoundError') {
        setError('没有检测到麦克风设备')
      } else {
        setError('打开麦克风失败：' + (e?.message || name))
      }
      teardown()
      setActive(false)
    }
  }, [supported, threshold, silenceMs, maxSegmentMs, flush, teardown])

  const toggle = useCallback(() => {
    if (activeRef.current) stop()
    else start()
  }, [start, stop])

  // 组件卸载时一定释放麦克风，否则标签页会一直显示录音中
  useEffect(() => () => {
    activeRef.current = false
    streamRef.current?.getTracks().forEach(t => t.stop())
    try { nodeRef.current?.disconnect() } catch (e) { /* ignore */ }
    ctxRef.current?.close().catch(() => {})
  }, [])

  return {
    supported,
    active,
    level,
    error,
    start,
    stop,
    toggle,
    clearError: () => setError(null),
  }
}
