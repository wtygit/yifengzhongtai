package com.jeecg.modules.jmreport.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 审核页「聊天截图」本地上传后的静态访问：/mcp/uploaded/screenshots/{文件名}
 */
@Configuration
public class McpChatScreenshotResourceConfig implements WebMvcConfigurer {

    @Value("${jeecg.path.upload:/opt/upload}")
    private String jeecgUploadRoot;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path dir = Paths.get(jeecgUploadRoot, "mcp-chat-screenshot").toAbsolutePath().normalize();
        String location = dir.toUri().toString();
        if (!location.endsWith("/")) {
            location += "/";
        }
        registry.addResourceHandler("/mcp/uploaded/screenshots/**")
                .addResourceLocations(location)
                .setCachePeriod(3600);
    }
}
