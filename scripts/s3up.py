# -*- coding: utf-8 -*-
import sys
from s3_utils import sanitize_path, validate_local_path, upload_file_using_client

ALLOWED_MODULES = {'tronlink-FullNode'}


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("Usage: python s3up.py <local_file_path> <module>")
        sys.exit(1)

    local_file_path = sys.argv[1]
    s3_bucket_name = 'tronlink-dev'
    s3_prefix = sys.argv[2]

    if s3_prefix not in ALLOWED_MODULES:
        print(f"Error: unknown module '{s3_prefix}'")
        sys.exit(1)
    validated_path = validate_local_path(local_file_path)
    safe_file_name = sanitize_path(validated_path)
    s3_object_key = f"backend/{s3_prefix}/{safe_file_name}"
    upload_file_using_client(validated_path, s3_bucket_name, s3_object_key)
