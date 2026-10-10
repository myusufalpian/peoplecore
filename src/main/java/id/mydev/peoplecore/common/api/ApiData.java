package id.mydev.peoplecore.common.api;

import java.util.Map;

public record ApiData<T>(T data, Object metadata) {
    public static <T> ApiData<T> of(T data) {
        return new ApiData<>(data, Map.of());
    }
}
