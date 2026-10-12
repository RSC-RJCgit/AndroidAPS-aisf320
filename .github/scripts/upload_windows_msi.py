"""Upload the Windows installer using the same Drive secret and folder as Branch CI."""

import base64
import json
import os
from pathlib import Path
import sys
import urllib.error
import urllib.parse
import urllib.request


DRIVE = "https://www.googleapis.com/drive/v3/files"
CHUNK_SIZE = 8 * 1024 * 1024


def credentials(encoded):
    parts = base64.b64decode("".join(encoded.split()), validate=True).decode().split("|")
    if len(parts) not in (2, 3) or not parts[0] or not parts[1]:
        raise ValueError("GDRIVE_OAUTH2 must encode client_id|refresh_token, with an optional client_secret")
    form = {"client_id": parts[0], "refresh_token": parts[1], "grant_type": "refresh_token"}
    if len(parts) == 3 and parts[2]:
        form["client_secret"] = parts[2]
    return form


def json_request(url, token=None, metadata=None, method=None):
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    body = None
    if metadata is not None:
        body = json.dumps(metadata).encode()
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, body, headers, method=method)
    with urllib.request.urlopen(request, timeout=120) as response:
        return json.load(response)


def quote_query(value):
    return value.replace("\\", "\\\\").replace("'", "\\'")


def find_file(token, name, parent=None, folder=False):
    query = f"name='{quote_query(name)}' and trashed=false"
    if parent:
        query += f" and '{quote_query(parent)}' in parents"
    if folder:
        query += " and mimeType='application/vnd.google-apps.folder'"
    else:
        query += " and mimeType!='application/vnd.google-apps.folder'"
    params = urllib.parse.urlencode({"q": query, "fields": "files(id)", "orderBy": "createdTime", "pageSize": 1})
    files = json_request(f"{DRIVE}?{params}", token).get("files", [])
    return files[0]["id"] if files else None


def get_folder(token, name, parent=None):
    existing = find_file(token, name, parent, folder=True)
    if existing:
        return existing
    metadata = {"name": name, "mimeType": "application/vnd.google-apps.folder"}
    if parent:
        metadata["parents"] = [parent]
    return json_request(DRIVE, token, metadata, "POST")["id"]


def upload(token, file, parent):
    existing = find_file(token, file.name, parent)
    suffix = f"/{urllib.parse.quote(existing, safe='')}" if existing else ""
    url = f"https://www.googleapis.com/upload/drive/v3/files{suffix}?uploadType=resumable&fields=id"
    metadata = {"name": file.name}
    if not existing:
        metadata["parents"] = [parent]
    size = file.stat().st_size
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
        "X-Upload-Content-Type": "application/octet-stream",
        "X-Upload-Content-Length": str(size),
    }
    request = urllib.request.Request(url, json.dumps(metadata).encode(), headers, method="PATCH" if existing else "POST")
    with urllib.request.urlopen(request, timeout=120) as response:
        session = response.headers["Location"]
    session_url = urllib.parse.urlparse(session)
    if session_url.scheme != "https" or session_url.hostname != "www.googleapis.com":
        raise ValueError("Drive returned an unexpected upload address")
    with file.open("rb") as stream:
        offset = 0
        while chunk := stream.read(CHUNK_SIZE):
            end = offset + len(chunk) - 1
            request = urllib.request.Request(session, chunk, {
                "Authorization": f"Bearer {token}",
                "Content-Type": "application/octet-stream",
                "Content-Range": f"bytes {offset}-{end}/{size}",
            }, method="PUT")
            try:
                with urllib.request.urlopen(request, timeout=120) as response:
                    result = json.load(response)
                if end + 1 != size:
                    raise ValueError("Drive finished the upload before the whole file was sent")
                return result["id"]
            except urllib.error.HTTPError as error:
                if error.code != 308 or error.headers.get("Range") != f"bytes=0-{end}" or end + 1 == size:
                    raise
            offset = end + 1
    raise ValueError("Installer is empty or its upload did not finish")


def main():
    files = list(Path("msi-artifacts").glob("*.msi"))
    if len(files) != 1 or files[0].stat().st_size == 0:
        raise ValueError("Expected one nonempty MSI in msi-artifacts")
    folder_name = os.environ["MSI_DRIVE_FOLDER"]
    form = credentials(os.environ["GDRIVE_OAUTH2"])
    for value in form.values():
        masked = value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print(f"::add-mask::{masked}")
    request = urllib.request.Request("https://oauth2.googleapis.com/token", urllib.parse.urlencode(form).encode(),
                                     {"Content-Type": "application/x-www-form-urlencoded"}, method="POST")
    with urllib.request.urlopen(request, timeout=120) as response:
        token = json.load(response)["access_token"]
    print(f"::add-mask::{token}")
    root = os.environ.get("GDRIVE_AAPS_FOLDER_ID") or get_folder(token, "AAPS")
    parent = get_folder(token, folder_name, root)
    print(f"Drive AAPS folder: https://drive.google.com/drive/folders/{root}")
    print(f"Drive build folder: https://drive.google.com/drive/folders/{parent}")
    file_id = upload(token, files[0], parent)
    link = f"https://drive.google.com/file/d/{file_id}/view"
    print(f"Uploaded {files[0].name} to AAPS/{folder_name}")
    print(f"Download Windows installer: {link}")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as stream:
            stream.write(f"\n[Download Windows installer from Google Drive]({link})\n")


if __name__ == "__main__":
    try:
        main()
    except urllib.error.HTTPError as error:
        print(f"Google Drive request failed: HTTP {error.code}", file=sys.stderr)
        sys.exit(1)
    except (ValueError, KeyError, OSError) as error:
        # Do not print decoded secrets or OAuth response bodies.
        print(f"Windows MSI upload failed ({type(error).__name__}); check the MSI and Drive configuration", file=sys.stderr)
        sys.exit(1)
