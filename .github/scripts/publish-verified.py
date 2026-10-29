#!/usr/bin/env python3
"""Publish prebuilt artifacts only after checking the EmaTech release receipt."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import urllib.error
import urllib.request
import base64
import zipfile


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def checked(root, name):
    path = (root / name).resolve()
    if not path.is_relative_to(root.resolve()) or not path.is_file():
        raise ValueError(f"Invalid release path: {name}")
    return path


def validate(root, checkout, repository, commit, version):
    manifest = json.loads((root / "manifest.json").read_text())
    if (manifest["repository"], manifest["commit"], manifest["version"]) != (repository, commit, version):
        raise ValueError("Release identity does not match the checked-out commit")
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        raise ValueError("A stable semantic version is required")
    for name, expected in manifest["files"].items():
        if digest(checked(root, name)) != expected:
            raise ValueError(f"Bundle checksum mismatch: {name}")
    for name, expected in manifest["source_files"].items():
        if digest(checked(checkout, name)) != expected:
            raise ValueError(f"Source checksum mismatch: {name}")
    receipt = json.loads(checked(root, "release-receipt.json").read_text())
    if receipt["execution_environment"] != "EmaTech" or receipt["test_failures"] != 0:
        raise ValueError("Successful EmaTech correctness evidence is required")
    if receipt["abis"] != {"rest": 29, "dubbo": 7, "spring_dubbo": 3, "redis": 6, "glowroot": 6}:
        raise ValueError("Native ABI contract mismatch")
    for artifact in manifest["artifacts"]:
        if artifact["version"] != version or artifact["pom"] not in manifest["files"]:
            raise ValueError("Artifact/version mismatch")
        for item in artifact["downloads"]:
            if item["file"] not in manifest["files"]:
                raise ValueError("Artifact without a recorded checksum")
    return manifest


def download(url, actor, token):
    auth = base64.b64encode(f"{actor}:{token}".encode()).decode()
    request = urllib.request.Request(url, headers={"Authorization": f"Basic {auth}"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.read()
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise RuntimeError(f"Authenticated package download failed: HTTP {error.code}") from None


def deploy_args(root, artifact, base, version):
    args = ["mvn", "-B", "-ntp", "org.apache.maven.plugins:maven-deploy-plugin:3.1.3:deploy-file",
            f"-Durl={base}", "-DrepositoryId=github", f"-DpomFile={checked(root, artifact['pom'])}",
            f"-DgroupId={artifact['group']}", f"-DartifactId={artifact['id']}", f"-Dversion={version}",
            f"-Dfile={checked(root, artifact['primary'])}", "-DgeneratePom=false", "-DretryFailedDeploymentCount=2"]
    if artifact.get("sources"):
        args.append(f"-Dsources={checked(root, artifact['sources'])}")
    attached = artifact.get("attached", [])
    if attached:
        args.extend(["-Dfiles=" + ",".join(str(checked(root, item["file"])) for item in attached),
                     "-Dclassifiers=" + ",".join(item["classifier"] for item in attached),
                     "-Dtypes=" + ",".join("jar" for item in attached)])
    return args


def main():
    checkout = Path.cwd()
    repository, commit, version = (os.environ[k] for k in ("GITHUB_REPOSITORY", "GITHUB_SHA", "RELEASE_VERSION"))
    bundle = Path(os.environ["RELEASE_BUNDLE"])
    if digest(bundle) != os.environ["RELEASE_BUNDLE_SHA256"]:
        raise ValueError("Release bundle SHA-256 mismatch")
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        with zipfile.ZipFile(bundle) as archive:
            for info in archive.infolist():
                if not (root / info.filename).resolve().is_relative_to(root):
                    raise ValueError("Unsafe archive path")
            archive.extractall(root)
        manifest = validate(root, checkout, repository, commit, version)
        actor, token = os.environ["GITHUB_ACTOR"], os.environ["GITHUB_TOKEN"]
        base = f"https://maven.pkg.github.com/{repository}"
        proof = []
        for artifact in manifest["artifacts"]:
            prefix = f"{artifact['group'].replace('.', '/')}/{artifact['id']}/{version}"
            existing = []
            for item in artifact["downloads"]:
                data = download(f"{base}/{prefix}/{item['name']}", actor, token)
                if data is not None and hashlib.sha256(data).hexdigest() != manifest["files"][item["file"]]:
                    raise ValueError(f"Immutable package conflict: {artifact['id']} {item['name']}")
                existing.append(data is not None)
            if not all(existing):
                if any(existing):
                    raise ValueError(f"Partial package exists: {artifact['id']}; repair explicitly before retrying")
                subprocess.run(deploy_args(root, artifact, base, version), cwd=root, check=True)
            for item in artifact["downloads"]:
                data = download(f"{base}/{prefix}/{item['name']}", actor, token)
                if data is None or hashlib.sha256(data).hexdigest() != manifest["files"][item["file"]]:
                    raise ValueError(f"Published package verification failed: {item['name']}")
                proof.append({"artifact": item["name"], "sha256": hashlib.sha256(data).hexdigest(), "fresh_authenticated_download": True})
        result = root / "publication-verification.json"
        result.write_text(json.dumps({"repository": repository, "commit": commit, "version": version, "packages": proof}, indent=2) + "\n")
        sums = root / "SHA256SUMS"
        sums.write_text("".join(f"{manifest['files'][name]}  {Path(name).name}\n" for name in manifest['release_assets']))
        assets = [str(checked(root, name)) for name in manifest["release_assets"]] + [str(result), str(sums), str(root / 'manifest.json')]
        subprocess.run(["gh", "release", "create", f"v{version}", "--repo", repository, "--target", commit,
                        "--title", f"{repository.split('/')[-1]} {version}", "--notes-file", str(checked(root, manifest["notes"])),
                        "--latest", *assets], check=True)
        print(f"STABLE_PUBLISHED {repository} {version} verified_downloads={len(proof)}")


if __name__ == "__main__":
    main()
