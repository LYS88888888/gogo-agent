import { useAuthStore } from '../store/authStore';
import { getApiBase } from './config';

function authHeaders(): Record<string, string> {
  const token = useAuthStore.getState().token;
  return token ? { Authorization: token } : {};
}

/** 单条预订记录（机票 / 酒店 / 火车 等） */
export interface MyTravelBooking {
  bookingId: string;
  bizType: string | null;
  bizTypeLabel: string | null;
  title: string | null;
  status: string | null;
  statusLabel: string | null;
  externalStatus: string | null;
  totalAmount: string | null;
  currency: string | null;
  platform: string | null;
  externalOrderNo: string | null;
  paymentStatus: string | null;
  startTime: number | null;
  endTime: number | null;
  bookedAt: number | null;
  /** 第三方平台订单详情页 URL，为 null 时不展示跳转入口 */
  orderUrl: string | null;
}

/** 「我的差旅」列表项：差旅单 + 最新审批 + 预订摘要 */
export interface MyTravelOrder {
  orderId: string;
  destination: string | null;
  departureCity: string | null;
  departureDate: string | null;
  returnDate: string | null;
  purpose: string | null;
  status: string | null;
  statusLabel: string | null;
  createdAt: number | null;
  updatedAt: number | null;
  international: boolean;
  approvalId: string | null;
  approvalStatus: string | null;
  approvalStatusLabel: string | null;
  approvalRemark: string | null;
  approvalSubmitTime: number | null;
  approvalUpdateTime: number | null;
  bookingCount: number;
  bookings: MyTravelBooking[];
  planHtmlUrl: string | null;
}

/** 拉取当前登录用户的全部出差申请（含审批状态与预订摘要） */
export async function fetchMyTravelOrders(): Promise<MyTravelOrder[]> {
  const res = await fetch(`${getApiBase()}/api/my-travel/orders`, {
    headers: authHeaders(),
  });
  if (res.status === 401) throw new Error('UNAUTHORIZED');
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

/** 拉取指定差旅单的行程方案 HTML 文本 */
export async function fetchPlanHtml(orderId: string): Promise<string> {
  const res = await fetch(`${getApiBase()}/api/my-travel/plan-html/${orderId}`, {
    headers: authHeaders(),
  });
  if (res.status === 401) throw new Error('UNAUTHORIZED');
  if (res.status === 404) throw new Error('NOT_FOUND');
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.text();
}

/** 删除指定预订记录（逻辑删除） */
export async function deleteBooking(bookingId: string): Promise<{ deleted: boolean }> {
  const res = await fetch(`${getApiBase()}/api/my-travel/bookings/${bookingId}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (res.status === 401) throw new Error('UNAUTHORIZED');
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

/** 取消指定预订记录（同步调用第三方平台取消接口 + 更新内部状态为 CANCELLED） */
export async function cancelBooking(
  bookingId: string,
  reason?: string,
): Promise<{ cancelled: boolean; message: string }> {
  const res = await fetch(`${getApiBase()}/api/my-travel/bookings/${bookingId}/cancel`, {
    method: 'POST',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(reason ? { reason } : {}),
  });
  if (res.status === 401) throw new Error('UNAUTHORIZED');
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.message || `HTTP ${res.status}`);
  }
  return res.json();
}
