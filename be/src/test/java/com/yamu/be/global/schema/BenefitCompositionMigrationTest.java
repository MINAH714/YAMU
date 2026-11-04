package com.yamu.be.global.schema;

import static org.assertj.core.api.Assertions.*;
import com.yamu.be.support.MySqlTestSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties={"spring.application.name=benefit-composition-upgrade", "spring.flyway.target=20261102.1", "spring.jpa.hibernate.ddl-auto=none"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class BenefitCompositionMigrationTest extends MySqlTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Test void preservesBothIncludeExcludePathsBeforeConsolidatingTheTarget() {
        jdbc.update("INSERT INTO card_product(card_product_id,issuer_code,product_name,card_type,created_at,updated_at) VALUES(1,'TEST','migration','CREDIT',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        jdbc.update("INSERT INTO card_benefit(card_benefit_id,card_product_id,benefit_code,policy_version,benefit_name,benefit_type,calculation_type,rate,value_unit,created_at) VALUES(1,1,'BASE',1,'test','DISCOUNT','RATE',0.05,'KRW',UTC_TIMESTAMP(6))");
        jdbc.update("INSERT INTO benefit_target(card_benefit_id,target_type,target_value,is_excluded,created_at) VALUES(1,'CATEGORY','CAFE',0,UTC_TIMESTAMP(6)),(1,'CATEGORY','CAFE',1,UTC_TIMESTAMP(6)),(1,'CONDITION','CHANNEL:ONLINE',1,UTC_TIMESTAMP(6))");
        var upgrade=Flyway.configure().configuration(flyway.getConfiguration()).target(MigrationVersion.LATEST).load();
        upgrade.migrate();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM benefit_target WHERE target_type='CATEGORY'",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM benefit_target_group",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT benefit_target_id) FROM benefit_target_group_member",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM benefit_target_group WHERE is_excluded=1",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT is_excluded FROM benefit_target WHERE target_type='CONDITION'",Integer.class)).isEqualTo(1);
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
    }
}
