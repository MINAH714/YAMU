package com.yamu.be.seed.persona;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.seed.persona.PersonaSeedFile.Persona;
import com.yamu.be.seed.persona.PersonaSeedService.Report;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * {@code persona-seed} 프로필로 켜면 페르소나 파일을 읽어 넣고 바로 종료한다(웹 서버는 띄우지 않는다).
 * 사용법은 be/seed/PERSONA.md.
 *
 * <pre>
 * .\gradlew.bat bootRun --args="--spring.profiles.active=local,persona-seed"           # 넣기(이미 있으면 건너뜀)
 * .\gradlew.bat bootRun --args="--spring.profiles.active=local,persona-seed --reset"   # PENDING 페르소나만 다시 넣기
 * </pre>
 *
 * 종료 코드: 0 모두 성공·건너뜀, 1 실패한 페르소나 있음, 2 파일을 읽지 못함.
 */
@Slf4j
@Component
@Profile("persona-seed")
@RequiredArgsConstructor
public class PersonaSeedRunner implements ApplicationRunner {

    private final PersonaSeedService seedService;
    private final ObjectMapper objectMapper;
    private final ApplicationContext context;

    @Value("${yamu.persona-seed.file:seed/persona.local.json}")
    private String file;

    @Value("${yamu.persona-seed.reset:false}")
    private boolean resetProperty;

    @Override
    public void run(ApplicationArguments args) {
        boolean reset = resetProperty || args.containsOption("reset");
        int exitCode;
        try {
            List<Persona> personas = read(Path.of(file));
            log.info("페르소나 {}명 읽음 (reset={})", personas.size(), reset);
            Report report = seedService.seed(personas, reset);
            report.outcomes().forEach(o -> System.out.printf("%-20s %-20s %s%n", o.persona(), o.result(),
                    o.message() == null ? "" : o.message()));
            exitCode = report.hasFailure() ? 1 : 0;
        } catch (UnreadableFile e) {
            log.error("페르소나 파일을 읽지 못했습니다: {}", e.getMessage());
            exitCode = 2;
        }
        int code = exitCode;
        System.exit(SpringApplication.exit(context, () -> code));
    }

    List<Persona> read(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new UnreadableFile(path.toAbsolutePath() + " 이 없습니다. seed/persona.example.json을 복사해 만드세요.");
        }
        try {
            List<Persona> personas = objectMapper.readValue(Files.readString(path), new TypeReference<>() {
            });
            if (personas == null || personas.isEmpty()) {
                throw new UnreadableFile("페르소나가 하나도 없습니다.");
            }
            return personas;
        } catch (JsonProcessingException e) {
            // 메시지에 파일 내용(이름·번호)이 들어갈 수 있어 위치만 남긴다.
            var location = e.getLocation();
            throw new UnreadableFile("JSON 형식 오류" + (location == null ? ""
                    : "(" + location.getLineNr() + "행 " + location.getColumnNr() + "열)"));
        } catch (IOException e) {
            throw new UnreadableFile("파일을 열 수 없습니다(" + e.getClass().getSimpleName() + ")");
        }
    }

    static final class UnreadableFile extends RuntimeException {
        UnreadableFile(String message) {
            super(message);
        }
    }
}
