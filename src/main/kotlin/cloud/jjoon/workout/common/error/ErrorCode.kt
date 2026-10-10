package cloud.jjoon.workout.common.error

import org.springframework.http.HttpStatus

enum class ErrorCode(val status: HttpStatus, val message: String) {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근할 수 없는 데이터입니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 경로를 찾을 수 없습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "일시적으로 처리할 수 없습니다. 잠시 후 다시 시도해 주세요."),

    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "PIN이 올바르지 않습니다. 다시 입력해 주세요."),
    AUTH_ACCOUNT_LOCKED(HttpStatus.UNAUTHORIZED, "PIN을 5회 잘못 입력해 로그인이 잠겼습니다."),
    AUTH_SESSION_REPLACED(HttpStatus.UNAUTHORIZED, "다른 기기에서 로그인되어 로그아웃되었습니다."),
    AUTH_SESSION_REVOKED(HttpStatus.UNAUTHORIZED, "다시 로그인해 주세요."),
    AUTH_SESSION_EXPIRED(HttpStatus.UNAUTHORIZED, "오랫동안 사용하지 않아 로그아웃되었습니다. 다시 로그인해 주세요."),

    WORKOUT_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "운동 기록을 찾을 수 없습니다."),
    WORKOUT_SESSION_ALREADY_IN_PROGRESS(HttpStatus.CONFLICT, "이미 진행 중인 운동이 있습니다."),
    WORKOUT_SESSION_NOT_EDITABLE(HttpStatus.CONFLICT, "완료된 운동 기록은 수정할 수 없습니다."),
    WORKOUT_SESSION_ALREADY_COMPLETED(HttpStatus.CONFLICT, "이미 완료된 운동입니다."),
    WORKOUT_SESSION_HAS_NO_SETS(HttpStatus.CONFLICT, "세트를 하나 이상 기록해야 운동을 완료할 수 있습니다."),
    EXERCISE_NOT_FOUND(HttpStatus.NOT_FOUND, "운동을 찾을 수 없습니다."),
    EXERCISE_NAME_DUPLICATED(HttpStatus.CONFLICT, "같은 부위에 같은 이름의 종목이 있습니다."),
    EXERCISE_ORDER_OUTDATED(HttpStatus.CONFLICT, "종목 목록이 바뀌었습니다. 다시 불러와 순서를 정해 주세요."),
    EXERCISE_CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND, "운동 부위를 찾을 수 없습니다."),
    SESSION_EXERCISE_NOT_FOUND(HttpStatus.NOT_FOUND, "운동 기록에서 해당 운동을 찾을 수 없습니다."),
    WORKOUT_SET_NOT_FOUND(HttpStatus.NOT_FOUND, "세트를 찾을 수 없습니다."),
    WORKOUT_DAY_NOT_FOUND(HttpStatus.NOT_FOUND, "그날의 운동 기록을 찾을 수 없습니다."),

    MEDIA_UNSUPPORTED_TYPE(HttpStatus.BAD_REQUEST, "지원하지 않는 파일 형식입니다. 사진은 JPEG·PNG·HEIC, 동영상은 MP4·MOV만 올릴 수 있습니다."),
    /** The message names the exceeded limit; see [BusinessException.userMessage] (workout-media 8.2). */
    MEDIA_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "올릴 수 있는 사진·동영상의 한도를 넘었습니다."),
    MEDIA_NOT_FOUND(HttpStatus.NOT_FOUND, "사진·동영상을 찾을 수 없습니다."),
}
