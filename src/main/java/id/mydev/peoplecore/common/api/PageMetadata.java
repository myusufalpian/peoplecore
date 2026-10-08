package id.mydev.peoplecore.common.api;

public record PageMetadata(int page, int size, long totalElements, int totalPages) {
    public PageMetadata {
        validate(page, size, totalElements);
        if (totalPages != totalElements / size + (totalElements % size == 0 ? 0 : 1)) {
            throw new InvalidPaginationException();
        }
    }

    public static PageMetadata of(int page, int size, long totalElements) {
        validate(page, size, totalElements);
        return new PageMetadata(page, size, totalElements, Math.toIntExact(totalElements / size + (totalElements % size == 0 ? 0 : 1)));
    }

    public static void validate(int page, int size, long totalElements) {
        if (page < 0 || size < 1 || size > 100 || totalElements < 0) {
            throw new InvalidPaginationException();
        }
    }
}
