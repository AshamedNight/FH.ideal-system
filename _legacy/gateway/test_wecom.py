#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
test_wecom.py — 企业微信渠道的确定性测试

跑法：python -m unittest test_wecom -v

三件事要锁住：
  · 加解密对不对（用 FIPS-197 和企业微信官方两组向量）
  · 回调能不能正确识别消息（非文本要忽略，重复推送要去重）
  · 5 秒窗口的处理方式对不对（回调必须立刻返回，不能在里面跑判定）

最后一组用真实 HTTP 跑一遍：起服务、发 GET 验证、发 POST 消息。
不是模拟调用，是真的走了一遍网络。
"""

import json
import tempfile
import threading
import time
import unittest
import urllib.parse
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

from gateway import Hub, MockEngine, Persona, Surface
from store import Store
from wecom import WeComAdapter, WeComClient, WeComError
from wecom_crypto import (WeComCrypto, _decrypt_block, _encrypt_block,
                          _expand_key)
from wecom_server import Dispatcher, make_handler, parse_xml, to_inbound

HERE = Path(__file__).parent

# 企业微信官方文档「加解密方案说明」里的样例参数
DOC_TOKEN = "QDG6eK"
DOC_AESKEY = "jWmYm7qr5nMoAUwZRjGtBxmz3KA1tkAj3ykkR6q2B2C"
DOC_CORP = "wx5823bf96d3bd56c7"
DOC_TS, DOC_NONCE = "1409659813", "1372623149"
DOC_SIG = "477715d11cdb4164915debcba66cb864d751f3e6"
DOC_ENC = (
    "RypEvHKD8QQKFhvQ6QleEB4J58tiPdvo+rtK1I9qca6aM/wvqnLSV5zEPeusUiX5L5X/0lWfrf0QADHHhGd3QczcdCUpj911L3vg3W/sYYvuJTs3TUUkSUXxaccAS0qhxchrRYt66wiSpGLYL42aM6A8dTT"
    "+6k4aSknmPj48kzJs8qLjvd4Xgpue06DOdnLxAUHzM6+kDZ+HMZfJYuR+LtwGc2hgf5gsijff0ekUNXZiqATP7PF5mZxZ3Izoun1s4zG4LUMnvw2r+KqCKIw+3IQH03v+BCA9nMELNqbSf6tiWSrXJB3LAVGUcallcrw8V2t9EL4EhzJWrQUax5wLVMNS0+rUPA3k22Ncx4XXZS9o0MBH27Bo6BpNelZpS+/uh9KsNlY6bHCmJU9p8g7m3fVKn28H3KDYA5Pl/T8Z1ptDAVe0lXdQ2YoyyH2uyPIGHBZZIs2pDBS8R07+qN+E7Q=="
)


class TestAES(unittest.TestCase):
    """FIPS-197 附录 C.3 的 AES-256 向量。"""

    KEY = bytes.fromhex("000102030405060708090a0b0c0d0e0f"
                        "101112131415161718191a1b1c1d1e1f")
    PT = bytes.fromhex("00112233445566778899aabbccddeeff")
    CT = "8ea2b7ca516745bfeafc49904b496089"

    def test_encrypt(self):
        rk = _expand_key(self.KEY)
        self.assertEqual(_encrypt_block(self.PT, rk).hex(), self.CT)

    def test_decrypt(self):
        rk = _expand_key(self.KEY)
        self.assertEqual(_decrypt_block(bytes.fromhex(self.CT), rk), self.PT)

    def test_bad_key_length(self):
        with self.assertRaises(ValueError):
            _expand_key(b"\x00" * 16)


class TestCryptoAgainstOfficialSample(unittest.TestCase):
    """用企业微信文档里的真实样例验证整条链路。"""

    def setUp(self):
        self.c = WeComCrypto(DOC_TOKEN, DOC_AESKEY, DOC_CORP)

    def test_signature_matches_doc(self):
        self.assertEqual(self.c.signature(DOC_TS, DOC_NONCE, DOC_ENC), DOC_SIG)

    def test_verify(self):
        self.assertTrue(self.c.verify(DOC_SIG, DOC_TS, DOC_NONCE, DOC_ENC))

    def test_verify_rejects_tampered(self):
        self.assertFalse(self.c.verify("deadbeef", DOC_TS, DOC_NONCE, DOC_ENC))

    def test_decrypt_matches_doc_plaintext(self):
        plain = self.c.decrypt(DOC_ENC)
        self.assertIn("<Content><![CDATA[hello]]></Content>", plain)
        self.assertIn(DOC_CORP, plain)

    def test_roundtrip(self):
        xml = "<xml><Content><![CDATA[你好，多少钱]]></Content></xml>"
        enc = self.c.encrypt(xml)
        self.assertEqual(self.c.decrypt(enc), xml)

    def test_wrong_receive_id_rejected(self):
        c2 = WeComCrypto(DOC_TOKEN, DOC_AESKEY, "wx_other")
        with self.assertRaises(ValueError):
            c2.decrypt(DOC_ENC)

    def test_bad_aes_key(self):
        with self.assertRaises(ValueError):
            WeComCrypto("t", "短了", "c")


class TestParse(unittest.TestCase):

    def test_text_message(self):
        xml = ("<xml><ToUserName>corp</ToUserName>"
               "<FromUserName>zhangsan</FromUserName>"
               "<CreateTime>1700000000</CreateTime>"
               "<MsgType>text</MsgType><Content>这个多少钱</Content>"
               "<MsgId>123</MsgId><AgentID>1000002</AgentID></xml>")
        fields = parse_xml(xml)
        msg = to_inbound(fields)
        self.assertIsNotNone(msg)
        self.assertEqual(msg.text, "这个多少钱")
        self.assertEqual(msg.conv_id, "zhangsan")
        self.assertEqual(msg.surface, Surface.PRIVATE)
        self.assertEqual(msg.channel, "wecom")

    def test_image_ignored(self):
        # 图片消息生成不出有意义的回复，接了只会淹没真实消息
        fields = {"MsgType": "image", "FromUserName": "u1",
                  "Content": "", "CreateTime": "1"}
        self.assertIsNone(to_inbound(fields))

    def test_event_ignored(self):
        self.assertIsNone(to_inbound({"MsgType": "event",
                                      "FromUserName": "u1",
                                      "Content": ""}))

    def test_empty_content_ignored(self):
        self.assertIsNone(to_inbound({"MsgType": "text",
                                      "FromUserName": "u1",
                                      "Content": "   "}))


class FakeTransport:
    """假的 HTTP 层，用于测客户端逻辑。"""

    def __init__(self, token_resp=None, send_resp=None):
        self.token_resp = token_resp or {"errcode": 0,
                                         "access_token": "TOKEN",
                                         "expires_in": 7200}
        self.send_resp = send_resp or {"errcode": 0}
        self.calls = []

    def __call__(self, method, url, params):
        self.calls.append((method, url, params))
        if url.endswith("/cgi-bin/gettoken"):
            return self.token_resp
        return self.send_resp


class TestClient(unittest.TestCase):

    def test_token_cached(self):
        t = FakeTransport()
        c = WeComClient("corp", "secret", "1000002", transport=t)
        c.ensure_token()
        c.ensure_token()
        token_calls = [x for x in t.calls if x[1].endswith("/gettoken")]
        self.assertEqual(len(token_calls), 1, "令牌应缓存，不能每次都取")

    def test_token_error_raises(self):
        t = FakeTransport(token_resp={"errcode": 40001, "errmsg": "invalid secret"})
        c = WeComClient("corp", "bad", "1", transport=t)
        with self.assertRaises(WeComError) as ctx:
            c.ensure_token()
        self.assertFalse(ctx.exception.recoverable, "secret 错误重试无用")

    def test_send_payload_shape(self):
        t = FakeTransport()
        c = WeComClient("corp", "s", "1000002", transport=t)
        self.assertTrue(c.send_text("zhangsan", "你好"))
        method, url, payload = t.calls[-1]
        self.assertEqual(method, "POST")
        self.assertIn("/message/send", url)
        self.assertEqual(payload["touser"], "zhangsan")
        self.assertEqual(payload["msgtype"], "text")
        self.assertEqual(payload["text"]["content"], "你好")
        self.assertEqual(payload["agentid"], 1000002)

    def test_send_empty_rejected(self):
        t = FakeTransport()
        c = WeComClient("corp", "s", "1", transport=t)
        self.assertFalse(c.send_text("u", "   "))
        self.assertEqual(t.calls, [], "空消息不该发请求")

    def test_send_truncates(self):
        t = FakeTransport()
        c = WeComClient("corp", "s", "1", transport=t)
        c.send_text("u", "啊" * 3000)
        self.assertLessEqual(len(t.calls[-1][2]["text"]["content"]), 2048)


class TestAdapter(unittest.TestCase):

    def test_poll_drains_queue(self):
        a = WeComAdapter(WeComClient("c", "s", "1", transport=FakeTransport()))
        from gateway import Inbound
        a.push(Inbound("wecom", "u1", "u1", "hi"))
        self.assertEqual(len(a.poll()), 1)
        self.assertEqual(a.poll(), [], "取过之后应为空")

    def test_queue_bound(self):
        a = WeComAdapter(WeComClient("c", "s", "1", transport=FakeTransport()),
                         max_queue=2)
        from gateway import Inbound
        for i in range(5):
            a.push(Inbound("wecom", f"u{i}", f"u{i}", f"m{i}"))
        self.assertEqual(len(a.poll()), 2)
        self.assertEqual(a.dropped, 3)


class TestDispatcherAndHttp(unittest.TestCase):
    """
    真起一个 HTTP 服务，走一遍 URL 验证和消息推送。
    这组用例覆盖的是「协议对不对」，不是「内部函数对不对」。
    """

    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp())
        cls.cfg = {
            "corp_id": DOC_CORP, "corp_secret": "s", "agent_id": "1000002",
            "token": DOC_TOKEN, "encoding_aes_key": DOC_AESKEY,
            "db": str(cls.tmp / "state.db"),
            "persona": str(HERE / "persona.json"),
        }
        cls.d = Dispatcher(cls.cfg, dry_run=True)
        cls.srv = ThreadingHTTPServer(("127.0.0.1", 0),
                                      make_handler(cls.d))
        cls.port = cls.srv.server_address[1]
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()
        time.sleep(0.3)

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()
        cls.srv.server_close()

    def _url(self, path, **q):
        qs = urllib.parse.urlencode(q)
        return f"http://127.0.0.1:{self.port}{path}?{qs}"

    def test_url_verification_echoes_plaintext(self):
        echo = "echo_test_12345"
        enc = self.d.crypto.encrypt(echo)
        ts, nonce = "1700000001", "n1"
        sig = self.d.crypto.signature(ts, nonce, enc)
        with urllib.request.urlopen(self._url(
                "/wecom/callback", msg_signature=sig, timestamp=ts,
                nonce=nonce, echostr=enc), timeout=5) as r:
            self.assertEqual(r.status, 200)
            self.assertEqual(r.read().decode(), echo)

    def test_url_verification_rejects_bad_signature(self):
        enc = self.d.crypto.encrypt("x")
        with self.assertRaises(urllib.error.HTTPError) as ctx:
            urllib.request.urlopen(self._url(
                "/wecom/callback", msg_signature="bad", timestamp="1",
                nonce="2", echostr=enc), timeout=5)
        self.assertEqual(ctx.exception.code, 403)

    def test_post_message_enqueues(self):
        inner = ("<xml><ToUserName>%s</ToUserName>"
                 "<FromUserName>lisi</FromUserName>"
                 "<CreateTime>1700000002</CreateTime>"
                 "<MsgType>text</MsgType><Content>这个多少钱</Content>"
                 "<MsgId>999001</MsgId><AgentID>1000002</AgentID></xml>"
                 % DOC_CORP)
        enc = self.d.crypto.encrypt(inner)
        body = ("<xml><ToUserName>%s</ToUserName><Encrypt><![CDATA[%s]]>"
                "</Encrypt><AgentID>1000002</AgentID></xml>" % (DOC_CORP, enc))
        ts, nonce = "1700000003", "n3"
        sig = self.d.crypto.signature(ts, nonce, enc)

        req = urllib.request.Request(
            self._url("/wecom/callback", msg_signature=sig, timestamp=ts,
                      nonce=nonce), data=body.encode("utf-8"), method="POST")
        with urllib.request.urlopen(req, timeout=5) as r:
            # 必须立刻返回空串：5 秒窗口内不能在这里跑判定
            self.assertEqual(r.status, 200)
            self.assertEqual(r.read().decode(), "")

        self.assertTrue(self.d.store.seen("wecom:999001"))
        pending = self.d.adapter.poll()
        self.assertEqual(len(pending), 1)
        self.assertEqual(pending[0].text, "这个多少钱")
        self.assertEqual(pending[0].conv_id, "lisi")

    def test_duplicate_msgid_ignored(self):
        inner = ("<xml><ToUserName>%s</ToUserName>"
                 "<FromUserName>wangwu</FromUserName>"
                 "<CreateTime>1700000004</CreateTime>"
                 "<MsgType>text</MsgType><Content>有货吗</Content>"
                 "<MsgId>999002</MsgId></xml>" % DOC_CORP)
        enc = self.d.crypto.encrypt(inner)
        body = ("<xml><ToUserName>%s</ToUserName><Encrypt><![CDATA[%s]]>"
                "</Encrypt></xml>" % (DOC_CORP, enc))
        ts, nonce = "1700000005", "n5"
        sig = self.d.crypto.signature(ts, nonce, enc)
        url = self._url("/wecom/callback", msg_signature=sig,
                        timestamp=ts, nonce=nonce)

        for _ in range(2):        # 模拟平台重试三次
            req = urllib.request.Request(url, data=body.encode(),
                                         method="POST")
            urllib.request.urlopen(req, timeout=5).read()

        pending = self.d.adapter.poll()
        self.assertEqual(len(pending), 1, "重复推送被当成两条消息了")

    def test_health(self):
        with urllib.request.urlopen(
                f"http://127.0.0.1:{self.port}/health", timeout=5) as r:
            data = json.loads(r.read().decode())
        self.assertIn("accepted", data)
        self.assertTrue(data["dry_run"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
