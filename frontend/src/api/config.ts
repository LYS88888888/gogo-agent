/**
 * 集中管理后端 API 地址，支持本地集群轮询模式。
 *
 * 使用方式：
 * 1. 在 frontend/.env.local 中配置多个后端地址（逗号分隔）：
 *    VITE_CLUSTER_URLS=http://localhost:8080,http://localhost:8081,http://localhost:8082
 *
 * 2. 在侧边栏开启「集群轮询」开关后，每次 API 请求自动轮询下一个地址。
 *
 * 3. 未配置 VITE_CLUSTER_URLS 时，降级使用 VITE_API_BASE（默认 http://localhost:8080）。
 */

import { useUiStore } from '../store/uiStore';

/** 默认单实例地址 */
const DEFAULT_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8080';

/** 从环境变量解析集群地址列表 */
function parseClusterUrls(): string[] {
  const raw: string | undefined = import.meta.env.VITE_CLUSTER_URLS;
  if (!raw) return [DEFAULT_BASE];
  return raw
    .split(',')
    .map((u: string) => u.trim())
    .filter(Boolean);
}

const clusterUrls = parseClusterUrls();

/** 轮询游标，每次调用 nextBase() 递增 */
let cursor = 0;

/**
 * 获取当前应使用的后端 API 地址。
 * - 集群模式开启时：每次调用返回下一个地址（轮询）
 * - 集群模式关闭时：始终返回第一个地址
 */
export function getApiBase(): string {
  const clusterMode = useUiStore.getState().clusterMode;
  if (!clusterMode || clusterUrls.length <= 1) {
    return clusterUrls[0] ?? DEFAULT_BASE;
  }
  const base = clusterUrls[cursor % clusterUrls.length];
  cursor = (cursor + 1) % clusterUrls.length;
  return base;
}

/** 获取当前集群地址列表（供 UI 展示） */
export function getClusterUrls(): string[] {
  return [...clusterUrls];
}

/** 集群地址数量是否 > 1（决定是否展示开关） */
export function isClusterAvailable(): boolean {
  return clusterUrls.length > 1;
}
