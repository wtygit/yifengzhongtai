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
 * 报表工作台页面过滤器，用于在左上角显示"一丰中台"并添加"进入api工作台"按钮
 */
public class JimuReportPageFilter implements Filter {

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // 初始化方法，空实现
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // 仅处理报表工作台页面。登录页不再改写 HTML：注入的 logo/base64 脚本可能破坏页内脚本，
        // 导致登录失败时 ?error=1 等前端提示与弹窗完全不执行。
        String path = httpRequest.getRequestURI();
        if (path.equals("/jmreport/list") || path.endsWith("/jmreport/list")) {
            // 创建响应包装器来捕获响应内容
            ResponseWrapper responseWrapper = new ResponseWrapper(httpResponse);
            chain.doFilter(request, responseWrapper);

            // 获取响应内容
            String html = responseWrapper.getContent();

            // 用"一丰中台"图片替换原有的"积木报表"图片
            String yifengLogoBase64 = YifengLogoConfig.getLogoBase64() != null ? YifengLogoConfig.getLogoBase64() : "";
            
            try {
                // 如果提供了base64图片，替换HTML中的图片src
                if (yifengLogoBase64 != null && !yifengLogoBase64.isEmpty()) {
                    // 替换包含jimu或积木的图片src为"一丰中台"图片
                    html = html.replaceAll("(?i)(<img[^>]*src=[\"'])[^\"']*(jimu|积木)[^\"']*([\"'][^>]*>)", 
                        "$1" + yifengLogoBase64 + "$3");
                }
                
                // 查找jimu-header元素，如果还没有"一丰中台"，准备替换
                if (html.contains("jimu-header") && !html.contains("一丰中台")) {
                    // 如果没有提供图片，使用文字
                    html = html.replaceAll("(?i)(<span[^>]*class=[\"']jimu-header[\"'][^>]*>).*?(</span>)", 
                        "$1<span style=\"color: white; font-size: 16px; font-weight: bold; padding-left: 20px; display: inline-block;\">一丰中台</span>$2");
                }
            } catch (Exception regexEx) {
                regexEx.printStackTrace();
            }
            
            // 添加CSS和JavaScript来隐藏左上角的图片，并确保"一丰中台"文字显示在正确位置
            if (!html.contains("/* Hide report logo, show 一丰中台 */")) {
                String hideLogoCss = "<style>/* Hide report left-top logo, show 一丰中台 */ " +
                    // 隐藏jimu-header区域的背景图片和伪元素
                    ".jimu-header::before, .jimu-header::after { display: none !important; background-image: none !important; content: none !important; } " +
                    // 隐藏jimu-header内的所有图片和SVG
                    ".jimu-header img, .jimu-header svg, .jimu-header picture { display: none !important; visibility: hidden !important; } " +
                    // 隐藏左上角区域的logo图片（明确排除header-right区域）
                    "div:not(.header-right) img[src*=\"jimu\"], " +
                    "div:not(.header-right) img[src*=\"积木\"], " +
                    "div:not(.header-right) img[alt*=\"积木\"], " +
                    "div:not(.header-right) img[alt*=\"jimu\"], " +
                    "div:not(.header-right) svg[class*=\"jimu\"], " +
                    "div:not(.header-right) svg[class*=\"logo\"] { " +
                    "display: none !important; visibility: hidden !important; } " +
                    // 确保jimu-header显示，并且"一丰中台"文字可见
                    ".jimu-header { display: flex !important; align-items: center !important; } " +
                    ".jimu-header span { color: white !important; font-size: 16px !important; font-weight: bold !important; padding-left: 20px !important; display: inline-block !important; }</style>";
                
                String injectScript = "<script>/* Replace logo with 一丰中台 image */ " +
                    "(function() { " +
                    "  var yifengLogoBase64 = '" + yifengLogoBase64 + "'; " +
                    "  function replaceLogo() { " +
                    "    var jimuHeader = document.querySelector('.jimu-header'); " +
                    "    if (jimuHeader) { " +
                    "      // 查找原有的logo图片 " +
                    "      var oldImgs = jimuHeader.querySelectorAll('img, svg'); " +
                    "      for (var i = 0; i < oldImgs.length; i++) { " +
                    "        var img = oldImgs[i]; " +
                    "        var src = img.src || img.getAttribute('src') || ''; " +
                    "        var alt = img.alt || img.getAttribute('alt') || ''; " +
                    "        // 如果是积木报表的logo，替换为一丰中台图片 " +
                    "        if (src.includes('jimu') || src.includes('积木') || alt.includes('积木') || alt.includes('jimu')) { " +
                    "          if (yifengLogoBase64 && yifengLogoBase64 !== '') { " +
                    "            img.src = yifengLogoBase64; " +
                    "            img.alt = '一丰中台'; " +
                    "            img.style.cssText = 'height: 30px; padding-left: 20px; display: inline-block;'; " +
                    "          } else { " +
                    "            // 如果没有图片，替换为文字 " +
                    "            var textSpan = document.createElement('span'); " +
                    "            textSpan.style.cssText = 'color: white; font-size: 16px; font-weight: bold; padding-left: 20px; display: inline-block;'; " +
                    "            textSpan.textContent = '一丰中台'; " +
                    "            img.parentNode.replaceChild(textSpan, img); " +
                    "          } " +
                    "        } " +
                    "      } " +
                    "      // 如果jimu-header内没有内容或没有一丰中台，添加它 " +
                    "      if (jimuHeader.innerHTML.trim() === '' || (!jimuHeader.innerHTML.includes('一丰中台') && !jimuHeader.querySelector('img[alt=\"一丰中台\"]'))) { " +
                    "        if (yifengLogoBase64 && yifengLogoBase64 !== '') { " +
                    "          var img = document.createElement('img'); " +
                    "          img.src = yifengLogoBase64; " +
                    "          img.alt = '一丰中台'; " +
                    "          img.style.cssText = 'height: 30px; padding-left: 20px; display: inline-block;'; " +
                    "          jimuHeader.appendChild(img); " +
                    "        } else { " +
                    "          var textSpan = document.createElement('span'); " +
                    "          textSpan.style.cssText = 'color: white; font-size: 16px; font-weight: bold; padding-left: 20px; display: inline-block;'; " +
                    "          textSpan.textContent = '一丰中台'; " +
                    "          jimuHeader.appendChild(textSpan); " +
                    "        } " +
                    "      } " +
                    "    } " +
                    "    // 也处理父div内的图片 " +
                    "    var parentDiv = document.querySelector('div[style*=\"justify-content: space-between\"]'); " +
                    "    if (parentDiv) { " +
                    "      var firstDiv = parentDiv.querySelector('div:first-child'); " +
                    "      if (firstDiv && !firstDiv.classList.contains('header-right')) { " +
                    "        var logoImgs = firstDiv.querySelectorAll('img, svg'); " +
                    "        for (var i = 0; i < logoImgs.length; i++) { " +
                    "          var img = logoImgs[i]; " +
                    "          var src = img.src || img.getAttribute('src') || ''; " +
                    "          var alt = img.alt || img.getAttribute('alt') || ''; " +
                    "          if (src.includes('jimu') || src.includes('积木') || alt.includes('积木') || alt.includes('jimu')) { " +
                    "            if (yifengLogoBase64 && yifengLogoBase64 !== '') { " +
                    "              img.src = yifengLogoBase64; " +
                    "              img.alt = '一丰中台'; " +
                    "              img.style.cssText = 'height: 30px; padding-left: 20px; display: inline-block;'; " +
                    "            } else { " +
                    "              img.remove(); " +
                    "            } " +
                    "          } " +
                    "        } " +
                    "      } " +
                    "    } " +
                    "  } " +
                    "  if (document.readyState === 'loading') { " +
                    "    document.addEventListener('DOMContentLoaded', replaceLogo); " +
                    "  } else { " +
                    "    replaceLogo(); " +
                    "  } " +
                    "  setTimeout(replaceLogo, 300); " +
                    "  setTimeout(replaceLogo, 800); " +
                    "  setTimeout(replaceLogo, 1500); " +
                    "})();</script>";
                
                if (html.contains("</head>")) {
                    html = html.replace("</head>", hideLogoCss + "</head>");
                }
                if (html.contains("</body>")) {
                    html = html.replace("</body>", injectScript + "</body>");
                } else if (html.contains("</html>")) {
                    html = html.replace("</html>", injectScript + "</html>");
                }
            }

            // 检查是否已经存在右上角扩展入口，避免重复插入（API 工作台 / 多表关联）
            if (html.contains("进入api工作台") || html.contains("/api-generator/list")
                    || html.contains("多表关联与字段组合") || html.contains("/table-association")) {
                // 已经存在，直接返回
            } else {
                // 创建扩展入口链接，使用与"进入BI工作台"相同的样式
                String apiLinkHtml = "&nbsp;&nbsp;<a class=\"jimu-logout jimu-switch\" href=\"/api-generator/list\"><i class=\"ivu-icon ivu-icon-ios-code\" style=\"font-size: 20px;\"></i> 进入api工作台</a>";
                String assocLinkHtml = "&nbsp;&nbsp;<a class=\"jimu-logout jimu-switch\" href=\"/table-association\"><i class=\"ivu-icon ivu-icon-ios-git-branch\" style=\"font-size: 20px;\"></i> 多表关联与字段组合</a>";
                
                // 查找"进入BI工作台"链接的位置，在其后插入"进入api工作台"链接
                int biWorkbenchIndex = html.indexOf("进入BI工作台");
                if (biWorkbenchIndex != -1) {
                    // 找到"进入BI工作台"文本后，查找对应的 </a> 标签
                    // 从"进入BI工作台"位置向后查找 </a>
                    int searchStart = biWorkbenchIndex;
                    int biLinkEndIndex = html.indexOf("</a>", searchStart);
                    if (biLinkEndIndex != -1) {
                        // 在 </a> 标签后插入扩展入口链接
                        html = html.substring(0, biLinkEndIndex + 4) 
                               + apiLinkHtml + assocLinkHtml 
                               + html.substring(biLinkEndIndex + 4);
                    } else {
                        // 如果找不到对应的 </a>，尝试在 header-right 区域内查找
                        int headerRightIndex = html.indexOf("header-right");
                        if (headerRightIndex != -1) {
                            // 查找 header-right 内的第一个 </a> 标签
                            int firstLinkEndIndex = html.indexOf("</a>", headerRightIndex);
                            if (firstLinkEndIndex != -1) {
                                html = html.substring(0, firstLinkEndIndex + 4) 
                                       + apiLinkHtml + assocLinkHtml 
                                       + html.substring(firstLinkEndIndex + 4);
                            }
                        }
                    }
                } else {
                    // 如果找不到"进入BI工作台"，尝试在 header-right 区域内查找
                    int headerRightIndex = html.indexOf("header-right");
                    if (headerRightIndex != -1) {
                        // 查找 header-right 结束标签 </span> 的位置
                        int headerRightEndIndex = html.indexOf("</span>", headerRightIndex);
                        if (headerRightEndIndex != -1) {
                            // 在 </span> 前插入API工作台链接
                            html = html.substring(0, headerRightEndIndex) 
                                   + apiLinkHtml 
                                   + html.substring(headerRightEndIndex);
                        }
                    }
                }
            }

            // 将修改后的响应内容写回客户端
            httpResponse.setContentType("text/html;charset=UTF-8");
            PrintWriter out = httpResponse.getWriter();
            out.write(html);
            out.flush();
            out.close();
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