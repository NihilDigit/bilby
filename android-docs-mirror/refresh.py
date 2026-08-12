#!/usr/bin/env python3
"""Mirror the Android developer documentation pages this project depends on.

developer.android.com is server-rendered devsite HTML, so this crawler works
from the rendered page instead of a content API: it takes the article body for
the Markdown, and the book navigation plus in-body links for discovery. Scope is
a fixed set of path prefixes, and every request is checked against the live
robots.txt before it is issued.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import gzip
import hashlib
import html
import json
import mimetypes
import os
import re
import sys
import threading
import time
from html.parser import HTMLParser
from pathlib import Path
from typing import Any, Iterable
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urldefrag, urljoin, urlparse, urlsplit, urlunsplit
from urllib.request import Request, urlopen


BASE_URL = "https://developer.android.com"
DEFAULT_OUT = Path(__file__).resolve().parent
USER_AGENT = "Bilby-AndroidDocsMirror/0.1 (+local documentation mirror)"

# Group name -> (seed paths, scope prefixes). A prefix also matches the path
# with its trailing slash removed, so "/media/media3/" covers the section index.
GROUPS: dict[str, dict[str, tuple[str, ...]]] = {
    "media3-guide": {
        "seeds": ("/media/media3", "/media/media3/exoplayer/hello-world"),
        "prefixes": ("/media/media3/",),
    },
    "media-implement": {
        "seeds": ("/media/implement/playback-app",),
        "prefixes": ("/media/implement/",),
    },
    "media3-reference": {
        "seeds": (
            "/reference/androidx/media3/common/package-summary",
            "/reference/androidx/media3/exoplayer/package-summary",
            "/reference/androidx/media3/session/package-summary",
            "/reference/androidx/media3/ui/package-summary",
            "/reference/androidx/media3/datasource/package-summary",
        ),
        "prefixes": (
            "/reference/androidx/media3/common/",
            "/reference/androidx/media3/exoplayer/",
            "/reference/androidx/media3/session/",
            "/reference/androidx/media3/ui/",
            "/reference/androidx/media3/datasource/",
        ),
    },
    "compose": {
        "seeds": ("/develop/ui/compose/documentation",),
        "prefixes": ("/develop/ui/compose/",),
    },
    "navigation-3": {
        "seeds": ("/guide/navigation/navigation-3",),
        "prefixes": ("/guide/navigation/navigation-3/",),
    },
    "background-work": {
        "seeds": ("/develop/background-work", "/develop/ui/views/notifications"),
        "prefixes": ("/develop/background-work/", "/develop/ui/views/notifications/"),
    },
}

UPSTREAM_FILES = (
    (
        "RELEASENOTES.md",
        "https://raw.githubusercontent.com/androidx/media/release/RELEASENOTES.md",
    ),
    # The session library has no api/current.txt of its own; the repository keeps
    # one signature file at the root covering every androidx.media3 package.
    (
        "media-api.txt",
        "https://raw.githubusercontent.com/androidx/media/release/api.txt",
    ),
)

ASSET_HOSTS = {
    "developer.android.com",
    "developers.google.com",
    "www.gstatic.com",
    "storage.googleapis.com",
    "lh3.googleusercontent.com",
}
IMAGE_EXTENSIONS = {".apng", ".avif", ".gif", ".ico", ".jpeg", ".jpg", ".png", ".svg", ".webp"}

VOID_TAGS = {
    "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta",
    "param", "source", "track", "wbr",
}
DROP_TAGS = {
    "script", "style", "template", "noscript", "svg", "form", "button", "iframe",
    "devsite-toc", "devsite-feedback", "devsite-page-rating", "devsite-thumb-rating",
    "devsite-bookmark", "devsite-content-footer", "devsite-actions",
    "devsite-feature-tooltip", "devsite-recommendations",
}
DROP_CLASSES = {
    "nocontent", "devsite-article-meta", "devsite-banner", "devsite-page-bookmark",
    "devsite-content-footer", "devsite-article-meta-updated", "devsite-github-link",
}
INLINE_TAGS = {
    "a", "abbr", "b", "bdi", "bdo", "cite", "code", "data", "del", "dfn", "em",
    "i", "img", "ins", "kbd", "mark", "q", "s", "samp", "small", "span", "strong",
    "sub", "sup", "time", "u", "var", "wbr", "br", "devsite-nowrap",
}
HEADING_TAGS = {"h1": 1, "h2": 2, "h3": 3, "h4": 4, "h5": 5, "h6": 6}


def request_url(url: str) -> str:
    """Percent-encode what http.client refuses to send. Some image filenames on
    the site contain spaces, and an unencoded space aborts the request."""

    parts = urlsplit(url)
    return urlunsplit(parts._replace(path=quote(parts.path, safe="/%:@&=+$,~()!*';")))


class FetchFailure(RuntimeError):
    def __init__(self, url: str, message: str, *, status: int | None = None, attempts: int = 1):
        super().__init__(f"{url}: {message}")
        self.url = url
        self.message = message
        self.status = status
        self.attempts = attempts


class Response:
    __slots__ = ("status", "data", "headers", "attempts")

    def __init__(self, status: int, data: bytes, headers: dict[str, str], attempts: int):
        self.status = status
        self.data = data
        self.headers = headers
        self.attempts = attempts

    @property
    def unchanged(self) -> bool:
        return self.status == 304

    def validators(self) -> dict[str, str]:
        return {
            key: self.headers[key]
            for key in ("etag", "last-modified")
            if self.headers.get(key)
        }


def fetch(
    url: str,
    *,
    accept: str = "*/*",
    attempts: int = 4,
    validators: dict[str, str] | None = None,
) -> Response:
    """Fetch a public resource with bounded retries and a polite user agent.

    `validators` turns the request conditional: a page that has not changed
    comes back as 304 with no body, which is what makes a refresh minutes
    rather than an hour."""

    last_error: Exception | None = None
    for attempt in range(1, attempts + 1):
        # Reference pages are around a megabyte of HTML each; without gzip the
        # crawl is bandwidth-bound long before it is latency-bound.
        headers = {"Accept": accept, "Accept-Encoding": "gzip", "User-Agent": USER_AGENT}
        if validators:
            if validators.get("etag"):
                headers["If-None-Match"] = validators["etag"]
            if validators.get("last-modified"):
                headers["If-Modified-Since"] = validators["last-modified"]
        try:
            with urlopen(Request(request_url(url), headers=headers), timeout=60) as response:
                received = {key.lower(): value for key, value in response.headers.items()}
                data = response.read()
                if received.get("content-encoding", "").lower() == "gzip":
                    data = gzip.decompress(data)
                return Response(response.status, data, received, attempt)
        except HTTPError as error:
            last_error = error
            if error.code == 304:
                received = {key.lower(): value for key, value in error.headers.items()}
                return Response(304, b"", received, attempt)
            if error.code not in {408, 425, 429, 500, 502, 503, 504}:
                raise FetchFailure(url, f"HTTP {error.code}", status=error.code, attempts=attempt) from error
            retry_after = error.headers.get("Retry-After")
            try:
                delay = min(30.0, float(retry_after)) if retry_after else 2.0**attempt
            except (TypeError, ValueError):
                delay = 2.0**attempt
            time.sleep(delay)
        except (URLError, TimeoutError, OSError) as error:
            last_error = error
            time.sleep(min(30.0, 2.0**attempt))
    status = getattr(last_error, "code", None)
    raise FetchFailure(url, str(last_error or "unknown fetch error"), status=status, attempts=attempts)


def write_bytes(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".part")
    temporary.write_bytes(data)
    temporary.replace(path)


def write_text(path: Path, value: str) -> None:
    write_bytes(path, value.encode("utf-8"))


def write_json(path: Path, value: Any) -> None:
    write_text(path, json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class Robots:
    """The `User-agent: *` group of a robots.txt, matched longest rule first."""

    def __init__(self, text: str):
        self.allow: list[str] = []
        self.disallow: list[str] = []
        applies = False
        for raw_line in text.splitlines():
            line = raw_line.split("#", 1)[0].strip()
            if not line or ":" not in line:
                continue
            field, _, value = line.partition(":")
            field = field.strip().lower()
            value = value.strip()
            if field == "user-agent":
                applies = value == "*"
            elif applies and field == "allow" and value:
                self.allow.append(value)
            elif applies and field == "disallow" and value:
                self.disallow.append(value)

    def allows(self, path: str) -> bool:
        best_allow = max((rule for rule in self.allow if path.startswith(rule)), key=len, default="")
        best_deny = max((rule for rule in self.disallow if path.startswith(rule)), key=len, default="")
        if not best_deny:
            return True
        return len(best_allow) >= len(best_deny)


class Node:
    __slots__ = ("tag", "attrs", "children", "parent")

    def __init__(self, tag: str, attrs: dict[str, str] | None = None, parent: "Node | None" = None):
        self.tag = tag
        self.attrs = attrs or {}
        self.children: list[Any] = []
        self.parent = parent

    def classes(self) -> set[str]:
        return set((self.attrs.get("class") or "").split())

    def find(self, predicate) -> "Node | None":
        for child in self.children:
            if isinstance(child, Node):
                if predicate(child):
                    return child
                found = child.find(predicate)
                if found is not None:
                    return found
        return None

    def iter_nodes(self) -> Iterable["Node"]:
        for child in self.children:
            if isinstance(child, Node):
                yield child
                yield from child.iter_nodes()

    def text(self) -> str:
        parts: list[str] = []
        for child in self.children:
            if isinstance(child, str):
                parts.append(child)
            elif child.tag == "br":
                parts.append("\n")
            else:
                parts.append(child.text())
        return "".join(parts)


class DomBuilder(HTMLParser):
    """Tolerant tree builder: devsite HTML is regular, but `p` and `li` do get
    left open, and an unmatched end tag must not unwind the whole stack."""

    AUTO_CLOSE = {
        "li": {"li"},
        "td": {"td", "th", "p"},
        "th": {"td", "th", "p"},
        "tr": {"td", "th", "tr", "p"},
        "dt": {"dt", "dd", "p"},
        "dd": {"dt", "dd", "p"},
        "p": {"p"},
    }
    BLOCK_CLOSES_P = {
        "div", "ul", "ol", "table", "pre", "section", "aside", "blockquote",
        "h1", "h2", "h3", "h4", "h5", "h6", "hr", "dl", "figure",
    }

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.root = Node("#root")
        self.stack = [self.root]

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        tag = tag.lower()
        closing = self.AUTO_CLOSE.get(tag) or ({"p"} if tag in self.BLOCK_CLOSES_P else set())
        while len(self.stack) > 1 and self.stack[-1].tag in closing:
            self.stack.pop()
        node = Node(tag, {key.lower(): (value or "") for key, value in attrs}, self.stack[-1])
        self.stack[-1].children.append(node)
        if tag not in VOID_TAGS:
            self.stack.append(node)

    def handle_startendtag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        tag = tag.lower()
        node = Node(tag, {key.lower(): (value or "") for key, value in attrs}, self.stack[-1])
        self.stack[-1].children.append(node)

    def handle_endtag(self, tag: str) -> None:
        tag = tag.lower()
        if tag in VOID_TAGS:
            return
        for index in range(len(self.stack) - 1, 0, -1):
            if self.stack[index].tag == tag:
                del self.stack[index:]
                return

    def handle_data(self, data: str) -> None:
        self.stack[-1].children.append(data)


BODY_404 = re.compile(r"<body[^>]*\btemplate=\"404\"", re.I)
HREF = re.compile(r"<a\b[^>]*?\shref=\"([^\"]*)\"", re.I)
IMG_TAG = re.compile(r"<img\b[^>]*>", re.I)
IMG_SRC = re.compile(r"\s(?:src|data-src)=\"([^\"]*)\"|\ssrcset=\"([^\"]*)\"", re.I)


def nav_and_article(markup: str) -> tuple[str | None, str | None]:
    """Slice out the book navigation and the article body. Discovery only needs
    these two regions, and slicing is far cheaper than building the tree."""

    nav = None
    start = markup.find('<nav class="devsite-book-nav')
    if start != -1:
        end = markup.find("</nav>", start)
        nav = markup[start : end if end != -1 else len(markup)]
    marker = markup.find("devsite-article-body")
    if marker == -1:
        return nav, None
    start = markup.rfind("<div", 0, marker)
    end = markup.find("</article>", marker)
    return nav, markup[start if start != -1 else marker : end if end != -1 else len(markup)]


def page_title(markup: str) -> str:
    """Guide pages keep the h1 above the article body, reference pages inside
    it, so the title is read from the first h1 in the whole document."""

    start = markup.find("<h1")
    if start == -1:
        return ""
    end = markup.find("</h1>", start)
    fragment = markup[start : end + 5 if end != -1 else len(markup)]
    heading = parse_html(fragment).find(lambda node: node.tag == "h1")
    return collapse(visible_text(heading)).strip() if heading else ""


def image_source(node: Node) -> str:
    source = node.attrs.get("src") or node.attrs.get("data-src") or ""
    if not source and node.attrs.get("srcset"):
        source = node.attrs["srcset"].split(",")[0].strip().split(" ")[0]
    return source


def dropped(node: Node) -> bool:
    """Chrome that devsite injects into the article: the bookmark widget inside
    the h1, the Kotlin/Java view switcher, the GitHub link inside code samples."""

    if node.tag in DROP_TAGS or node.classes() & DROP_CLASSES:
        return True
    return "data-nosnippet" in node.attrs or "hidden" in node.attrs


def visible_text(node: Node) -> str:
    parts: list[str] = []
    for child in node.children:
        if isinstance(child, str):
            parts.append(child)
        elif child.tag == "br":
            parts.append("\n")
        elif not dropped(child):
            parts.append(visible_text(child))
    return "".join(parts)


def parse_html(markup: str) -> Node:
    builder = DomBuilder()
    builder.feed(markup)
    builder.close()
    return builder.root


def collapse(value: str) -> str:
    return re.sub(r"[ \t ]+", " ", value.replace("​", "")).replace("\n", " ")


def escape_inline(value: str) -> str:
    return value.replace("|", "\\|")


def clean_markdown(value: str) -> str:
    value = value.replace("\r\n", "\n").replace("\r", "\n")
    value = re.sub(r"[ \t]+\n", "\n", value)
    value = re.sub(r"\n{3,}", "\n\n", value)
    return value.strip() + "\n"


def code_language(node: Node) -> str:
    syntax = (node.attrs.get("syntax") or "").strip().lower()
    if syntax:
        return {"c++": "cpp", "shell": "shell", "none": ""}.get(syntax, syntax)
    classes = node.classes()
    for name in classes:
        if name.startswith("lang-"):
            return name[5:].lower()
    if "api-signature" in classes:
        return "java"
    return ""


class Renderer:
    """Renders one devsite article body into Markdown.

    `resolve_link` and `resolve_image` are supplied by the crawler so that links
    inside the mirror become relative paths and everything else stays absolute.
    """

    def __init__(self, resolve_link, resolve_image):
        self.resolve_link = resolve_link
        self.resolve_image = resolve_image
        self.selector_depth = 0
        self.code_source: tuple[str, str] | None = None

    # -- inline ---------------------------------------------------------------

    def inline(self, node: Node) -> str:
        parts: list[str] = []
        for child in node.children:
            if isinstance(child, str):
                parts.append(collapse(child))
                continue
            parts.append(self.inline_node(child))
        return "".join(parts)

    def inline_node(self, node: Node) -> str:
        tag = node.tag
        if dropped(node):
            return ""
        if tag == "br":
            return " "
        if "expand-control" in node.classes():
            # "From androidx.media3.common.Player", the heading of one inherited
            # member group.
            inner = self.inline(node).strip()
            return f"**{inner}**" if inner else ""
        if tag == "img":
            return self.image(node)
        if tag in {"code", "kbd", "samp"}:
            text = collapse(visible_text(node)).strip()
            if not text:
                return ""
            fence = "`" * (max((len(run) for run in re.findall(r"`+", text)), default=0) + 1)
            padding = " " if text.startswith("`") or text.endswith("`") else ""
            literal = f"{fence}{padding}{text}{padding}{fence}"
            # Reference pages link the type inside the code span rather than
            # around it, so the link is lost unless it is pulled back out. A
            # signature spans several links at once; picking one of them would
            # point the whole signature at an annotation, so it stays plain.
            anchors = [item for item in node.iter_nodes() if item.tag == "a" and item.attrs.get("href")]
            if len(anchors) == 1 and collapse(visible_text(anchors[0])).strip() == text:
                href = self.resolve_link(anchors[0].attrs["href"])
                if href:
                    return f"[{literal}]({href})"
            return literal
        if tag in {"strong", "b"}:
            inner = self.inline(node).strip()
            return f"**{inner}**" if inner else ""
        if tag in {"em", "i", "dfn", "var", "cite"}:
            inner = self.inline(node).strip()
            return f"*{inner}*" if inner else ""
        if tag == "a":
            inner = self.inline(node).strip()
            href = self.resolve_link(node.attrs.get("href", ""))
            if not inner:
                return ""
            if not href:
                return inner
            return f"[{inner}]({href})"
        return self.inline(node)

    def image(self, node: Node) -> str:
        source = image_source(node)
        if not source:
            return ""
        alt = collapse(node.attrs.get("alt") or "").strip() or "image"
        return f"![{alt}]({self.resolve_image(source)})"

    # -- blocks ---------------------------------------------------------------

    def blocks(self, node: Node) -> str:
        parts: list[str] = []
        buffer: list[str] = []

        def flush() -> None:
            text = "".join(buffer).strip()
            buffer.clear()
            if text:
                parts.append(text)

        for child in node.children:
            if isinstance(child, str):
                buffer.append(collapse(child))
                continue
            if dropped(child):
                continue
            # A heading is sometimes wrapped in a link. Rendered inline it stops
            # being a heading at all, so the link gives way to the structure.
            if child.tag in INLINE_TAGS and child.find(lambda item: item.tag in HEADING_TAGS) is None:
                buffer.append(self.inline_node(child))
                continue
            flush()
            rendered = self.block_node(child)
            if rendered.strip():
                parts.append(rendered.strip("\n"))
        flush()
        return "\n\n".join(parts)

    def block_node(self, node: Node) -> str:
        tag = node.tag
        if tag in HEADING_TAGS:
            text = self.inline(node).strip()
            if not text:
                return ""
            if self.selector_depth:
                # Inside a Kotlin/Java/Groovy tab strip the heading is a tab
                # label, not a document section.
                return f"**{text}**"
            return "#" * HEADING_TAGS[tag] + " " + text
        if tag == "p":
            return self.inline(node).strip()
        if tag == "pre":
            return self.code_block(node)
        if tag in {"ul", "ol"}:
            return self.list_block(node, ordered=tag == "ol", depth=0)
        if tag == "table":
            return self.table_block(node)
        if tag == "hr":
            return "---"
        if tag == "blockquote":
            return self.quote(self.blocks(node))
        if tag == "aside":
            label = next(iter(node.classes() & {"note", "caution", "warning", "tip", "key-point", "special", "objective", "success", "beta", "dogfood"}), "")
            body = self.blocks(node).strip()
            if not body:
                return ""
            if label:
                # Most asides already open with their own bolded label.
                name = label.replace("-", " ").title()
                if not re.match(rf"\**{re.escape(name)}\b", body, re.I):
                    body = f"{name}: {body}"
            return self.quote(body)
        if tag == "dl":
            return self.definition_list(node)
        if tag in {"devsite-selector", "devsite-code", "tabs"} or "ds-selector-tabs" in node.classes():
            if tag != "devsite-code":
                self.selector_depth += 1
                try:
                    return self.blocks(node)
                finally:
                    self.selector_depth -= 1
            return self.blocks(node)
        if tag == "figcaption":
            # Usually one line, but a figure's caption sometimes carries the
            # legend for the figure as a table.
            inner = self.blocks(node).strip()
            if not inner:
                return ""
            return f"*{inner}*" if "\n" not in inner else inner
        return self.blocks(node)

    def definition_list(self, node: Node) -> str:
        parts: list[str] = []
        for child in node.children:
            if not isinstance(child, Node) or dropped(child):
                continue
            if child.tag == "dt":
                term = self.inline(child).strip()
                if term:
                    parts.append(f"**{term}**")
            elif child.tag == "dd":
                body = self.blocks(child).strip()
                if body:
                    parts.append(body)
        return "\n\n".join(parts)

    def quote(self, body: str) -> str:
        lines = body.strip().split("\n")
        return "\n".join(("> " + line).rstrip() for line in lines)

    def code_block(self, node: Node) -> str:
        text = html.unescape(self.code_text(node)).replace(" ", " ")
        text = text.replace("\r\n", "\n").rstrip()
        if not text.strip():
            return ""
        fence = "`" * max(3, max((len(run) for run in re.findall(r"`{3,}", text)), default=0) + 1)
        block = f"{fence}{code_language(node)}\n{text}\n{fence}"
        if self.code_source and self.code_source[1]:
            block += f"\n\n*Source: [{self.code_source[0]}]({self.code_source[1]})*"
        return block

    def code_text(self, node: Node) -> str:
        """Flatten a `pre` to plain text. devsite hangs the sample's GitHub link
        inside the element, so it would otherwise land on the last code line."""

        parts: list[str] = []
        self.code_source = None

        def walk(current: Node) -> None:
            for child in current.children:
                if isinstance(child, str):
                    parts.append(child)
                    continue
                if "devsite-github-link" in child.classes():
                    link = child.find(lambda item: item.tag == "a")
                    if link is not None:
                        self.code_source = (collapse(link.text()).strip(), link.attrs.get("href", ""))
                    continue
                if child.tag in DROP_TAGS:
                    continue
                if child.tag == "br":
                    parts.append("\n")
                    continue
                walk(child)

        walk(node)
        return "".join(parts)

    def list_block(self, node: Node, ordered: bool, depth: int) -> str:
        lines: list[str] = []
        index = 1
        for child in node.children:
            if not isinstance(child, Node) or child.tag != "li":
                continue
            if child.tag in DROP_TAGS:
                continue
            marker = f"{index}. " if ordered else "- "
            index += 1
            body = self.list_item(child, depth)
            if not body.strip():
                continue
            indent = "    " * depth
            first, *rest = body.split("\n")
            lines.append(indent + marker + first)
            padding = indent + " " * len(marker)
            lines.extend((padding + line).rstrip() if line.strip() else "" for line in rest)
        return "\n".join(lines)

    def list_item(self, node: Node, depth: int) -> str:
        parts: list[str] = []
        buffer: list[str] = []

        def flush() -> None:
            text = "".join(buffer).strip()
            buffer.clear()
            if text:
                parts.append(text)

        for child in node.children:
            if isinstance(child, str):
                buffer.append(collapse(child))
                continue
            if dropped(child):
                continue
            if child.tag in INLINE_TAGS:
                buffer.append(self.inline_node(child))
                continue
            if child.tag in {"ul", "ol"}:
                flush()
                nested = self.list_block(child, ordered=child.tag == "ol", depth=depth + 1)
                if nested.strip():
                    parts.append(nested)
                continue
            flush()
            rendered = self.block_node(child)
            if rendered.strip():
                parts.append(rendered.strip("\n"))
        flush()
        return "\n\n".join(parts)

    def table_block(self, node: Node) -> str:
        if flattens_to_sections(node):
            return self.nested_table_sections(node)
        source_rows = own_rows(node)
        lead = ""
        if source_rows:
            cells = [child for child in source_rows[0].children if isinstance(child, Node) and child.tag in {"td", "th"}]
            heading = cells[0].find(lambda child: child.tag in HEADING_TAGS) if len(cells) == 1 else None
            if heading is not None:
                # "Public fields", "Public methods" and the other section titles
                # are headings inside a header cell spanning the whole table.
                # Left in the cell they stop being headings at all.
                lead = self.block_node(heading)
                source_rows = source_rows[1:]

        rows: list[tuple[bool, list[str]]] = []
        for row in source_rows:
            cells_text: list[str] = []
            header = False
            for cell in row.children:
                if not isinstance(cell, Node) or cell.tag not in {"td", "th"}:
                    continue
                header = header or cell.tag == "th"
                cells_text.append(self.cell_text(cell))
            if cells_text:
                rows.append((header, cells_text))
        if not rows:
            return lead
        if lead:
            lead += "\n\n"
        width = max(len(cells) for _, cells in rows)
        if width == 1:
            # A one-column Markdown table adds nothing over the text itself.
            return lead + "\n\n".join(cells[0] for _, cells in rows if cells[0].strip())
        header_cells = rows[0][1] if rows[0][0] else [""] * width
        body = rows[1:] if rows[0][0] else rows
        lines = [
            "| " + " | ".join(pad(header_cells, width)) + " |",
            "| " + " | ".join("---" for _ in range(width)) + " |",
        ]
        for _, cells in body:
            lines.append("| " + " | ".join(pad(cells, width)) + " |")
        return lead + "\n".join(lines)

    def nested_table_sections(self, node: Node) -> str:
        parts: list[str] = []
        for row in own_rows(node):
            for cell in row.children:
                if not isinstance(cell, Node) or cell.tag not in {"td", "th"} or dropped(cell):
                    continue
                rendered = self.blocks(cell).strip()
                if rendered:
                    parts.append(rendered)
        return "\n\n".join(parts)

    def cell_text(self, node: Node) -> str:
        rendered = self.blocks(node)
        rendered = re.sub(r"```[a-z0-9+#-]*\n(.*?)\n```", lambda m: "`" + m.group(1).replace("\n", " ") + "`", rendered, flags=re.S)
        rendered = re.sub(r"\n+", "<br>", rendered.strip())
        return escape_inline(rendered)


def flattens_to_sections(table: Node) -> bool:
    """Whether this table has to be broken into sections instead.

    Two shapes cannot survive as a Markdown table. "Inherited constants" is a
    table whose one cell holds another table of members, and flattening it puts
    a whole inherited API on a single line — 45,000 characters on the worst
    reference page. Compose's option tables put a real multi-line snippet in a
    cell, and a cell cannot hold a fenced block, so the code would be squashed
    onto one line with its indentation gone."""

    if table.find(lambda child: child.tag == "table") is not None:
        return True
    for node in table.iter_nodes():
        if node.tag == "pre" and "\n" in node.text().strip():
            return True
    return False


def own_rows(table: Node) -> list[Node]:
    """Rows of this table only. A plain descendant walk would also pick up the
    rows of a nested table and render them twice."""

    rows: list[Node] = []

    def walk(node: Node) -> None:
        for child in node.children:
            if not isinstance(child, Node):
                continue
            if child.tag == "tr":
                rows.append(child)
            elif child.tag in {"thead", "tbody", "tfoot"}:
                walk(child)

    walk(table)
    return rows


def pad(cells: list[str], width: int) -> list[str]:
    return [cells[index] if index < len(cells) else "" for index in range(width)]


def page_relative_path(path: str) -> str:
    return path.strip("/") or "index"


def markdown_path(out: Path, slug: str) -> Path:
    return out / "pages" / (slug + ".md")


def raw_path(out: Path, slug: str) -> Path:
    return out / "metadata" / "raw-pages" / (slug + ".html.gz")


def existing_asset(out: Path, url: str) -> Path | None:
    """Find an already-downloaded image by its URL digest. The manifest is
    written at the end of a run, so a run that dies part way leaves files on
    disk that no record points at; this is what stops the next run redoing
    every download."""

    digest = hashlib.sha256(url.encode("utf-8")).hexdigest()[:16]
    for candidate in sorted((out / "assets").glob(digest + ".*")):
        if candidate.is_file():
            return candidate
    return None


def asset_path(out: Path, url: str, content_type: str | None = None) -> Path:
    parsed = urlparse(url)
    suffix = Path(parsed.path).suffix.lower()
    if suffix not in IMAGE_EXTENSIONS:
        guessed = mimetypes.guess_extension((content_type or "").split(";", 1)[0].strip())
        suffix = guessed if guessed in IMAGE_EXTENSIONS else (suffix or ".bin")
    digest = hashlib.sha256(url.encode("utf-8")).hexdigest()[:16]
    return out / "assets" / (digest + suffix)


class Mirror:
    def __init__(self, args: argparse.Namespace):
        self.out: Path = args.out.resolve()
        self.args = args
        self.groups = {name: value for name, value in GROUPS.items() if not args.only or name in args.only}
        self.prefixes = {name: value["prefixes"] for name, value in self.groups.items()}
        self.lock = threading.Lock()
        self.seen: set[str] = set()
        self.page_records: dict[str, dict[str, Any]] = {}
        # Parsed articles are not kept: a full reference crawl holds several
        # thousand DOMs at once and runs the process out of memory. Rendering
        # reads each page back from the gzip cache instead.
        self.image_urls: set[str] = set()
        self.failures: list[dict[str, Any]] = []
        self.asset_records: dict[str, dict[str, Any]] = {}
        self.robots: Robots | None = None
        self.slugs: dict[str, str] = {}
        self.claimed_slugs: set[str] = set()
        self.validators: dict[str, dict[str, str]] = {}
        self.changed: list[dict[str, Any]] = []
        self.queue: list[str] = []
        self.truncated = False

    # -- scope ----------------------------------------------------------------

    def group_of(self, path: str) -> str | None:
        for name, prefixes in self.prefixes.items():
            for prefix in prefixes:
                if path == prefix.rstrip("/") or path.startswith(prefix):
                    return name
        return None

    def crawlable(self, path: str) -> bool:
        return bool(self.robots and self.robots.allows(path))

    def slug_for(self, path: str) -> str:
        with self.lock:
            existing = self.slugs.get(path)
            if existing:
                return existing
            base = page_relative_path(path)
            slug = base
            counter = 2
            # Windows paths are case-insensitive, and the reference tree has
            # both a `util` package directory and a `Util` class page.
            while slug.lower() in self.claimed_slugs:
                slug = f"{base}~{counter}"
                counter += 1
            self.claimed_slugs.add(slug.lower())
            self.slugs[path] = slug
            return slug

    # -- crawl ----------------------------------------------------------------

    def load_robots(self) -> str:
        response = fetch(BASE_URL + "/robots.txt", accept="text/plain")
        write_bytes(self.out / "metadata" / "robots.txt", response.data)
        text = response.data.decode("utf-8", errors="replace")
        self.robots = Robots(text)
        return text

    def fetch_page(self, path: str) -> tuple[str, str]:
        """Return (markup, status).

        A cached page is returned without a request. `--refresh` and `--check`
        revalidate it instead: the stored ETag and Last-Modified go out as
        If-None-Match and If-Modified-Since, and a 304 keeps the cached copy."""

        slug = self.slug_for(path)
        cache = raw_path(self.out, slug)
        url = BASE_URL + path
        cached = cache.exists()
        revalidate = self.args.refresh or self.args.check
        if cached and not revalidate:
            return gzip.decompress(cache.read_bytes()).decode("utf-8", errors="replace"), "cached"

        stored = self.validators.get(path) if cached else None
        if cached and (stored is None or "article_sha256" not in stored):
            # A mirror built before content hashing has the pages but not their
            # digests; take them from the cache rather than calling everything
            # changed on the next check.
            previous_markup = gzip.decompress(cache.read_bytes()).decode("utf-8", errors="replace")
            stored = dict(stored or {})
            stored["article_sha256"] = sha256((nav_and_article(previous_markup)[1] or previous_markup).encode("utf-8"))
            with self.lock:
                self.validators[path] = stored
        response = fetch(url, accept="text/html", validators=stored)
        if response.unchanged and cached:
            with self.lock:
                self.validators.setdefault(path, {}).update(response.validators())
            return gzip.decompress(cache.read_bytes()).decode("utf-8", errors="replace"), "unchanged"

        markup = response.data.decode("utf-8", errors="replace")
        # devsite answers every conditional request with 200: it sends no ETag,
        # ignores If-Modified-Since, and stamps a fresh CSP nonce into the page
        # on each render, so the body always differs. The article slice does
        # not, and it is the only part being mirrored, so that is what decides
        # whether a page actually changed.
        digest = sha256((nav_and_article(markup)[1] or markup).encode("utf-8"))
        current = dict(response.validators())
        current["article_sha256"] = digest
        unchanged = bool(stored) and stored.get("article_sha256") == digest
        with self.lock:
            self.validators[path] = current
            if cached and not unchanged:
                self.changed.append({
                    "path": path,
                    "url": url,
                    "last_modified": response.headers.get("last-modified"),
                })
        if unchanged:
            return markup, "unchanged"
        if not self.args.check:
            write_bytes(cache, gzip.compress(response.data, 6))
        return markup, "downloaded"

    def load_previous(self) -> bool:
        """Restore the last run's page list so Markdown can be rebuilt, checked
        or verified without walking the site again."""

        metadata = self.out / "metadata"
        pages = metadata / "pages.json"
        if not pages.exists():
            return False
        state = metadata / "crawl-state.json"
        if state.exists():
            try:
                saved = json.loads(state.read_text(encoding="utf-8"))
            except (json.JSONDecodeError, OSError):
                saved = {}
            self.validators.update(saved.get("validators", {}))
            self.truncated = bool(saved.get("queue"))
        records = json.loads(pages.read_text(encoding="utf-8"))
        for record in records:
            if not self.group_of(record["path"]):
                continue
            self.page_records[record["path"]] = record
            self.slugs[record["path"]] = record["slug"]
            self.claimed_slugs.add(record["slug"].lower())
        assets = metadata / "assets.json"
        if assets.exists():
            self.image_urls.update(
                record["url"] for record in json.loads(assets.read_text(encoding="utf-8"))
            )
        failures = metadata / "failures.json"
        if failures.exists():
            # Nothing is crawled in this mode, so the crawl's failures would
            # otherwise be written back as an empty list.
            self.failures.extend(
                record
                for record in json.loads(failures.read_text(encoding="utf-8"))
                if record.get("reason") != "render"
            )
        return bool(self.page_records)

    def state_path(self) -> Path:
        return self.out / "metadata" / "crawl-state.json"

    def load_state(self) -> bool:
        """Pick up an interrupted or completed crawl. Without this, a run that
        is stopped part way starts from the seeds again and rewalks everything
        it already has."""

        path = self.state_path()
        if self.args.refresh:
            return False
        if not path.exists():
            # A mirror built before crawl state existed still has its page list.
            return self.load_previous()
        try:
            state = json.loads(path.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            return False
        for record in state.get("pages", []):
            if not self.group_of(record["path"]):
                continue
            self.page_records[record["path"]] = record
            self.slugs[record["path"]] = record["slug"]
            self.claimed_slugs.add(record["slug"].lower())
        self.seen.update(state.get("seen", []))
        self.queue = [path_ for path_ in state.get("queue", []) if self.group_of(path_)]
        self.failures.extend(state.get("failures", []))
        self.image_urls.update(state.get("images", []))
        self.validators.update(state.get("validators", {}))
        return True

    def save_state(self, also_queued: Iterable[str] = ()) -> None:
        write_json(
            self.state_path(),
            {
                "saved_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                "queue": self.queue + [path for path in also_queued],
                "seen": sorted(self.seen),
                "pages": [self.page_records[path] for path in sorted(self.page_records)],
                "failures": self.failures,
                "images": sorted(self.image_urls),
                "validators": self.validators,
            },
        )

    def crawl(self) -> None:
        resumed = self.load_state()
        if resumed:
            print(f"resumed: {len(self.page_records)} pages done, {len(self.queue)} queued", flush=True)
        if not resumed:
            self.queue = []
            self.seen = set()
            for group in self.groups.values():
                for seed in group["seeds"]:
                    if seed not in self.seen:
                        self.seen.add(seed)
                        self.queue.append(seed)
        if self.args.refresh:
            # Revalidate every page already mirrored, and walk out from them
            # again so pages added upstream since the last run are picked up.
            self.queue = []
            self.seen = set()
            for group in self.groups.values():
                for seed in group["seeds"]:
                    self.seen.add(seed)
                    self.queue.append(seed)

        pool = concurrent.futures.ThreadPoolExecutor(max_workers=max(1, min(10, self.args.workers)))
        done = 0
        try:
            while self.queue:
                if self.args.max_pages:
                    room = self.args.max_pages - len(self.page_records)
                    if room <= 0:
                        # The rest of the queue stays for the next run, and the
                        # manifest has to say the scope is short.
                        self.truncated = True
                        break
                    batch, self.queue = self.queue[:room], self.queue[room:]
                else:
                    batch, self.queue = self.queue, []
                for index, result in enumerate(pool.map(self.visit, batch), start=1):
                    done += 1
                    if done % 50 == 0:
                        print(f"pages {done} visited", flush=True)
                    if result is None:
                        continue
                    for link in result:
                        if link not in self.seen:
                            self.seen.add(link)
                            self.queue.append(link)
                    if index % 200 == 0:
                        # Checkpoint mid-batch: a batch can be a thousand pages,
                        # and an interrupt in the middle should not lose them.
                        self.save_state(batch[index:])
                print(f"pages {done} visited, {len(self.queue)} queued", flush=True)
                self.save_state()
        finally:
            pool.shutdown()
            # Anything still queued means the crawl did not finish, whether it
            # was capped by --max-pages or interrupted.
            self.truncated = bool(self.queue)
            self.save_state()

    def record_failure(
        self,
        path: str,
        group: str,
        reason: str,
        *,
        error: str | None = None,
        status: int | None = None,
        attempts: int = 1,
    ) -> None:
        entry = {
            "path": path,
            "url": BASE_URL + path,
            "group": group,
            "reason": reason,
            "status": status,
            "attempts": attempts,
            "last_seen": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        }
        if error:
            entry["error"] = error
        with self.lock:
            self.failures = [item for item in self.failures if item.get("path") != path]
            self.failures.append(entry)

    def visit(self, path: str) -> list[str] | None:
        group = self.group_of(path)
        if not group:
            return None
        if not self.crawlable(path):
            self.record_failure(path, group, "robots-disallowed")
            return None
        try:
            markup, status = self.fetch_page(path)
        except FetchFailure as error:
            self.record_failure(path, group, "fetch", error=error.message, status=error.status, attempts=error.attempts)
            return None
        except Exception as error:  # one malformed URL must not end the run
            self.record_failure(path, group, "fetch", error=f"{type(error).__name__}: {error}")
            return None
        if BODY_404.search(markup):
            self.record_failure(path, group, "not-found", status=404)
            return None
        nav, article = nav_and_article(markup)
        if article is None:
            self.record_failure(path, group, "no-article-body")
            return None

        links = self.extract_links(nav, article)
        images = self.extract_images(article)
        slug = self.slug_for(path)  # takes the lock itself, so claim it first
        with self.lock:
            self.image_urls.update(images)
            self.page_records[path] = {
                "path": path,
                "group": group,
                "url": BASE_URL + path,
                "status": status,
                "slug": slug,
            }
        return links

    def extract_links(self, nav: str | None, article: str) -> list[str]:
        """Discovery reads the markup with a regex rather than the DOM. Parsing
        every page twice — once here, once to render it — is what dominates the
        run, and a page's own rendering pass is the one that has to be exact."""

        found: list[str] = []
        for scope in (nav, article):
            if not scope:
                continue
            for href in HREF.findall(scope):
                if not href or href.startswith(("#", "mailto:", "javascript:")):
                    continue
                parsed = urlparse(urljoin(BASE_URL + "/", html.unescape(href)))
                if parsed.hostname != "developer.android.com" or parsed.query:
                    continue
                candidate = parsed.path.rstrip("/") or "/"
                if self.group_of(candidate):
                    found.append(candidate)
        return found

    def extract_images(self, article: str) -> set[str]:
        urls: set[str] = set()
        for tag in IMG_TAG.findall(article):
            match = IMG_SRC.search(tag)
            if not match:
                continue
            # Only srcset is a comma-separated candidate list. Splitting a plain
            # src the same way truncates the filenames that contain a space.
            source = html.unescape(match.group(1) or "").strip()
            if not source and match.group(2):
                source = html.unescape(match.group(2)).split(",")[0].strip().split(" ")[0]
            if source.startswith("data:") or not source:
                continue
            absolute, _ = urldefrag(urljoin(BASE_URL + "/", source))
            parsed = urlparse(absolute)
            if parsed.scheme in {"http", "https"} and parsed.hostname in ASSET_HOSTS:
                urls.add(absolute)
        return urls

    # -- assets ---------------------------------------------------------------

    def collect_assets(self) -> list[str]:
        return sorted(self.image_urls)

    def download_assets(self, urls: list[str]) -> None:
        previous: dict[str, dict[str, Any]] = {}
        manifest = self.out / "metadata" / "assets.json"
        if manifest.exists():
            try:
                previous = {record["url"]: record for record in json.loads(manifest.read_text(encoding="utf-8"))}
            except (json.JSONDecodeError, OSError, KeyError, TypeError):
                previous = {}

        def download(url: str) -> dict[str, Any]:
            parsed = urlparse(url)
            if parsed.hostname == "developer.android.com" and not self.crawlable(parsed.path):
                return {"url": url, "status": "robots-disallowed"}
            if not self.args.refresh:
                cached = previous.get(url)
                if cached and cached.get("path") and (self.out / cached["path"]).exists():
                    return {**cached, "status": "cached"}
                on_disk = existing_asset(self.out, url)
                if on_disk is not None:
                    data = on_disk.read_bytes()
                    return {
                        "url": url,
                        "status": "cached",
                        "path": on_disk.relative_to(self.out).as_posix(),
                        "bytes": len(data),
                        "sha256": sha256(data),
                    }
            existing = previous.get(url) or {}
            validators = {
                key: existing[key] for key in ("etag", "last-modified") if existing.get(key)
            }
            local = existing_asset(self.out, url)
            try:
                response = fetch(url, validators=validators if local is not None else None)
            except FetchFailure as error:
                return {
                    "url": url,
                    "status": "error",
                    "error": error.message,
                    "http_status": error.status,
                    "attempts": error.attempts,
                    "last_seen": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                }
            except Exception as error:  # one malformed URL must not end the run
                return {"url": url, "status": "error", "error": f"{type(error).__name__}: {error}"}
            if response.unchanged and local is not None:
                return {**existing, "url": url, "status": "cached", "path": local.relative_to(self.out).as_posix()}
            path = asset_path(self.out, url, response.headers.get("content-type"))
            write_bytes(path, response.data)
            return {
                "url": url,
                "status": "downloaded",
                "path": path.relative_to(self.out).as_posix(),
                "bytes": len(response.data),
                "sha256": sha256(response.data),
                "content_type": response.headers.get("content-type"),
                **response.validators(),
            }

        if self.args.no_assets:
            for url in urls:
                cached = previous.get(url)
                if cached and cached.get("path") and (self.out / cached["path"]).exists():
                    self.asset_records[url] = {**cached, "status": "cached"}
                    continue
                on_disk = existing_asset(self.out, url)
                if on_disk is not None:
                    self.asset_records[url] = {
                        "url": url,
                        "status": "cached",
                        "path": on_disk.relative_to(self.out).as_posix(),
                    }
                else:
                    self.asset_records[url] = {"url": url, "status": "not-downloaded"}
            return

        with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, min(10, self.args.workers))) as pool:
            for index, record in enumerate(pool.map(download, urls), start=1):
                self.asset_records[record["url"]] = record
                if index % 25 == 0 or index == len(urls):
                    print(f"assets {index}/{len(urls)}", flush=True)

    # -- render ---------------------------------------------------------------

    def render_all(self, fetched_at: str) -> None:
        asset_map = {
            url: record["path"]
            for url, record in self.asset_records.items()
            if record.get("path") and record.get("status") in {"downloaded", "cached"}
        }
        for index, path in enumerate(sorted(self.page_records), start=1):
            record = self.page_records[path]
            cache = raw_path(self.out, record["slug"])
            if not cache.exists():
                continue
            markup = gzip.decompress(cache.read_bytes()).decode("utf-8", errors="replace")
            _, slice_ = nav_and_article(markup)
            if slice_ is None:
                continue
            # The whole slice is rendered, not the article-body element found
            # inside it: several Compose pages carry a stray </div> that closes
            # that element early, and everything after it would be dropped.
            article = parse_html(slice_)
            target = markdown_path(self.out, record["slug"])

            def resolve_link(href: str, target=target) -> str:
                if not href:
                    return ""
                if href.startswith("#"):
                    return href
                if href.startswith(("mailto:", "javascript:")):
                    return href if href.startswith("mailto:") else ""
                absolute = urljoin(BASE_URL + "/", href)
                parsed = urlparse(absolute)
                if parsed.hostname == "developer.android.com" and not parsed.query:
                    other = parsed.path.rstrip("/") or "/"
                    mirrored = self.page_records.get(other)
                    if mirrored:
                        local = markdown_path(self.out, mirrored["slug"])
                        relative = relative_posix(local, target.parent)
                        return relative + (("#" + parsed.fragment) if parsed.fragment else "")
                return absolute

            def resolve_image(source: str, target=target) -> str:
                if source.startswith("data:"):
                    return source
                absolute, fragment = urldefrag(urljoin(BASE_URL + "/", source))
                local = asset_map.get(absolute)
                if local:
                    return relative_posix(self.out / local, target.parent)
                return absolute

            title = page_title(markup) or path.rsplit("/", 1)[-1]
            record["title"] = title

            renderer = Renderer(resolve_link, resolve_image)
            try:
                body = clean_markdown(renderer.blocks(article))
            except Exception as error:  # one unhandled shape must not lose the rest
                self.failures.append({
                    "path": path,
                    "group": record["group"],
                    "reason": "render",
                    "error": f"{type(error).__name__}: {error}",
                })
                continue
            upstream = (self.validators.get(path) or {}).get("last-modified")
            header = f"<!-- source: {record['url']} | fetched: {fetched_at}"
            header += f" | upstream-last-modified: {upstream}" if upstream else ""
            header += " -->\n\n"
            if not body.startswith("# "):
                header += f"# {title}\n\n"
            markdown = header + body
            write_text(target, markdown)
            record["markdown_path"] = target.relative_to(self.out).as_posix()
            record["markdown_bytes"] = len(markdown.encode("utf-8"))
            if index % 100 == 0 or index == len(self.page_records):
                print(f"markdown {index}/{len(self.page_records)}", flush=True)

    # -- upstream -------------------------------------------------------------

    def download_upstream(self) -> list[dict[str, Any]]:
        records: list[dict[str, Any]] = []
        for name, url in UPSTREAM_FILES:
            target = self.out / "upstream" / name
            if target.exists() and not self.args.refresh:
                data = target.read_bytes()
                records.append({"name": name, "url": url, "status": "cached", "bytes": len(data), "sha256": sha256(data)})
                continue
            try:
                response = fetch(url, accept="text/plain", validators=self.validators.get(url))
            except FetchFailure as error:
                records.append({
                    "name": name,
                    "url": url,
                    "status": "error",
                    "error": error.message,
                    "http_status": error.status,
                    "attempts": error.attempts,
                })
                continue
            if response.unchanged and target.exists():
                data = target.read_bytes()
                records.append({"name": name, "url": url, "status": "unchanged", "bytes": len(data), "sha256": sha256(data)})
                continue
            self.validators[url] = response.validators()
            write_bytes(target, response.data)
            records.append({
                "name": name,
                "url": url,
                "status": "downloaded",
                "bytes": len(response.data),
                "sha256": sha256(response.data),
                "last_modified": response.headers.get("last-modified"),
            })
        return records


def relative_posix(target: Path, start: Path) -> str:
    return Path(os.path.relpath(target, start)).as_posix()


GROUP_TITLES = {
    "media3-guide": "Media3 指南",
    "media-implement": "媒体实现指引",
    "media3-reference": "Media3 API 参考",
    "compose": "Compose",
    "navigation-3": "Navigation 3",
    "background-work": "后台与前台服务、通知",
}

# A grep hit prints its whole line. Past roughly this width the hit stops being
# readable in a terminal — it is about forty wrapped lines — and the reader gets
# a wall of text instead of an answer. The reference pages' inherited-member
# tables used to land at 45,000 characters on one line.
MAX_LINE_LENGTH = 4000


def build_index(out: Path, records: list[dict[str, Any]], groups: dict[str, Any]) -> str:
    """pages/INDEX.md: the map another agent reads first.

    Class pages are listed by package rather than one by one — 1,212 entries
    would bury the guides, and their paths follow the URL exactly."""

    lines = [
        "# Android 文档镜像索引",
        "",
        "文件路径与站点 URL 一一对应：`/media/media3/session/background-playback` 对应",
        "`pages/media/media3/session/background-playback.md`。先在 `pages/` 里 grep，命中之后读文件头部那行注释回源。",
        "",
        "目录是顺着链接走出来的，站点没有可对账的完整清单，因此没有入口链接指向的孤儿页不会出现在这里。"
        "grep 不到时，「官方没写」和「镜像没抓到」都有可能，见 README 的「覆盖面无法自证完整」一节。",
        "",
    ]
    by_group: dict[str, list[dict[str, Any]]] = {}
    for record in records:
        by_group.setdefault(record["group"], []).append(record)

    for name in groups:
        pages = sorted(by_group.get(name, []), key=lambda item: item["path"])
        if not pages:
            continue
        lines.append(f"## {GROUP_TITLES.get(name, name)}（{len(pages)} 页）")
        lines.append("")
        if name == "media3-reference":
            packages = [record for record in pages if record["path"].endswith("/package-summary")]
            lines.append(
                f"共 {len(pages)} 页，其中类页面 {len(pages) - len(packages)} 个，路径即类的全限定名。"
                "按包列出索引页："
            )
            lines.append("")
            for record in packages:
                package = record["path"].rsplit("/", 2)[0].split("/reference/")[-1].replace("/", ".")
                lines.append(f"- [{package}]({record['path'].strip('/')}.md)")
            lines.append("")
            continue
        for record in pages:
            title = record.get("title") or record["path"].rsplit("/", 1)[-1]
            lines.append(f"- [{title}]({record['path'].strip('/')}.md)")
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def renders_as_table(node: Node) -> bool:
    """Whether this table becomes a Markdown table.

    A table that has to become sections, a table whose rows all have one
    column, and the leading section-title row that is lifted out as a heading
    all mean that counting `<table>` alone over-expects."""

    if flattens_to_sections(node):
        return False
    rows = own_rows(node)
    if rows:
        cells = [child for child in rows[0].children if isinstance(child, Node) and child.tag in {"td", "th"}]
        if len(cells) == 1 and cells[0].find(lambda child: child.tag in HEADING_TAGS) is not None:
            rows = rows[1:]
    widths = [
        len([child for child in row.children if isinstance(child, Node) and child.tag in {"td", "th"}])
        for row in rows
    ]
    widths = [width for width in widths if width]
    return bool(widths) and max(widths) > 1


def count_source_shapes(markup: str) -> dict[str, int]:
    """Count the shapes that must survive conversion, read from the source HTML
    independently of the renderer."""

    _, article = nav_and_article(markup)
    if article is None:
        return {}
    root = parse_html(article)

    selector_ids = {
        id(node)
        for node in root.iter_nodes()
        if node.tag in {"devsite-selector", "tabs"} or "ds-selector-tabs" in node.classes()
    }

    def inside_selector(node: Node) -> bool:
        current = node.parent
        while current is not None:
            if id(current) in selector_ids:
                return True
            current = current.parent
        return False

    def is_dropped(node: Node) -> bool:
        current: Node | None = node
        while current is not None:
            if dropped(current):
                return True
            current = current.parent
        return False

    counts = {"code": 0, "heading": 0, "table": 0, "image": 0}
    for node in root.iter_nodes():
        if is_dropped(node):
            continue
        if node.tag == "pre":
            counts["code"] += 1
        elif node.tag in HEADING_TAGS and not inside_selector(node):
            counts["heading"] += 1
        elif node.tag == "table" and renders_as_table(node):
            counts["table"] += 1
        elif node.tag == "img" and image_source(node):
            counts["image"] += 1
    return counts


def count_markdown_shapes(markdown: str) -> dict[str, int]:
    body = markdown.split("\n")
    counts = {"code": 0, "heading": 0, "table": 0, "image": 0}
    fence = ""
    previous_table = False
    for line in body:
        # A fence inside a list item is indented to the item's text column, and
        # a shell snippet's own comments look like headings. A snippet that
        # itself contains ``` is wrapped in a longer fence, so closing has to
        # match the fence that opened.
        stripped = line.lstrip()
        if fence:
            if stripped.rstrip() == fence:
                fence = ""
            continue
        if stripped.startswith("```"):
            fence = stripped[: len(stripped) - len(stripped.lstrip("`"))]
            counts["code"] += 1
            continue
        if re.match(r"#{1,6} ", stripped):
            counts["heading"] += 1
        is_table = stripped.startswith("| ")
        if is_table and not previous_table:
            counts["table"] += 1
        previous_table = is_table
        # The marker, not the whole pattern: some alt text contains a bracket.
        counts["image"] += line.count("![")
    return counts


def verify(mirror: "Mirror", sample: int, seed: int) -> tuple[int, list[dict[str, Any]]]:
    """Compare a sample of pages against their source HTML.

    Counts are compared rather than text: a conversion rule that quietly drops
    half the code blocks is exactly what this has to catch, and a count is the
    cheapest signal that says so."""

    import random

    records = [
        record
        for record in mirror.page_records.values()
        if raw_path(mirror.out, record["slug"]).exists()
        and markdown_path(mirror.out, record["slug"]).exists()
    ]
    records.sort(key=lambda item: item["path"])
    chosen = records if sample <= 0 or sample >= len(records) else random.Random(seed).sample(records, sample)
    chosen.sort(key=lambda item: item["path"])

    results: list[dict[str, Any]] = []
    for record in chosen:
        markup = gzip.decompress(raw_path(mirror.out, record["slug"]).read_bytes()).decode("utf-8", errors="replace")
        markdown = markdown_path(mirror.out, record["slug"]).read_text(encoding="utf-8")
        expected = count_source_shapes(markup)
        found = count_markdown_shapes(markdown)
        longest = max((len(line) for line in markdown.split("\n")), default=0)
        problems = []
        for key, value in expected.items():
            if found.get(key, 0) < value:
                problems.append(f"{key} {found.get(key, 0)}/{value}")
        if longest > MAX_LINE_LENGTH:
            problems.append(f"line {longest} > {MAX_LINE_LENGTH}")
        results.append({
            "path": record["path"],
            "expected": expected,
            "found": found,
            "longest_line": longest,
            "problems": problems,
        })

    failed = [result for result in results if result["problems"]]
    print(f"verified {len(results)} pages, {len(failed)} with problems")
    for result in results:
        expected, found = result["expected"], result["found"]
        state = "FAIL " + "; ".join(result["problems"]) if result["problems"] else "ok"
        print(
            f"  {result['path']}: code {found.get('code', 0)}/{expected.get('code', 0)}"
            f"  headings {found.get('heading', 0)}/{expected.get('heading', 0)}"
            f"  tables {found.get('table', 0)}/{expected.get('table', 0)}"
            f"  images {found.get('image', 0)}/{expected.get('image', 0)}"
            f"  longest {result['longest_line']}  {state}"
        )

    over_length = sorted(
        (
            (max((len(line) for line in markdown_path(mirror.out, record["slug"]).read_text(encoding="utf-8").split("\n")), default=0), record["path"])
            for record in records
        ),
        reverse=True,
    )
    breached = [item for item in over_length if item[0] > MAX_LINE_LENGTH]
    print(f"longest line across all {len(records)} pages: {over_length[0][0] if over_length else 0}")
    print(f"pages over {MAX_LINE_LENGTH} characters on one line: {len(breached)}")
    for length, path in breached[:10]:
        print(f"  {length} {path}")

    report = {
        "checked_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "sample": len(results),
        "population": len(records),
        "seed": seed,
        "max_line_length": MAX_LINE_LENGTH,
        "longest_line": over_length[0][0] if over_length else 0,
        "pages_over_max_line": [{"path": path, "longest_line": length} for length, path in breached],
        "results": results,
    }
    write_json(mirror.out / "metadata" / "verify.json", report)
    return (0 if not failed and not breached else 1), results


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT, help="mirror output directory")
    parser.add_argument("--workers", type=int, default=6, help="maximum concurrent requests, capped at 10")
    parser.add_argument("--no-assets", action="store_true", help="rebuild Markdown without downloading images")
    parser.add_argument("--refresh", action="store_true", help="revalidate everything with conditional requests and walk the site again")
    parser.add_argument("--only", action="append", choices=sorted(GROUPS), help="restrict the crawl to one group; repeatable")
    parser.add_argument("--max-pages", type=int, default=0, help="stop after this many pages, for a quick check")
    parser.add_argument("--drop-html", action="store_true", help="discard the raw HTML cache when the run finishes")
    parser.add_argument("--render-only", action="store_true", help="rebuild Markdown from the cached HTML without crawling the site")
    parser.add_argument("--check", action="store_true", help="conditional requests only: report which pages changed upstream, write nothing")
    parser.add_argument("--verify", action="store_true", help="compare a sample of pages against their source HTML and report fidelity")
    parser.add_argument("--sample", type=int, default=30, help="pages to sample in --verify; 0 checks every page")
    parser.add_argument("--seed", type=int, default=0, help="sampling seed for --verify, so a report can be reproduced")
    args = parser.parse_args()

    mirror = Mirror(args)
    mirror.out.mkdir(parents=True, exist_ok=True)

    if args.verify:
        if not mirror.load_previous():
            print("nothing to verify: run a crawl first", file=sys.stderr)
            return 1
        code, _ = verify(mirror, args.sample, args.seed)
        return code

    robots_text = mirror.load_robots()
    fetched_at = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())

    if args.check:
        if not mirror.load_previous():
            print("nothing to check: run a crawl first", file=sys.stderr)
            return 1
        return check(mirror)

    if args.render_only:
        if not mirror.load_previous():
            print("nothing to render: run a crawl first", file=sys.stderr)
            return 1
    else:
        mirror.crawl()
    assets = mirror.collect_assets()
    mirror.download_assets(assets)
    mirror.render_all(fetched_at)
    upstream = mirror.download_upstream()

    metadata = mirror.out / "metadata"
    records = [mirror.page_records[path] for path in sorted(mirror.page_records)]
    write_text(mirror.out / "pages" / "INDEX.md", build_index(mirror.out, records, mirror.groups))
    write_json(metadata / "pages.json", records)
    write_json(metadata / "assets.json", [mirror.asset_records[url] for url in sorted(mirror.asset_records)])
    write_json(metadata / "failures.json", sorted(mirror.failures, key=lambda item: (item.get("path", ""), item.get("url", ""))))
    write_json(
        metadata / "routes.json",
        {name: sorted(path for path, record in mirror.page_records.items() if record["group"] == name) for name in mirror.groups},
    )

    group_counts = {
        name: len([record for record in mirror.page_records.values() if record["group"] == name])
        for name in mirror.groups
    }
    asset_status = {}
    for record in mirror.asset_records.values():
        asset_status[record["status"]] = asset_status.get(record["status"], 0) + 1

    manifest = {
        "source": BASE_URL,
        "fetched_at": fetched_at,
        "scope": {
            name: {
                "prefixes": list(group["prefixes"]),
                "pages": group_counts.get(name, 0),
                "seeds": list(group["seeds"]),
                # The page count is what discovery reached, not what exists. It
                # belongs next to the count: "grep found nothing" has to be
                # readable as either "the docs do not cover it" or "the mirror
                # never saw the page", and those call for different responses.
                "discovery": (
                    "breadth-first from the seeds, following the book navigation and in-body links; "
                    "a page inside these prefixes that nothing links to is not discoverable and is not mirrored"
                ),
                "truncated": mirror.truncated,
            }
            for name, group in mirror.groups.items()
        },
        "page_count": len(mirror.page_records),
        "markdown_count": len([record for record in mirror.page_records.values() if record.get("markdown_bytes")]),
        "failure_count": len(mirror.failures),
        "asset_count": len(mirror.asset_records),
        "asset_status": asset_status,
        "upstream": upstream,
        "robots": {
            "url": BASE_URL + "/robots.txt",
            "disallow": mirror.robots.disallow if mirror.robots else [],
            "allow": mirror.robots.allow if mirror.robots else [],
        },
        "notes": [
            "Coverage cannot prove itself complete: the page list is walked from links, "
            "so an orphan page inside the scope would never be seen. Read a missing page as "
            "either 'not documented' or 'not discovered', and check the site before concluding the first.",
            "Generated content is kept out of Git through .git/info/exclude.",
            "Every request is checked against the live robots.txt; disallowed paths are recorded, not fetched.",
            "Images that cannot be downloaded keep their original URL in the Markdown.",
        ],
    }
    write_json(mirror.out / "manifest.json", manifest)
    mirror.save_state()

    if args.drop_html:
        # Only this run's pages: --only must not wipe another group's cache.
        for record in mirror.page_records.values():
            cached = raw_path(mirror.out, record["slug"])
            if cached.exists():
                cached.unlink()

    print(json.dumps({key: manifest[key] for key in ("fetched_at", "page_count", "markdown_count", "failure_count", "asset_count", "asset_status")}, ensure_ascii=False, indent=2))
    print(json.dumps(group_counts, ensure_ascii=False, indent=2))
    print(len(robots_text), "bytes of robots.txt applied", flush=True)
    return exit_code(mirror)


def exit_code(mirror: "Mirror") -> int:
    """0 clean, 2 only permanently gone upstream, 1 anything that may be ours.

    A dead link in the site's own navigation is not a fault of this mirror and
    must not look like one on a schedule, but it still should not read as a
    clean run."""

    gone = {404, 410}
    hard = [
        failure
        for failure in mirror.failures
        if failure.get("reason") not in {"not-found"} and failure.get("status") not in gone
    ]
    hard += [
        record
        for record in mirror.asset_records.values()
        if record.get("status") == "error" and record.get("http_status") not in gone
    ]
    if hard:
        print(f"{len(hard)} failures that are not upstream 404s", file=sys.stderr)
        return 1
    if mirror.failures or any(record.get("status") == "error" for record in mirror.asset_records.values()):
        return 2
    return 0


def check(mirror: "Mirror") -> int:
    """Conditional requests only. Reports what moved upstream, writes nothing
    but the report."""

    paths = sorted(mirror.page_records)
    workers = max(1, min(10, mirror.args.workers))

    def probe(path: str) -> None:
        try:
            mirror.fetch_page(path)
        except FetchFailure as error:
            mirror.record_failure(path, mirror.page_records[path]["group"], "fetch", error=error.message, status=error.status, attempts=error.attempts)

    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        for index, _ in enumerate(pool.map(probe, paths), start=1):
            if index % 100 == 0 or index == len(paths):
                print(f"checked {index}/{len(paths)}", flush=True)

    report = {
        "checked_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "pages_checked": len(paths),
        "changed": sorted(mirror.changed, key=lambda item: item["path"]),
        "failed": mirror.failures,
    }
    write_json(mirror.out / "metadata" / "check.json", report)
    print(f"{len(mirror.changed)} of {len(paths)} pages changed upstream")
    for entry in report["changed"][:40]:
        print(f"  {entry['path']}")
    if len(report["changed"]) > 40:
        print(f"  ... and {len(report['changed']) - 40} more, see metadata/check.json")
    return exit_code(mirror)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print("interrupted", file=sys.stderr)
        raise SystemExit(130)
