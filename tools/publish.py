#!/usr/bin/env python3
"""
신풍승무 통합시뮬레이터 배포 도구

하나로 된 시뮬레이터 HTML을
  1) 본문(index.html)과 이미지·영상 파일로 나누고
  2) 비밀번호로 암호화(AES-256-GCM, 키는 PBKDF2-SHA256)해서
  3) docs/data/ 아래에 manifest.json + f/<id>.bin 으로 저장합니다.

암호화는 내용이 같으면 결과도 같게(결정적) 만들기 때문에,
규정 변경으로 본문만 바뀌면 앱은 바뀐 파일만 다시 내려받습니다.

사용법:
  SIM_PASSWORD='비밀번호' python3 tools/publish.py 시뮬레이터.html --note "6단계 문구 수정"
  (비밀번호를 환경변수로 주지 않으면 실행 중에 물어봅니다)

평문(암호화 전) 파일은 저장소에 절대 올리지 않습니다. --plain-out 으로 지정한 폴더는 검증용입니다.
"""
import argparse, base64, datetime, getpass, hashlib, hmac, json, os, re, sys
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
from cryptography.hazmat.primitives import hashes

FORMAT = 1
KDF_ITER = 200_000
CHECK_TEXT = b"SINPUNG-SIM-OK"
DATA_URI = re.compile(r'data:([a-z]+/[a-z0-9.+-]+);base64,([A-Za-z0-9+/=]+)')
EXT = {"image/png": "png", "image/gif": "gif", "image/webp": "webp", "image/jpeg": "jpg",
       "image/svg+xml": "svg", "video/mp4": "mp4", "audio/mpeg": "mp3", "font/woff2": "woff2"}
MIN_EXTRACT = 1500  # 이보다 작은 내장 데이터는 본문에 그대로 둠


def split_html(html: str):
    """내장(base64) 이미지·영상을 a/<해시>.<확장자> 파일로 빼낸다."""
    files = {}

    def repl(m):
        mime, b64 = m.group(1), m.group(2)
        if len(b64) < MIN_EXTRACT:
            return m.group(0)
        raw = base64.b64decode(b64)
        name = "a/%s.%s" % (hashlib.sha256(raw).hexdigest()[:16], EXT.get(mime, "bin"))
        files[name] = raw
        return name

    body = DATA_URI.sub(repl, html)
    return body, files


def derive(password: str, salt: bytes):
    kdf = PBKDF2HMAC(algorithm=hashes.SHA256(), length=64, salt=salt, iterations=KDF_ITER)
    k = kdf.derive(password.encode("utf-8"))
    return k[:32], k[32:]  # 암호화 키, IV 생성용 키


def encrypt_det(enc_key, mac_key, plain: bytes) -> bytes:
    iv = hmac.new(mac_key, plain, hashlib.sha256).digest()[:12]
    return iv + AESGCM(enc_key).encrypt(iv, plain, None)


def decrypt(enc_key, blob: bytes) -> bytes:
    return AESGCM(enc_key).decrypt(blob[:12], blob[12:], None)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("source", help="시뮬레이터 HTML 파일")
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "..", "docs", "data"))
    ap.add_argument("--note", default="", help="이번 업데이트 내용 한 줄")
    ap.add_argument("--plain-out", default="", help="(검증용) 분리된 평문 파일을 저장할 폴더")
    ap.add_argument("--new-salt", action="store_true", help="비밀번호를 바꿀 때만 사용")
    a = ap.parse_args()

    password = os.environ.get("SIM_PASSWORD") or getpass.getpass("비밀번호: ")
    if len(password) < 4:
        sys.exit("비밀번호가 너무 짧습니다.")

    out = os.path.abspath(a.out)
    fdir = os.path.join(out, "f")
    os.makedirs(fdir, exist_ok=True)
    man_path = os.path.join(out, "manifest.json")
    old = json.load(open(man_path, encoding="utf-8")) if os.path.exists(man_path) else None

    # 같은 비밀번호면 salt를 유지해야 바뀌지 않은 파일의 암호문이 그대로 유지됨
    if old and not a.new_salt:
        salt = base64.b64decode(old["kdf"]["salt"])
    else:
        salt = os.urandom(16)
    enc_key, mac_key = derive(password, salt)

    if old and not a.new_salt:
        try:
            ok = decrypt(enc_key, base64.b64decode(old["check"])) == CHECK_TEXT
        except Exception:
            ok = False
        if not ok:
            sys.exit("기존 배포본과 비밀번호가 다릅니다. 비밀번호를 바꾸려면 --new-salt 를 붙이세요.")

    html = open(a.source, encoding="utf-8").read()
    body, assets = split_html(html)
    parts = {"index.html": body.encode("utf-8")}
    parts.update(assets)

    if a.plain_out:
        for p, data in parts.items():
            fp = os.path.join(a.plain_out, p)
            os.makedirs(os.path.dirname(fp), exist_ok=True)
            open(fp, "wb").write(data)

    entries, keep = [], set()
    for path, plain in parts.items():
        blob = encrypt_det(enc_key, mac_key, plain)
        sha = hashlib.sha256(blob).hexdigest()
        fid = sha[:20]
        fp = os.path.join(fdir, fid + ".bin")
        if not os.path.exists(fp):
            open(fp, "wb").write(blob)
        entries.append({"path": path, "id": fid, "sha256": sha, "size": len(blob)})
        keep.add(fid + ".bin")
    for f in os.listdir(fdir):  # 이번 버전에 쓰이지 않는 옛 파일 정리
        if f.endswith(".bin") and f not in keep:
            os.remove(os.path.join(fdir, f))

    now = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=9)))
    base_ver = now.strftime("%Y.%m.%d")
    n = 1
    if old and str(old.get("version", "")).startswith(base_ver + "-"):
        try:
            n = int(old["version"].split("-")[1]) + 1
        except Exception:
            n = 2
    changed = (not old) or {e["id"] for e in entries} != {e["id"] for e in old.get("files", [])}
    version = "%s-%d" % (base_ver, n) if changed else old["version"]

    check_iv = os.urandom(12)
    manifest = {
        "format": FORMAT,
        "version": version,
        "updated": now.strftime("%Y-%m-%d %H:%M") if changed else old.get("updated"),
        "note": a.note if changed else old.get("note", ""),
        "kdf": {"alg": "PBKDF2-SHA256", "iter": KDF_ITER, "salt": base64.b64encode(salt).decode()},
        "check": base64.b64encode(check_iv + AESGCM(enc_key).encrypt(check_iv, CHECK_TEXT, None)).decode()
        if (not old or a.new_salt) else old["check"],
        "entry": "index.html",
        "files": entries,
    }
    json.dump(manifest, open(man_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)

    total = sum(e["size"] for e in entries)
    if old and changed:
        oldids = {e["id"] for e in old.get("files", [])}
        dl = sum(e["size"] for e in entries if e["id"] not in oldids)
        print("변경된 파일 다운로드 크기: %.2f MB" % (dl / 1e6))
    print("버전 %s · 파일 %d개 · 전체 %.1f MB%s" % (version, len(entries), total / 1e6, "" if changed else " (변경 없음)"))


if __name__ == "__main__":
    main()
