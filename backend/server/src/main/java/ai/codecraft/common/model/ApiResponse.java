package ai.codecraft.common.model;

import java.util.UUID;

public record ApiResponse<T>(String code, String message, T data, String requestId) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("OK", "操作成功", data, UUID.randomUUID().toString());
    }

    public static ApiResponse<Void> failure(String code) {
        return new ApiResponse<>(code, "请求未完成，请检查输入或重新登录", null, UUID.randomUUID().toString());
    }
}
