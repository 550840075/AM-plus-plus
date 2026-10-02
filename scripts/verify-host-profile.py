"""Read-only verification of the exact AM++ host profiles against a user-supplied package.

Every check below is an evidence claim taken from the adaptation record: the DEX class name, the
complete method signature (owner, static/instance descriptor and return type) and, where the
adaptation pinned one, the field name. No APK code is executed and no file is written.

Usage:
    python scripts/verify-host-profile.py apple-music-6-5-3.xapk \
        --version-name 6.5.3 --version-code 1599 --glass
"""
import argparse
import io
import json
import struct
import sys
import zipfile
from pathlib import Path


def _uleb(data, offset):
    value = 0
    for shift in range(0, 35, 7):
        byte = data[offset]
        offset += 1
        value |= (byte & 127) << shift
        if not byte & 128:
            return value, offset
    raise ValueError("Invalid ULEB128")


def dex_classes(data):
    """Return {class descriptor: {"super": ..., "methods": set, "fields": dict}}."""
    if not data.startswith(b"dex\n"):
        raise ValueError("Not a DEX file")

    def u32(offset):
        return struct.unpack_from("<I", data, offset)[0]

    def u16(offset):
        return struct.unpack_from("<H", data, offset)[0]

    strings = []
    for i in range(u32(56)):
        _, offset = _uleb(data, u32(u32(60) + 4 * i))
        strings.append(data[offset:data.index(0, offset)].decode("utf8", errors="replace"))
    types = [strings[u32(u32(68) + 4 * i)] for i in range(u32(64))]
    protos = []
    for i in range(u32(72)):
        base = u32(76) + i * 12
        parameters = u32(base + 8)
        params = "" if not parameters else "".join(
            types[u16(parameters + 4 + j * 2)] for j in range(u32(parameters))
        )
        protos.append("(" + params + ")" + types[u32(base + 4)])
    field_defs = []
    for i in range(u32(80)):
        _, type_idx, name_idx = struct.unpack_from("<HHI", data, u32(84) + i * 8)
        field_defs.append((strings[name_idx], types[type_idx]))
    method_defs = []
    for i in range(u32(88)):
        _, proto_idx, name_idx = struct.unpack_from("<HHI", data, u32(92) + i * 8)
        method_defs.append(strings[name_idx] + protos[proto_idx])
    classes = {}
    for i in range(u32(96)):
        base = u32(100) + i * 32
        class_name = types[u32(base)]
        superclass = u32(base + 8)
        offset = u32(base + 24)
        methods = set()
        fields = {}
        if offset:
            counts = []
            for _ in range(4):
                count, offset = _uleb(data, offset)
                counts.append(count)
            for section in counts[:2]:
                index = 0
                for _ in range(section):
                    delta, offset = _uleb(data, offset)
                    index += delta
                    _, offset = _uleb(data, offset)
                    fields[field_defs[index][0]] = field_defs[index][1]
            for section in counts[2:]:
                index = 0
                for _ in range(section):
                    delta, offset = _uleb(data, offset)
                    index += delta
                    _, offset = _uleb(data, offset)
                    _, offset = _uleb(data, offset)
                    methods.add(method_defs[index])
        classes[class_name] = {
            "super": types[superclass] if superclass != 0xFFFFFFFF else None,
            "methods": methods,
            "fields": fields,
        }
    return classes


PROFILE_DIRECTORY = Path(__file__).resolve().parents[1] / "host-applemusic/src/main/resources/host-profiles"


def load_profiles():
    index = json.loads((PROFILE_DIRECTORY / "index.json").read_text(encoding="utf-8"))
    profiles = {}
    for filename in index["profiles"]:
        data = json.loads((PROFILE_DIRECTORY / filename).read_text(encoding="utf-8"))
        if data["productionEnabled"]:
            profiles[(data["versionName"], str(data["versionCode"]))] = data["verification"]
    return profiles


PROFILES = load_profiles()
CATALOG_QUERY_SHAPE = "(Ljava/lang/String;Ljava/util/Map;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"


def find_method(classes, owner, signature):
    current = owner
    while current in classes:
        if signature in classes[current]["methods"]:
            return current
        current = classes[current]["super"]
    return None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("package", type=Path, help="APK, XAPK or APKS (all DEX splits are checked)")
    parser.add_argument("--version-name", default=None)
    parser.add_argument("--version-code", default=None)
    parser.add_argument("--glass", action="store_true", help="also verify the phone glass seams")
    args = parser.parse_args()

    with zipfile.ZipFile(args.package) as package:
        names = package.namelist()
        manifest = None
        if "manifest.json" in names:
            manifest = json.loads(package.read("manifest.json"))
        base_name = next(
            (name for name in names if name.endswith(".apk") and "config" not in name),
            None,
        )
        if base_name is None:
            base_name = args.package.name
            apk = zipfile.ZipFile(args.package)
        else:
            apk = zipfile.ZipFile(io.BytesIO(package.read(base_name)))
        version_name = args.version_name or (manifest or {}).get("version_name")
        version_code = args.version_code or str((manifest or {}).get("version_code", ""))
        profile = PROFILES.get((version_name or "", str(version_code)))
        if profile is None:
            raise SystemExit(
                "Unsupported or undocumented version tuple: %s (%s). Known profiles: %s"
                % (version_name, version_code, ", ".join("%s (%s)" % key for key in sorted(PROFILES))),
            )
        if version_code != profile["version_code"]:
            raise SystemExit(
                "Version tuple mismatch: %s (%s) does not match the recorded %s profile"
                % (version_name, version_code, profile["version_code"]),
            )

        ALIASED_TYPES = profile.get("aliases", {})
        GLASS_METHODS = profile.get("glass_methods", {})
        LAYOUTS = profile.get("layouts", [])
        classes = {}
        for name in apk.namelist():
            if name.endswith(".dex"):
                classes.update(dex_classes(apk.read(name)))
        available_layouts = set(apk.namelist())
        for split_name in names:
            if not split_name.endswith(".apk") or split_name == base_name:
                continue
            with zipfile.ZipFile(io.BytesIO(package.read(split_name))) as split:
                available_layouts.update(split.namelist())
                for name in split.namelist():
                    if name.endswith(".dex"):
                        classes.update(dex_classes(split.read(name)))

        failures = []
        checks = 0

        for owner, signatures in profile["methods"].items():
            if owner not in classes:
                failures.append("missing class %s" % owner)
                continue
            for signature in signatures:
                checks += 1
                where = find_method(classes, owner, signature)
                if where is None:
                    failures.append("missing method %s %s" % (owner, signature))
        for owner, field_names in profile["fields"].items():
            for field_name in field_names:
                checks += 1
                if owner not in classes:
                    failures.append("missing class %s" % owner)
                elif field_name not in classes[owner]["fields"]:
                    failures.append("missing field %s %s" % (owner, field_name))

        for owner, signatures in ALIASED_TYPES.items():
            for signature in signatures:
                checks += 1
                if owner not in classes:
                    failures.append("missing obfuscated type %s" % owner)
                elif signature not in classes[owner]["methods"]:
                    failures.append("missing alias member %s %s" % (owner, signature))

        query = profile.get("catalog_query")
        if query:
            owner = query["owner"]
            checks += 1
            if owner not in classes:
                failures.append("missing catalog query class %s" % owner)
            elif query["verified"] + CATALOG_QUERY_SHAPE not in classes[owner]["methods"]:
                failures.append(
                    "missing catalog query %s %s"
                    % (owner, query["verified"] + CATALOG_QUERY_SHAPE)
                )
            if query["preferred"] != query["verified"]:
                checks += 1
                if owner in classes and (
                    query["preferred"] + CATALOG_QUERY_SHAPE in classes[owner]["methods"]
                ):
                    failures.append(
                        "%s %s satisfies the catalog query shape on %s, so the verified rename %s "
                        "is unreachable" % (
                            owner,
                            query["preferred"],
                            version_name,
                            query["verified"],
                        )
                    )

        interceptor = profile.get("content_http_interceptor")
        if interceptor:
            owner = interceptor["owner"]
            checks += 1
            if owner not in classes:
                failures.append("missing content HTTP interceptor %s" % owner)
            elif interceptor["signature"] not in classes[owner]["methods"]:
                failures.append(
                    "missing content HTTP interceptor method %s %s"
                    % (owner, interceptor["signature"])
                )
            vacated = interceptor.get("vacated")
            if vacated:
                checks += 1
                vacated_owner, vacated_signature = vacated
                if vacated_owner in classes and (
                    vacated_signature in classes[vacated_owner]["methods"]
                ):
                    failures.append(
                        "%s still declares %s on %s, so the pinned interceptor %s is shadowed"
                        % (vacated_owner, vacated_signature, version_name, owner)
                    )

        if args.glass:
            for owner, signatures in GLASS_METHODS.items():
                for signature in signatures:
                    checks += 1
                    if find_method(classes, owner, signature) is None:
                        failures.append("missing glass hook %s %s" % (owner, signature))
            for layout in LAYOUTS:
                checks += 1
                if layout not in available_layouts:
                    failures.append("missing layout %s" % layout)

        print("package: %s" % args.package)
        print("base apk: %s" % base_name)
        print("version tuple: %s (%s)" % (version_name, version_code))
        print("checks: %d, failures: %d" % (checks, len(failures)))
        if failures:
            for failure in failures:
                print("FAIL %s" % failure)
            sys.exit(1)
        print("PASS: Apple Music %s (%s) profile symbols verified" % (version_name, version_code))


if __name__ == "__main__":
    main()
