/**
 * 数据读取钩子：把「加载中 / 成功 / 失败（可重试）」三态封装成一个 hook。
 *
 * 为什么统一封装：后端未启动、网络抖动、鉴权失败都必须显示可重试的错误态而不是白屏
 * （docs/architecture.md §8）。如果每个页面各写一遍 `useState(loading/error/data)`，
 * 很容易漏掉某一条分支；这里用 `reloadToken` 触发重取，并提供 `reload()`。
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError, CODE_UNAUTHORIZED, isUnreachable, messageOf } from '../api/client.ts'

/** 三态加载结果。 */
export type Loadable<T> =
  | { readonly kind: 'loading' }
  | { readonly kind: 'ready'; readonly data: T }
  | { readonly kind: 'failed'; readonly message: string; readonly unreachable: boolean; readonly unauthorized: boolean }

/**
 * 加载一次数据，并暴露 `reload()`。
 *
 * @param loader - 加载函数（依赖变化时会重新执行）
 * @param deps - 依赖数组；任一变化触发重新加载
 * @returns 当前三态结果与重新加载函数
 */
export function useLoadable<T>(loader: () => Promise<T>, deps: readonly unknown[]): {
  readonly state: Loadable<T>
  readonly reload: () => void
} {
  const [state, setState] = useState<Loadable<T>>({ kind: 'loading' })
  const [token, setToken] = useState(0)
  // loader 每次渲染都是新函数，放进 ref，避免它成为 effect 依赖而无限重取
  const loaderRef = useRef(loader)
  loaderRef.current = loader

  useEffect(() => {
    let cancelled = false
    // 重新加载时保留上一次的成功数据：否则每次 reload 都会把整块 UI 换成"加载中"，
    // 上传/提交后页面会明显闪一下（用户以为没成功）。首屏仍从 loading 开始。
    setState(previous => (previous.kind === 'ready' ? previous : { kind: 'loading' }))
    void (async () => {
      try {
        const data = await loaderRef.current()
        if (!cancelled) setState({ kind: 'ready', data })
      } catch (error) {
        if (cancelled) return
        setState({
          kind: 'failed',
          message: messageOf(error),
          unreachable: isUnreachable(error),
          unauthorized: error instanceof ApiError && error.code === CODE_UNAUTHORIZED,
        })
      }
    })()
    return () => { cancelled = true }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 依赖由调用方显式给出
  }, [...deps, token])

  const reload = useCallback(() => { setToken(value => value + 1) }, [])
  return { state, reload }
}
