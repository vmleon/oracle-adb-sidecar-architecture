package dev.victormartin.adbsidecar.back.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class DataSourceConfig {

    @Bean(name = "adbDataSource")
    @ConfigurationProperties("datasources.adb")
    public DataSource adbDataSource() {
        return DataSourceBuilder.create().build();
    }

    @Bean(name = "oracleDataSource")
    @ConfigurationProperties("datasources.oracle")
    public DataSource oracleDataSource() {
        return DataSourceBuilder.create().build();
    }

    @Bean(name = "postgresDataSource")
    @ConfigurationProperties("datasources.postgres")
    public DataSource postgresDataSource() {
        return DataSourceBuilder.create().build();
    }

    @Bean(name = "adbJdbc")
    public JdbcTemplate adbJdbc(@Qualifier("adbDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    @Bean(name = "oracleJdbc")
    public JdbcTemplate oracleJdbc(@Qualifier("oracleDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    @Bean(name = "postgresJdbc")
    public JdbcTemplate postgresJdbc(@Qualifier("postgresDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }
}
