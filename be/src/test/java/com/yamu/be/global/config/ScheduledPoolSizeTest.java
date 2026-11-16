package com.yamu.be.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.util.ClassUtils;

/**
 * 정기 작업 스레드 수({@code spring.task.scheduling.pool.size})가 {@code @Scheduled} 작업 수보다 적지 않은지 본다(S15P31E103-477).
 * 적으면 긴 작업이 스레드를 잡는 동안 결과 불명 결제 복구·출금 미확인 재대조가 밀린다. 새 정기 작업을 추가하면 application.yml의 값도 올린다.
 *
 * <p>{@code @ConditionalOnProperty}로 켜고 끄는 작업(결제 복구 등)도 운영에서는 켜지므로 같이 센다. 그래서 Spring의 후보 스캐너
 * (조건을 평가해 꺼진 작업을 뺀다) 대신 클래스 파일을 직접 읽는다.
 */
class ScheduledPoolSizeTest {

    private static final String CLASSES = "classpath*:com/yamu/be/**/*.class";

    @Test
    void 정기_작업_스레드는_Scheduled_작업_수보다_적지_않다() throws Exception {
        List<String> tasks = scheduledTasks();

        assertThat(tasks).as("결제 복구 작업을 못 찾으면 검사 자체가 깨진 것")
                .contains("WithdrawalRecoveryJob.run");
        assertThat(poolSize())
                .as("application.yml spring.task.scheduling.pool.size를 작업 수 이상으로 올리세요. 작업: %s", tasks)
                .isGreaterThanOrEqualTo(tasks.size());
    }

    /** 한 메서드에 @Scheduled가 여러 개면 작업도 여러 개라 각각 센다. */
    private static List<String> scheduledTasks() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        MetadataReaderFactory readers = new CachingMetadataReaderFactory(resolver);
        List<String> tasks = new ArrayList<>();
        for (Resource resource : resolver.getResources(CLASSES)) {
            AnnotationMetadata metadata = readers.getMetadataReader(resource).getAnnotationMetadata();
            if (!metadata.hasAnnotatedMethods(Scheduled.class.getName())
                    && !metadata.hasAnnotatedMethods(Schedules.class.getName())) {
                continue;
            }
            Class<?> type = ClassUtils.forName(metadata.getClassName(), ClassUtils.getDefaultClassLoader());
            for (Method method : type.getDeclaredMethods()) {
                int count = AnnotatedElementUtils
                        .getMergedRepeatableAnnotations(method, Scheduled.class, Schedules.class).size();
                for (int i = 0; i < count; i++) {
                    tasks.add(type.getSimpleName() + "." + method.getName());
                }
            }
        }
        return tasks;
    }

    private static int poolSize() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        Object size = sources.get(0).getProperty("spring.task.scheduling.pool.size");
        assertThat(size).as("application.yml에 spring.task.scheduling.pool.size가 있어야 한다").isNotNull();
        return Integer.parseInt(size.toString());
    }
}
