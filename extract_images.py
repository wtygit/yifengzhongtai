#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
提取 Word 文档中的图片
"""

from docx import Document
import os
import zipfile

def extract_images_from_docx(docx_path, output_folder):
    """
    从 docx 文件中提取图片
    """
    # 确保输出目录存在
    output_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), output_folder)
    if not os.path.exists(output_dir):
        os.makedirs(output_dir)
    
    # docx 文件实际上是 zip 文件
    docx_full_path = os.path.abspath(docx_path)
    with zipfile.ZipFile(docx_full_path, 'r') as zip_ref:
        # 列出所有文件
        file_list = zip_ref.namelist()
        
        # 提取 media 文件夹中的图片
        image_files = [f for f in file_list if f.startswith('word/media/')]
        
        extracted_images = []
        for image_file in image_files:
            # 提取文件名
            filename = os.path.basename(image_file)
            if not filename:  # 跳过目录
                continue
            output_path = os.path.join(output_dir, filename)
            
            # 读取并保存图片
            with zip_ref.open(image_file) as source:
                with open(output_path, 'wb') as target:
                    target.write(source.read())
            
            extracted_images.append(output_path)
            print(f"已提取: {filename}")
        
        return extracted_images

def analyze_docx_structure(docx_path):
    """
    分析 docx 文档结构，获取段落信息
    """
    doc = Document(docx_path)
    
    print("\n=== 文档段落内容 ===")
    for i, para in enumerate(doc.paragraphs):
        if para.text.strip():
            print(f"段落 {i}: {para.text[:150]}")
    
    print("\n=== 文档中的图片 ===")
    image_count = 0
    for rel in doc.part.rels.values():
        if "image" in rel.target_ref:
            print(f"图片 {image_count}: {rel.target_ref}")
            image_count += 1
    
    return image_count

if __name__ == "__main__":
    docx_path = "关键页面截图.docx"
    output_folder = "extracted_images"
    
    print("正在分析文档结构...")
    image_count = analyze_docx_structure(docx_path)
    
    print(f"\n正在提取图片到 {output_folder} 文件夹...")
    images = extract_images_from_docx(docx_path, output_folder)
    
    print(f"\n共提取 {len(images)} 张图片")
    print(f"图片保存在: {os.path.abspath(output_folder)}")
