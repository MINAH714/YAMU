package com.yamu.be.global.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PageResponseTest {

    @Test
    void 전체_개수로_페이지_수와_다음_페이지_여부를_계산한다() {
        PageResponse<String> first = PageResponse.of(List.of("a"), 1, 20, 57);
        PageResponse<String> last = PageResponse.of(List.of("a"), 3, 20, 57);

        assertThat(first.totalPages()).isEqualTo(3);
        assertThat(first.hasNext()).isTrue();
        assertThat(last.hasNext()).isFalse();
    }

    @Test
    void 결과가_없으면_페이지_수_0이고_다음_페이지가_없다() {
        PageResponse<String> empty = PageResponse.of(List.of(), 1, 20, 0);

        assertThat(empty.totalPages()).isZero();
        assertThat(empty.hasNext()).isFalse();
    }

    @Test
    void offset은_앞_페이지들의_행_수다() {
        assertThat(PageResponse.offset(1, 20)).isZero();
        assertThat(PageResponse.offset(3, 20)).isEqualTo(40);
    }

    @ParameterizedTest
    @CsvSource({"0, 20", "-1, 20", "1, 0", "1, -5"})
    void page나_size가_1보다_작으면_of가_예외를_던진다(int page, int size) {
        assertThatThrownBy(() -> PageResponse.of(List.of(), page, size, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"0, 20", "-1, 20", "1, 0", "1, -5"})
    void page나_size가_1보다_작으면_offset이_예외를_던진다(int page, int size) {
        assertThatThrownBy(() -> PageResponse.offset(page, size))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
