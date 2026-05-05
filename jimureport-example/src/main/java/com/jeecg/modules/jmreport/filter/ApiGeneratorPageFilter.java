package com.jeecg.modules.jmreport.filter;

import com.jeecg.modules.jmreport.config.YifengLogoConfig;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import jakarta.servlet.WriteListener;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * API工作台页面过滤器，用于在左上角显示"一丰中台"
 */
public class ApiGeneratorPageFilter implements Filter {

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // 初始化方法，空实现
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // 处理API工作台页面的请求
        String path = httpRequest.getRequestURI();
        if (path.equals("/api-generator/list")) {
            // 检查响应是否已经提交，如果已提交则直接放行
            if (httpResponse.isCommitted()) {
                chain.doFilter(request, response);
                return;
            }
            
            try {
                // 创建响应包装器来捕获响应内容
                ResponseWrapper responseWrapper = new ResponseWrapper(httpResponse);
                chain.doFilter(request, responseWrapper);

                // 获取响应内容
                String html = responseWrapper.getContent();
                
                // 如果HTML为空或太短，可能是错误页面或重定向，直接返回原始内容
                if (html == null || html.length() < 100) {
                    if (!httpResponse.isCommitted()) {
                        httpResponse.setContentType("text/html;charset=UTF-8");
                        PrintWriter out = httpResponse.getWriter();
                        out.write(html != null ? html : "");
                        out.flush();
                    }
                    return;
                }

                // 用"一丰中台"图片或文字替换navbar-brand
                String yifengLogoBase64 = YifengLogoConfig.getLogoBase64() != null ? YifengLogoConfig.getLogoBase64() : "";
                
                try {
                    // 如果提供了base64图片，使用图片；否则使用文字
                    if (yifengLogoBase64 != null && !yifengLogoBase64.isEmpty()) {
                        // 替换navbar-brand为图片
                        html = html.replaceAll("(?i)(<a[^>]*class=[\"']navbar-brand[\"'][^>]*>).*?(</a>)", 
                            "$1<img src=\"" + yifengLogoBase64 + "\" style=\"height: 30px; padding-left: 20px; display: inline-block;\" alt=\"一丰中台\">$2");
                    } else {
                        // 如果没有图片，使用文字（模板中已经修改为"一丰中台"）
                        if (!html.contains("一丰中台")) {
                            html = html.replaceAll("(?i)(<a[^>]*class=[\"']navbar-brand[\"'][^>]*>).*?(</a>)", 
                                "$1一丰中台$2");
                        }
                    }
                } catch (Exception regexEx) {
                    regexEx.printStackTrace();
                }

                // 将修改后的响应内容写回客户端
                if (!httpResponse.isCommitted()) {
                    httpResponse.setContentType("text/html;charset=UTF-8");
                    httpResponse.setContentLength(html.getBytes(StandardCharsets.UTF_8).length);
                    PrintWriter out = httpResponse.getWriter();
                    out.write(html);
                    out.flush();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            // 其他请求直接放行
            chain.doFilter(request, response);
        }
    }

    @Override
    public void destroy() {
        // 销毁方法，空实现
    }

    /**
     * 响应包装器，用于捕获响应内容
     */
    private static class ResponseWrapper extends HttpServletResponseWrapper {
        private ByteArrayOutputStream outputStream;
        private PrintWriter writer;

        public ResponseWrapper(HttpServletResponse response) {
            super(response);
            outputStream = new ByteArrayOutputStream();
            writer = new PrintWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            return new ServletOutputStream() {
                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener listener) {
                }

                @Override
                public void write(int b) throws IOException {
                    outputStream.write(b);
                }
            };
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            return writer;
        }

        public String getContent() {
            writer.flush();
            return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
