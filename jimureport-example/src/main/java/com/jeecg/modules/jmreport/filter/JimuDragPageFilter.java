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
 * BI工作台页面过滤器，用于在左上角显示"一丰中台"
 */
public class JimuDragPageFilter implements Filter {

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // 初始化方法，空实现
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // 处理BI工作台相关页面的请求
        String path = httpRequest.getRequestURI();
        if (path != null && path.startsWith("/drag/")) {
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
                    // 检查响应是否已提交
                    if (!httpResponse.isCommitted()) {
                        httpResponse.setContentType("text/html;charset=UTF-8");
                        PrintWriter out = httpResponse.getWriter();
                        out.write(html != null ? html : "");
                        out.flush();
                    }
                    return;
                }

                // 用"一丰中台"图片替换原有的"积木BI"图片
                String yifengLogoBase64 = YifengLogoConfig.getLogoBase64() != null ? YifengLogoConfig.getLogoBase64() : "";
                
                try {
                    // 如果提供了base64图片，替换HTML中的图片src
                    if (yifengLogoBase64 != null && !yifengLogoBase64.isEmpty()) {
                        // 替换包含jimu或积木的图片src为"一丰中台"图片
                        html = html.replaceAll("(?i)(<img[^>]*src=[\"'])[^\"']*(jimu|积木)[^\"']*([\"'][^>]*>)", 
                            "$1" + yifengLogoBase64 + "$3");
                    }
                    
                    // 查找jimu-header元素，替换其内容为"一丰中台"
                    if (html.contains("jimu-header") && !html.contains("一丰中台")) {
                        // 如果没有提供图片，使用文字
                        html = html.replaceAll("(?i)(<span[^>]*class=[\"']jimu-header[\"'][^>]*>).*?(</span>)", 
                            "$1<span style=\"color: white; font-size: 16px; font-weight: bold; padding-left: 20px; display: inline-block;\">一丰中台</span>$2");
                    }
                } catch (Exception regexEx) {
                    regexEx.printStackTrace();
                }
                
                // 添加CSS和JavaScript来隐藏左上角的图片，并显示"一丰中台"文字
                if (!html.contains("/* Hide BI left-top logo, show 一丰中台 */")) {
                    String hideLogoCss = "<style>/* Hide BI left-top logo, show 一丰中台 */ " +
                        // 隐藏jimu-header区域的背景图片和伪元素
                        ".jimu-header::before, .jimu-header::after { display: none !important; background-image: none !important; } " +
                        // 隐藏jimu-header内的图片和SVG（积木相关的）
                        ".jimu-header img[src*=\"jimu\"], .jimu-header img[src*=\"积木\"], " +
                        ".jimu-header svg[class*=\"jimu\"], .jimu-header svg[class*=\"logo\"] { " +
                        "display: none !important; } " +
                        // 隐藏左上角区域的logo图片（明确排除header-right区域）
                        "body img[src*=\"jimu\"]:not(.header-right img):not([alt=\"一丰中台\"]), " +
                        "body img[src*=\"积木\"]:not(.header-right img):not([alt=\"一丰中台\"]), " +
                        "body img[alt*=\"积木\"]:not(.header-right img):not([alt=\"一丰中台\"]), " +
                        "body img[alt*=\"jimu\"]:not(.header-right img):not([alt=\"一丰中台\"]), " +
                        "body img[alt*=\"积木BI\"]:not(.header-right img):not([alt=\"一丰中台\"]) { " +
                        "display: none !important; } " +
                        // 确保jimu-header在左上角显示，优先级最高
                        ".jimu-header { " +
                        "display: flex !important; " +
                        "align-items: center !important; " +
                        "order: -999 !important; " +
                        "z-index: 9999 !important; " +
                        "position: relative !important; " +
                        "visibility: visible !important; " +
                        "opacity: 1 !important; " +
                        "} " +
                        // 确保header容器使用flex布局，左边元素优先
                        "div[style*=\"justify-content: space-between\"] > div:first-child:not(.header-right) { " +
                        "display: flex !important; " +
                        "align-items: center !important; " +
                        "order: -1 !important; " +
                        "} " +
                        // 确保jimu-header内的文字和图片可见
                        ".jimu-header span, .jimu-header img[alt=\"一丰中台\"] { " +
                        "display: inline-block !important; " +
                        "visibility: visible !important; " +
                        "opacity: 1 !important; " +
                        "z-index: 10000 !important; " +
                        "position: relative !important; " +
                        "}</style>";
                    
                    // 添加JavaScript来替换logo为"一丰中台"图片，并确保在左上角
                    String injectScript = "<script>/* Replace logo with 一丰中台 image and move to left-top */ " +
                        "(function() { " +
                        "  var yifengLogoBase64 = '" + yifengLogoBase64 + "'; " +
                        "  function replaceLogo() { " +
                        "    // 查找header容器 " +
                        "    var headerContainer = document.querySelector('div[style*=\"justify-content: space-between\"]') || " +
                        "                        document.querySelector('div[style*=\"background-color: #1890FF\"]'); " +
                        "    if (!headerContainer) return; " +
                        "    " +
                        "    // 查找左上角容器（第一个div，不包含header-right） " +
                        "    var leftContainer = headerContainer.querySelector('div:first-child'); " +
                        "    if (!leftContainer || leftContainer.classList.contains('header-right')) { " +
                        "      // 创建左上角容器 " +
                        "      leftContainer = document.createElement('div'); " +
                        "      leftContainer.style.cssText = 'display: flex !important; align-items: center !important; order: -999 !important; z-index: 9999 !important; position: relative !important;'; " +
                        "      headerContainer.insertBefore(leftContainer, headerContainer.firstChild); " +
                        "    } else { " +
                        "      // 确保leftContainer在最前面 " +
                        "      leftContainer.style.cssText = (leftContainer.style.cssText || '') + ' display: flex !important; align-items: center !important; order: -999 !important; z-index: 9999 !important; position: relative !important;'; " +
                        "      headerContainer.insertBefore(leftContainer, headerContainer.firstChild); " +
                        "    } " +
                        "    " +
                        "    // 查找所有包含'一丰中台'的元素（可能在右边） " +
                        "    var allElements = document.querySelectorAll('*'); " +
                        "    var yifengInRight = null; " +
                        "    for (var i = 0; i < allElements.length; i++) { " +
                        "      var el = allElements[i]; " +
                        "      var text = el.textContent || ''; " +
                        "      var alt = el.alt || el.getAttribute('alt') || ''; " +
                        "      if ((text.includes('一丰中台') || alt === '一丰中台') && " +
                        "          el.parentElement && el.parentElement.classList.contains('header-right')) { " +
                        "        yifengInRight = el; " +
                        "        break; " +
                        "      } " +
                        "    } " +
                        "    " +
                        "    // 如果'一丰中台'在右边，移动到左边 " +
                        "    if (yifengInRight) { " +
                        "      yifengInRight.remove(); " +
                        "    } " +
                        "    " +
                        "    // 查找或创建jimu-header " +
                        "    var jimuHeader = document.querySelector('.jimu-header'); " +
                        "    if (!jimuHeader) { " +
                        "      jimuHeader = document.createElement('span'); " +
                        "      jimuHeader.className = 'jimu-header'; " +
                        "      jimuHeader.style.cssText = 'color: white !important; font-size: 16px !important; font-weight: bold !important; padding-left: 20px !important; display: inline-block !important; visibility: visible !important; opacity: 1 !important; z-index: 9999 !important; position: relative !important;'; " +
                        "      leftContainer.insertBefore(jimuHeader, leftContainer.firstChild); " +
                        "    } else { " +
                        "      // 确保jimu-header在leftContainer中 " +
                        "      if (jimuHeader.parentElement !== leftContainer) { " +
                        "        jimuHeader.remove(); " +
                        "        leftContainer.insertBefore(jimuHeader, leftContainer.firstChild); " +
                        "      } else { " +
                        "        // 移动到最前面 " +
                        "        leftContainer.insertBefore(jimuHeader, leftContainer.firstChild); " +
                        "      } " +
                        "    } " +
                        "    " +
                        "    // 删除原有的logo图片 " +
                        "    var oldImgs = jimuHeader.querySelectorAll('img, svg'); " +
                        "    for (var i = 0; i < oldImgs.length; i++) { " +
                        "      var img = oldImgs[i]; " +
                        "      var src = img.src || img.getAttribute('src') || ''; " +
                        "      var alt = img.alt || img.getAttribute('alt') || ''; " +
                        "      if (src.includes('jimu') || src.includes('积木') || alt.includes('积木') || alt.includes('jimu')) { " +
                        "        img.remove(); " +
                        "      } " +
                        "    } " +
                        "    " +
                        "    // 检查是否已经有'一丰中台' " +
                        "    var hasYifeng = (jimuHeader.textContent && jimuHeader.textContent.includes('一丰中台')) || " +
                        "                    jimuHeader.querySelector('img[alt=\"一丰中台\"]'); " +
                        "    " +
                        "    if (!hasYifeng || yifengInRight) { " +
                        "      // 清空内容，添加'一丰中台' " +
                        "      jimuHeader.innerHTML = ''; " +
                        "      if (yifengLogoBase64 && yifengLogoBase64 !== '') { " +
                        "        var img = document.createElement('img'); " +
                        "        img.src = yifengLogoBase64; " +
                        "        img.alt = '一丰中台'; " +
                        "        img.style.cssText = 'height: 30px !important; padding-left: 20px !important; display: inline-block !important; visibility: visible !important; opacity: 1 !important; z-index: 10000 !important; position: relative !important;'; " +
                        "        jimuHeader.appendChild(img); " +
                        "      } else { " +
                        "        var textSpan = document.createElement('span'); " +
                        "        textSpan.style.cssText = 'color: white !important; font-size: 16px !important; font-weight: bold !important; padding-left: 20px !important; display: inline-block !important; visibility: visible !important; opacity: 1 !important; z-index: 10000 !important; position: relative !important;'; " +
                        "        textSpan.textContent = '一丰中台'; " +
                        "        jimuHeader.appendChild(textSpan); " +
                        "      } " +
                        "    } " +
                        "  } " +
                        "  if (document.readyState === 'loading') { " +
                        "    document.addEventListener('DOMContentLoaded', replaceLogo); " +
                        "  } else { " +
                        "    replaceLogo(); " +
                        "  } " +
                        "  setTimeout(replaceLogo, 500); " +
                        "  setTimeout(replaceLogo, 1000); " +
                        "  setTimeout(replaceLogo, 2000); " +
                        "})();</script>";
                    
                    // 在head标签结束前添加CSS
                    if (html.contains("</head>")) {
                        html = html.replace("</head>", hideLogoCss + "</head>");
                    }
                    
                    // 在body结束标签前添加脚本
                    if (html.contains("</body>")) {
                        html = html.replace("</body>", injectScript + "</body>");
                    } else if (html.contains("</html>")) {
                        html = html.replace("</html>", injectScript + "</html>");
                    }
                }

                // 在 BI 页面中注入“多表关联数据集”入口按钮（列表页与设计器共用）
                String taScript = "<script>(function(){"
                        + "function addBtn(win){"
                        + "try{var d=win.document;if(!d||d.getElementById('yf-ta-dataset-btn'))return;"
                        + "var bar=d.querySelector('.header-right')||d.querySelector('.jimu-header-right')||d.querySelector('.drag-header-right');"
                        + "if(!bar){bar=d.body;}"
                        + "if(!bar)return;"
                        + "var a=d.createElement('a');"
                        + "a.id='yf-ta-dataset-btn';"
                        + "a.href='/table-association';"
                        + "a.target='_blank';"
                        + "a.innerText='多表关联数据集';"
                        + "a.style.cssText='margin-left:8px;color:#fff;cursor:pointer;font-size:13px;';"
                        + "bar.appendChild(a);"
                        + "}catch(e){}}"
                        + "if(document.readyState==='loading'){"
                        + "document.addEventListener('DOMContentLoaded',function(){addBtn(window);});"
                        + "}else{addBtn(window);}"
                        + "setTimeout(function(){"
                        + "var ifs=document.querySelectorAll('iframe');"
                        + "for(var i=0;i<ifs.length;i++){try{addBtn(ifs[i].contentWindow);}catch(e){}}"
                        + "},1200);"
                        + "})();</script>";

                if (html.contains("</body>")) {
                    html = html.replace("</body>", taScript + "</body>");
                } else if (html.contains("</html>")) {
                    html = html.replace("</html>", taScript + "</html>");
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
                // 如果处理过程中出现异常，记录日志
                e.printStackTrace();
                // 异常情况下，如果响应未提交，尝试返回原始响应
                // 注意：此时不能再次调用chain.doFilter，因为响应已经被处理过了
                // 如果响应已提交，说明已经发送了，无法再处理
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
