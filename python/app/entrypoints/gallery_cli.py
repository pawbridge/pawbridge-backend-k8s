"""Build/publish a complete local photo manifest; never fetch APMS/R2 credentials."""
import argparse
import json

from app.gallery.builder import read_manifest, build_gallery


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--photo-root", required=True)
    parser.add_argument("--state-dir", required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    records, digest = read_manifest(args.manifest)
    print(json.dumps({"records": len(records), "snapshot_sha256": digest, "apply": args.apply}), flush=True)
    if not args.apply:
        return
    from app.vision.dinov3 import get_encoder, gallery_index, visual_profile
    if visual_profile() != "sam3-animal-focus":
        raise RuntimeError("Gallery builds require the SAM 3 profile")
    from app.gallery.storage import storage_session
    from app.gallery.runtime import runtime_owner
    with storage_session() as store, runtime_owner(args.state_dir):
        result = build_gallery(store, get_encoder, args.manifest, args.photo_root,
                               gallery_index(), args.state_dir,
                               progress=lambda row: print(json.dumps(row), flush=True))
    print(json.dumps(result), flush=True)


if __name__ == "__main__":
    main()
