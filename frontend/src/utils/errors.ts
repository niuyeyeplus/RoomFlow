// 契约错误码 -> 友好中文提示（docs/api/README.md 错误码表）
// 通过结构化字段读取，不依赖 api/client，保持 utils 为叶子模块

const CODE_MESSAGES: Record<number, string> = {
  40001: '参数校验失败，请检查输入',
  40002: '请求格式错误',
  40101: '登录状态已失效，请重新登录',
  40102: '登录已过期',
  40103: '用户名或密码错误',
  40104: '登录状态已失效，请重新登录',
  40301: '无权限执行该操作',
  40302: '账号已被禁用',
  40401: '资源不存在或不可见',
  40901: '用户名已存在',
  40902: '会议时间与该房间已有会议冲突',
  40903: '报名人数已满',
  40904: '你已被该会议禁止报名',
  40905: '会议时间不符合规则（须在72小时内开始、15分钟对齐、时长15分钟至24小时）',
  40906: '该会议室已停用，请选择其他会议室',
  40907: '该会议室存在进行中的会议，暂不可停用',
  40908: '你已报名该会议，请勿重复报名',
  40909: '当前会议状态不允许该操作',
  50000: '服务器内部错误，请稍后重试',
  [-1]: '网络异常，请检查网络连接后重试'
}

interface ErrorLike {
  code?: unknown
  message?: unknown
}

/** 从 ApiError / 未知异常中提取面向用户的中文提示 */
export function friendlyMessage(err: unknown): string {
  const e = err as ErrorLike | null | undefined
  const code = typeof e?.code === 'number' ? e.code : null
  if (code !== null && CODE_MESSAGES[code]) return CODE_MESSAGES[code]
  if (typeof e?.message === 'string' && e.message && e.message !== 'success') return e.message
  return '操作失败，请稍后重试'
}
