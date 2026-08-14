package com.example.freshdemo.common.response;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * [LG-fm 컨벤션 적용] 목록 응답 공통 포맷 — 오프셋 방식 하나만 둔다(LG-fm 근거: 무한 스크롤이
 * 필요해지면 커서 방식 응답을 별도로 만든다). fresh-demo엔 아직 페이지네이션이 필요한 목록
 * 엔드포인트가 없어 사용처는 없지만, 앞으로 관리자용 목록 API 등이 생길 때를 대비해 컨벤션만
 * 먼저 맞춰둔다.
 */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements
) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements());
    }

    public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalElements) {
        return new PageResponse<>(items, page, size, totalElements);
    }
}
