package com.yamu.be.seed.qa;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.*;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
/** Offline CLI: no Spring application, migration, financial call or background worker starts. */
public final class QaAssetHistoryCli {
    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        try {
            if(args.length!=2 || !Set.of("plan","apply","verify","reset","purge").contains(args[0]))
                throw new IllegalArgumentException("Command and input file required");
            var spec=new ObjectMapper().registerModule(new JavaTimeModule())
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(Files.readString(Path.of(args[1])),QaAssetHistory.Spec.class);
            if(Set.of("apply","reset","purge").contains(args[0])&&!"true".equals(System.getenv("QA_SEED_MAINTENANCE")))
                throw new IllegalArgumentException("Maintenance required");
            try(var connection=DriverManager.getConnection(env("QA_SEED_JDBC_URL"),env("QA_SEED_DB_USERNAME"),env("QA_SEED_DB_PASSWORD"))) {
                try (var statement=connection.createStatement()) { statement.execute("SET time_zone = '+00:00'"); }
                connection.setAutoCommit(false);
                try {
                    var service=new QaAssetHistory(new JdbcTemplate(new SingleConnectionDataSource(connection,true)),Clock.systemUTC());
                    var report=service.run(args[0],spec);
                    if(Set.of("plan","verify").contains(args[0])) connection.rollback();else connection.commit();
                    System.out.println(report);
                } catch(Exception failure) { connection.rollback();throw failure; }
            }
        } catch(Exception failure) {
            Set<String> safeReasons=Set.of("No verified actual opening boundary", "Boundary must be first real history date",
                    "Negative synthetic opening", "Negative intermediate synthetic balance", "Wrong owner", "Missing ownership",
                    "Inputs changed; reset only QA asset history first", "No installed QA history", "Stored rows differ",
                    "Missing valid coverage", "Maintenance required", "Only synthetic streams can be removed");
            String detail=failure.getMessage()!=null && safeReasons.contains(failure.getMessage())?failure.getMessage():"Check input, ownership, boundary and database prerequisites";
            System.err.println("QA asset history incomplete ("+failure.getClass().getSimpleName()+"): "+detail);
            System.exit(1);
        }
    }
    private static String env(String key) { return Objects.requireNonNull(System.getenv(key),"Missing environment setting"); }
}
