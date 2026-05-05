package com.jeecg.modules.jmreport.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;

/**
 * 海典同步数据源配置（外部库：antis_yifengdata_hub）
 *
 * 仅供 MCP 核心查询使用，不影响系统本地报表库。
 */
@Configuration
public class HaidianDataSourceConfig {
    @Value("${haidian.datasource.hikari.maximum-pool-size:5}")
    private int haidianMaxPoolSize;
    @Value("${haidian.datasource.hikari.minimum-idle:1}")
    private int haidianMinIdle;
    @Value("${haidian.datasource.hikari.connection-timeout:10000}")
    private long haidianConnTimeoutMs;
    @Value("${haidian.datasource.hikari.validation-timeout:3000}")
    private long haidianValidationTimeoutMs;
    @Value("${haidian.datasource.hikari.idle-timeout:120000}")
    private long haidianIdleTimeoutMs;
    @Value("${haidian.datasource.hikari.max-lifetime:180000}")
    private long haidianMaxLifetimeMs;
    @Value("${haidian.datasource.hikari.keepalive-time:60000}")
    private long haidianKeepaliveMs;

    /**
     * 系统本地报表库数据源（沿用 spring.datasource 配置）
     * 提供名为 dataSource 的 Bean，兼容现有代码中的 @Qualifier("dataSource")
     */
    @Bean(name = "dataSource")
    @Primary
    @ConfigurationProperties(prefix = "spring.datasource")
    public DataSource mainDataSource() {
        return DataSourceBuilder.create().build();
    }

    /**
     * 默认 JdbcTemplate（使用主数据源），供 minidao/积木报表等未指定 Qualifier 的注入使用，避免误用海典库
     */
    @Bean(name = "jdbcTemplate")
    @Primary
    public JdbcTemplate jdbcTemplate(@Qualifier("dataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /**
     * 默认 NamedParameterJdbcTemplate（使用主数据源），积木报表 minidao 使用此类执行 SQL
     */
    @Bean(name = "namedParameterJdbcTemplate")
    @Primary
    public NamedParameterJdbcTemplate namedParameterJdbcTemplate(@Qualifier("jdbcTemplate") JdbcTemplate jdbcTemplate) {
        return new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    /**
     * 海典数据源 DataSource
     * 配置前缀：haidian.datasource
     * @Lazy 避免启动/报表列表等默认流程时创建该连接池，确保默认走主数据源
     */
    @Bean(name = "haidianDataSource")
    @Lazy
    @ConfigurationProperties(prefix = "haidian.datasource")
    public DataSource haidianDataSource() {
        DataSource ds = DataSourceBuilder.create().build();
        if (ds instanceof HikariDataSource h) {
            // 显式设置连接池生命周期参数，避免服务端回收连接后校验告警：
            // "No operations allowed after connection closed"
            h.setMaximumPoolSize(haidianMaxPoolSize);
            h.setMinimumIdle(haidianMinIdle);
            h.setConnectionTimeout(haidianConnTimeoutMs);
            h.setValidationTimeout(haidianValidationTimeoutMs);
            h.setIdleTimeout(haidianIdleTimeoutMs);
            h.setMaxLifetime(haidianMaxLifetimeMs);
            h.setKeepaliveTime(haidianKeepaliveMs);
        }
        return ds;
    }

    /**
     * 海典数据源 JdbcTemplate
     */
    @Bean(name = "haidianJdbcTemplate")
    @Lazy
    public JdbcTemplate haidianJdbcTemplate(@Qualifier("haidianDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}

