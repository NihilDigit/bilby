#!/usr/bin/env python3
"""Mirror the Kotlin language, coroutine and standard library documentation.

kotlinlang.org serves two kinds of static HTML. The guide pages under /docs/ are
built by Writerside and carry their navigation in a separate HelpTOC.json; that
file is the scope authority here, because it names the sections and their order
and therefore survives page renames. The API reference under /api/ is Dokka
output, also plain HTML, so it is converted with a second set of rules.

Everything outside the language, coroutine and standard library sections is left
alone on purpose. See README.md for what that excludes and why.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import gzip
import hashlib
import html
import json
import os
import random
import re
import sys
import threading
import time
import zlib
from html.parser import HTMLParser
from http.client import HTTPConnection, HTTPSConnection, HTTPException
from pathlib import Path
from typing import Any, Callable, Iterable
from urllib.parse import urljoin, urlparse

BASE_URL = "https://kotlinlang.org"
DEFAULT_OUT = Path(__file__).resolve().parent
USER_AGENT = "Bilby-KotlinDocsMirror/0.1 (+local documentation mirror)"
MAX_WORKERS = 10

TOC_URL = f"{BASE_URL}/docs/HelpTOC.json"
CONFIG_URL = f"{BASE_URL}/docs/config.json"
ROBOTS_URL = f"{BASE_URL}/robots.txt"
CORE_SITEMAP_URL = f"{BASE_URL}/api/core/sitemap.xml"
COROUTINES_SITEMAP_URL = f"{BASE_URL}/api/kotlinx.coroutines/sitemap.xml"

# Sections are addressed by their title path in HelpTOC.json. The coroutine
# paths are listed first because "Concurrency" sits inside "Language guide" and
# the first match wins.
SECTION_PATHS: list[tuple[str, tuple[str, ...]]] = [
    ("coroutines", ("Language guide", "Concurrency")),
    ("coroutines", ("Library guides", "Coroutines (kotlinx.coroutines)")),
    ("stdlib", ("Library guides", "Standard library")),
    ("language", ("Language guide",)),
]

GROUP_TITLES = {
    "language": "语言参考",
    "coroutines": "协程",
    "stdlib": "标准库主题",
    "api": "API 参考",
}
GROUP_ORDER = ("language", "coroutines", "stdlib", "api")

API_SITEMAP_PREFIXES = (
    f"{BASE_URL}/api/core/kotlin-stdlib/",
    f"{BASE_URL}/api/kotlinx.coroutines/",
)

# kotlin-stdlib documents its JS, Native and Wasm targets in the same Dokka
# site as the common and JVM API. Those packages are out of scope for an Android
# client and are the largest single source of irrelevant grep hits in the
# mirror, so they are dropped by package rather than by module.
API_PACKAGE_EXCLUDES = ("kotlin.js", "kotlin.native", "kotlin.wasm", "kotlinx.cinterop", "org.w3c", "org.khronos")

IMAGE_EXTENSIONS = {".apng", ".avif", ".gif", ".ico", ".jpeg", ".jpg", ".png", ".svg", ".webp"}
RETRY_STATUS = {408, 425, 429, 500, 502, 503, 504}
VOID_TAGS = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr"}


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------


class FetchFailure(RuntimeError):
    def __init__(self, url: str, message: str, status: int | None = None, attempts: int = 0):
        super().__init__(f"{url}: {message}")
        self.url = url
        self.message = message
        self.status = status
        self.attempts = attempts


class Response:
    def __init__(self, url: str, status: int, body: bytes, headers: dict[str, str]):
        self.url = url
        self.status = status
        self.body = body
        self.headers = headers

    @property
    def etag(self) -> str | None:
        return self.headers.get("etag")

    @property
    def last_modified(self) -> str | None:
        return self.headers.get("last-modified")


class Http:
    """Keep-alive HTTP client over http.client, one connection per thread.

    urlopen closes the socket after every request, which turns a revalidation
    pass over ~1500 pages into ~1500 TLS handshakes. Reusing the connection is
    what makes the second run finish in seconds rather than half a minute.
    """

    def __init__(self, attempts: int = 4, timeout: float = 45.0):
        self.attempts = attempts
        self.timeout = timeout
        self.local = threading.local()
        self.request_count = 0
        self.downloaded_bytes = 0
        self._counter_lock = threading.Lock()

    def _connection(self, scheme: str, host: str):
        pool: dict[tuple[str, str], Any] = getattr(self.local, "pool", None)
        if pool is None:
            pool = self.local.pool = {}
        key = (scheme, host)
        if key not in pool:
            factory = HTTPSConnection if scheme == "https" else HTTPConnection
            pool[key] = factory(host, timeout=self.timeout)
        return pool[key]

    def _discard(self, scheme: str, host: str) -> None:
        pool: dict[tuple[str, str], Any] = getattr(self.local, "pool", {})
        connection = pool.pop((scheme, host), None)
        if connection is not None:
            try:
                connection.close()
            except OSError:
                pass

    def close(self) -> None:
        for connection in getattr(self.local, "pool", {}).values():
            try:
                connection.close()
            except OSError:
                pass
        self.local.pool = {}

    def _once(self, url: str, headers: dict[str, str]) -> Response:
        parsed = urlparse(url)
        host = parsed.netloc
        scheme = parsed.scheme or "https"
        target = parsed.path or "/"
        if parsed.query:
            target += "?" + parsed.query
        connection = self._connection(scheme, host)
        request_headers = {
            "User-Agent": USER_AGENT,
            "Accept-Encoding": "gzip",
            "Connection": "keep-alive",
            "Host": host,
            **headers,
        }
        try:
            connection.request("GET", target, headers=request_headers)
            raw = connection.getresponse()
            body = raw.read()
            status = raw.status
            response_headers = {key.lower(): value for key, value in raw.getheaders()}
        except (HTTPException, OSError) as error:
            self._discard(scheme, host)
            raise FetchFailure(url, str(error) or error.__class__.__name__) from error
        encoding = response_headers.get("content-encoding", "")
        if body and "gzip" in encoding:
            body = gzip.decompress(body)
        elif body and "deflate" in encoding:
            body = zlib.decompress(body, -zlib.MAX_WBITS)
        return Response(url, status, body, response_headers)

    def get(self, url: str, headers: dict[str, str] | None = None, redirects: int = 5) -> Response:
        headers = dict(headers or {})
        last: FetchFailure | None = None
        for attempt in range(self.attempts):
            try:
                response = self._once(url, headers)
            except FetchFailure as error:
                last = FetchFailure(url, error.message, None, attempt + 1)
                time.sleep(min(20.0, (2.0**attempt) + random.random()))
                continue
            with self._counter_lock:
                self.request_count += 1
                self.downloaded_bytes += len(response.body)
            if response.status in (301, 302, 303, 307, 308):
                location = response.headers.get("location")
                if not location or redirects <= 0:
                    raise FetchFailure(url, f"HTTP {response.status} without a usable Location", response.status, attempt + 1)
                return self.get(urljoin(url, location), headers, redirects - 1)
            if response.status in RETRY_STATUS:
                last = FetchFailure(url, f"HTTP {response.status}", response.status, attempt + 1)
                retry_after = response.headers.get("retry-after")
                try:
                    delay = min(30.0, float(retry_after)) if retry_after else 2.0**attempt
                except ValueError:
                    delay = 2.0**attempt
                time.sleep(delay)
                continue
            if response.status >= 400:
                raise FetchFailure(url, f"HTTP {response.status}", response.status, attempt + 1)
            return response
        raise FetchFailure(url, last.message if last else "unknown fetch error", last.status if last else None, self.attempts)


# ---------------------------------------------------------------------------
# On-disk state
# ---------------------------------------------------------------------------


def write_bytes(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".part")
    temporary.write_bytes(data)
    temporary.replace(path)


def write_text(path: Path, text: str) -> None:
    write_bytes(path, text.encode("utf-8"))


def write_json(path: Path, value: Any) -> None:
    write_text(path, json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def read_json(path: Path, default: Any) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return default


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class Ledger:
    """Fetch state that survives an interrupted run.

    Completed URLs are appended to progress.jsonl as they land, so a run killed
    halfway leaves a record on disk rather than only in memory. The next run
    folds that file back into cache.json and resumes: everything already written
    is revalidated with a conditional request instead of downloaded again.
    """

    def __init__(self, metadata: Path, read_only: bool = False):
        self.metadata = metadata
        self.read_only = read_only
        self.cache_path = metadata / "cache.json"
        self.progress_path = metadata / "progress.jsonl"
        self.failures_path = metadata / "failures.json"
        self.entries: dict[str, dict[str, Any]] = read_json(self.cache_path, {})
        self.failures: dict[str, dict[str, Any]] = read_json(self.failures_path, {})
        self._lock = threading.Lock()
        self._progress = None
        self._fold_progress()

    def _fold_progress(self) -> None:
        if not self.progress_path.exists():
            return
        recovered = 0
        for line in self.progress_path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError:
                continue
            if isinstance(record, dict) and record.get("url"):
                self.entries[record["url"]] = record
                recovered += 1
        if self.read_only:
            return
        if recovered:
            print(f"resumed {recovered} entries from an interrupted run", flush=True)
        write_json(self.cache_path, self.entries)
        self.progress_path.unlink()

    def open(self) -> None:
        self.metadata.mkdir(parents=True, exist_ok=True)
        self._progress = self.progress_path.open("a", encoding="utf-8")

    def get(self, url: str) -> dict[str, Any] | None:
        return self.entries.get(url)

    def record(self, entry: dict[str, Any]) -> None:
        with self._lock:
            self.entries[entry["url"]] = entry
            self.failures.pop(entry["url"], None)
            if self._progress is not None:
                self._progress.write(json.dumps(entry, ensure_ascii=False) + "\n")
                self._progress.flush()

    def fail(self, url: str, error: FetchFailure) -> None:
        with self._lock:
            previous = self.failures.get(url, {})
            self.failures[url] = {
                "url": url,
                "status": error.status,
                "message": error.message,
                "attempts": int(previous.get("attempts", 0)) + max(1, error.attempts),
                "last_seen": timestamp(),
            }

    def close(self) -> None:
        if self._progress is not None:
            self._progress.close()
            self._progress = None
        write_json(self.cache_path, self.entries)
        write_json(self.failures_path, self.failures)
        if self.progress_path.exists():
            self.progress_path.unlink()


def timestamp() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


# ---------------------------------------------------------------------------
# HTML to Markdown
# ---------------------------------------------------------------------------


def collapse(text: str) -> str:
    return re.sub(r"\s+", " ", text)


def clean_markdown(value: str) -> str:
    value = value.replace("\r\n", "\n").replace("\r", "\n")
    value = re.sub(r"[ \t]+\n", "\n", value)
    value = re.sub(r"\n[ \t]+\n", "\n\n", value)
    value = re.sub(r"\n{3,}", "\n\n", value)
    return value.strip() + "\n"


class Element:
    __slots__ = ("tag", "classes", "attrs", "action", "data")

    def __init__(self, tag: str, classes: set[str], attrs: dict[str, str]):
        self.tag = tag
        self.classes = classes
        self.attrs = attrs
        self.action = "keep"
        self.data: Any = None


class Converter(HTMLParser):
    """Convert one page's content element into Markdown.

    Output goes through a stack of sinks so that code blocks, table cells and
    link labels can be captured and re-emitted rather than parsed a second time.
    """

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.sinks: list[list[str]] = [[]]
        self.stack: list[Element] = []
        self.started = False
        self.finished = False
        self.suppress = 0
        self.code_depth = 0
        self.list_stack: list[dict[str, Any]] = []
        self.tables: list[dict[str, Any]] = []
        self.base_url = ""
        self.asset_resolver: Callable[[str], str | None] = lambda url: None
        self.headings = 0
        self.code_blocks = 0
        self.markdown_tables = 0

    # -- subclass hooks ----------------------------------------------------

    def is_root(self, tag: str, classes: set[str], attrs: dict[str, str]) -> bool:
        raise NotImplementedError

    def is_dropped(self, tag: str, classes: set[str], attrs: dict[str, str]) -> bool:
        return tag in {"script", "style", "noscript", "nav", "header", "footer", "svg", "form", "button", "object", "iframe"}

    def open_element(self, element: Element) -> None:
        pass

    def close_element(self, element: Element) -> None:
        pass

    # -- sink plumbing -----------------------------------------------------

    def emit(self, text: str) -> None:
        if text:
            self.sinks[-1].append(text)

    def push_sink(self) -> None:
        self.sinks.append([])

    def pop_sink(self) -> str:
        return "".join(self.sinks.pop())

    def block(self) -> None:
        sink = self.sinks[-1]
        while sink and sink[-1].strip() == "" and "\n\n" not in sink[-1]:
            sink.pop()
        if sink and not "".join(sink[-2:]).endswith("\n\n"):
            sink.append("\n\n")

    def newline(self) -> None:
        sink = self.sinks[-1]
        if sink and not sink[-1].endswith("\n"):
            sink.append("\n")

    # -- parser callbacks --------------------------------------------------

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        tag = tag.lower()
        attributes = {key.lower(): (value or "") for key, value in attrs}
        classes = set(attributes.get("class", "").split())
        if not self.started:
            if self.is_root(tag, classes, attributes):
                self.started = True
                self.stack.append(Element(tag, classes, attributes))
            return
        if self.finished:
            return
        element = Element(tag, classes, attributes)
        if tag not in VOID_TAGS:
            self.stack.append(element)
        if self.suppress:
            # Inside a dropped subtree nothing is rendered, and only the element
            # that opened the drop may close it again. Marking descendants
            # "drop" as well would let each of their end tags cancel one level
            # of suppression and leak the tail of the subtree into the output.
            element.action = "suppressed"
            return
        if self.is_dropped(tag, classes, attributes):
            element.action = "drop"
            if tag in VOID_TAGS:
                return
            self.suppress += 1
            return
        self.open_element(element)
        if element.action == "drop" and tag not in VOID_TAGS:
            self.suppress += 1

    def handle_startendtag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        self.handle_starttag(tag, attrs)

    def handle_endtag(self, tag: str) -> None:
        tag = tag.lower()
        if not self.started or self.finished or tag in VOID_TAGS:
            return
        for index in range(len(self.stack) - 1, -1, -1):
            if self.stack[index].tag == tag:
                break
        else:
            return
        while len(self.stack) > index:
            element = self.stack.pop()
            if len(self.stack) == 0:
                self.finished = True
                return
            if element.action == "drop":
                self.suppress = max(0, self.suppress - 1)
                continue
            if not self.suppress:
                self.close_element(element)

    def handle_data(self, data: str) -> None:
        if not self.started or self.finished or self.suppress:
            return
        if self.code_depth:
            self.emit(data)
            return
        if not data.strip():
            sink = self.sinks[-1]
            if sink and not sink[-1].endswith((" ", "\n")):
                self.emit(" ")
            return
        self.emit(collapse(data))

    # -- shared rendering --------------------------------------------------

    def start_code(self, language: str) -> None:
        self.block()
        self.code_depth += 1
        self.push_sink()

    def end_code(self, language: str) -> None:
        body = self.pop_sink()
        self.code_depth -= 1
        body = body.strip("\n")
        fence = "```"
        while fence in body:
            fence += "`"
        self.emit(f"{fence}{language}\n{body}\n{fence}")
        self.code_blocks += 1
        self.block()

    def close_emphasis(self, marker: str) -> None:
        # "**Since Kotlin **" renders as literal asterisks; the trailing space
        # belongs outside the markers.
        sink = self.sinks[-1]
        trailing = ""
        while sink and sink[-1].endswith(" ") and sink[-1].strip():
            sink[-1] = sink[-1][:-1]
            trailing = " "
            break
        self.emit(marker)
        self.emit(trailing)

    def heading(self, level: int) -> None:
        self.block()
        self.emit("#" * level + " ")
        self.headings += 1

    def start_link(self) -> None:
        if self.code_depth:
            return
        self.push_sink()

    def end_link(self, href: str) -> None:
        if self.code_depth:
            return
        label = self.pop_sink().strip()
        target = self.resolve_link(href)
        if not label:
            return
        if not target:
            self.emit(label)
            return
        self.emit(f"[{label}]({target})")

    def resolve_link(self, href: str) -> str:
        href = (href or "").strip()
        if not href or href.startswith(("javascript:", "#")):
            return ""
        return urljoin(self.base_url, href)

    # -- lists -------------------------------------------------------------

    def open_list(self, ordered: bool) -> None:
        self.block()
        self.list_stack.append({"ordered": ordered, "index": 0})

    def close_list(self) -> None:
        if self.list_stack:
            self.list_stack.pop()
        self.block()

    def open_item(self, element: Element) -> None:
        context = self.list_stack[-1] if self.list_stack else {"ordered": False, "index": 0}
        context["index"] = int(context.get("index", 0)) + 1
        element.action = "list-item"
        element.data = f"{context['index']}. " if context["ordered"] else "- "
        self.newline()
        self.push_sink()

    def close_item(self, element: Element) -> None:
        body = clean_markdown(self.pop_sink()).strip()
        marker = str(element.data or "- ")
        if not body:
            return
        # Item text is indented after the fact rather than while it is written,
        # so a nested list inside the item lands one level deeper without the
        # inner items needing to know how deep they already are.
        lines = body.split("\n")
        rendered = marker + lines[0]
        for line in lines[1:]:
            rendered += "\n" + (" " * len(marker) + line if line.strip() else "")
        self.emit(rendered)
        self.newline()

    def image(self, src: str, alt: str) -> None:
        absolute = urljoin(self.base_url, src)
        local = self.asset_resolver(absolute)
        self.block()
        self.emit(f"![{collapse(alt) or 'image'}]({local or absolute})")
        self.block()

    # -- tables ------------------------------------------------------------

    def start_table(self) -> None:
        self.tables.append({"rows": [], "row": None})
        self.block()

    def start_row(self) -> None:
        if self.tables:
            self.tables[-1]["row"] = []

    def end_row(self) -> None:
        if self.tables and self.tables[-1]["row"] is not None:
            self.tables[-1]["rows"].append(self.tables[-1]["row"])
            self.tables[-1]["row"] = None

    def start_cell(self) -> None:
        if self.tables:
            self.push_sink()

    def end_cell(self) -> None:
        if not self.tables:
            return
        text = clean_markdown(self.pop_sink()).strip()
        text = text.replace("|", "\\|").replace("\n", "<br>")
        row = self.tables[-1]["row"]
        if row is None:
            row = self.tables[-1]["row"] = []
        row.append(text)

    def end_table(self) -> None:
        if not self.tables:
            return
        table = self.tables.pop()
        rows: list[list[str]] = [row for row in table["rows"] if row]
        if not rows:
            return
        width = max(len(row) for row in rows)
        padded = [row + [""] * (width - len(row)) for row in rows]
        header, body = padded[0], padded[1:]
        lines = ["| " + " | ".join(header) + " |", "| " + " | ".join("---" for _ in header) + " |"]
        lines += ["| " + " | ".join(row) + " |" for row in body]
        self.block()
        self.emit("\n".join(lines))
        self.markdown_tables += 1
        self.block()

    # -- entry point -------------------------------------------------------

    def run(self, page_html: str, base_url: str, asset_resolver: Callable[[str], str | None]) -> str:
        self.base_url = base_url
        self.asset_resolver = asset_resolver
        self.feed(page_html)
        self.close()
        return clean_markdown(self.sinks[0] and "".join(self.sinks[0]) or "")


class DocsConverter(Converter):
    """Writerside guide pages: content lives in <article class="article">."""

    DROP_CLASSES = {"last-modified", "navigation-links", "video-player", "feedback", "tabs-section"}

    def is_root(self, tag: str, classes: set[str], attrs: dict[str, str]) -> bool:
        return tag == "article" and "article" in classes

    def is_dropped(self, tag: str, classes: set[str], attrs: dict[str, str]) -> bool:
        if super().is_dropped(tag, classes, attrs):
            return True
        if classes & self.DROP_CLASSES:
            return True
        if attrs.get("data-feedback-placeholder") or attrs.get("id") == "disqus_thread":
            return True
        return False

    def open_element(self, element: Element) -> None:
        tag, classes, attrs = element.tag, element.classes, element.attrs
        if tag in {"h1", "h2", "h3", "h4", "h5", "h6"}:
            self.heading(int(tag[1]))
        elif tag == "div" and "code-block" in classes:
            language = attrs.get("data-lang", "")
            element.data = language
            self.start_code(language)
        elif tag == "pre":
            element.data = ""
            self.start_code("")
        elif tag == "code" and self.code_depth == 0:
            element.action = "inline-code"
            self.emit("`")
        elif tag == "aside" and "prompt" in classes:
            element.action = "callout"
            element.data = attrs.get("data-type", "note")
            self.block()
            self.push_sink()
        elif tag == "div" and "tabs__content" in classes:
            title = attrs.get("data-title", "")
            if title:
                self.block()
                self.emit(f"**{collapse(title)}**")
                self.block()
        elif tag in {"p", "div", "section", "figure", "figcaption", "blockquote"}:
            self.block()
        elif tag in {"ul", "ol"}:
            self.open_list(tag == "ol")
        elif tag == "li":
            self.open_item(element)
        elif tag in {"strong", "b"} or (tag == "span" and "strong" in classes):
            element.action = "bold"
            self.emit("**")
        elif tag in {"em", "i"} or (tag == "span" and "emphasis" in classes):
            element.action = "italic"
            self.emit("*")
        elif tag == "a":
            element.action = "link"
            self.start_link()
        elif tag == "img":
            self.image(attrs.get("src", ""), attrs.get("alt", ""))
        elif tag == "br":
            self.newline()
        elif tag == "table":
            self.start_table()
        elif tag == "tr":
            self.start_row()
        elif tag in {"td", "th"}:
            self.start_cell()

    def close_element(self, element: Element) -> None:
        tag, classes = element.tag, element.classes
        if tag in {"h1", "h2", "h3", "h4", "h5", "h6"}:
            self.block()
        elif tag == "div" and "code-block" in classes:
            self.end_code(element.data or "")
        elif tag == "pre":
            self.end_code("")
        elif element.action == "inline-code":
            self.emit("`")
        elif element.action == "callout":
            body = clean_markdown(self.pop_sink()).strip()
            kind = str(element.data or "note").strip().lower()
            label = {"tip": "提示", "note": "注意", "warning": "警告"}.get(kind, kind or "注意")
            quoted = "\n".join("> " + line if line else ">" for line in body.split("\n"))
            self.emit(f"> **{label}**\n>\n{quoted}" if body else "")
            self.block()
        elif element.action == "bold":
            self.close_emphasis("**")
        elif element.action == "italic":
            self.close_emphasis("*")
        elif element.action == "link":
            self.end_link(element.attrs.get("href", ""))
        elif element.action == "list-item":
            self.close_item(element)
        elif tag in {"ul", "ol"}:
            self.close_list()
        elif tag == "table":
            self.end_table()
        elif tag == "tr":
            self.end_row()
        elif tag in {"td", "th"}:
            self.end_cell()
        elif tag in {"p", "section", "figure", "figcaption", "blockquote", "div"}:
            self.block()


class DokkaConverter(Converter):
    """Dokka API pages: content lives in <div class="main-content">.

    Two structural details matter. Declarations are repeated once per source set
    (common, jvm, js, native, wasm-*), and the repeats are dropped by comparing
    rendered text rather than by keeping whichever block comes first: Dokka also
    uses the same per-source-set wrapper around whole member tables, so a
    position rule silently ate the members that only one source set declares.
    Comparing content cannot drop anything unique. And the signature block is a
    <div class="symbol monospace"> rather than a <pre>, so it is captured as a
    Kotlin code block by class.
    """

    DROP_CLASSES = {
        "breadcrumbs",
        "copy-popup-wrapper",
        "anchor-wrapper",
        "source-link-wrapper",
        "platform-bookmarks-row",
        "filtered-message",
        "navigation-controls",
        "tabs-section",
        "sideMenu",
        "footer",
        "navigation",
    }

    def is_root(self, tag: str, classes: set[str], attrs: dict[str, str]) -> bool:
        return tag == "div" and "main-content" in classes

    def is_dropped(self, tag: str, classes: set[str], attrs: dict[str, str]) -> bool:
        if super().is_dropped(tag, classes, attrs):
            return True
        return bool(classes & self.DROP_CLASSES)

    def open_element(self, element: Element) -> None:
        tag, classes, attrs = element.tag, element.classes, element.attrs
        if tag == "div" and "platform-hinted" in classes:
            element.action = "platform-hinted"
            element.data = {"seen": set()}
            self.block()
        elif tag == "div" and "sourceset-dependent-content" in classes:
            element.action = "sourceset"
            element.data = (self.headings, self.code_blocks, self.markdown_tables)
            self.push_sink()
        elif tag == "div" and "symbol" in classes:
            element.action = "symbol"
            self.start_code("kotlin")
        elif tag == "code" and self.code_depth == 0:
            element.action = "inline-code"
            self.emit("`")
        elif tag == "code" and self.code_depth:
            # Dokka puts the language on the inner <code class="block lang-kotlin">,
            # so the enclosing <pre> only learns it once the child opens.
            language = next((name[5:] for name in classes if name.startswith("lang-")), "")
            holder = next((item for item in reversed(self.stack) if item.action == "pre"), None)
            if holder is not None and language:
                holder.data = language
        elif tag == "pre":
            element.action = "pre"
            element.data = ""
            self.start_code("")
        elif tag in {"h1", "h2", "h3", "h4", "h5", "h6"}:
            self.heading(2 if tag == "h1" and self.headings else int(tag[1]))
        elif tag == "div" and "table-row" in classes:
            self.block()
            self.emit("---")
            self.block()
        elif tag == "a":
            element.action = "link"
            self.start_link()
        elif tag == "img":
            self.image(attrs.get("src", ""), attrs.get("alt", ""))
        elif tag == "br":
            self.emit("\n" if self.code_depth else " ")
        elif tag in {"strong", "b"}:
            element.action = "bold"
            self.emit("**")
        elif tag in {"em", "i"}:
            element.action = "italic"
            self.emit("*")
        elif tag in {"ul", "ol"}:
            self.open_list(tag == "ol")
        elif tag == "li":
            self.open_item(element)
        elif tag == "table":
            self.start_table()
        elif tag == "tr":
            self.start_row()
        elif tag in {"td", "th"}:
            self.start_cell()
        elif tag in {"p", "div", "section"}:
            self.block()

    def close_element(self, element: Element) -> None:
        tag = element.tag
        if element.action == "sourceset":
            body = self.pop_sink()
            holder = next((item for item in reversed(self.stack) if item.action == "platform-hinted"), None)
            key = collapse(body).strip()
            if holder is not None and key and key in holder.data["seen"]:
                # An exact repeat of a sibling source set. Restore the counters
                # the discarded render bumped, so --verify measures what the
                # page actually contains.
                self.headings, self.code_blocks, self.markdown_tables = element.data
                return
            if holder is not None and key:
                holder.data["seen"].add(key)
            self.block()
            self.emit(body)
            self.block()
        elif element.action == "symbol":
            self.end_code("kotlin")
        elif element.action == "pre":
            self.end_code(str(element.data or ""))
        elif element.action == "inline-code":
            self.emit("`")
        elif element.action == "bold":
            self.close_emphasis("**")
        elif element.action == "italic":
            self.close_emphasis("*")
        elif element.action == "link":
            self.end_link(element.attrs.get("href", ""))
        elif element.action == "list-item":
            self.close_item(element)
        elif tag in {"ul", "ol"}:
            self.close_list()
        elif tag == "table":
            self.end_table()
        elif tag == "tr":
            self.end_row()
        elif tag in {"td", "th"}:
            self.end_cell()
        elif tag in {"h1", "h2", "h3", "h4", "h5", "h6", "p", "div", "section"}:
            self.block()

    def end_code(self, language: str) -> None:
        # Dokka wraps signatures in a code block whose body is one long line of
        # spans; an empty one appears where a declaration has no visible symbol.
        body = "".join(self.sinks[-1]).strip()
        if not body:
            self.pop_sink()
            self.code_depth -= 1
            return
        super().end_code(language)


def convert_page(page_html: str, url: str, kind: str, asset_resolver: Callable[[str], str | None]) -> tuple[str, dict[str, int]]:
    converter = DocsConverter() if kind == "docs" else DokkaConverter()
    markdown = converter.run(page_html, url, asset_resolver)
    counts = {
        "headings": converter.headings,
        "code_blocks": converter.code_blocks,
        "tables": converter.markdown_tables,
    }
    return markdown, counts


def page_title(page_html: str) -> str:
    match = re.search(r"<title>(.*?)</title>", page_html, re.S | re.I)
    if not match:
        return ""
    title = html.unescape(collapse(match.group(1))).strip()
    return re.sub(r"\s*\|\s*(Kotlin Documentation|Kotlin)\s*$", "", title)


# ---------------------------------------------------------------------------
# Scope
# ---------------------------------------------------------------------------


class Target:
    __slots__ = ("url", "group", "kind", "title", "path", "section")

    def __init__(self, url: str, group: str, kind: str, title: str, path: Path, section: str = ""):
        self.url = url
        self.group = group
        self.kind = kind
        self.title = title
        self.path = path
        self.section = section


def page_output_path(out: Path, url: str) -> Path:
    path = urlparse(url).path
    if path.endswith("/"):
        return out / "pages" / path.strip("/") / "index.md"
    if path.endswith(".html"):
        return out / "pages" / (path.strip("/")[: -len(".html")] + ".md")
    return out / "pages" / (path.strip("/") + ".md")


def resolve_section(pages: dict[str, Any], top_level: list[str], titles: tuple[str, ...]) -> str:
    """Find a TOC node by its title path, so a renamed page cannot silently
    drop out of scope while a renamed section fails loudly instead."""

    candidates = list(top_level)
    node_id = ""
    for title in titles:
        for candidate in candidates:
            if pages[candidate]["title"] == title:
                node_id = candidate
                candidates = pages[candidate].get("pages", [])
                break
        else:
            raise RuntimeError(f"HelpTOC no longer has a section titled {' > '.join(titles)!r}")
    return node_id


def collect_docs_targets(out: Path, toc: dict[str, Any]) -> list[Target]:
    pages = toc["entities"]["pages"]
    top_level = toc["topLevelIds"]
    targets: list[Target] = []
    claimed: set[str] = set()

    for group, titles in SECTION_PATHS:
        root_id = resolve_section(pages, top_level, titles)
        section = " > ".join(titles)

        def walk(node_id: str) -> None:
            node = pages[node_id]
            url = node.get("url", "")
            if url and not url.startswith("http") and node_id not in claimed:
                claimed.add(node_id)
                absolute = f"{BASE_URL}/docs/{url}"
                targets.append(Target(absolute, group, "docs", node["title"], page_output_path(out, absolute), section))
            for child in node.get("pages", []):
                if child not in claimed or pages[child].get("pages"):
                    walk(child)

        walk(root_id)
    return targets


def parse_sitemap(xml: str) -> list[str]:
    return re.findall(r"<loc>([^<]+)</loc>", xml)


def collect_api_targets(out: Path, sitemaps: dict[str, str], full: bool) -> list[Target]:
    targets: list[Target] = []
    seen: set[str] = set()
    for url in sorted({url for xml in sitemaps.values() for url in parse_sitemap(xml)}):
        if not url.startswith(API_SITEMAP_PREFIXES):
            continue
        if not full and not url.endswith("/"):
            continue
        if url.endswith(("navigation.html", "all-types.html", "index-list.html")):
            continue
        if url in seen:
            continue
        segments = url[len(BASE_URL) :].strip("/").split("/")
        package = segments[3] if len(segments) > 3 else ""
        if any(package == name or package.startswith(name + ".") for name in API_PACKAGE_EXCLUDES):
            continue
        seen.add(url)
        section = "kotlin-stdlib" if "/kotlin-stdlib/" in url else "kotlinx.coroutines"
        title = url[len(BASE_URL) :].strip("/").split("/")[-1] or section
        targets.append(Target(url, "api", "api", title, page_output_path(out, url), section))
    return targets


# ---------------------------------------------------------------------------
# Fetching pages
# ---------------------------------------------------------------------------


class Mirror:
    def __init__(self, out: Path, http: Http, ledger: Ledger, args: argparse.Namespace):
        self.out = out
        self.http = http
        self.ledger = ledger
        self.args = args
        self.assets: dict[str, str] = {}
        self.asset_lock = threading.Lock()

    def is_fresh(self, url: str) -> bool:
        """Whether a mirrored page may be left alone without asking upstream.

        A conditional request still costs a full round trip, and this site sits
        behind a CDN where that is most of a second; revalidating a thousand
        pages on every run would cost more than it saves. Inside the window the
        request is skipped outright, which is what makes a repeat run finish in
        seconds. --max-age 0, --refresh and --check all bypass it."""

        if self.args.refresh or self.args.max_age <= 0:
            return False
        entry = self.ledger.get(url)
        if not entry or not (self.out / entry.get("path", "")).exists():
            return False
        checked = entry.get("checked_at") or entry.get("fetched_at")
        if not checked:
            return False
        try:
            age = time.time() - time.mktime(time.strptime(checked, "%Y-%m-%dT%H:%M:%SZ")) + time.timezone
        except ValueError:
            return False
        return 0 <= age < self.args.max_age * 3600

    def conditional_headers(self, url: str) -> dict[str, str]:
        if self.args.refresh:
            return {}
        entry = self.ledger.get(url)
        if not entry:
            return {}
        if not (self.out / entry.get("path", "")).exists():
            return {}
        headers = {}
        if entry.get("etag"):
            headers["If-None-Match"] = entry["etag"]
        if entry.get("last_modified"):
            headers["If-Modified-Since"] = entry["last_modified"]
        return headers

    def asset_resolver(self, markdown_path: Path) -> Callable[[str], str | None]:
        def resolve(url: str) -> str | None:
            parsed = urlparse(url)
            if parsed.netloc != urlparse(BASE_URL).netloc:
                return None
            if Path(parsed.path).suffix.lower() not in IMAGE_EXTENSIONS:
                return None
            local = "assets/" + parsed.path.strip("/")
            with self.asset_lock:
                self.assets[url.split("#")[0]] = local
            return Path(os.path.relpath(self.out / local, markdown_path.parent)).as_posix()

        return resolve

    def fetch_page(self, target: Target) -> dict[str, Any]:
        if self.is_fresh(target.url):
            return {"url": target.url, "status": "fresh", "path": self.ledger.get(target.url).get("path", "")}
        headers = self.conditional_headers(target.url)
        try:
            response = self.http.get(target.url, headers)
        except FetchFailure as error:
            self.ledger.fail(target.url, error)
            return {"url": target.url, "status": "error", "message": error.message, "code": error.status}
        if response.status == 304:
            entry = dict(self.ledger.get(target.url) or {})
            entry.update({"url": target.url, "checked_at": timestamp()})
            self.ledger.record(entry)
            return {"url": target.url, "status": "unchanged", "path": entry.get("path", "")}

        page_html = response.body.decode("utf-8", errors="replace")
        markdown, counts = convert_page(page_html, target.url, target.kind, self.asset_resolver(target.path))
        title = page_title(page_html) or target.title
        fetched = timestamp()
        provenance = f"> 来源: {target.url}  抓取: {fetched}"
        if response.last_modified:
            provenance += f"  上游更新: {response.last_modified}"
        body = f"{provenance}\n\n{markdown}"
        write_text(target.path, body)
        entry = {
            "url": target.url,
            "path": target.path.relative_to(self.out).as_posix(),
            "group": target.group,
            "kind": target.kind,
            "section": target.section,
            "title": title,
            "etag": response.etag,
            "last_modified": response.last_modified,
            "sha256": sha256(response.body),
            "source_bytes": len(response.body),
            "markdown_bytes": len(body.encode("utf-8")),
            "counts": counts,
            "fetched_at": fetched,
            "checked_at": fetched,
        }
        self.ledger.record(entry)
        return {"url": target.url, "status": "downloaded", "path": entry["path"]}

    def fetch_asset(self, url: str, local: str) -> dict[str, Any]:
        path = self.out / local
        if path.exists() and self.is_fresh(url):
            return {"url": url, "status": "fresh"}
        headers = self.conditional_headers(url)
        try:
            response = self.http.get(url, headers)
        except FetchFailure as error:
            self.ledger.fail(url, error)
            return {"url": url, "status": "error", "message": error.message, "code": error.status}
        if response.status == 304 and path.exists():
            return {"url": url, "status": "unchanged"}
        write_bytes(path, response.body)
        self.ledger.record(
            {
                "url": url,
                "path": local,
                "kind": "asset",
                "group": "asset",
                "etag": response.etag,
                "last_modified": response.last_modified,
                "sha256": sha256(response.body),
                "source_bytes": len(response.body),
                "fetched_at": timestamp(),
                "checked_at": timestamp(),
            }
        )
        return {"url": url, "status": "downloaded"}

    def run_pool(self, label: str, jobs: Iterable[Any], worker: Callable[[Any], dict[str, Any]]) -> list[dict[str, Any]]:
        jobs = list(jobs)
        results: list[dict[str, Any]] = []
        if not jobs:
            return results
        workers = max(1, min(MAX_WORKERS, self.args.workers))
        with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
            futures = [pool.submit(worker, job) for job in jobs]
            for index, future in enumerate(concurrent.futures.as_completed(futures), start=1):
                result = future.result()
                results.append(result)
                if index % 50 == 0 or index == len(jobs):
                    print(f"{label} {index}/{len(jobs)}", flush=True)
        return results


# ---------------------------------------------------------------------------
# INDEX.md
# ---------------------------------------------------------------------------


def prune(out: Path, targets: list[Target], ledger: Ledger) -> list[str]:
    """Drop mirrored pages that the current scope no longer claims.

    Without this a page stays behind after upstream deletes it or after the
    scope narrows, and it reads exactly like a page that is still current.
    Only Markdown under pages/ is touched, and only on a run that resolved the
    whole scope."""

    wanted = {target.path.resolve() for target in targets}
    wanted.add((out / "pages" / "INDEX.md").resolve())
    removed: list[str] = []
    for path in sorted((out / "pages").rglob("*.md")):
        if path.resolve() in wanted:
            continue
        relative = path.relative_to(out).as_posix()
        path.unlink()
        removed.append(relative)
    if removed:
        stale = [url for url, entry in ledger.entries.items() if entry.get("path") in removed]
        for url in stale:
            ledger.entries.pop(url, None)
        for parent in sorted((out / "pages").rglob("*"), reverse=True):
            if parent.is_dir() and not any(parent.iterdir()):
                parent.rmdir()
    return removed


def write_index(out: Path, targets: list[Target], entries: dict[str, dict[str, Any]], kotlin_version: str) -> None:
    lines = [
        "# Kotlin 文档镜像索引",
        "",
        f"对应 Kotlin {kotlin_version}，生成于 {timestamp()}。",
        "",
        "先在 `pages/` 下 grep 关键词，命中后读该文件头部的来源 URL 回到官方页面核对。",
        "",
    ]
    by_group: dict[str, list[Target]] = {group: [] for group in GROUP_ORDER}
    for target in targets:
        if target.url in entries or target.path.exists():
            by_group[target.group].append(target)

    for group in GROUP_ORDER:
        group_targets = by_group[group]
        if not group_targets:
            continue
        lines.append(f"## {GROUP_TITLES[group]}（{len(group_targets)} 页）")
        lines.append("")
        if group == "api":
            lines.append("Dokka 生成的 API 参考，此处只列出包索引；类型与成员页在同一目录下。")
            lines.append("")
            package_targets = [
                target
                for target in group_targets
                if len(urlparse(target.url).path.strip("/").split("/")) == 4
            ]
            for target in sorted(package_targets, key=lambda item: item.url):
                relative = target.path.relative_to(out / "pages").as_posix()
                lines.append(f"- [{target.title}]({relative}) — {target.section}")
        else:
            for target in group_targets:
                relative = target.path.relative_to(out / "pages").as_posix()
                title = entries.get(target.url, {}).get("title") or target.title
                lines.append(f"- [{title}]({relative})")
        lines.append("")
    write_text(out / "pages" / "INDEX.md", "\n".join(lines).rstrip() + "\n")


# ---------------------------------------------------------------------------
# Modes
# ---------------------------------------------------------------------------


def load_scope(out: Path, http: Http, args: argparse.Namespace, metadata: Path, persist: bool) -> tuple[list[Target], str, dict[str, Any]]:
    def document(url: str, name: str) -> bytes:
        """Scope inputs are re-read from metadata/ while the copy there is
        younger than the freshness window, so a repeat run issues no requests
        at all before it can decide there is nothing to do."""

        path = metadata / name
        if not args.refresh and args.max_age > 0 and path.exists():
            age = time.time() - path.stat().st_mtime
            if 0 <= age < args.max_age * 3600:
                return path.read_bytes()
        raw = http.get(url).body
        if persist:
            write_bytes(path, raw)
        return raw

    toc_raw = document(TOC_URL, "HelpTOC.json")
    config_raw = document(CONFIG_URL, "config.json")
    document(ROBOTS_URL, "robots.txt")

    toc = json.loads(toc_raw)
    config = json.loads(config_raw)
    version_match = re.search(r"/tag/v([0-9][^\"/]*)", config.get("productWebUrl", ""))
    kotlin_version = version_match.group(1) if version_match else "unknown"

    targets = collect_docs_targets(out, toc)
    if not args.no_api:
        sitemaps = {}
        for name, url in (("core", CORE_SITEMAP_URL), ("coroutines", COROUTINES_SITEMAP_URL)):
            sitemaps[name] = document(url, f"sitemap-{name}.xml").decode("utf-8", errors="replace")
        targets += collect_api_targets(out, sitemaps, args.full_api)
    return targets, kotlin_version, config


def run_check(out: Path, http: Http, ledger: Ledger, args: argparse.Namespace, metadata: Path) -> int:
    targets, kotlin_version, _ = load_scope(out, http, args, metadata, persist=False)
    known = [target for target in targets if ledger.get(target.url)]
    unknown = [target for target in targets if not ledger.get(target.url)]
    changed: list[str] = []
    failed: list[str] = []

    def probe(target: Target) -> dict[str, Any]:
        entry = ledger.get(target.url) or {}
        headers = {}
        if entry.get("etag"):
            headers["If-None-Match"] = entry["etag"]
        if entry.get("last_modified"):
            headers["If-Modified-Since"] = entry["last_modified"]
        try:
            response = http.get(target.url, headers)
        except FetchFailure as error:
            return {"url": target.url, "status": "error", "message": error.message}
        return {"url": target.url, "status": "unchanged" if response.status == 304 else "changed"}

    workers = max(1, min(MAX_WORKERS, args.workers))
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        for result in pool.map(probe, known):
            if result["status"] == "changed":
                changed.append(result["url"])
            elif result["status"] == "error":
                failed.append(result["url"])

    print(f"Kotlin {kotlin_version}，范围内 {len(targets)} 页，已镜像 {len(known)} 页")
    print(f"上游有变动: {len(changed)}，未镜像: {len(unknown)}，探测失败: {len(failed)}")
    for url in sorted(changed)[:60]:
        print(f"  changed  {url}")
    for url in sorted(target.url for target in unknown)[:60]:
        print(f"  missing  {url}")
    for url in sorted(failed)[:20]:
        print(f"  error    {url}")
    return 0 if not failed else 1


SOURCE_CODE_PATTERN = re.compile(r"<div[^>]+class=\"[^\"]*code-block[^\"]*\"|<pre[\s>]", re.I)
SOURCE_HEADING_PATTERN = re.compile(r"<h[1-6][\s>]", re.I)
SOURCE_TABLE_PATTERN = re.compile(r"<table[\s>]", re.I)
SOURCE_ROW_PATTERN = re.compile(r"<div class=\"table-row table-row_content\"")
SOURCE_ROW_LINK_PATTERN = re.compile(r"<div class=\"main-subrow[^\"]*\"[^>]*>.*?<a href=\"([^\"]+)\"", re.S)
SOURCE_SECTION_PATTERN = re.compile(r"<h[1-6][^>]+class=\"(?:cover|tableheader)\"[^>]*>(.*?)</h[1-6]>", re.I | re.S)

# Metrics are per page kind because the two page kinds are not comparable in the
# same units. A guide page must match its source element for element. A Dokka
# page cannot: it renders one declaration once per source set and the mirror
# keeps a single copy, so counting raw signature blocks would report a shortfall
# on every API page for ever. What matters there is that no declaration and no
# section went missing, which is what these two measure.
DOCS_METRICS = ("code_blocks", "headings", "tables")
API_METRICS = ("members", "sections")
METRIC_LABELS = {
    "code_blocks": "代码块",
    "headings": "标题",
    "tables": "表格",
    "members": "成员",
    "sections": "小节",
}


def strip_tags(value: str) -> str:
    return collapse(html.unescape(re.sub(r"<[^>]+>", "", value))).strip()


def api_members(source: str, base_url: str) -> set[str]:
    """Every declaration the page lists, by its own link target.

    Counted as a set because Dokka repeats a row when several source sets
    declare the same member, and a repeat carries nothing the first one did
    not."""

    members: set[str] = set()
    for row in SOURCE_ROW_PATTERN.split(source)[1:]:
        match = SOURCE_ROW_LINK_PATTERN.search(row)
        if match:
            members.add(urljoin(base_url, match.group(1)))
    return members


def api_sections(source: str) -> set[str]:
    return {text for text in (strip_tags(match) for match in SOURCE_SECTION_PATTERN.findall(source)) if text}


def markdown_headings(markdown: str) -> set[str]:
    headings = re.findall(r"(?m)^[ \t>]*#{1,6} +(.+?)\s*$", markdown)
    return {re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text).strip() for text in headings}


def content_slice(page_html: str, kind: str) -> str:
    if kind == "docs":
        start = page_html.find('<article class="article"')
        end = page_html.find("</article>", start + 1)
    else:
        start = page_html.find('<div class="main-content"')
        end = page_html.find('<div class="footer', start + 1)
    if start < 0:
        return ""
    return page_html[start : end if end > start else len(page_html)]


def run_verify(out: Path, http: Http, ledger: Ledger, args: argparse.Namespace, metadata: Path) -> int:
    mirrored = [entry for entry in ledger.entries.values() if entry.get("kind") in {"docs", "api"} and entry.get("path")]
    if not mirrored:
        print("镜像为空，先执行一次完整抓取", file=sys.stderr)
        return 1
    random.seed(args.seed)
    # Stratified, because the API reference outnumbers the guide pages nine to
    # one and a flat sample would almost never look at a guide page, which is
    # where the harder conversion rules live.
    buckets: dict[str, list[dict[str, Any]]] = {}
    for entry in mirrored:
        buckets.setdefault(entry.get("group", "other"), []).append(entry)
    sample: list[dict[str, Any]] = []
    quota = max(1, args.sample // max(1, len(buckets)))
    for group in sorted(buckets):
        sample += random.sample(buckets[group], min(quota, len(buckets[group])))
    remainder = [entry for entry in mirrored if entry not in sample]
    if len(sample) < args.sample and remainder:
        sample += random.sample(remainder, min(args.sample - len(sample), len(remainder)))
    report: list[dict[str, Any]] = []

    def check(entry: dict[str, Any]) -> dict[str, Any]:
        path = out / entry["path"]
        if not path.exists():
            return {"url": entry["url"], "status": "missing-markdown"}
        try:
            response = http.get(entry["url"], {})
        except FetchFailure as error:
            return {"url": entry["url"], "status": "error", "message": error.message}
        page_html = response.body.decode("utf-8", errors="replace")
        source = content_slice(page_html, entry["kind"])
        # Counted from the rendered Markdown rather than from the ledger, so a
        # stale render is caught as well.
        markdown = path.read_text(encoding="utf-8")
        if entry["kind"] == "docs":
            # A fence is indented inside a list item and prefixed with "> "
            # inside a callout, so anchoring at column 0 would undercount
            # exactly the pages that nest code in steps or in tips.
            found = {
                "code_blocks": len(re.findall(r"(?m)^[ \t>]*```", markdown)) // 2,
                "headings": len(re.findall(r"(?m)^[ \t>]*#{1,6} ", markdown)),
                "tables": len(re.findall(r"(?m)^[ \t>]*\|\s*---", markdown)),
            }
            expected = {
                "code_blocks": len(SOURCE_CODE_PATTERN.findall(source)),
                "headings": len(SOURCE_HEADING_PATTERN.findall(source)),
                "tables": len(SOURCE_TABLE_PATTERN.findall(source)),
            }
            missing_detail: dict[str, list[str]] = {}
        else:
            members = api_members(source, entry["url"])
            sections = api_sections(source)
            headings = markdown_headings(markdown)
            absent_members = sorted(url for url in members if url not in markdown)
            absent_sections = sorted(text for text in sections if text not in headings)
            expected = {"members": len(members), "sections": len(sections)}
            found = {
                "members": len(members) - len(absent_members),
                "sections": len(sections) - len(absent_sections),
            }
            missing_detail = {"members": absent_members[:10], "sections": absent_sections[:10]}
        return {
            "url": entry["url"],
            "kind": entry["kind"],
            "group": entry.get("group", "other"),
            "path": entry["path"],
            "status": "checked",
            "expected": expected,
            "found": found,
            "missing": {key: value for key, value in missing_detail.items() if value},
            "signatures": len(re.findall(r"(?m)^[ \t>]*```", markdown)) // 2,
        }

    workers = max(1, min(MAX_WORKERS, args.workers))
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        report = list(pool.map(check, sample))

    def shortfall(item: dict[str, Any]) -> int:
        if item["status"] != "checked":
            return 1
        metrics = DOCS_METRICS if item["kind"] == "docs" else API_METRICS
        return sum(max(0, item["expected"][key] - item["found"][key]) for key in metrics)

    def render_section(kind: str, title: str, metrics: tuple[str, ...]) -> None:
        rows = [item for item in report if item.get("kind") == kind and item["status"] == "checked"]
        if not rows:
            return
        print()
        print(f"{title}（样本 {len(rows)} 页）")
        header = f"  {'页面':<52}" + "".join(f"{METRIC_LABELS[key]:>12}" for key in metrics)
        print(header)
        for item in sorted(rows, key=lambda record: record["path"]):
            name = item["path"]
            if len(name) > 50:
                name = "..." + name[-47:]
            cells = "".join(f"{str(item['found'][key]) + '/' + str(item['expected'][key]):>12}" for key in metrics)
            flag = "  ×" if shortfall(item) else ""
            print(f"  {name:<52}{cells}{flag}")

    render_section("docs", "指南页", DOCS_METRICS)
    render_section("api", "API 页", API_METRICS)

    failed = [item for item in report if item["status"] != "checked"]
    if failed:
        print()
        print("未能对照:")
        for item in failed:
            print(f"  {item['url']}  {item['status']}")

    print()
    print(f"{'组':<12}{'样本':>6}{'完全匹配页':>12}{'条目':>16}{'匹配率':>10}")
    groups: dict[str, list[dict[str, Any]]] = {}
    for item in report:
        groups.setdefault(item.get("group", "other"), []).append(item)
    total_expected = total_found = 0
    for group in GROUP_ORDER:
        items = groups.get(group) or []
        if not items:
            continue
        metrics = DOCS_METRICS if group != "api" else API_METRICS
        checked = [item for item in items if item["status"] == "checked"]
        expected = sum(item["expected"][key] for item in checked for key in metrics)
        found = sum(item["found"][key] for item in checked for key in metrics)
        total_expected += expected
        total_found += found
        clean = len([item for item in items if not shortfall(item)])
        rate = 100.0 * found / expected if expected else 100.0
        label = GROUP_TITLES.get(group, group)
        print(f"{label:<12}{len(items):>6}{f'{clean}/{len(items)}':>12}{f'{found}/{expected}':>16}{rate:>9.1f}%")

    worst = sorted((item for item in report if shortfall(item)), key=shortfall, reverse=True)[:5]
    if worst:
        print()
        print("缺口最大的页面:")
        for item in worst:
            detail = "、".join(f"{METRIC_LABELS[key]} {len(value)}" for key, value in item.get("missing", {}).items())
            print(f"  {item.get('path', item['url'])}  少 {shortfall(item)} 项{'（' + detail + '）' if detail else ''}")

    passed = not worst
    overall = 100.0 * total_found / total_expected if total_expected else 100.0
    print()
    print("指南页按元素逐项对照，API 页对照声明与小节是否齐全，两者都要求零缺口。")
    print(f"整体匹配 {total_found}/{total_expected}（{overall:.1f}%），结论: {'通过' if passed else '未通过'}")
    write_json(
        metadata / "verify.json",
        {
            "generated_at": timestamp(),
            "passed": passed,
            "matched": total_found,
            "expected": total_expected,
            "sample": report,
        },
    )
    print("完整数据见 metadata/verify.json")
    return 0 if passed else 1


def tally(results: list[dict[str, Any]], status: str) -> int:
    return len([item for item in results if item.get("status") == status])


def run_mirror(out: Path, http: Http, ledger: Ledger, args: argparse.Namespace, metadata: Path) -> int:
    started = time.time()
    targets, kotlin_version, config = load_scope(out, http, args, metadata, persist=True)
    mirror = Mirror(out, http, ledger, args)

    # A failure recorded against a URL that has since left the scope would keep
    # the exit code non-zero for good, so the ledger is trimmed to the scope
    # before anything is fetched. Asset failures are keyed by image URL and are
    # not in the target list.
    in_scope = {target.url for target in targets}
    for url in [key for key in ledger.failures if key not in in_scope and not key.endswith(tuple(IMAGE_EXTENSIONS))]:
        ledger.failures.pop(url)

    print(f"范围内 {len(targets)} 页（Kotlin {kotlin_version}）", flush=True)
    page_results = mirror.run_pool("pages", targets, mirror.fetch_page)

    asset_results: list[dict[str, Any]] = []
    if not args.no_assets and mirror.assets:
        asset_results = mirror.run_pool("assets", list(mirror.assets.items()), lambda item: mirror.fetch_asset(*item))

    removed = prune(out, targets, ledger) if not args.no_api else []
    if removed:
        print(f"清理了 {len(removed)} 个不再在范围内的页面", flush=True)

    entries = ledger.entries
    write_index(out, targets, entries, kotlin_version)

    by_group: dict[str, int] = {}
    for target in targets:
        if target.url in entries and (out / entries[target.url].get("path", "")).exists():
            by_group[target.group] = by_group.get(target.group, 0) + 1

    page_failures = [url for url in ledger.failures if not url.endswith(tuple(IMAGE_EXTENSIONS))]
    asset_failures = [url for url in ledger.failures if url.endswith(tuple(IMAGE_EXTENSIONS))]
    # Assets are discovered while converting a page, so a run that converted
    # nothing discovers none; the standing total is counted off disk instead.
    assets_root = out / "assets"
    mirrored_assets = [path for path in assets_root.rglob("*") if path.is_file()] if assets_root.exists() else []
    manifest = {
        "source": BASE_URL,
        "generated_at": timestamp(),
        "kotlin_version": kotlin_version,
        "docs_build": config.get("productWebUrl", ""),
        "elapsed_seconds": round(time.time() - started, 1),
        "requests": http.request_count,
        "downloaded_bytes": http.downloaded_bytes,
        "scope": {
            "included": {
                "语言参考": "HelpTOC 的 Language guide 一节，去掉 Concurrency",
                "协程": "HelpTOC 的 Language guide > Concurrency 与 Library guides > Coroutines",
                "标准库主题": "HelpTOC 的 Library guides > Standard library",
                "API 参考": "Dokka 站点 /api/core/kotlin-stdlib/ 与 /api/kotlinx.coroutines/"
                + ("，含成员页" if args.full_api else "，只含包与类型索引页"),
            },
            "excluded": [
                "Kotlin Multiplatform、Native、JS、Wasm 的全部文档",
                "Compose Multiplatform 与 Ktor",
                "kotlinx.serialization、Lincheck、kotlin-metadata-jvm 库指南",
                "Gradle/Maven 构建、编译器与编译器插件、KSP",
                "教程、Koans、书籍、社区、基金会等学习与社区页",
                "版本发布公告与兼容性归档",
                "api/core 下的 kotlin-test 与 kotlin-reflect",
            ],
            "robots": "kotlinlang.org/robots.txt 没有任何 Disallow 规则，只声明 sitemap",
        },
        "counts": {
            "targets": len(targets),
            "pages_by_group": {GROUP_TITLES[group]: by_group.get(group, 0) for group in GROUP_ORDER if by_group.get(group)},
            "downloaded": tally(page_results, "downloaded"),
            "revalidated": tally(page_results, "unchanged"),
            "skipped_as_fresh": tally(page_results, "fresh"),
            "assets_downloaded": tally(asset_results, "downloaded"),
            "assets_mirrored": len(mirrored_assets),
            "pruned": len(removed),
            "page_failures": len(page_failures),
            "asset_failures": len(asset_failures),
        },
        "notes": [
            "生成内容通过 .git/info/exclude 排除，只有 README.md 与 refresh.py 进 Git。",
            "Dokka 页面按源集去重，保留 Dokka 标记为活动的第一份（存在 common 时即 common）。",
            "文档描述语言与库的意图；某个 API 在本项目钉住的版本里是否存在以构件为准。",
        ],
    }
    write_json(out / "manifest.json", manifest)
    print(json.dumps(manifest["counts"], ensure_ascii=False, indent=2))
    if ledger.failures:
        print(f"失败 {len(ledger.failures)} 项，见 metadata/failures.json", file=sys.stderr)
    return 0 if not ledger.failures else 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT, help="镜像输出目录")
    parser.add_argument("--workers", type=int, default=6, help=f"并发请求数，上限 {MAX_WORKERS}")
    parser.add_argument("--no-assets", action="store_true", help="不下载正文引用的图片")
    parser.add_argument("--no-api", action="store_true", help="跳过 Dokka API 参考")
    parser.add_argument("--full-api", action="store_true", help="API 参考连成员页一并抓取")
    parser.add_argument("--refresh", action="store_true", help="忽略缓存，重新下载全部内容")
    parser.add_argument("--max-age", type=float, default=24.0, help="镜像在多少小时内视为新鲜、不再询问上游；0 表示每页都发条件请求")
    parser.add_argument("--check", action="store_true", help="只发条件请求，报告上游哪些页变过，不写文件")
    parser.add_argument("--verify", action="store_true", help="抽样对照源 HTML，输出保真度报告")
    parser.add_argument("--sample", type=int, default=12, help="--verify 的抽样页数")
    parser.add_argument("--seed", type=int, default=0, help="--verify 的抽样随机种子")
    args = parser.parse_args()

    if args.workers > MAX_WORKERS:
        print(f"--workers 上限为 {MAX_WORKERS}", file=sys.stderr)
        return 2

    out = args.out.resolve()
    metadata = out / "metadata"
    out.mkdir(parents=True, exist_ok=True)
    metadata.mkdir(parents=True, exist_ok=True)

    http = Http()
    ledger = Ledger(metadata, read_only=args.check)
    try:
        if args.check:
            return run_check(out, http, ledger, args, metadata)
        if args.verify:
            return run_verify(out, http, ledger, args, metadata)
        ledger.open()
        return run_mirror(out, http, ledger, args, metadata)
    finally:
        if not args.check:
            ledger.close()
        http.close()


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print("interrupted", file=sys.stderr)
        raise SystemExit(130)
