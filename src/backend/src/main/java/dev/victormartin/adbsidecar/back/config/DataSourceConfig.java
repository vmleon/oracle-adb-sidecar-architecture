package dev.victormartin.adbsidecar.back.config;

import java.sql.SQLException;

import javax.sql.DataSource;

import oracle.ucp.jdbc.PoolDataSource;
import oracle.ucp.jdbc.PoolDataSourceFactory;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class DataSourceConfig {

    public static class JdbcConn {
        private String jdbcUrl;
        private String username;
        private String password;

        public String getJdbcUrl() { return jdbcUrl; }
        public void setJdbcUrl(String jdbcUrl) { this.jdbcUrl = jdbcUrl; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    @Bean("adbProps")
    @ConfigurationProperties("datasources.adb")
    public JdbcConn adbProps() {
        return new JdbcConn();
    }

    @Bean("oracleProps")
    @ConfigurationProperties("datasources.oracle")
    public JdbcConn oracleProps() {
        return new JdbcConn();
    }

    @Bean(name = "adbDataSource")
    public DataSource adbDataSource(@Qualifier("adbProps") JdbcConn p) throws SQLException {
        return makeUcpPool("adb-ucp", p);
    }

    @Bean(name = "oracleDataSource")
    public DataSource oracleDataSource(@Qualifier("oracleProps") JdbcConn p) throws SQLException {
        return makeUcpPool("oracle-ucp", p);
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

    private static PoolDataSource makeUcpPool(String poolName, JdbcConn p) throws SQLException {
        PoolDataSource pds = PoolDataSourceFactory.getPoolDataSource();
        pds.setConnectionPoolName(poolName);
        pds.setConnectionFactoryClassName("oracle.jdbc.pool.OracleDataSource");
        pds.setURL(p.getJdbcUrl());
        pds.setUser(p.getUsername());
        pds.setPassword(p.getPassword());
        pds.setInitialPoolSize(2);
        pds.setMinPoolSize(2);
        pds.setMaxPoolSize(10);
        return pds;
    }
}
