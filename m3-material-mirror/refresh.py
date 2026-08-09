#!/usr/bin/env python3
"""Mirror the primary, data-backed pages from m3.material.io.

The site is an Angular shell. Its current page content is exposed as versioned
JSON, so this crawler downloads that JSON first and only treats the homepage
bundle as a route/version manifest. It intentionally skips search, archive,
and legacy blog routes.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import html
import json
import mimetypes
import os
import re
import sys
import time
from html.parser import HTMLParser
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qsl, unquote, urlencode, urlparse, urlunparse
from urllib.request import Request, urlopen


BASE_URL = "https://m3.material.io"
DEFAULT_OUT = Path(__file__).resolve().parent
USER_AGENT = "Bilby-MaterialMirror/0.1 (+local documentation mirror)"
MAIN_SECTIONS = ("foundations", "styles", "components", "develop")
ASSET_HOSTS = {
    "firebasestorage.googleapis.com",
    "lh3.googleusercontent.com",
    "storage.googleapis.com",
    "fonts.gstatic.com",
    "fonts.googleapis.com",
}
IMAGE_EXTENSIONS = {".apng", ".avif", ".gif", ".ico", ".jpeg", ".jpg", ".png", ".svg", ".webp"}
FONT_EXTENSIONS = {".eot", ".otf", ".ttf", ".woff", ".woff2"}
VIDEO_EXTENSIONS = {".avi", ".m4v", ".mov", ".mp4", ".mkv", ".webm"}


class FetchFailure(RuntimeError):
    def __init__(self, url: str, message: str):
        super().__init__(f"{url}: {message}")
        self.url = url


def fetch(url: str, *, accept: str = "*/*", attempts: int = 4) -> tuple[bytes, dict[str, str]]:
    """Fetch a public resource with bounded retries and a polite user agent."""

    last_error: Exception | None = None
    for attempt in range(attempts):
        request = Request(
            url,
            headers={
                "Accept": accept,
                "User-Agent": USER_AGENT,
            },
        )
        try:
            with urlopen(request, timeout=45) as response:
                return response.read(), {key.lower(): value for key, value in response.headers.items()}
        except HTTPError as error:
            last_error = error
            if error.code not in {408, 425, 429, 500, 502, 503, 504}:
                raise FetchFailure(url, f"HTTP {error.code}") from error
            retry_after = error.headers.get("Retry-After")
            try:
                delay = min(30.0, float(retry_after)) if retry_after else 2.0 ** attempt
            except ValueError:
                delay = 2.0 ** attempt
            time.sleep(delay)
        except (URLError, TimeoutError, OSError) as error:
            last_error = error
            time.sleep(min(30.0, 2.0 ** attempt))
    raise FetchFailure(url, str(last_error or "unknown fetch error"))


def write_bytes(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".part")
    temporary.write_bytes(data)
    temporary.replace(path)


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".part")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def extract_main_script(home_html: str) -> str:
    match = re.search(r'<script[^>]+src=["\']([^"\']*?/main\.[^"\']+?\.js)["\']', home_html, re.I)
    if not match:
        raise RuntimeError("could not find the current Angular main bundle")
    return match.group(1) if match.group(1).startswith("http") else BASE_URL + match.group(1)


def extract_shell_urls(home_html: str) -> list[str]:
    urls = re.findall(r'<(?:script|link)[^>]+(?:src|href)=["\']([^"\']+)["\']', home_html, re.I)
    return sorted({url if url.startswith("http") else BASE_URL + url for url in urls if url.startswith("/")})


def discover_routes(bundle: str, version: str) -> list[dict[str, str]]:
    version_match = re.search(r'carbonVersion:"([^"]+)"', bundle)
    if version_match and version_match.group(1) != version:
        raise RuntimeError(f"bundle version mismatch: expected {version}, got {version_match.group(1)}")

    pairs = re.findall(r'"slug":"([^"]*)","exportedCarbonFileId":"([^"]+?\.json)"', bundle)
    seen: set[str] = set()
    routes: list[dict[str, str]] = []
    for slug, file_id in pairs:
        if slug in seen:
            continue
        if slug and not any(slug == section or slug.startswith(section + "/") for section in MAIN_SECTIONS):
            continue
        seen.add(slug)
        routes.append(
            {
                "slug": slug,
                "content_id": file_id,
                "content_url": f"{BASE_URL}/_dsm/content/m3/{version}/{file_id}",
            }
        )
    if not routes:
        raise RuntimeError("no primary M3 routes found in the current bundle")
    return sorted(routes, key=lambda route: route["slug"])


def page_path(out: Path, slug: str) -> Path:
    return out / "pages" / ("index.md" if not slug else slug + ".md")


def raw_page_path(out: Path, slug: str) -> Path:
    return out / "metadata" / "raw-pages" / ("index.json" if not slug else slug + ".json")


def extract_urls(raw: bytes) -> set[str]:
    text = html.unescape(raw.decode("utf-8", errors="ignore")).replace("\\/", "/")
    urls = set()
    for match in re.findall(r"https?://[^\"'<>\\\s]+", text):
        urls.add(match.rstrip(".,;)]}"))
    return urls


def normalize_text(value: Any) -> str:
    return re.sub(r"\s+", " ", html.unescape(str(value or ""))).strip()


def escape_table(value: Any) -> str:
    return normalize_text(value).replace("|", "\\|").replace("\n", " ")


def clean_markdown(value: str) -> str:
    value = value.replace("\r\n", "\n").replace("\r", "\n")
    value = re.sub(r"[ \t]+\n", "\n", value)
    value = re.sub(r"\n{3,}", "\n\n", value)
    value = re.sub(r"\n[ \t]+\n", "\n\n", value)
    value = re.sub(r"(?m)^([*-]|\d+\.)[ \t]*\n(?:[ \t]*\n)?[ \t]*(?=\S)", r"\1 ", value)
    value = re.sub(r"(?m)^([-*] .+)\n\n(?=^[-*] )", r"\1\n", value)
    value = re.sub(r"(?m)^(\d+\. .+)\n\n(?=^\d+\. )", r"\1\n", value)
    return value.strip() + "\n"


class HtmlToMarkdown(HTMLParser):
    """Small dependency-free HTML-to-Markdown converter for CMS snippets."""

    BLOCK_TAGS = {"article", "aside", "blockquote", "div", "figure", "footer", "header", "main", "nav", "p", "section"}

    def __init__(self, resolver):
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []
        self.resolver = resolver
        self.list_stack: list[str] = []
        self.link_stack: list[str] = []
        self.pre_depth = 0

    def block(self) -> None:
        if self.parts and not self.parts[-1].endswith("\n\n"):
            self.parts.append("\n\n")

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        tag = tag.lower()
        attributes = dict(attrs)
        if tag in {"h1", "h2", "h3", "h4", "h5", "h6"}:
            self.block()
            self.parts.append("#" * int(tag[1]) + " ")
        elif tag in self.BLOCK_TAGS:
            self.block()
        elif tag in {"ul", "ol"}:
            self.block()
            self.list_stack.append(tag)
        elif tag == "li":
            self.block()
            prefix = "1. " if self.list_stack and self.list_stack[-1] == "ol" else "- "
            self.parts.append(prefix)
        elif tag in {"strong", "b"}:
            self.parts.append("**")
        elif tag in {"em", "i"}:
            self.parts.append("*")
        elif tag == "code" and self.pre_depth == 0:
            self.parts.append("`")
        elif tag == "pre":
            self.block()
            self.pre_depth += 1
            self.parts.append("```\n")
        elif tag == "br":
            self.parts.append("\n")
        elif tag == "a":
            self.parts.append("[")
            self.link_stack.append(attributes.get("href") or "")
        elif tag == "img":
            src = attributes.get("src") or attributes.get("data-src") or ""
            if src:
                self.block()
                self.parts.append(f"![{escape_table(attributes.get('alt') or 'image')}]({self.resolver(src)})")
                self.block()
        elif tag in {"td", "th"}:
            self.parts.append("| ")

    def handle_endtag(self, tag: str) -> None:
        tag = tag.lower()
        if tag in {"h1", "h2", "h3", "h4", "h5", "h6", "p", "li", "td", "th", "tr"} | self.BLOCK_TAGS:
            self.block()
        elif tag in {"ul", "ol"}:
            if self.list_stack:
                self.list_stack.pop()
            self.block()
        elif tag in {"strong", "b"}:
            self.parts.append("**")
        elif tag in {"em", "i"}:
            self.parts.append("*")
        elif tag == "code" and self.pre_depth == 0:
            self.parts.append("`")
        elif tag == "pre":
            self.pre_depth = max(0, self.pre_depth - 1)
            self.parts.append("\n```\n")
            self.block()
        elif tag == "a":
            href = self.link_stack.pop() if self.link_stack else ""
            self.parts.append(f"]({self.resolver(href)})" if href else "]")

    def handle_data(self, data: str) -> None:
        if self.pre_depth:
            self.parts.append(data)
        else:
            self.parts.append(re.sub(r"\s+", " ", data))

    def convert(self, value: str) -> str:
        try:
            self.feed(value)
            self.close()
        except Exception:
            return normalize_text(re.sub(r"<[^>]+>", " ", value))
        return clean_markdown("".join(self.parts))


def markdown_resolver(url: str, asset_map: dict[str, str], out: Path, markdown_path: Path) -> str:
    url = html.unescape(url or "")
    if not url:
        return url
    local = asset_map.get(url)
    if not local:
        parsed = urlparse(url)
        if parsed.hostname not in ASSET_HOSTS:
            query = [(key, value) for key, value in parse_qsl(parsed.query, keep_blank_values=True) if key != "cachebust" and not key.startswith("utm_")]
            return urlunparse(parsed._replace(query=urlencode(query)))
        return url
    return Path(os.path.relpath(out / local, markdown_path.parent)).as_posix()


def resource_references(value: Any, include_token_tables: bool = False) -> list[dict[str, str]]:
    found: dict[tuple[str, str], dict[str, str]] = {}

    def walk(node: Any) -> None:
        if isinstance(node, dict):
            name = node.get("resourceName")
            module_type = node.get("libraryModuleType")
            if name and module_type and (include_token_tables or module_type != "TOKEN_TABLE"):
                found[(str(name), str(module_type))] = {"resource_name": str(name), "module_type": str(module_type)}
            for child in node.values():
                walk(child)
        elif isinstance(node, list):
            for child in node:
                walk(child)

    walk(value)
    return sorted(found.values(), key=lambda item: (item["module_type"], item["resource_name"]))


def resource_url(version: str, reference: dict[str, str]) -> str:
    name = reference["resource_name"]
    if reference["module_type"] == "TOKEN_TABLE":
        name = name.split("components/", 1)[-1]
        filename = "TOKEN_TABLE." + name
    else:
        filename = name.replace("/", "_")
    return f"{BASE_URL}/_dsm/data/dsdb-m3/{version}/{filename}.json"


DROP_RESOURCE_KEYS = {
    "author", "authors", "createTime", "email", "gaiaId", "imageUrl", "name",
    "revisionAuthor", "revisionCreateTime", "revisionId", "updatedTimestamp",
}


def render_json_value(value: Any, resolver, level: int = 4, key: str = "") -> str:
    if level > 6 or value is None or value == [] or value == {}:
        return ""
    if isinstance(value, (str, int, float, bool)):
        return f"- **{key}**: {escape_table(value)}\n" if key else escape_table(value)
    if isinstance(value, list):
        objects = [item for item in value if isinstance(item, dict)]
        if objects:
            columns = []
            for item in objects:
                for column in item:
                    if column in DROP_RESOURCE_KEYS or column not in columns:
                        if column not in DROP_RESOURCE_KEYS:
                            columns.append(column)
            columns = columns[:8]
            if columns:
                lines = ["| " + " | ".join(columns) + " |", "| " + " | ".join("---" for _ in columns) + " |"]
                for item in objects:
                    lines.append("| " + " | ".join(escape_table(item.get(column, "")) for column in columns) + " |")
                return "\n".join(lines) + "\n"
        return "\n".join(f"- {escape_table(item)}" for item in value if isinstance(item, (str, int, float, bool))) + "\n"
    if isinstance(value, dict):
        chunks: list[str] = []
        for child_key, child_value in value.items():
            if child_key in DROP_RESOURCE_KEYS or child_value in (None, [], {}):
                continue
            label = re.sub(r"(?<!^)([A-Z])", r" \1", str(child_key)).title()
            if isinstance(child_value, (str, int, float, bool)):
                chunks.append(f"- **{label}**: {escape_table(child_value)}")
            else:
                nested = render_json_value(child_value, resolver, level + 1, label)
                if nested:
                    chunks.append(f"##### {label}\n\n{nested.strip()}")
        return "\n\n".join(chunks) + ("\n" if chunks else "")
    return ""


def render_resource(value: Any, module_type: str, resolver) -> str:
    if not isinstance(value, dict):
        return ""
    title = value.get("displayName") or module_type.replace("_", " ").title()
    parts = [f"#### {title}"]
    definition = value.get("definition")
    if definition:
        parts.append(normalize_text(definition))
    image = value.get("componentImage")
    if isinstance(image, dict) and image.get("imageUrl"):
        parts.append(f"![{escape_table(title)}]({resolver(image['imageUrl'])})")
    if "connections" in value and isinstance(value["connections"], list):
        rows = []
        for connection in value["connections"]:
            if isinstance(connection, dict):
                rows.append({key: connection.get(key, "") for key in ("displayName", "resourceType", "status", "resourceUrl")})
        if rows:
            parts.append("| Implementation | Type | Status | Link |\n| --- | --- | --- | --- |\n" + "\n".join(
                f"| {escape_table(row['displayName'])} | {escape_table(row['resourceType'])} | {escape_table(row['status'])} | "
                f"{('[' + escape_table(row['resourceUrl']) + '](' + resolver(row['resourceUrl']) + ')') if row['resourceUrl'] else ''} |"
                for row in rows
            ))
    useful = {key: value[key] for key in value if key not in {"connections", "componentImage"} and key not in DROP_RESOURCE_KEYS}
    rendered = render_json_value(useful, resolver)
    if rendered:
        parts.append(rendered)
    return clean_markdown("\n\n".join(parts))


def render_page(page: dict[str, Any], resources: list[tuple[str, Any]], asset_map: dict[str, str], out: Path, markdown_path: Path) -> str:
    resolver = lambda url: markdown_resolver(url, asset_map, out, markdown_path)
    title = normalize_text(page.get("headerTitle") or page.get("title") or "Material Design")
    parts = [f"# {title}"]
    description = page.get("description")
    if description:
        parts.append(normalize_text(description))
    header_image = page.get("headerImageUrl")
    if header_image:
        parts.append(f"![{escape_table(title)}]({resolver(header_image)})")

    for section in page.get("sections") or []:
        section_name = normalize_text(section.get("name"))
        if section_name and section_name.lower() not in {"tab 1", "tab1"}:
            parts.append(f"## {section_name}")
        for block in section.get("contentBlocks") or []:
            block_title = normalize_text(block.get("title"))
            if block_title:
                parts.append(f"### {block_title}")
            for chunk in block.get("contentChunks") or []:
                chunk_parts: list[str] = []
                html_value = chunk.get("htmlValue")
                if html_value:
                    chunk_parts.append(HtmlToMarkdown(resolver).convert(html_value))
                image_url = chunk.get("imageUrl") or chunk.get("imageUrlFife")
                if image_url and not html_value:
                    chunk_parts.append(f"![{escape_table(chunk.get('altText') or 'image')}]({resolver(image_url)})")
                snippet = chunk.get("snippetCode")
                if snippet:
                    language = normalize_text(chunk.get("snippetLanguage"))
                    chunk_parts.append(f"```{language}\n{snippet.rstrip()}\n```")
                for field, label in (("codeUrl", "Code example"), ("prototypeUrl", "Prototype"), ("videoUrl", "Video"), ("linkUrl", "Source") ):
                    url = chunk.get(field)
                    if url:
                        chunk_parts.append(f"[{label}]({resolver(url)})")
                footer = chunk.get("footer")
                if footer:
                    footer_text = HtmlToMarkdown(resolver).convert(footer) if "<" in str(footer) else normalize_text(footer)
                    if footer_text:
                        if re.search(r"<(?:ul|ol|li)\b", str(footer), re.I):
                            chunk_parts.append(footer_text.strip())
                        else:
                            chunk_parts.append(f"_{footer_text.strip()}_")
                if chunk_parts:
                    parts.append("\n\n".join(part for part in chunk_parts if part.strip()))

    for module_type, resource in resources:
        if module_type == "TOKEN_TABLE":
            continue
        rendered = render_resource(resource, module_type, resolver)
        if rendered:
            parts.append(rendered)
    return clean_markdown("\n\n".join(parts))


def asset_kind(url: str) -> str | None:
    parsed = urlparse(url)
    if parsed.hostname not in ASSET_HOSTS:
        return None
    suffix = Path(unquote(parsed.path)).suffix.lower()
    if suffix in VIDEO_EXTENSIONS:
        return "video"
    if suffix in FONT_EXTENSIONS or parsed.hostname in {"fonts.gstatic.com", "fonts.googleapis.com"}:
        return "font"
    if suffix in IMAGE_EXTENSIONS or parsed.hostname in {"firebasestorage.googleapis.com", "lh3.googleusercontent.com"}:
        return "image"
    return "other"


def asset_path(out: Path, url: str, kind: str, content_type: str | None = None) -> Path:
    parsed = urlparse(url)
    suffix = Path(unquote(parsed.path)).suffix.lower()
    if not suffix:
        guessed = mimetypes.guess_extension((content_type or "").split(";", 1)[0].strip())
        suffix = guessed or ".bin"
    digest = hashlib.sha256(url.encode("utf-8")).hexdigest()[:16]
    return out / "assets" / kind / (digest + suffix)


def fetch_to_path(url: str, path: Path, refresh: bool) -> dict[str, Any]:
    if path.exists() and not refresh:
        data = path.read_bytes()
        return {"url": url, "path": path.as_posix(), "status": "cached", "sha256": sha256(data), "bytes": len(data)}
    data, headers = fetch(url)
    write_bytes(path, data)
    return {
        "url": url,
        "path": path.as_posix(),
        "status": "downloaded",
        "sha256": sha256(data),
        "bytes": len(data),
        "content_type": headers.get("content-type"),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT, help="mirror output directory")
    parser.add_argument("--workers", type=int, default=6, help="maximum concurrent requests")
    parser.add_argument("--no-assets", action="store_true", help="write the asset manifest without downloading assets")
    parser.add_argument("--all-assets", action="store_true", help="also download video and other media")
    parser.add_argument("--keep-json", action="store_true", help="keep the raw page/resource JSON under metadata")
    parser.add_argument("--include-token-tables", action="store_true", help="include noisy low-level component token tables")
    parser.add_argument("--refresh", action="store_true", help="redownload files already present")
    args = parser.parse_args()

    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    metadata = out / "metadata"

    home_url = BASE_URL + "/"
    home_html, _ = fetch(home_url, accept="text/html")
    write_bytes(metadata / "home.html", home_html)
    home_text = home_html.decode("utf-8", errors="replace")

    robots, _ = fetch(BASE_URL + "/robots.txt", accept="text/plain")
    write_bytes(metadata / "robots.txt", robots)
    sitemap, _ = fetch(BASE_URL + "/sitemap.xml", accept="application/xml")
    write_bytes(metadata / "sitemap.xml", sitemap)

    main_url = extract_main_script(home_text)
    bundle, _ = fetch(main_url, accept="text/javascript")
    write_bytes(metadata / "main.js", bundle)
    bundle_text = bundle.decode("utf-8", errors="replace")
    version_match = re.search(r'carbonVersion:"([^"]+)"', bundle_text)
    if not version_match:
        raise RuntimeError("could not find carbonVersion in the main bundle")
    version = version_match.group(1)

    routes = discover_routes(bundle_text, version)
    write_json(metadata / "routes.json", routes)

    shell_urls = extract_shell_urls(home_text)
    shell_records: list[dict[str, Any]] = []
    for url in shell_urls:
        parsed = urlparse(url)
        if parsed.hostname != urlparse(BASE_URL).hostname:
            continue
        name = Path(parsed.path).name
        if not name or name in {"main.js"}:
            continue
        try:
            record = fetch_to_path(url, out / "shell" / name, args.refresh)
            shell_records.append(record)
        except FetchFailure as error:
            shell_records.append({"url": url, "status": "error", "error": str(error)})
    write_json(metadata / "shell-assets.json", shell_records)

    page_records: list[dict[str, Any]] = []
    page_payloads: dict[str, dict[str, Any]] = {}
    page_urls: set[str] = set()

    def download_page(route: dict[str, str]) -> dict[str, Any]:
        try:
            raw, headers = fetch(route["content_url"], accept="application/json")
            payload = json.loads(raw)
            if args.keep_json:
                write_bytes(raw_page_path(out, route["slug"]), raw)
            record = {
                "slug": route["slug"],
                "content_id": route["content_id"],
                "status": "downloaded",
                "sha256": sha256(raw),
                "bytes": len(raw),
                "content_type": headers.get("content-type"),
                "md_path": page_path(out, route["slug"]).relative_to(out).as_posix(),
            }
            return {"record": record, "payload": payload, "urls": extract_urls(raw)}
        except (FetchFailure, json.JSONDecodeError, OSError) as error:
            return {"record": {"slug": route["slug"], "content_id": route["content_id"], "status": "error", "error": str(error)}, "payload": {}, "urls": set()}

    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, args.workers)) as pool:
        futures = [pool.submit(download_page, route) for route in routes]
        for index, future in enumerate(concurrent.futures.as_completed(futures), start=1):
            result = future.result()
            page_records.append(result["record"])
            if result["record"].get("status") != "error":
                page_payloads[result["record"]["slug"]] = result["payload"]
            page_urls.update(result["urls"])
            print(f"pages {index}/{len(futures)}", flush=True)

    page_records.sort(key=lambda record: record.get("slug", ""))

    resource_refs_by_page: dict[str, list[dict[str, str]]] = {}
    resource_jobs: dict[str, dict[str, str]] = {}
    for slug, payload in page_payloads.items():
        refs = resource_references(payload, args.include_token_tables)
        resource_refs_by_page[slug] = refs
        for reference in refs:
            url = resource_url(version, reference)
            resource_jobs[url] = reference

    resource_values: dict[str, Any] = {}
    resource_records: list[dict[str, Any]] = []

    def download_resource(item: tuple[str, dict[str, str]]) -> dict[str, Any]:
        url, reference = item
        try:
            raw, headers = fetch(url, accept="application/json")
            payload = json.loads(raw)
            if args.keep_json:
                filename = hashlib.sha256(url.encode("utf-8")).hexdigest()[:20] + ".json"
                write_bytes(out / "metadata" / "raw-resources" / filename, raw)
            return {
                "url": url,
                "resource_name": reference["resource_name"],
                "module_type": reference["module_type"],
                "status": "downloaded",
                "sha256": sha256(raw),
                "bytes": len(raw),
                "content_type": headers.get("content-type"),
                "payload": payload,
                "urls": extract_urls(raw),
            }
        except (FetchFailure, json.JSONDecodeError, OSError) as error:
            return {"url": url, "resource_name": reference["resource_name"], "module_type": reference["module_type"], "status": "error", "error": str(error), "payload": {}, "urls": set()}

    resource_items = list(resource_jobs.items())
    if resource_items:
        with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, args.workers)) as pool:
            futures = [pool.submit(download_resource, item) for item in resource_items]
            for index, future in enumerate(concurrent.futures.as_completed(futures), start=1):
                result = future.result()
                payload = result.pop("payload", {})
                resource_values[result["url"]] = payload
                resource_records.append(result)
                page_urls.update(result.pop("urls", set()))
                print(f"resources {index}/{len(futures)}", flush=True)
    resource_records.sort(key=lambda record: record["url"])
    write_json(metadata / "resources.json", resource_records)

    assets: list[tuple[str, str]] = []
    for url in sorted(page_urls):
        kind = asset_kind(url)
        if kind and (args.all_assets or kind in {"image", "font"}):
            assets.append((url, kind))
    asset_records: list[dict[str, Any]] = []

    def download_asset(item: tuple[str, str]) -> dict[str, Any]:
        url, kind = item
        path = asset_path(out, url, kind)
        try:
            record = fetch_to_path(url, path, args.refresh)
            record["path"] = path.relative_to(out).as_posix()
            record["kind"] = kind
            return record
        except FetchFailure as error:
            return {"url": url, "kind": kind, "path": path.as_posix(), "status": "error", "error": str(error)}

    if not args.no_assets:
        with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, args.workers)) as pool:
            futures = [pool.submit(download_asset, item) for item in assets]
            for index, future in enumerate(concurrent.futures.as_completed(futures), start=1):
                asset_records.append(future.result())
                if index % 25 == 0 or index == len(futures):
                    print(f"assets {index}/{len(futures)}", flush=True)
    else:
        previous_records: dict[str, dict[str, Any]] = {}
        previous_manifest = metadata / "assets.json"
        if previous_manifest.exists():
            try:
                previous_records = {record["url"]: record for record in json.loads(previous_manifest.read_text(encoding="utf-8"))}
            except (json.JSONDecodeError, OSError, KeyError, TypeError):
                previous_records = {}
        asset_records = []
        for url, kind in assets:
            previous = previous_records.get(url)
            if previous and previous.get("path"):
                previous_path = Path(previous["path"])
                if previous_path.is_absolute():
                    try:
                        previous["path"] = previous_path.resolve().relative_to(out).as_posix()
                    except ValueError:
                        previous = None
                if previous and (out / previous["path"]).exists():
                    asset_records.append(previous)
                    continue
            if not previous:
                asset_records.append({"url": url, "kind": kind, "status": "not-downloaded"})
            else:
                asset_records.append({"url": url, "kind": kind, "status": "not-downloaded"})
    asset_records.sort(key=lambda record: record["url"])
    write_json(metadata / "assets.json", asset_records)

    asset_map = {
        record["url"]: record["path"]
        for record in asset_records
        if record.get("path") and record.get("status") in {"downloaded", "cached"}
    }
    for record in page_records:
        slug = record.get("slug", "")
        payload = page_payloads.get(slug)
        if not payload:
            continue
        markdown_path = page_path(out, slug)
        resources_for_page: list[tuple[str, Any]] = []
        for reference in resource_refs_by_page.get(slug, []):
            url = resource_url(version, reference)
            if url in resource_values:
                resources_for_page.append((reference["module_type"], resource_values[url]))
        markdown = render_page(payload, resources_for_page, asset_map, out, markdown_path)
        markdown_path.parent.mkdir(parents=True, exist_ok=True)
        markdown_path.write_text(markdown, encoding="utf-8")
        record["markdown_bytes"] = len(markdown.encode("utf-8"))
    write_json(metadata / "pages.json", page_records)

    manifest = {
        "source": BASE_URL,
        "fetched_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "carbon_version": version,
        "scope": ["home", *MAIN_SECTIONS],
        "route_count": len(routes),
        "page_count": len([record for record in page_records if record.get("status") != "error"]),
        "markdown_count": len([record for record in page_records if record.get("markdown_bytes") is not None]),
        "resource_count": len(resource_records),
        "asset_reference_count": len(page_urls),
        "asset_count": len(asset_records),
        "asset_downloaded_count": len([record for record in asset_records if record.get("status") in {"downloaded", "cached"}]),
        "notes": [
            "Generated data is intentionally kept outside Git via .git/info/exclude.",
            "Search, archive, legacy blog, analytics, and third-party page links are not crawled.",
            "Review the source site's terms and each asset's license before redistribution.",
        ],
    }
    write_json(out / "manifest.json", manifest)
    print(json.dumps(manifest, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print("interrupted", file=sys.stderr)
        raise SystemExit(130)
