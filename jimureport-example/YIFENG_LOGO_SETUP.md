# 一丰中台Logo配置说明

## 配置步骤

1. **将"一丰中台"图片转换为base64编码**

   可以使用以下方法：
   - 在线工具：https://www.base64-image.de/ 或 https://base64.guru/converter/encode/image
   - 项目自带脚本（文字转 Base64）：项目根目录 `scripts/text_to_base64.py`，运行后可将“一丰”等文字生成 Base64 Data URI。
   - 或使用 Python 读图片：
     ```python
     import base64
     with open("一丰中台.png", "rb") as image_file:
         encoded_string = base64.b64encode(image_file.read()).decode('utf-8')
         print(f"data:image/png;base64,{encoded_string}")
     ```

2. **修改配置文件**

   打开文件：`jimureport-example/src/main/java/com/jeecg/modules/jmreport/config/YifengLogoConfig.java`
   
   找到这一行：
   ```java
   public static final String YIFENG_LOGO_BASE64 = "";
   ```
   
   将base64编码填入：
   ```java
   public static final String YIFENG_LOGO_BASE64 = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAA...";
   ```

3. **重启项目**

   配置完成后，重启Spring Boot项目，三个首页的左上角都会显示"一丰中台"图片。

## 说明

- 如果 `YIFENG_LOGO_BASE64` 为空字符串，系统会使用文字"一丰中台"代替图片
- 支持的图片格式：PNG、JPEG、GIF等
- base64编码格式：`data:image/png;base64,xxxxx` 或 `data:image/jpeg;base64,xxxxx`
