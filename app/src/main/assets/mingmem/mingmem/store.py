import hashlib
import json
import os
import threading
import time
from contextlib import contextmanager
from pathlib import Path

from .chain import append_prev_hash
from .settings import LOCK_STALE, LOCK_TIMEOUT, NL, SIG_TAIL_BYTES
from .text import ns_filename


NL_BYTES = bytes([10])


class Store(object):
    def __init__(self, data_root):
        self.root = Path(data_root)
        self.events_dir = self.root / "events"
        self.index_path = self.root / "index.sqlite"
        self.lock_dir = self.root / ".mingmem-lock"
        self._lock_count = 0
        self._locked = False

    def ensure_dirs(self):
        self.events_dir.mkdir(parents=True, exist_ok=True)

    def event_files(self):
        if not self.events_dir.is_dir():
            return []
        return sorted([p for p in self.events_dir.glob("*.jsonl") if p.is_file()])

    def ns_path(self, ns):
        base = self.events_dir / ns_filename(ns)
        # 不同 ns 经过安全化后可能落到同一文件名（如 project:A/B 与 project:A-B）。
        # 已存在文件的首条有效事件若属于别的 ns，则改用带 hash 后缀的文件，避免混写。
        if base.exists():
            try:
                with open(base, "r", encoding="utf-8") as fh:
                    for line in fh:
                        line = line.strip()
                        if not line:
                            continue
                        try:
                            old = json.loads(line)
                        except ValueError:
                            continue
                        if old.get("ns") != ns:
                            suffix = hashlib.sha1(str(ns).encode("utf-8")).hexdigest()[:8]
                            return self.events_dir / (base.stem + "-" + suffix + ".jsonl")
                        break
            except OSError:
                pass
        return base

    @contextmanager
    def lock(self):
        """数据根级写锁（G27）。

        用「原子建目录」实现：POSIX 与 Windows 都保证 mkdir 的原子性，不依赖
        fcntl/msvcrt 的平台分支。临界区只包一次 append（微秒级），因此 LOCK_STALE
        取秒级即可安全清理崩溃残留。
        """
        if self._lock_count > 0:
            # 可重入：同进程内 append() 与命令级锁不能互相等待。
            self._lock_count += 1
            try:
                yield
            finally:
                self._lock_count -= 1
            return
        self.ensure_dirs()
        deadline = time.time() + LOCK_TIMEOUT
        held = False
        while True:
            try:
                os.mkdir(str(self.lock_dir))
                held = True
                break
            except FileExistsError:
                pass
            except OSError:
                # 建不了锁目录（如只读挂载）时退化为无锁写入，但仍保证 fsync。
                break
            try:
                age = time.time() - self.lock_dir.stat().st_mtime
            except OSError:
                age = 0.0
            if age > LOCK_STALE:
                try:
                    os.rmdir(str(self.lock_dir))
                except OSError:
                    pass
                continue
            if time.time() > deadline:
                raise TimeoutError("数据根被其他进程占用（锁：" + str(self.lock_dir) + "）；"
                                  "如确认无进程在写，删除该目录后重试")
            time.sleep(0.01)
        self._lock_count = 1
        self._locked = held
        stop_beat = self._start_heartbeat() if held else (lambda: None)
        try:
            yield
        finally:
            stop_beat()
            self._lock_count = 0
            self._locked = False
            if held:
                try:
                    os.rmdir(str(self.lock_dir))
                except OSError:
                    pass

    def _start_heartbeat(self):
        """持锁期间定期刷新锁目录 mtime，防止「还在跑的锁」被当成残留清掉。

        为什么必须有：LOCK_STALE 的本意是「进程崩了留下的锁」，但命令级加锁
        （cli.main 把整条命令包在 lock() 里）的持锁时长可以远超 LOCK_STALE ——
        rebuild / purge / cycle / export 在大库上都是秒级以上。没有心跳时，第二个
        进程会在 LOCK_STALE 秒后把仍在使用的锁目录删掉并自己抢锁，于是两个进程
        同时持锁，互斥形同虚设（可能丢事件、撕坏事件文件）。
        进程真的崩掉时不心跳 ⇒ mtime 自然变旧 ⇒ 仍由 LOCK_STALE 逻辑清理。
        """
        interval = max(0.05, float(LOCK_STALE) / 3.0)
        stopped = threading.Event()

        def beat():
            while not stopped.wait(interval):
                try:
                    os.utime(str(self.lock_dir), None)
                except OSError:
                    return

        worker = threading.Thread(target=beat, name="mingmem-lock-heartbeat", daemon=True)
        worker.start()

        def stop():
            stopped.set()
            worker.join(timeout=interval * 4 + 1.0)

        return stop

    def append(self, ev):
        """追加一条事件：加锁 + 单次 O_APPEND 写 + fsync（G27）。

        O_APPEND 保证写入落在文件末尾；整行一次写入避免出现半行；fsync 保证
        进程崩溃后事件不会只停在页缓存里。
        """
        self.ensure_dirs()
        path = self.ns_path(ev["ns"])
        with self.lock():
            # 链值必须在锁内、以文件当前状态为准算出来，否则并发追加会串错链。
            ev["prev_hash"] = append_prev_hash(path)
            data = (json.dumps(ev, ensure_ascii=False) + NL).encode("utf-8")
            flags = os.O_WRONLY | os.O_CREAT | os.O_APPEND | getattr(os, "O_BINARY", 0)
            fd = os.open(str(path), flags)
            try:
                written = 0
                while written < len(data):
                    chunk = os.write(fd, data[written:])
                    if chunk <= 0:
                        raise OSError("写入事件失败：" + str(path))
                    written += chunk
                os.fsync(fd)
            finally:
                os.close(fd)
        return path

    def tail_digest(self, path):
        """文件末尾最后一个非空行的 sha256 前 16 位（只读尾部 SIG_TAIL_BYTES 字节）。"""
        try:
            with open(path, "rb") as fh:
                fh.seek(0, os.SEEK_END)
                size = fh.tell()
                fh.seek(max(0, size - SIG_TAIL_BYTES))
                chunk = fh.read(SIG_TAIL_BYTES)
        except OSError:
            return ""
        lines = [ln for ln in chunk.split(NL_BYTES) if ln.strip()]
        last = lines[-1] if lines else b""
        return hashlib.sha256(last).hexdigest()[:16]

    def signature(self):
        """投影签名（G28）：逐文件 [名字, 字节数, 纳秒 mtime, 末行摘要]。

        原来的 [字节数, 秒级 mtime] 在同一秒内追加/等大小替换时会算不出变化，
        导致投影过期却仍被当成「最新」。纳秒 mtime + 末行摘要闭合这一漏洞。
        注：行内修改且大小、纳秒 mtime 都被还原的情况仍可能漏（极端），
        由 doctor 的事件↔投影对账兜底。
        """
        items = []
        for p in self.event_files():
            st = p.stat()
            mtime_ns = getattr(st, "st_mtime_ns", int(round(st.st_mtime * 1e9)))
            items.append([p.name, int(st.st_size), int(mtime_ns), self.tail_digest(p)])
        raw = json.dumps(items, ensure_ascii=False).encode("utf-8")
        return hashlib.sha256(raw).hexdigest()

    def stats(self):
        info = {"files": 0, "lines": 0, "bad_lines": 0, "bytes": 0}
        for p in self.event_files():
            info["files"] += 1
            info["bytes"] += p.stat().st_size
            with open(p, "r", encoding="utf-8") as f:
                for line in f:
                    if not line.strip():
                        continue
                    info["lines"] += 1
                    try:
                        json.loads(line)
                    except ValueError:
                        info["bad_lines"] += 1
        return info
