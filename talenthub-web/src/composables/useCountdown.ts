import { onMounted, onUnmounted, ref } from 'vue'

/** 目标时刻倒计时，到点时置 reached 供调用方刷新数据 */
export function useCountdown(target: () => string | undefined) {
  const text = ref('')
  const reached = ref(false)
  let timer: number | undefined

  function tick() {
    const value = target()
    if (!value) {
      text.value = ''
      return
    }
    const diffMs = new Date(value).getTime() - Date.now()
    if (diffMs <= 0) {
      if (text.value !== '') {
        reached.value = true
      }
      text.value = ''
      return
    }
    const totalSeconds = Math.floor(diffMs / 1000)
    const hours = Math.floor(totalSeconds / 3600)
    const minutes = Math.floor((totalSeconds % 3600) / 60)
    const seconds = totalSeconds % 60
    const pad = (n: number) => String(n).padStart(2, '0')
    text.value = hours > 0
      ? `${hours}:${pad(minutes)}:${pad(seconds)}`
      : `${pad(minutes)}:${pad(seconds)}`
  }

  onMounted(() => {
    tick()
    timer = window.setInterval(tick, 1000)
  })
  onUnmounted(() => {
    if (timer !== undefined) {
      window.clearInterval(timer)
    }
  })

  return { text, reached }
}
