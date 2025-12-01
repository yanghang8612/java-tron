# -*- coding: utf-8 -*-
import sys
import boto3

def upload_file_using_client(file_path, bucket_name, s3_key):
    # 创建 S3 客户端
    s3 = boto3.client('s3')

    try:
        # 上传文件
        s3.upload_file(
            Filename=file_path,
            Bucket=bucket_name,
            Key=s3_key,
            ExtraArgs={
                #'ACL': 'public-read',  # 设置访问权限（可选）
                'ContentType': 'text/plain'  # 设置内容类型（可选）
            }
        )
        print(f"File {local_file_path} uploaded to {s3_key}")
    except Exception as e:
        print(f"Error uploading file: {e}")

if __name__ == "__main__":
    # 获取第一个参数
    local_file_path = sys.argv[1]
    # S3 存储桶名称
    s3_bucket_name = 'tronlink-artifacts-dev'
    # S3  对象键（即上传到 S3 后的文件名和路径）
    s3_prefix = sys.argv[2]  # 例如传入 "tronlink-server"
    s3_object_key = f"{s3_prefix}/{local_file_path}"
    upload_file_using_client(local_file_path, s3_bucket_name, s3_object_key)
