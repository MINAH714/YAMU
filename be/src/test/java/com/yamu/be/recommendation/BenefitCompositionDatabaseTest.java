package com.yamu.be.recommendation;

import static org.assertj.core.api.Assertions.*;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import com.yamu.be.card.domain.UserCardRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties="spring.application.name=benefit-composition-database")
class BenefitCompositionDatabaseTest extends MySqlTestSupport {
    @Autowired DataSource source;
    @Autowired JdbcTemplate jdbc;
    @Autowired RecommendationPolicyLoader loader;
    @Autowired RecommendationInputAssembler assembler;
    @Autowired UserCardRepository cards;

    @Test void migrationSucceedsWithBinaryLoggingAndDefaultCreatorTrust() {
        assertThat(jdbc.queryForObject("SELECT @@GLOBAL.log_bin", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT @@GLOBAL.log_bin_trust_function_creators", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.triggers
                WHERE trigger_schema=DATABASE()
                  AND trigger_name IN ('benefit_fallback_insert', 'benefit_fallback_update')
                """, Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history WHERE version='20261103.1' AND success=1
                """, Long.class)).isEqualTo(1);
    }

    @Test void seedRerunPreservesPathsAndLoadsActualGroupsAndFallbacks() throws Exception {
        for(var name:List.of("01-card-catalog.sql","02-card-catalog-ab20.sql","03-benefit-composition.sql")) apply(name);
        long groupCount=jdbc.queryForObject("SELECT COUNT(*) FROM benefit_target_group",Long.class);
        long targetCount=jdbc.queryForObject("SELECT COUNT(*) FROM benefit_target",Long.class);
        apply("01-card-catalog.sql"); apply("02-card-catalog-ab20.sql"); apply("03-benefit-composition.sql");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM benefit_target_group",Long.class)).isEqualTo(groupCount);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM benefit_target",Long.class)).isEqualTo(targetCount);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM card_product",Long.class)).isEqualTo(17);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM benefit_target WHERE target_type<>'CONDITION' AND is_excluded<>0",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM benefit_target_group_member m JOIN benefit_target_group g USING(benefit_target_group_id) WHERE g.group_code='APP_STORE_YOUTUBE_PREMIUM'",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM card_benefit WHERE fallback_benefit_id IS NOT NULL",Long.class)).isEqualTo(2);
        var product=jdbc.queryForObject("SELECT card_product_id FROM card_product WHERE issuer_code='KB' AND product_name='KB국민 노리2 체크카드(KB Pay)'",Long.class);
        var fixtures=new OwnershipFixtures(jdbc);long user=fixtures.user();long card=fixtures.card(user,product,null,"ACTIVE");
        var owned=cards.findById(card).orElseThrow();
        var data=loader.load(user,List.of(owned),LocalDate.of(2026,11,3));
        assertThat(data.applicationGroups()).hasSize(1);assertThat(data.purchaseItems()).hasSize(1);
        var policy=assembler.policy(owned,data,LocalDate.of(2026,11,3),300_000L,new java.util.ArrayList<>());
        assertThat(policy.complete()).isFalse();
        assertThat(policy.applicationGroups()).hasSize(1);
        assertThat(policy.benefits().stream().flatMap(b->b.conditions().targetGroups().stream()).anyMatch(g->g.members().size()==2)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_limit_usage",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM monthly_limit_usage",Long.class)).isZero();
        var benefits=jdbc.queryForList("SELECT card_benefit_id FROM card_benefit WHERE card_product_id=? ORDER BY card_benefit_id",Long.class,product);
        var group=jdbc.queryForObject("SELECT benefit_target_group_id FROM benefit_target_group WHERE card_benefit_id=? LIMIT 1",Long.class,benefits.get(0));
        var target=jdbc.queryForObject("SELECT benefit_target_id FROM benefit_target WHERE card_benefit_id=? LIMIT 1",Long.class,benefits.get(1));
        assertThatThrownBy(()->jdbc.update("INSERT INTO benefit_target_group_member VALUES(?,?,?)",group,target,benefits.get(0))).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    private void apply(String name) throws Exception {
        try(var connection=source.getConnection()) {
            try { ScriptUtils.executeSqlScript(connection,new EncodedResource(new FileSystemResource(Path.of("seed",name)),StandardCharsets.UTF_8)); }
            catch(Exception failure) { connection.createStatement().execute("ROLLBACK");throw failure; }
        }
    }
}
