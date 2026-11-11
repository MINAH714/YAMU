package com.yamu.be.benefitlimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.benefitlimit.LimitJudge.Judgement;
import com.yamu.be.benefitlimit.LimitJudge.Rule;
import com.yamu.be.benefitlimit.LimitJudge.Tier;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 월 혜택 한도 판정의 경계(S15P31E103-374). */
class LimitJudgeTest {

    /** 실적 30만~50만 1만원, 50만~ 2만원. 혜택 조건 없음. */
    static final Rule TIERED = new Rule(null, List.of(new Tier(300_000, 500_000L, 10_000), new Tier(500_000, null, 20_000)),
            List.of(0L));

    @Test
    void 기본_한도만_있으면_실적과_상관없이_기본_한도다() {
        Rule rule = new Rule(10_000L, List.of(), List.of(0L));

        assertThat(LimitJudge.judge(rule, 0, 3_000)).contains(new Judgement(LimitStatus.AVAILABLE, 10_000, 7_000, null));
    }

    @Test
    void 남은_한도가_최대의_20퍼센트_이하면_NEAR_END_0이면_EXHAUSTED다() {
        Rule rule = new Rule(10_000L, List.of(), List.of(0L));

        assertThat(LimitJudge.judge(rule, 0, 7_999).orElseThrow().status()).isEqualTo(LimitStatus.AVAILABLE);
        assertThat(LimitJudge.judge(rule, 0, 8_000)).contains(new Judgement(LimitStatus.NEAR_END, 10_000, 2_000, null));
        assertThat(LimitJudge.judge(rule, 0, 9_999).orElseThrow().status()).isEqualTo(LimitStatus.NEAR_END);
        assertThat(LimitJudge.judge(rule, 0, 10_000)).contains(new Judgement(LimitStatus.EXHAUSTED, 10_000, 0, null));
    }

    @Test
    void 사용량이_최대보다_커도_남은_한도는_0_아래로_내려가지_않는다() {
        Rule rule = new Rule(10_000L, List.of(), List.of(0L));

        assertThat(LimitJudge.judge(rule, 0, 12_000).orElseThrow().remaining()).isZero();
    }

    @Test
    void 실적_구간은_하한_포함_상한_미포함이다() {
        assertThat(LimitJudge.judge(TIERED, 300_000, 0).orElseThrow().maximum()).isEqualTo(10_000);
        assertThat(LimitJudge.judge(TIERED, 499_999, 0).orElseThrow().maximum()).isEqualTo(10_000);
        assertThat(LimitJudge.judge(TIERED, 500_000, 0).orElseThrow().maximum()).isEqualTo(20_000);
    }

    @Test
    void 지난달_실적이_가장_낮은_구간에_못_미치면_LOCKED이고_기준은_그_구간_하한이다() {
        assertThat(LimitJudge.judge(TIERED, 299_999, 0)).contains(new Judgement(LimitStatus.LOCKED, 0, 0, 300_000L));
        assertThat(LimitJudge.judge(TIERED, 0, 0)).contains(new Judgement(LimitStatus.LOCKED, 0, 0, 300_000L));
    }

    @Test
    void 연결된_혜택_전부의_최소_전월_실적에_못_미치면_기본_한도가_있어도_LOCKED다() {
        Rule rule = new Rule(10_000L, List.of(), List.of(400_000L, 300_000L));

        assertThat(LimitJudge.judge(rule, 299_999, 0)).contains(new Judgement(LimitStatus.LOCKED, 0, 0, 300_000L));
        assertThat(LimitJudge.judge(rule, 300_000, 0).orElseThrow().status()).isEqualTo(LimitStatus.AVAILABLE);
    }

    @Test
    void 혜택_조건과_구간이_같이_있으면_둘_다_맞는_가장_작은_실적이_기준이다() {
        Rule rule = new Rule(null, List.of(new Tier(300_000, null, 10_000)), List.of(400_000L));

        assertThat(LimitJudge.judge(rule, 350_000, 0)).contains(new Judgement(LimitStatus.LOCKED, 0, 0, 400_000L));
    }

    @Test
    void 맞는_구간의_한도가_0원이면_다음_구간을_기준으로_LOCKED다() {
        Rule rule = new Rule(null, List.of(new Tier(0, 300_000L, 0), new Tier(300_000, null, 5_000)), List.of(0L));

        assertThat(LimitJudge.judge(rule, 100_000, 0)).contains(new Judgement(LimitStatus.LOCKED, 0, 0, 300_000L));
    }

    @Test
    void 실적과_상관없이_한도가_0원이면_잠김이_아니라_EXHAUSTED다() {
        Rule rule = new Rule(0L, List.of(), List.of(0L));

        assertThat(LimitJudge.judge(rule, 900_000, 0)).contains(new Judgement(LimitStatus.EXHAUSTED, 0, 0, null));
    }

    @Test
    void 최대_한도를_정할_수_없으면_판정하지_않는다() {
        // 기본 한도와 구간이 같이 있음: 우선순위를 가정하지 않는다(추천 엔진과 같음)
        assertThat(LimitJudge.judge(new Rule(10_000L, List.of(new Tier(0, null, 5_000)), List.of(0L)), 0, 0)).isEmpty();
        // 둘 다 없음
        assertThat(LimitJudge.judge(new Rule(null, List.of(), List.of(0L)), 0, 0)).isEmpty();
        // 구간이 겹침
        assertThat(LimitJudge.judge(new Rule(null, List.of(new Tier(0, 400_000L, 1_000), new Tier(300_000, null, 2_000)),
                List.of(0L)), 350_000, 0)).isEmpty();
    }
}
