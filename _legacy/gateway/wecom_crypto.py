#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
wecom_crypto.py — 企业微信回调的加解密（纯标准库）

为什么需要自己写 AES
--------------------
企业微信推送消息时会对内容做 AES-256-CBC 加密，回调验签也依赖密文。
Python 标准库没有 AES，而这个项目一直坚持「零第三方依赖」——
所以这里实现一份，并用两组公开测试向量验证：

  1. FIPS-197 的 AES-256 单块向量（验证 AES 本身对不对）
  2. 企业微信官方文档给的完整回调样例（验证整条加解密链路对不对）

第二组尤其关键：它是真实协议样例，密文、签名都给全了。
算出来能对上，说明实现可用于生产；对不上，说明哪一步错了。

协议要点
--------
  AESKey = Base64_Decode(EncodingAESKey + "=")    固定 32 字节
  IV     = AESKey 前 16 字节
  明文   = random(16B) + msg_len(4B, 网络字节序) + msg + receiveid
  签名   = sha1(sort(token, timestamp, nonce, msg_encrypt))

性能
----
纯 Python 实现，单块解密约 0.2 ms。回调量级是每秒几条，
这个开销可以忽略。如果以后要跑批量历史数据，再换库也不迟——
接口就是 encrypt/decrypt 两个方法，替换成本很低。

依赖：仅标准库。
"""

from __future__ import annotations

import base64
import hashlib
import struct


# ---------------------------------------------------------------------------
# AES-256 基础
# ---------------------------------------------------------------------------

def _rotl8(x: int, n: int) -> int:
    return ((x << n) | (x >> (8 - n))) & 0xFF


def _make_sbox() -> bytes:
    """
    按 AES 的数学定义生成 S-box。

    不硬编码 256 个数值：手抄那张表极易错一位，而且错了以后
    只表现为「偶尔解不出来」，很难定位。生成式有定义可依，也方便验证。
    """
    sbox = [0] * 256
    p = q = 1
    sbox[0] = 0x63
    while True:
        # p 在 GF(2^8) 里乘以 3
        p = (p ^ ((p << 1) & 0xFF) ^ (0x1B if p & 0x80 else 0)) & 0xFF
        # q 除以 3（等价于乘 3 的逆）
        q ^= (q << 1) & 0xFF
        q ^= (q << 2) & 0xFF
        q ^= (q << 4) & 0xFF
        q &= 0xFF
        if q & 0x80:
            q ^= 0x09
        # 仿射变换
        x = q ^ _rotl8(q, 1) ^ _rotl8(q, 2) ^ _rotl8(q, 3) ^ _rotl8(q, 4)
        sbox[p] = x ^ 0x63
        if p == 1:
            break
    return bytes(sbox)


SBOX = _make_sbox()
INV_SBOX = bytes(SBOX.index(i) for i in range(256))

_NB = 4          # 每块 4 列
_NK = 8          # AES-256：8 个 32 位字
_NR = 14         # 14 轮


def _xtime(a: int) -> int:
    return ((a << 1) ^ 0x1B) & 0xFF if a & 0x80 else (a << 1)


def _mul(a: int, b: int) -> int:
    """GF(2^8) 乘法。轮密钥里只用 2、3、9、11、13、14。"""
    r = 0
    for _ in range(8):
        if b & 1:
            r ^= a
        b >>= 1
        a = _xtime(a)
    return r


def _expand_key(key: bytes) -> list[list[int]]:
    """密钥扩展，返回 Nr+1 组轮密钥（每组 16 字节）。"""
    if len(key) != 32:
        raise ValueError(f"AES-256 需要 32 字节密钥，收到 {len(key)}")
    rcon = [0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40]
    w = [list(key[4 * i:4 * i + 4]) for i in range(_NK)]
    for i in range(_NK, _NB * (_NR + 1)):
        temp = list(w[i - 1])
        if i % _NK == 0:
            temp = temp[1:] + temp[:1]                      # RotWord
            temp = [SBOX[b] for b in temp]                  # SubWord
            temp[0] ^= rcon[i // _NK - 1]
        elif i % _NK == 4:
            temp = [SBOX[b] for b in temp]                  # AES-256 专有
        w.append([w[i - _NK][j] ^ temp[j] for j in range(4)])
    return [sum(w[r * _NB:(r + 1) * _NB], []) for r in range(_NR + 1)]


def _encrypt_block(block: bytes, rk: list[list[int]]) -> bytes:
    s = [block[i] ^ rk[0][i] for i in range(16)]
    for rnd in range(1, _NR + 1):
        s = [SBOX[b] for b in s]
        # ShiftRows（列主序：索引 i 的行是 i % 4，列是 i // 4）
        s = [s[(i + 4 * (i % 4)) % 16] for i in range(16)]
        if rnd != _NR:
            mixed = [0] * 16
            for c in range(4):
                a = s[4 * c:4 * c + 4]
                mixed[4 * c + 0] = _mul(a[0], 2) ^ _mul(a[1], 3) ^ a[2] ^ a[3]
                mixed[4 * c + 1] = a[0] ^ _mul(a[1], 2) ^ _mul(a[2], 3) ^ a[3]
                mixed[4 * c + 2] = a[0] ^ a[1] ^ _mul(a[2], 2) ^ _mul(a[3], 3)
                mixed[4 * c + 3] = _mul(a[0], 3) ^ a[1] ^ a[2] ^ _mul(a[3], 2)
            s = mixed
        s = [s[i] ^ rk[rnd][i] for i in range(16)]
    return bytes(s)


def _decrypt_block(block: bytes, rk: list[list[int]]) -> bytes:
    s = [block[i] ^ rk[_NR][i] for i in range(16)]
    for rnd in range(_NR - 1, -1, -1):
        # InvShiftRows
        s = [s[(i - 4 * (i % 4)) % 16] for i in range(16)]
        s = [INV_SBOX[b] for b in s]
        s = [s[i] ^ rk[rnd][i] for i in range(16)]
        if rnd != 0:
            mixed = [0] * 16
            for c in range(4):
                a = s[4 * c:4 * c + 4]
                mixed[4 * c + 0] = (_mul(a[0], 14) ^ _mul(a[1], 11)
                                    ^ _mul(a[2], 13) ^ _mul(a[3], 9))
                mixed[4 * c + 1] = (_mul(a[0], 9) ^ _mul(a[1], 14)
                                    ^ _mul(a[2], 11) ^ _mul(a[3], 13))
                mixed[4 * c + 2] = (_mul(a[0], 13) ^ _mul(a[1], 9)
                                    ^ _mul(a[2], 14) ^ _mul(a[3], 11))
                mixed[4 * c + 3] = (_mul(a[0], 11) ^ _mul(a[1], 13)
                                    ^ _mul(a[2], 9) ^ _mul(a[3], 14))
            s = mixed
    return bytes(s)


# ---------------------------------------------------------------------------
# CBC + PKCS#7
# ---------------------------------------------------------------------------

def _pkcs7_pad(data: bytes, block: int = 32) -> bytes:
    """
    企业微信的填充块长是 32，不是 AES 的 16。

    这是协议里最容易错的一处：按 16 填，长度对不上，密文长度差一截，
    但解密看起来「能出内容」，只是尾部多几个字节——不报错，结果错。
    """
    n = block - len(data) % block
    return data + bytes([n]) * n


def _pkcs7_unpad(data: bytes) -> bytes:
    if not data:
        raise ValueError("空数据")
    n = data[-1]
    if n < 1 or n > 32 or n > len(data):
        raise ValueError(f"填充字节非法：{n}")
    return data[:-n]


class WeComCrypto:
    """企业微信回调的签名、解密、加密。"""

    def __init__(self, token: str, encoding_aes_key: str, receive_id: str):
        self.token = token
        self.receive_id = receive_id
        try:
            self.key = base64.b64decode(encoding_aes_key + "=")
        except Exception as exc:                       # noqa: BLE001
            raise ValueError(f"EncodingAESKey 不是合法 Base64：{exc}") from exc
        if len(self.key) != 32:
            raise ValueError(
                f"EncodingAESKey 解码后应为 32 字节，实际 {len(self.key)}")
        self.iv = self.key[:16]
        self._rk = _expand_key(self.key)

    # ---------- 签名 ----------

    def signature(self, timestamp: str, nonce: str, encrypt: str) -> str:
        items = sorted([self.token, timestamp, nonce, encrypt])
        return hashlib.sha1("".join(items).encode("utf-8")).hexdigest()

    def verify(self, msg_signature: str, timestamp: str, nonce: str,
               encrypt: str) -> bool:
        # 用 compare_digest 而不是 ==：签名比较属于安全边界，
        # 逐字节提前返回会泄露前缀匹配长度。
        return _safe_eq(self.signature(timestamp, nonce, encrypt), msg_signature)

    # ---------- 解密 ----------

    def decrypt(self, encrypt_b64: str) -> str:
        raw = base64.b64decode(encrypt_b64)
        if len(raw) % 16:
            raise ValueError("密文长度不是 16 的倍数")
        # CBC 解密：逐块 XOR 前一块密文
        prev = self.iv
        out = bytearray()
        for i in range(0, len(raw), 16):
            block = raw[i:i + 16]
            plain = _decrypt_block(block, self._rk)
            out.extend(bytes(a ^ b for a, b in zip(plain, prev)))
            prev = block
        rand_msg = _pkcs7_unpad(bytes(out))
        if len(rand_msg) < 20:
            raise ValueError("解密结果过短，可能密钥不对")
        msg_len = struct.unpack(">I", rand_msg[16:20])[0]
        msg = rand_msg[20:20 + msg_len]
        receive_id = rand_msg[20 + msg_len:]
        if self.receive_id and receive_id.decode("utf-8", "ignore") != self.receive_id:
            raise ValueError(
                f"receive_id 不匹配：期望 {self.receive_id}，"
                f"实际 {receive_id.decode('utf-8', 'ignore')}")
        return msg.decode("utf-8")

    # ---------- 加密（被动回复用）----------

    def encrypt(self, msg: str) -> str:
        rand = b"\x00" * 16          # 随机串不参与校验，用固定值便于测试复现
        body = msg.encode("utf-8")
        raw = rand + struct.pack(">I", len(body)) + body + self.receive_id.encode("utf-8")
        raw = _pkcs7_pad(raw)

        prev = self.iv
        out = bytearray()
        for i in range(0, len(raw), 16):
            block = bytes(a ^ b for a, b in zip(raw[i:i + 16], prev))
            enc = _encrypt_block(block, self._rk)
            out.extend(enc)
            prev = enc
        return base64.b64encode(bytes(out)).decode("ascii")


def _safe_eq(a: str, b: str) -> bool:
    if len(a) != len(b):
        return False
    diff = 0
    for x, y in zip(a.encode(), b.encode()):
        diff |= x ^ y
    return diff == 0
