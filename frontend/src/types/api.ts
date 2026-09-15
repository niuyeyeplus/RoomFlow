// API contract types — strictly mapped from docs/api/openapi.yaml (唯一契约)

/** 统一响应体 Result<T>：code=0 成功，非 0 为业务错误码 */
export interface ApiResult<T> {
  code: number
  message: string
  data: T
}

/** 字段级校验错误（code=40001 时 data.fieldErrors） */
export interface FieldError {
  field: string
  message: string
}

/** 错误响应 data 载荷 */
export interface ErrorResultData {
  fieldErrors?: FieldError[]
}

/** 分页响应 PageResult<T> */
export interface PageResult<T> {
  records: T[]
  total: number
  page: number
  size: number
}

export type Role = 'USER' | 'ADMIN'

export interface AccountVO {
  id: number
  username: string
  role: Role
  status: 0 | 1
  createdAt: string
}

export interface RegisterRequest {
  username: string
  password: string
}

export interface LoginRequest {
  username: string
  password: string
}

export interface RefreshRequest {
  refreshToken: string
}

export interface AuthTokenVO {
  tokenType: 'Bearer'
  accessToken: string
  accessTokenExpiresIn: number
  refreshToken: string
  refreshTokenExpiresIn: number
}

export type Equipment = 'PROJECTOR' | 'WHITEBOARD' | 'VIDEO_CONFERENCE' | 'PHONE'

export interface RoomVO {
  id: number
  name: string
  location: string | null
  capacity: number
  equipment: Equipment[]
  enabled: boolean
  createdAt: string
  updatedAt: string
}

export interface CreateRoomRequest {
  name: string
  location?: string | null
  capacity: number
  equipment?: Equipment[]
}

export interface UpdateRoomRequest {
  name: string
  location?: string | null
  capacity: number
  equipment?: Equipment[]
}

export interface RoomStatusRequest {
  enabled: boolean
}

/** 已占用时段，[startTime, endTime) 半开区间 */
export interface TimeSlot {
  meetingId: number
  startTime: string
  endTime: string
}

export interface RoomAvailabilityVO {
  roomId: number
  startDate: string
  endDate: string
  occupiedSlots: TimeSlot[]
}

export type MeetingStatus = 'ACTIVE' | 'ENDED' | 'CANCELLED' | 'DELETED'

/** 列表可筛选状态（DELETED 不可筛选） */
export type MeetingStatusFilter = 'ACTIVE' | 'ENDED' | 'CANCELLED'

/** null=未退出；USER_LEFT=主动退出（可重新报名）；KICKED=被踢（永久禁入） */
export type LeaveReason = 'USER_LEFT' | 'KICKED' | null

export interface ParticipantVO {
  id: number
  meetingId: number
  accountId: number
  username: string
  isOrganizer: boolean
  banned: boolean
  joinedAt: string
  leftAt: string | null
  leaveReason: LeaveReason
}

export interface MeetingVO {
  id: number
  title: string
  description: string | null
  roomId: number
  roomName: string
  organizerId: number
  organizerUsername: string
  startTime: string
  endTime: string
  status: MeetingStatus
  endedEarly: boolean
  participantCount: number
  createdAt: string
  updatedAt: string
}

export interface MeetingDetailVO extends MeetingVO {
  /** 全部参会记录（含已退出/被踢），按 joinedAt 升序 */
  participants: ParticipantVO[]
}

export interface CreateMeetingRequest {
  title: string
  description?: string | null
  roomId: number
  startTime: string
  endTime: string
}

export interface UpdateMeetingRequest {
  title: string
  description?: string | null
  roomId: number
  startTime: string
  endTime: string
}

export type NotificationType =
  'PARTICIPANT_JOINED' | 'PARTICIPANT_LEFT' | 'PARTICIPANT_KICKED' | 'MEETING_ENDED'

export interface NotificationVO {
  id: number
  type: NotificationType
  title: string
  content: string
  meetingId: number | null
  isRead: boolean
  createdAt: string
}

/** GET /api/meetings 查询参数 */
export interface ListMeetingsParams {
  page?: number
  size?: number
  roomId?: number
  /** 北京时间日历日 YYYY-MM-DD */
  date?: string
  status?: MeetingStatusFilter
  onlyMine?: boolean
}

/** GET /api/notifications 查询参数 */
export interface ListNotificationsParams {
  page?: number
  size?: number
  isRead?: boolean
}
