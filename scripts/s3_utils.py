# -*- coding: utf-8 -*-
import sys
import os
import boto3


def sanitize_path(path_str):
    """Block path traversal, keep only the filename part."""
    return os.path.basename(path_str)


def validate_local_path(file_path, allowed_base=None):
    """Validate that local file path does not escape the allowed directory."""
    if allowed_base is None:
        allowed_base = os.getcwd()
    real_path = os.path.realpath(file_path)
    real_base = os.path.realpath(allowed_base)
    if os.path.commonpath([real_path, real_base]) != real_base:
        print("Error: file path is outside allowed directory")
        sys.exit(1)
    if not os.path.isfile(real_path):
        print("Error: file does not exist")
        sys.exit(1)
    return real_path


def upload_file_using_client(file_path, bucket_name, s3_key):
    s3 = boto3.client('s3')
    try:
        s3.upload_file(
            Filename=file_path,
            Bucket=bucket_name,
            Key=s3_key,
            ExtraArgs={
                'ContentType': 'application/zip'
            }
        )
        print(f"File {file_path} uploaded to {s3_key}")
    except Exception as e:
        print(f"Error uploading file: {e}")
        sys.exit(1)
