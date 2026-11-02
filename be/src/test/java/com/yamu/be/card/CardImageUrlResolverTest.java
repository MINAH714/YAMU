package com.yamu.be.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CardImageUrlResolverTest {

    @ParameterizedTest
    @ValueSource(strings = {"https://cdn.example.com", "https://cdn.example.com/"})
    void 기준_주소의_끝_슬래시와_관계없이_정적_이미지_URL을_만든다(String baseUrl) {
        var resolver = new CardImageUrlResolver(new CardImageProperties(URI.create(baseUrl)));
        assertThat(resolver.resolve("cards/samsung-aap1767/static-front/hash.png"))
                .isEqualTo("https://cdn.example.com/cards/samsung-aap1767/static-front/hash.png");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "https://other.example/a.png", "//other.example/a.png", "/cards/a.png",
            "cards/../a.png", "cards/./a.png", "cards//a.png", "cards/a.png/", "cards/%2e%2e/a.png",
            "cards/a.png?x=1", "cards/a.png#x", "cards/a\\b.png", "other/a.png", "cards/a\nb.png"})
    void 이미지가_없거나_안전하지_않은_키는_null이다(String key) {
        var resolver = new CardImageUrlResolver(new CardImageProperties(URI.create("https://cdn.example.com")));
        assertThat(resolver.resolve(key)).isNull();
    }

    @Test
    void DB_길이_상한을_넘는_키는_사용하지_않는다() {
        var resolver = new CardImageUrlResolver(new CardImageProperties(URI.create("https://cdn.example.com")));
        assertThat(resolver.resolve("cards/" + "a".repeat(1019))).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://cdn.example.com", "/relative", "https://user@cdn.example.com",
            "https://cdn.example.com?x=1", "https://cdn.example.com#x", "https://cdn.example.com/a/../b"})
    void 잘못된_기준_주소는_기동_설정_오류로_거절한다(String baseUrl) {
        assertThatThrownBy(() -> new CardImageProperties(URI.create(baseUrl)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
