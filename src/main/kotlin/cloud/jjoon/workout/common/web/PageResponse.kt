package cloud.jjoon.workout.common.web

data class PageResponse<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
) {
    companion object {
        fun <T> of(content: List<T>, page: Int, size: Int, totalElements: Long) =
            PageResponse(content, page, size, totalElements, ((totalElements + size - 1) / size).toInt())
    }
}
