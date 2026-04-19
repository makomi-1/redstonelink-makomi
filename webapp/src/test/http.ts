/**
 * 统一构造 fetch mock 返回值，避免各测试重复拼装 Response 兼容对象。
 */
export function createJsonResponse<T>(
  payload: T,
  init: {
    ok?: boolean;
    status?: number;
  } = {},
): Response {
  return {
    ok: init.ok ?? true,
    status: init.status ?? 200,
    json: async () => payload,
  } as Response;
}
