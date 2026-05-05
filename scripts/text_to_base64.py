"""
将文字生成图片并转为 Base64，用于一丰报表 Logo 等配置。
使用说明见：jimureport-example/YIFENG_LOGO_SETUP.md
"""
from PIL import Image, ImageDraw, ImageFont
import base64
from io import BytesIO


def text_to_base64(text, size=(200, 100), bg_color=(255, 255, 255), text_color=(0, 0, 0)):
    """
    将文字生成图片并转为Base64编码
    :param text: 要显示的文字
    :param size: 图片尺寸 (宽, 高)
    :param bg_color: 背景色 (R, G, B)
    :param text_color: 文字色 (R, G, B)
    :return: Base64编码字符串
    """
    image = Image.new("RGB", size, bg_color)
    draw = ImageDraw.Draw(image)
    try:
        font = ImageFont.truetype("simhei.ttf", 40)
    except Exception:
        try:
            font = ImageFont.truetype("NotoSansCJK-Regular.otf", 40)
        except Exception:
            font = ImageFont.load_default()
    text_bbox = draw.textbbox((0, 0), text, font=font)
    text_width = text_bbox[2] - text_bbox[0]
    text_height = text_bbox[3] - text_bbox[1]
    x = (size[0] - text_width) // 2
    y = (size[1] - text_height) // 2
    draw.text((x, y), text, font=font, fill=text_color)
    byte_stream = BytesIO()
    image.save(byte_stream, format="PNG")
    return base64.b64encode(byte_stream.getvalue()).decode("utf-8")


if __name__ == "__main__":
    base64_result = text_to_base64("一丰")
    data_uri = f"data:image/png;base64,{base64_result}"
    print("生成的Base64 Data URI：")
    print(data_uri)
