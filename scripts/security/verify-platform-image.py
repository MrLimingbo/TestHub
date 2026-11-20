#!/usr/bin/env python3
"""Run the local API image with fresh MySQL, Redis and MinIO using production Compose.

The selected isolated Docker context must already have all four images. See
infra/minio/README.md for the source build. The check publishes only the API,
on a random loopback port, and removes its own Compose project on exit.
"""
import argparse
import base64
import http.cookiejar
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def command(args, *, environment, description, data=None, timeout=180):
    result = subprocess.run(args, cwd=ROOT, env=environment, input=data,
                            capture_output=True, timeout=timeout)
    require(result.returncode == 0, f"{description} failed ({result.returncode})")
    return result.stdout


def loopback_origin(compose):
    address = compose("port", "api", "8080").decode().strip()
    require(re.fullmatch(r"127\.0\.0\.1:\d+", address) is not None, "API is not bound to loopback")
    return "http://" + address


def wait_ready(request, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if json.loads(request("/actuator/health/readiness"))["status"] == "UP":
                return
        except (RuntimeError, OSError):
            pass
        time.sleep(1)
    raise RuntimeError(f"API readiness did not reach UP within {timeout} seconds")


def verify_http(compose, object_store_password, startup_timeout=90, request_timeout=10):
    origin = loopback_origin(compose)
    opener = urllib.request.build_opener(
        urllib.request.ProxyHandler({}),
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),
    )

    def request(path, method="GET", body=None, headers=None, expected=200):
        req = urllib.request.Request(origin + path, body, headers or {}, method=method)
        try:
            response = opener.open(req, timeout=request_timeout)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            require(response.status == expected, f"{method} {path}: HTTP {response.status}, expected {expected}")
            return response.read()

    def csrf():
        values = json.loads(request("/api/v1/auth/csrf"))["data"]
        return {values["headerName"]: values["token"]}

    def mysql(query):
        return compose("exec", "-T", "mysql", "sh", "-c",
                       'MYSQL_PWD="$MYSQL_PASSWORD" mysql --batch --skip-column-names '
                       '--user="$MYSQL_USER" "$MYSQL_DATABASE"', data=(query + "\n").encode()).strip()

    def s3(key, expected_status, expected_data=b""):
        path = urllib.parse.quote(key, safe="/")
        route = f"http://127.0.0.1:9000/testhub-files/{path}"
        options = (
            f'url = "{route}"\nuser = "testhub-qa:{object_store_password}"\n'
            'aws-sigv4 = "aws:amz:us-east-1:s3"\n'
        ).encode()
        response = compose("exec", "-T", "minio", "curl", "--silent", "--show-error",
                           "--max-time", "15", "--config", "-", "--write-out", "\n%{http_code}", data=options)
        content, status = response.rsplit(b"\n", 1)
        require(status == expected_status, f"Unexpected S3 status {status.decode()}")
        if expected_status == b"200":
            require(content == expected_data, "S3 object bytes differ from upload")

    wait_ready(request, startup_timeout)
    require(mysql("SELECT COUNT(*) FROM user_account;") == b"0", "QA database should start without demo accounts")
    request("/api/v1/auth/me", expected=401)
    account = "qa-" + secrets.token_hex(5)
    credentials = {"account": account, "email": account + "@example.test", "password": secrets.token_hex(24)}
    request("/api/v1/auth/register", "POST", json.dumps(credentials).encode(),
            {**csrf(), "Content-Type": "application/json"}, 201)
    access = json.loads(request("/api/v1/access/context"))["data"]
    organization = access["organizations"][0]["id"]
    project = access["projects"][organization][0]["id"]
    require(mysql(f"SELECT COUNT(*) FROM user_account WHERE account='{account}';") == b"1",
            "Registered account is missing from MySQL")

    sessions = compose("exec", "-T", "redis", "sh", "-c",
                       'REDISCLI_AUTH="$TESTHUB_REDIS_PASSWORD" redis-cli --scan --pattern "testhub:session:sessions:*"')
    require(sessions.strip(), "API session is missing from Redis")

    content = b"TestHub isolated image acceptance\n" + secrets.token_bytes(32)
    boundary = "testhub-" + uuid.uuid4().hex
    multipart = (f"--{boundary}\r\n"
                 'Content-Disposition: form-data; name="file"; filename="acceptance.bin"\r\n'
                 "Content-Type: application/octet-stream\r\n\r\n").encode()
    multipart += content + f"\r\n--{boundary}--\r\n".encode()
    files = f"/api/v1/projects/{project}/files"
    uploaded = json.loads(request(files, "POST", multipart, {**csrf(),
                         "Content-Type": f"multipart/form-data; boundary={boundary}"}))["data"]
    file_id, key = uploaded["id"], uploaded["objectKey"]
    require(re.fullmatch(r"[0-9A-HJKMNP-TV-Z]{26}", file_id) is not None, "Invalid file ID")
    require(mysql(f"SELECT object_key FROM file_object WHERE id='{file_id}';") == key.encode(),
            "Object metadata is missing from MySQL")
    require(mysql(f"SELECT COUNT(*) FROM audit_log WHERE resource_id='{file_id}' AND action='FILE_UPLOADED';") == b"1",
            "Upload audit is missing from MySQL")
    s3(key, b"200", content)

    compose("restart", "api")
    origin = loopback_origin(compose)
    wait_ready(request, startup_timeout)
    require(json.loads(request("/api/v1/auth/me"))["data"]["account"] == account,
            "Redis session did not survive API restart")
    require(request(f"{files}/{file_id}/download") == content, "Downloaded bytes differ from upload")
    request(f"{files}/{file_id}", "DELETE", headers=csrf(), expected=204)
    require(mysql(f"SELECT COUNT(*) FROM file_object WHERE id='{file_id}';") == b"0",
            "Deleted object metadata remains in MySQL")
    s3(key, b"404")
    request("/api/v1/auth/logout", "POST", headers=csrf(), expected=204)
    request("/api/v1/auth/me", expected=401)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--context", required=True, help="Dedicated QA Docker context")
    parser.add_argument("--startup-timeout", type=int, default=90,
                        help="Readiness deadline in seconds for each API start, including restart (default: 90)")
    parser.add_argument("--request-timeout", type=int, default=10,
                        help="HTTP request timeout in seconds (default: 10)")
    parser.add_argument("image", help="Locally built Linux API image")
    args = parser.parse_args()
    if args.startup_timeout <= 0:
        parser.error("--startup-timeout must be positive")
    if args.request_timeout <= 0:
        parser.error("--request-timeout must be positive")
    require(args.context not in {"default", "colima"}, "Use a separate QA Docker context")

    env = {key: value for key, value in os.environ.items()
           if not key.startswith(("COMPOSE_", "SPRING_", "TESTHUB_")) and key != "LOCALDOMAIN"}
    docker = ["docker", "--context", args.context]
    image_id = command(docker + ["image", "inspect", "--format", "{{.Id}}", args.image],
                       environment=env, description="Inspect local QA image").decode().strip()
    password, root_password, redis_password, object_store_password = [secrets.token_hex(24) for _ in range(4)]
    env.update(TESTHUB_PLATFORM_IMAGE=args.image, TESTHUB_WEB_IMAGE="testhub/qa-unused-web:local",
               TESTHUB_DB_PASSWORD=password, TESTHUB_DB_ROOT_PASSWORD=root_password,
               TESTHUB_REDIS_PASSWORD=redis_password, TESTHUB_MINIO_ACCESS_KEY="testhub-qa",
               TESTHUB_MINIO_SECRET_KEY=object_store_password,
               TESTHUB_ENCRYPTION_KEY=base64.b64encode(secrets.token_bytes(32)).decode(),
               TESTHUB_ENCRYPTION_KEY_ID="qa", TESTHUB_CORS_ALLOWED_ORIGINS="http://127.0.0.1")
    project = "testhub-image-qa-" + uuid.uuid4().hex[:12]
    with tempfile.TemporaryDirectory(prefix=project) as work:
        directory = Path(work)
        (directory / "empty.env").touch()
        override = directory / "compose.json"
        override.write_text(json.dumps({"services": {"api": {
            "ports": ["127.0.0.1::8080"],
            "networks": ["internal", "edge"],
            "environment": {"TESTHUB_REGISTRATION_ENABLED": "true",
                            "SPRING_APPLICATION_JSON": '{"testhub":{"bootstrap":{"demo-enabled":false}}}',
                            "TESTHUB_SECURE_COOKIE": "false"},
        }}}))
        argv = docker + ["compose", "--project-name", project, "--env-file", str(directory / "empty.env"),
                         "-f", str(ROOT / "compose.production.yaml"), "-f", str(override)]

        def compose(*options, data=None, timeout=180):
            return command(argv + list(options), environment=env, description=f"Compose {options[0]}",
                           data=data, timeout=timeout)

        try:
            compose("up", "-d", "--no-build", "--pull", "never", "--wait", "--wait-timeout", "180",
                    "mysql", "redis", "minio", "api", timeout=210)
            api = compose("ps", "-q", "api").decode().strip()
            actual = command(docker + ["inspect", "--format", "{{.Image}}", api],
                             environment=env, description="Inspect running API container").decode().strip()
            require(actual == image_id, "Running API container image ID differs from the requested image")
            verify_http(compose, object_store_password, args.startup_timeout, args.request_timeout)
        finally:
            compose("down", "--volumes", "--remove-orphans")
    print(json.dumps({"image": image_id, "readiness": "passed", "mysql_metadata_and_audit": "passed",
                      "redis_session_across_api_restart_and_logout": "passed",
                      "s3_signed_read_and_deletion": "passed", "api_download_byte_compare": "passed",
                      "owned_project_cleanup": "passed"}, indent=2))


if __name__ == "__main__":
    main()
