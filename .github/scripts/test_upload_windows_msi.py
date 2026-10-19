import base64
from email.message import Message
import io
import json
from pathlib import Path
import tempfile
import sys
import unittest
from unittest.mock import patch
import urllib.error

sys.dont_write_bytecode = True
import upload_windows_msi as uploader


class Response(io.BytesIO):
    def __init__(self, data, headers=None):
        super().__init__(json.dumps(data).encode())
        self.headers = headers or {}


class UploadWindowsMsiTest(unittest.TestCase):
    def test_existing_branch_ci_secret_format(self):
        encoded = base64.b64encode(b"client-id|refresh-token").decode()
        form = uploader.credentials(encoded)
        self.assertEqual(form["client_id"], "client-id")
        self.assertEqual(form["refresh_token"], "refresh-token")
        self.assertNotIn("client_secret", form)
        encoded = base64.b64encode(b"client-id|refresh-token|client-secret").decode()
        self.assertEqual(uploader.credentials(encoded)["client_secret"], "client-secret")

    def test_bad_credentials_fail_without_echoing_the_secret(self):
        for value in ("not base64!", base64.b64encode(b"secret-value-without-separator").decode()):
            with self.assertRaises(ValueError) as error:
                uploader.credentials(value)
            self.assertNotIn("secret-value", str(error.exception))

    def test_existing_folder_is_used(self):
        with patch.object(uploader, "find_file", return_value="oldest") as find, patch.object(
            uploader, "json_request"
        ) as request:
            self.assertEqual(uploader.get_folder("token", "build", "root", wait_seconds=0), "oldest")
            request.assert_not_called()
            self.assertTrue(find.call_args.kwargs["folder"])

    def test_folder_created_at_the_same_time_by_another_job_wins(self):
        # Two jobs created a folder each: the oldest one is used, so the files are not split between them
        with patch.object(uploader, "find_file", side_effect=[None, "other-job-folder"]), patch.object(
            uploader, "json_request", return_value={"id": "my-folder"}
        ):
            self.assertEqual(uploader.get_folder("token", "build", "root", wait_seconds=0), "other-job-folder")

    def test_new_folder_is_used_when_no_other_appears(self):
        with patch.object(uploader, "find_file", side_effect=[None, None]), patch.object(
            uploader, "json_request", return_value={"id": "my-folder"}
        ):
            self.assertEqual(uploader.get_folder("token", "build", "root", wait_seconds=0), "my-folder")

    def test_drive_query_escapes_names(self):
        self.assertEqual(uploader.quote_query("a'b\\c"), "a\\'b\\\\c")

    def test_rerun_updates_existing_file_without_deleting_it(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / "client.msi"
            file.write_bytes(b"installer")
            with patch.object(uploader, "find_file", return_value="existing-file"), patch.object(
                uploader.urllib.request, "urlopen", side_effect=[
                    Response({}, {"Location": "https://www.googleapis.com/upload/session"}),
                    Response({"id": "existing-file"}),
                ]
            ) as send:
                self.assertEqual(uploader.upload("token", file, "folder"), "existing-file")
                begin = send.call_args_list[0].args[0]
                self.assertEqual(begin.method, "PATCH")
                self.assertIn("/existing-file?", begin.full_url)
                self.assertNotIn("parents", json.loads(begin.data))
                self.assertEqual(send.call_args_list[1].args[0].data, b"installer")

    def test_multiple_chunks_use_the_acknowledged_range(self):
        headers = Message()
        headers["Range"] = "bytes=0-3"
        continued = urllib.error.HTTPError("https://www.googleapis.com/upload/session", 308, "Continue", headers, None)
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / "client.msi"
            file.write_bytes(b"abcdefgh")
            with patch.object(uploader, "CHUNK_SIZE", 4), patch.object(uploader, "find_file", return_value=None), patch.object(
                uploader.urllib.request, "urlopen", side_effect=[
                    Response({}, {"Location": "https://www.googleapis.com/upload/session"}),
                    continued,
                    Response({"id": "new-file"}),
                ]
            ) as send:
                self.assertEqual(uploader.upload("token", file, "folder"), "new-file")
                first, last = [call.args[0] for call in send.call_args_list[1:]]
                self.assertEqual(first.get_header("Content-range"), "bytes 0-3/8")
                self.assertEqual(last.get_header("Content-range"), "bytes 4-7/8")
                self.assertEqual(last.data, b"efgh")
                self.assertEqual(json.loads(send.call_args_list[0].args[0].data)["parents"], ["folder"])

    def test_unexpected_upload_host_never_receives_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / "client.msi"
            file.write_bytes(b"installer")
            with patch.object(uploader, "find_file", return_value=None), patch.object(
                uploader.urllib.request, "urlopen", return_value=Response({}, {"Location": "https://example.com/upload"})
            ) as send:
                with self.assertRaises(ValueError):
                    uploader.upload("token", file, "folder")
                self.assertEqual(send.call_count, 1)


    def test_finds_the_one_installer_of_each_type(self):
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / "AAPSClient2-4.0.0-arm64.dmg").write_bytes(b"mac")
            (Path(directory) / "AAPSClient2-4.0.0.msi").write_bytes(b"win")
            self.assertEqual(uploader.find_installer(directory, ".dmg").name, "AAPSClient2-4.0.0-arm64.dmg")
            self.assertEqual(uploader.find_installer(directory, ".msi").name, "AAPSClient2-4.0.0.msi")

    def test_missing_empty_or_unknown_installer_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                uploader.find_installer(directory, ".dmg")
            (Path(directory) / "empty.dmg").write_bytes(b"")
            with self.assertRaises(ValueError):
                uploader.find_installer(directory, ".dmg")
            with self.assertRaises(ValueError):
                uploader.find_installer(directory, ".exe")


if __name__ == "__main__":
    unittest.main()
