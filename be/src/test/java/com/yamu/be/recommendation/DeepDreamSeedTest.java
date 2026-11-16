package com.yamu.be.recommendation;

import static org.assertj.core.api.Assertions.*;
import com.yamu.be.support.MySqlTestSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;

/** Registers only in disposable MySQL. Never runs the private transaction replay. */
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class DeepDreamSeedTest extends MySqlTestSupport {
    @Container @ServiceConnection(name="redis")
    static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    @Autowired DataSource source;
    @Autowired JdbcTemplate jdbc;
    private void load(String file) throws Exception {
        try(var connection=source.getConnection()) {
            try { ScriptUtils.executeSqlScript(connection,new EncodedResource(new FileSystemResource(Path.of("seed",file)),StandardCharsets.UTF_8)); }
            catch(Exception failure) {
                try(var rollback=connection.createStatement()) { rollback.execute("ROLLBACK"); }
                catch(Exception cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
    }
    @Test void seedIsRepeatablePreservesOldCatalogAndDoesNotFillUnknownPolicy() throws Exception {
        load("01-card-catalog.sql");load("02-card-catalog-ab20.sql");load("03-benefit-composition.sql");
        var tables=List.of("card_product","card_benefit","limit_group","tier_limit","benefit_target","benefit_limit_link","benefit_application_group","benefit_application_member");
        var before=new HashMap<String,List<Map<String,Object>>>();for(var table:tables) before.put(table,jdbc.queryForList("SELECT * FROM "+table));
        load("04-deep-dream.sql");DeepDreamPolicySupport.verifyRegisteredPolicy(jdbc);
        var once=new HashMap<String,List<Map<String,Object>>>();for(var table:tables) { once.put(table,jdbc.queryForList("SELECT * FROM "+table));assertThat(once.get(table)).containsAll(before.get(table)); }
        load("04-deep-dream.sql");for(var table:tables) assertThat(jdbc.queryForList("SELECT * FROM "+table)).containsExactlyInAnyOrderElementsOf(once.get(table));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM card_benefit WHERE benefit_code LIKE 'DD_%'",Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM card_benefit WHERE benefit_code LIKE 'DD_%' AND (rounding_stage IS NOT NULL OR valid_from IS NOT NULL OR valid_until IS NOT NULL)",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM benefit_application_member m JOIN card_benefit b USING(card_benefit_id) WHERE b.benefit_code LIKE 'DD_%'",Integer.class)).isZero();
        jdbc.update("UPDATE card_benefit SET rate=0.009 WHERE benefit_code='DD_BASE' AND card_product_id=(SELECT card_product_id FROM card_product WHERE product_name='신한카드 Deep Dream' AND issuer_code='SHINHAN' AND card_type='CREDIT')");
        assertThatThrownBy(()->load("04-deep-dream.sql")).isInstanceOf(Exception.class).hasStackTraceContaining("exp005_guard");
        assertThat(jdbc.queryForObject("SELECT rate FROM card_benefit WHERE benefit_code='DD_BASE' AND card_product_id=(SELECT card_product_id FROM card_product WHERE product_name='신한카드 Deep Dream' AND issuer_code='SHINHAN' AND card_type='CREDIT')",java.math.BigDecimal.class)).isEqualByComparingTo("0.009");
    }
}
