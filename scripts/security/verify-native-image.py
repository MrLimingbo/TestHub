#!/usr/bin/env python3
"""Verify the actual runtime filesystem, native Expat behavior and Java callers.

Requires Docker and JDK 21. Uses only disposable, restricted containers and a
temporary directory below .testhub (also reachable from isolated Colima mounts).
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import tempfile
import uuid
import zipfile
import time
import select

ROOT = Path(__file__).resolve().parents[2]
NATIVE = ROOT / "scripts/security/native"
TOOLS = "testhub/native-security-tools:local"


def run(*args, **kwargs):
    try:
        return subprocess.run(args, check=True, text=True, capture_output=True,
                              timeout=kwargs.pop("timeout", 120), **kwargs)
    except subprocess.CalledProcessError as error:
        detail = (error.stderr or error.stdout or "")[-4000:]
        raise RuntimeError(f"{args[0]} {args[1]} failed ({error.returncode}):\n{detail}") from None


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def inventory(image, directory, patched, architecture=None):
    directory.mkdir()
    container = run("docker", "create", image).stdout.strip()
    metadata, hashes, paths, symlinks = {}, {}, set(), {}
    try:
        with subprocess.Popen(["docker", "export", container], stdout=subprocess.PIPE) as export:
            with tarfile.open(fileobj=export.stdout, mode="r|") as archive:
                for member in archive:
                    name = member.name.removeprefix("./")
                    paths.add(name)
                    if member.issym():
                        symlinks[name] = member.linkname
                    require(not (member.isfile() and member.mode & 0o6000), "SUID/SGID file: " + name)
                    require(not re.search(r"(^|/)(mount(?:\.[^/]+)?|umount|nsenter|busybox|login|nscd|bzip2recover)$|(^|/)libmount\.so|(^|/)bin/(ba)?sh$", name),
                            "Unexpected privileged tool/library or shell: " + name)
                    if not member.isfile():
                        continue
                    if name.startswith("var/lib/dpkg/status.d/"):
                        metadata[name] = archive.extractfile(member).read().decode()
                    elif re.fullmatch(r"usr/lib/[^/]+/lib(expatw?|uuid|fontconfig|freetype|gcc_s|stdc\+\+)\.so\.[\d.]+", name):
                        data = archive.extractfile(member).read()
                        (directory / Path(name).name).write_bytes(data)
                        hashes[name] = hashlib.md5(data).hexdigest()
                    elif name == "app/testhub-platform-api.jar":
                        with (directory / "application.jar").open("wb") as output:
                            shutil.copyfileobj(archive.extractfile(member), output)
                    else:
                        digest = hashlib.md5()
                        with archive.extractfile(member) as source:
                            while chunk := source.read(1024 * 1024):
                                digest.update(chunk)
                        hashes[name] = digest.hexdigest()
            require(export.wait(timeout=30) == 0, "docker export failed")
    finally:
        run("docker", "rm", container)
    expected = "2.10.0-1+testhub1" if patched else "2.9.0-1~deb13u1"
    status = metadata["var/lib/dpkg/status.d/libexpat1"]
    require(f"Version: {expected}\n" in status, "Unexpected Expat package version")
    for line in metadata["var/lib/dpkg/status.d/libexpat1.md5sums"].splitlines():
        checksum, name = line.split()
        require(hashes.get(name) == checksum, "Package inventory mismatch: " + name)
    if patched:
        glibc_version = "2.41-12+deb13u4+testhub1"
        glibc_status = metadata["var/lib/dpkg/status.d/libc6"]
        require(f"Version: {glibc_version}\n" in glibc_status, "Unexpected glibc package version")
        require("X-TestHub-Upstream-Commit: 506ea57086bfb9ce3daff1c14246a1cb532aba0a\n" in glibc_status,
                "Missing reviewed glibc backport provenance")
        for line in metadata["var/lib/dpkg/status.d/libc6.md5sums"].splitlines():
            checksum, name = line.split()
            require(hashes.get(name) == checksum, "glibc package inventory mismatch: " + name)
        cxx_backported = architecture in {"amd64", "arm64", "ppc64le", "riscv64", "s390x"}
        cxx_version = "16.2.0-4+testhub1" if cxx_backported else "16.2.0-4"
        for package in ("gcc-16-base", "libgcc-s1", "libstdc++6"):
            package_status = metadata["var/lib/dpkg/status.d/" + package]
            package_version = cxx_version if package == "libstdc++6" else "16.2.0-4"
            require(f"Version: {package_version}\n" in package_status, f"Unexpected {package} version")
            if package == "libstdc++6" and cxx_backported:
                require("X-TestHub-Upstream-Commit: 68b1b9056c70acb31f2964963b51c480ff820602\n" in package_status,
                        "Missing reviewed libstdc++ backport provenance")
            for line in metadata["var/lib/dpkg/status.d/" + package + ".md5sums"].splitlines():
                checksum, name = line.split()
                require(hashes.get(name) == checksum, "GCC runtime inventory mismatch: " + name)
        require("var/lib/dpkg/status.d/gcc-14-base" not in metadata, "Old GCC metadata retained")
        require(not any(name.endswith("libstdc++.so.6.0.33") for name in paths), "Old C++ runtime retained")
        require("app/testhub-runtime-guard" in paths, "Runtime environment guard missing")
        require(not any(Path(name).name == "xmlwf" for name in paths), "Expat xmlwf CLI must stay out of the runtime")
        require("X-TestHub-Upstream-Commit: 3d77567612994172f9d09b46f31bb604e16fb08d\n" in status,
                "Missing reviewed Expat backport provenance")
        require("Version: 2.14.3+dfsg-3\n" in metadata["var/lib/dpkg/status.d/libfreetype6"], "Unexpected FreeType package version")
        for line in metadata["var/lib/dpkg/status.d/libfreetype6.md5sums"].splitlines():
            checksum, name = line.split()
            require(hashes.get(name) == checksum, "FreeType inventory mismatch: " + name)
        require(not any(name.endswith("libfreetype.so.6.20.2") for name in paths), "Old FreeType retained")
        bundled = {name: target for name, target in symlinks.items()
                   if re.fullmatch(r"usr/lib/jvm/temurin-21-jre-[^/]+/lib/libfreetype.so", name)}
        require(len(bundled) == 1, "JDK must link to the verified system FreeType")
        target = next(iter(bundled.values()))
        require(re.fullmatch(r"/usr/lib/[^/]+/libfreetype.so.6", target) is not None, "Unexpected JDK FreeType target")
        require(symlinks.get(target.lstrip("/")) == "libfreetype.so.6.20.6", "JDK FreeType target not patched")
        require(not any(re.search(r"libexpatw?\.so\.1\.13\.0$", name) for name in paths), "Old Expat retained")
        for library in ("expat", "expatw"):
            links = [target for name, target in symlinks.items() if name.endswith(f"/lib{library}.so.1")]
            require(links == [f"lib{library}.so.1.13.1"], "Unexpected Expat SONAME link")
    require(not any(re.search(r"/(mount|libmount1|util-linux|util-linux-extra)$", name) for name in metadata),
            "Affected util-linux runtime package present")
    result = {"expat": expected, "libuuid": metadata["var/lib/dpkg/status.d/libuuid1"].split("Version: ")[1].splitlines()[0],
              "mount_nsenter_libmount_shell_suid_sgid_login_nscd_bzip2recover": "absent", "package_file_checksums": "passed"}
    if patched:
        result.update(glibc=glibc_version, freetype="2.14.3+dfsg-3", gcc_runtime="16.2.0-4", libstdcxx=cxx_version, jdk_freetype=next(iter(bundled)),
                      jdk_freetype_target=target, expat_xmlwf="absent")
    return result


def restricted(image, directory, entrypoint, *args, loader=False, environment=None, resolver=None, network="none", timeout=30):
    name = "testhub-native-probe-" + uuid.uuid4().hex[:12]
    command = ["docker", "run", "--rm", "--name", name, "--network", network, "--read-only",
               "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true", "--memory", "768m",
               "--cpus", "1", "--pids-limit", "64", "--ulimit", "core=0", "--tmpfs", "/tmp:noexec,nosuid,size=64m",
               "-v", f"{directory}:/qa:ro", "--env", "XDG_CACHE_HOME=/tmp/fontcache", "--entrypoint", entrypoint]
    if loader:
        command += ["--env", "LD_DEBUG=libs"]
    for key, value in (environment or {}).items():
        command += ["--env", key + "=" + value]
    if resolver:
        command += ["-v", f"{resolver}:/etc/resolv.conf:ro"]
    try:
        return subprocess.run(command + [image, *args], text=True, capture_output=True, timeout=timeout)
    finally:
        # A client timeout does not stop the daemon's container.
        subprocess.run(["docker", "rm", "-f", name], capture_output=True, timeout=30, check=False)


def verify_dns_network(image, work):
    name = "testhub-native-dns-" + uuid.uuid4().hex[:12]
    snapshot = name + "-snapshot"
    run("docker", "network", "create", "--internal", name)
    process = None
    try:
        command = ["docker", "run", "-d", "--name", name, "--network", name,
                   "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true",
                   "--memory", "192m", "--pids-limit", "64", "--tmpfs", "/tmp:size=16m",
                   "-v", f"{work}:/qa:ro", "--env", "LOCALDOMAIN=.", "--entrypoint", "/app/testhub-runtime-guard"]
        for alias in ("mysql", "redis", "minio", "mysql-svc.team.svc.cluster.local",
                      "redis-svc.team.svc.cluster.local", "minio-svc.team.svc.cluster.local"):
            command += ["--network-alias", alias]
        run(*command, image, "java", "-Xmx64m", "-cp", "/qa", "RuntimeNetworkProbe", "serve")
        deadline = time.monotonic() + 15
        while "fixture ready" not in run("docker", "logs", name).stdout:
            require(time.monotonic() < deadline, "DNS fixture did not start")
            time.sleep(0.2)
        search = "team.svc.cluster.local svc.cluster.local cluster.local"
        checks = []
        for label, domains, aliases in [
            ("compose", ".", ("mysql", "redis", "minio")),
            ("namespace-search", search, ("mysql-svc", "redis-svc", "minio-svc")),
        ]:
            probe = restricted(image, work, "/app/testhub-runtime-guard", "java", "-Xmx64m", "-cp", "/qa",
                               "RuntimeNetworkProbe", *aliases, network=name, environment={"LOCALDOMAIN": domains})
            require(probe.returncode == 0, probe.stdout + probe.stderr)
            checks.append({"case": label, "passed": True, "observed": probe.stdout.strip().splitlines()})
        resolver = work / "reload-resolv.conf"
        resolver.write_text("nameserver 127.0.0.11\nsearch " + search + "\n")
        command = ["docker", "run", "--rm", "-i", "--name", snapshot, "--network", name, "--read-only",
                   "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true", "--memory", "192m",
                   "--pids-limit", "64", "--tmpfs", "/tmp:size=16m", "-v", f"{work}:/qa:ro",
                   "-v", f"{resolver}:/etc/resolv.conf:ro", "--entrypoint", "/app/testhub-runtime-guard",
                   image, "java", "-Xmx64m", "-cp", "/qa", "RuntimeNetworkProbe", "snapshot"]
        process = subprocess.Popen(command, text=True, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        require(bool(select.select([process.stdout], [], [], 15)[0]), "DNS snapshot client timed out")
        require(process.stdout.readline().strip() == "snapshot ready", "DNS snapshot client failed to initialize")
        # Both fixtures are ordinary valid DNS configurations. This checks
        # preservation across a file reload without sending unsafe data to libc.
        resolver.write_text("nameserver 127.0.0.11\nsearch changed.test\n")
        time.sleep(1.1)
        stdout, stderr = process.communicate("\n", timeout=15)
        require(process.returncode == 0 and "redis-svc DNS and TCP check passed" in stdout, stdout + stderr)
        checks.append({"case": "resolver-file-reload", "passed": True, "validated_search_snapshot_retained": True})
        return checks
    finally:
        if process and process.poll() is None:
            process.kill()
            process.communicate()
        for container in (snapshot, name):
            subprocess.run(["docker", "rm", "-f", container], capture_output=True, timeout=30, check=False)
        run("docker", "network", "rm", name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("image")
    parser.add_argument("--baseline-image", help="Optional pinned 2.9.0 image for package and ABI comparison")
    parser.add_argument("--tools-image", help="Local image ID already built from scripts/security/native/Dockerfile")
    parser.add_argument("--java-probe-timeout", type=int, default=30,
                        help="DOM/XLSX/font/charset Java probe deadline in seconds (default: 30)")
    args = parser.parse_args()
    if args.java_probe_timeout <= 0:
        parser.error("--java-probe-timeout must be positive")
    (ROOT / ".testhub").mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="native-security-", dir=ROOT / ".testhub") as temporary:
        work = Path(temporary)
        # The image user must be able to traverse the read-only /qa bind mount.
        work.chmod(0o755)
        config = json.loads(run("docker", "image", "inspect", args.image).stdout)[0]
        require(config["Config"]["User"] in ("nonroot", "nonroot:nonroot", "65532", "65532:65532"), "Nonroot image required")
        require(config["Config"]["Entrypoint"][0] == "/app/testhub-runtime-guard", "Image must start through the runtime guard")
        result = {"image": config["Id"], "architecture": config["Architecture"],
                  "inventory": inventory(args.image, work / "new", True, config["Architecture"])}
        tools_image = args.tools_image or TOOLS
        if args.tools_image:
            require(re.fullmatch(r"sha256:[0-9a-f]{64}", tools_image), "Use the local tools image ID, not a tag")
            tools_config = json.loads(run("docker", "image", "inspect", tools_image).stdout)[0]
            require(tools_config["Architecture"] == config["Architecture"], "Native tools architecture differs from the API image")
        else:
            run("docker", "build", "-f", str(NATIVE / "Dockerfile"), "-t", tools_image, str(ROOT), timeout=900)
        compiler = run("docker", "create", tools_image).stdout.strip()
        try:
            run("docker", "cp", compiler + ":/probes/.", str(work))
        finally:
            run("docker", "rm", compiler)
        if args.baseline_image:
            inventory(args.baseline_image, work / "old", False)
            for library in ("expat", "expatw"):
                old = f"/qa/old/lib{library}.so.1.13.0"
                new = f"/qa/new/lib{library}.so.1.13.1"
                symbols = []
                for path in (old, new):
                    output = restricted(tools_image, work, "nm", "-D", "--defined-only", path)
                    require(output.returncode == 0, output.stderr)
                    symbols.append({line.split()[-1] for line in output.stdout.splitlines()})
                    output = restricted(tools_image, work, "readelf", "-d", path)
                    require(output.returncode == 0 and f"Library soname: [lib{library}.so.1]" in output.stdout, "SONAME mismatch")
                require(symbols[0] <= symbols[1], "Exported ABI symbols removed")
            result["abi"] = "both SONAMEs unchanged; all old exported symbols retained"
        result["native_cases"] = []
        for library in ("expat", "expatw"):
            for case in ("basic", "reset", "subparser"):
                probe = restricted(args.image, work, f"/qa/{library}-regression", case)
                require(probe.returncode == 0, f"{library}/{case}: {probe.returncode}\n{probe.stdout}\n{probe.stderr}")
                require("version=expat_2.10.0" in probe.stdout, "Wrong library loaded")
                result["native_cases"].append({"version": "2.10.0", "library": library, "case": case,
                                               "exit": probe.returncode, "observed": probe.stdout.strip()})
        result["compatibility"] = []
        for executable in ("encoding-compatibility", "encodingw-compatibility"):
            probe = restricted(args.image, work, "/qa/" + executable)
            require(probe.returncode == 0, probe.stdout + probe.stderr)
            result["compatibility"].append(probe.stdout.strip())
        for library in ("libfreetype.so.6", "/" + result["inventory"]["jdk_freetype"]):
            probe = restricted(args.image, work, "/qa/freetype-compatibility", library)
            require(probe.returncode == 0 and "FreeType 2.14.3 via " in probe.stdout, probe.stdout + probe.stderr)
            result["compatibility"].append(probe.stdout.strip())
        result["runtime_checks"] = []
        runtime_probes = ["runtime-guard-tests", "cxx-compatibility", "cxx-compatibility-legacy"]
        if config["Architecture"] == "ppc64le":
            runtime_probes.append("cxx-compatibility-ibm-long-double")
        for executable in runtime_probes:
            trace_loader = executable.startswith("cxx-compatibility")
            probe = restricted(args.image, work, "/qa/" + executable, loader=trace_loader)
            require(probe.returncode == 0, probe.stdout + probe.stderr)
            if trace_loader:
                expected_abi = 0 if executable.endswith("-legacy") else 1
                require(probe.stdout.startswith(f"C++ string ABI {expected_abi}:"),
                        f"{executable} did not use the expected C++ string ABI")
                loaded = re.search(r"calling init: (\S+/libstdc\+\+\.so\.6)(?:\s|$)", probe.stderr)
                require(loaded is not None, "C++ regression did not load the image libstdc++")
                require(result.setdefault("cxx_loader_path", loaded.group(1)) == loaded.group(1),
                        "C++ ABI probes loaded different libstdc++ libraries")
            result["runtime_checks"].append(probe.stdout.strip())
        result["dns_library_cases"] = []
        for case in ("ordinary", "exact-first-boundary", "max-entries", "oversize-first", "truncated-tail"):
            probe = restricted(args.image, work, "/qa/dns-search-regression", case)
            require(probe.returncode == 0, probe.stdout + probe.stderr)
            result["dns_library_cases"].append({"case": case, "exit": probe.returncode,
                                                "observed": probe.stdout.strip()})
        search = "team.svc.cluster.local svc.cluster.local cluster.local"
        cases = [
            ("resolver-file", "search " + search, {}, search, 0),
            ("environment-override", "search file.test", {"LOCALDOMAIN": " custom.test\t local.test "}, "custom.test local.test", 0),
            ("empty-override", "search file.test", {"LOCALDOMAIN": ""}, ".", 0),
            ("last-directive", "search first.test\ndomain last.test ignored.test", {}, "last.test", 0),
            ("ignored-directives", "search team.test\n search indented.test\nsearch \t\ndomain \t", {}, "team.test", 0),
            ("oversize-environment-rejected", "search file.test", {"LOCALDOMAIN": "a" * 256}, ".", 78),
            ("oversize-file-rejected", "search " + "a" * 256, {}, ".", 78),
            ("multiline-environment-rejected", "search file.test", {"LOCALDOMAIN": "first.test\nsecond.test"}, ".", 78),
        ]
        result["dns_environment_cases"] = []
        for label, contents, environment, expected, code in cases:
            resolver = work / (label + "-resolv.conf")
            resolver.write_text("nameserver 127.0.0.11\n" + contents + "\n")
            environment.update(LANGUAGE="ja", FONTCONFIG_FILE="/tmp/qa-fonts.conf", HOME="/tmp/qa-home")
            probe = restricted(args.image, work, "/app/testhub-runtime-guard", "/qa/runtime-environment", expected,
                               environment=environment, resolver=resolver)
            require(probe.returncode == code, label + "\n" + probe.stdout + probe.stderr)
            if code:
                require("DNS configuration rejected" in probe.stderr, "Expected actionable DNS rejection")
            result["dns_environment_cases"].append({"case": label, "exit": probe.returncode, "passed": True})
        lib = work / "lib"
        lib.mkdir()
        with zipfile.ZipFile(work / "new/application.jar") as jar:
            for name in jar.namelist():
                if name.startswith("BOOT-INF/lib/") and name.endswith(".jar"):
                    (lib / Path(name).name).write_bytes(jar.read(name))
        javac = str(Path(os.environ["JAVA_HOME"]) / "bin/javac") if "JAVA_HOME" in os.environ else "javac"
        run(javac, "--release", "21", "-cp", str(lib / "*"), "-d", str(work), str(NATIVE / "NativeImageProbe.java"))
        run(javac, "--release", "21", "-d", str(work), str(NATIVE / "RuntimeNetworkProbe.java"))
        probe = restricted(args.image, work, "/app/testhub-runtime-guard", "java", "-Djava.awt.headless=true", "-cp", "/qa:/qa/lib/*", "NativeImageProbe", loader=True, timeout=args.java_probe_timeout)
        require(probe.returncode == 0, probe.stdout + "\n" + probe.stderr[-3000:])
        require(re.search(r"calling init: .*libexpat\.so\.1", probe.stderr) is not None, "Font discovery did not exercise Expat")
        require(re.search(r"calling init: .*libfontconfig\.so\.1", probe.stderr) is not None, "Fontconfig not loaded")
        require(re.search(r"calling init: .*libfreetype\.so(?:\.6)?", probe.stderr) is not None, "FreeType not loaded")
        result["java"] = probe.stdout.strip().splitlines()
        result["fontconfig_loader_trace"] = "Expat and fontconfig initialization observed"
        result["dns_network"] = verify_dns_network(args.image, work)
        print(json.dumps(result, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
