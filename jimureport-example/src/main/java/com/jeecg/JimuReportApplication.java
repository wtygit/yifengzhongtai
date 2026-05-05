package com.jeecg;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/**
 * 一丰报表独立服务启动类
 * 排除 DataSource 自动配置，仅使用 HaidianDataSourceConfig 中声明的主数据源(jimureport)与海典数据源，避免报表请求误走海典库
 */
@SpringBootApplication(scanBasePackages = {"org.jeecg", "com.jeecg"}, exclude = {
        MongoAutoConfiguration.class,
        DataSourceAutoConfiguration.class
})
@EnableScheduling
public class JimuReportApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext application = SpringApplication.run(JimuReportApplication.class, args);
        Environment env = application.getEnvironment();
        String port = env.getProperty("server.port");
        String path = env.containsProperty("server.servlet.context-path")?env.getProperty("server.servlet.context-path"):"";
        String currentEncoding = System.getProperty("file.encoding", "UTF-8");
        if(!currentEncoding.equalsIgnoreCase("UTF-8")){
            // 默认编码不是UTF-8设置为UTF-8
            System.setProperty("file.encoding", "UTF-8");
        }

        System.out.println("\n----------------------------------------------------------\n\t" +
                "JimuReport 一丰报表平台 is running!  Access URL:\n\t" +
                "报表工作台: \t\thttp://localhost:" + port + path + "/jmreport/list\n\t" +
                "BI大屏工作台: \t\thttp://localhost:" + port + path + "/drag/list\n\t" +
                "----------------------------------------------------------");
    }

}
