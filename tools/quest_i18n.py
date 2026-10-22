#!/usr/bin/env python3
"""Migrate, export and validate GTLCore quest translations (Python 3.10+).

Parsing retains source spans; substitutions only touch explicitly selected
text values.  Numeric types, whitespace, comments, IDs, item NBT and commands are
never rewritten by serializing the whole document. Inputs are read-only and
outputs must be new files/directories outside the input quest tree. See
tools/quest-i18n-usage.txt for the format and complete examples.
"""
from __future__ import annotations

import argparse
import ast
import collections
import copy
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import re
from typing import Any

PREFIX = "gtlcore.quest.gtl."
DEFAULT_LANGUAGE = "zh_cn"
MANIFEST = {"format": 1, "default_language": DEFAULT_LANGUAGE, "key_prefix": PREFIX}
REFERENCE = re.compile(r"^\{[A-Za-z0-9_./:-]+\}$")
LANGUAGE = re.compile(r"[a-z]{2,8}(?:_[a-z0-9]{2,8}){0,2}")
KEY = re.compile(r"^gtlcore\.quest\.gtl\.[a-z_]+\.(?:[0-9a-f]{16}|root)\.[a-z_]+(?:\.[0-9]{4})?(?:\.text\.[0-9]{4})?$")
CATALOG_KEY = re.compile(r"[a-z0-9_.-]+")
MAX_VALUE_LENGTH = 16384  # Java String.length(): UTF-16 code units, not Python characters.
MAX_KEY_LENGTH = 256
MAX_FILE_BYTES = 16 * 1024 * 1024
MAX_ENTRIES = 100000
MAX_LANGUAGES = 256
MAX_TOTAL_BYTES = 64 * 1024 * 1024


@dataclass(frozen=True)
class Token:
    raw: str
    start: int
    end: int


@dataclass
class Node:
    kind: str
    value: Any
    start: int
    end: int

    def semantic(self) -> Any:
        if self.kind == "compound":
            return {key: value.semantic() for key, value in self.value.items()}
        if self.kind == "list":
            return [value.semantic() for value in self.value]
        if self.kind == "typed_list":
            return {"$nbt_type": self.value[0], "items": [x.semantic() for x in self.value[1]]}
        return self.value


TOKEN = re.compile(
    r'(?P<skip>[\s\ufeff]+|//[^\r\n]*|\#[^\r\n]*)'
    r'|(?P<string>"(?:[^"\\]|\\.)*"|\'(?:[^\'\\]|\\.)*\')'
    r'|(?P<punct>[{}\[\]:,;])|(?P<bare>[^\s{}\[\]:,;"\']+)',
    re.DOTALL,
)


def decode_string(raw: str) -> str:
    value = json.loads(raw) if raw.startswith('"') else ast.literal_eval(raw)
    if not isinstance(value, str):
        raise ValueError("SNBT string did not decode to text")
    return value


class Parser:
    def __init__(self, text: str, filename: str = "<input>"):
        self.text = text
        self.filename = filename
        self.tokens: list[Token] = []
        end = 0
        for match in TOKEN.finditer(text):
            if match.start() != end:
                raise ValueError(f"{filename}: unparsed characters at {end}")
            end = match.end()
            if match.lastgroup != "skip":
                self.tokens.append(Token(match.group(), match.start(), match.end()))
        if end != len(text):
            raise ValueError(f"{filename}: unparsed trailing text at {end}")
        self.index = 0

    def peek(self, offset: int = 0) -> str:
        if self.index + offset >= len(self.tokens):
            return ""
        return self.tokens[self.index + offset].raw

    def pop(self) -> Token:
        if self.index == len(self.tokens):
            raise ValueError(f"{self.filename}: unexpected end of SNBT")
        token = self.tokens[self.index]
        self.index += 1
        return token

    def expect(self, raw: str) -> Token:
        token = self.pop()
        if token.raw != raw:
            raise ValueError(f"{self.filename}:{token.start}: expected {raw!r}, got {token.raw!r}")
        return token

    def value(self) -> Node:
        token = self.pop()
        if token.raw == "{":
            values: dict[str, Node] = {}
            while self.peek() != "}":
                if self.peek() == ",":
                    self.pop()
                    continue
                key_token = self.pop()
                key = decode_string(key_token.raw) if key_token.raw.startswith(('"', "'")) else key_token.raw
                if key in values:
                    raise ValueError(f"{self.filename}:{key_token.start}: duplicate key {key!r}")
                self.expect(":")
                values[key] = self.value()
            return Node("compound", values, token.start, self.pop().end)
        if token.raw == "[":
            type_name = None
            if self.peek() in ("B", "I", "L") and self.peek(1) == ";":
                type_name = self.pop().raw
                self.pop()
            values = []
            while self.peek() != "]":
                if self.peek() == ",":
                    self.pop()
                    continue
                values.append(self.value())
            end = self.pop().end
            return Node("typed_list" if type_name else "list", (type_name, values) if type_name else values, token.start, end)
        if token.raw.startswith(('"', "'")):
            return Node("string", decode_string(token.raw), token.start, token.end)
        if token.raw in ("}", "]", ":", ",", ";"):
            raise ValueError(f"{self.filename}:{token.start}: unexpected {token.raw!r}")
        return Node("literal", token.raw, token.start, token.end)

    def parse(self) -> Node | None:
        if not self.tokens:
            return None
        root = self.value()
        if self.index != len(self.tokens):
            raise ValueError(f"{self.filename}: trailing SNBT tokens")
        if root.kind != "compound":
            raise ValueError(f"{self.filename}: root must be a compound")
        return root


@dataclass
class Document:
    path: Path
    relative: str
    data: bytes
    text: str
    root: Node | None


@dataclass
class TextField:
    document: Document
    object_kind: str
    object_id: str
    field: str
    path: tuple[Any, ...]
    node: Node

    @property
    def identity(self) -> str:
        return f"{self.object_kind}.{self.object_id}.{self.field}"

    @property
    def source(self) -> str | list[str]:
        if self.node.kind == "string":
            return self.node.value
        if self.node.kind == "list" and all(x.kind == "string" for x in self.node.value):
            return [x.value for x in self.node.value]
        raise ValueError(f"{self.document.relative}: {self.identity}: unexpected text type {self.node.kind}")


def child_objects(node: Node, key: str) -> list[Node]:
    child = node.value.get(key)
    if child is None:
        return []
    if child.kind != "list" or any(x.kind != "compound" for x in child.value):
        raise ValueError(f"{key}: expected compound list")
    return child.value


def object_id(node: Node, kind: str, filename: str) -> str:
    ident = node.value.get("id")
    if ident is None or ident.kind != "string" or not re.fullmatch(r"[0-9A-Fa-f]{16}", ident.value):
        raise ValueError(f"{filename}: {kind} has no valid stable ID")
    return ident.value.lower()


def scan(source: Path) -> tuple[list[Document], list[TextField], dict[str, Any]]:
    documents: list[Document] = []
    fields: list[TextField] = []
    objects: collections.Counter[str] = collections.Counter()
    kinds: collections.Counter[str] = collections.Counter()
    seen: dict[str, str] = {}

    def add_object(doc: Document, node: Node, kind: str, path: tuple[Any, ...], names: tuple[str, ...]):
        ident = "root" if kind == "book" else object_id(node, kind, doc.relative)
        if kind != "book":
            if ident in seen:
                raise ValueError(f"Duplicate object ID {ident} in {doc.relative} and {seen[ident]}")
            seen[ident] = doc.relative
        objects[kind] += 1
        for name in names:
            if name not in node.value:
                continue
            entry = TextField(doc, kind, ident, name, path + (name,), node.value[name])
            value = entry.source
            # Preserve absent and empty defaults: they invoke FTB's dynamic titles.
            if value == "" or value == []:
                continue
            fields.append(entry)
        if kind in ("task", "reward") and "type" in node.value:
            kinds[f"{kind}.{node.value['type'].value}"] += 1

    for path in sorted(source.rglob("*.snbt")):
        relative = path.relative_to(source).as_posix()
        data = path.read_bytes()
        text = data.decode("utf-8")
        root = Parser(text, relative).parse()
        doc = Document(path, relative, data, text, root)
        documents.append(doc)
        if root is None:
            continue
        if relative == "data.snbt":
            add_object(doc, root, "book", (), ("title", "lock_message"))
        elif relative == "chapter_groups.snbt":
            for index, node in enumerate(child_objects(root, "chapter_groups")):
                add_object(doc, node, "group", ("chapter_groups", index), ("title",))
        elif relative.startswith("chapters/"):
            add_object(doc, root, "chapter", (), ("title", "subtitle"))
            for index, quest in enumerate(child_objects(root, "quests")):
                qpath = ("quests", index)
                add_object(doc, quest, "quest", qpath, ("title", "subtitle", "description"))
                for name, kind in (("tasks", "task"), ("rewards", "reward")):
                    for child_index, node in enumerate(child_objects(quest, name)):
                        add_object(doc, node, kind, qpath + (name, child_index), ("title",))
            for index, node in enumerate(child_objects(root, "quest_links")):
                add_object(doc, node, "quest_link", ("quest_links", index), ("title",))
            objects["image"] += len(child_objects(root, "images"))
        elif relative.startswith("reward_tables/"):
            add_object(doc, root, "reward_table", (), ("title",))
        elif relative.startswith("lang/"):
            raise ValueError(f"Nonempty legacy language file needs explicit review: {relative}")
        else:
            raise ValueError(f"Unknown quest SNBT file needs explicit review: {relative}")
    units = [line for field in fields for line in (field.source if isinstance(field.source, list) else [field.source])]
    stats = {
        "source": str(source.resolve()),
        "files": len(documents),
        "objects": dict(objects),
        "types": dict(kinds),
        "translatable_fields": len(fields),
        "field_kinds": dict(collections.Counter(f"{x.object_kind}.{x.field}:{x.node.kind}" for x in fields)),
        "text_units": len(units),
        "empty_lines": sum(x == "" for x in units),
        "pagebreaks": units.count("{@pagebreak}"),
        "click_events": sum("clickEvent" in x for x in units),
        "inline_newlines": sum("\n" in x or "\r" in x for x in units),
        "source_files_sha256": {doc.relative: hashlib.sha256(doc.data).hexdigest() for doc in documents},
    }
    return documents, fields, stats


def replace_spans(text: str, replacements: list[tuple[int, int, str]]) -> str:
    """Apply disjoint selected-value edits while preserving all other characters."""
    ordered = sorted(replacements)
    end = 0
    chunks = []
    for start, stop, replacement in ordered:
        if start < end or stop < start or stop > len(text):
            raise ValueError("Overlapping or invalid source replacement")
        chunks.extend((text[end:start], replacement))
        end = stop
    chunks.append(text[end:])
    return "".join(chunks)


def lookup_path(root: Node, path: tuple[Any, ...]) -> Node:
    node = root
    for part in path:
        node = node.value[part]
    return node


def assert_selected_values_only(document: Document, result: str,
                                replacements: list[tuple[TextField, str]]) -> None:
    """Round-trip exact original SNBT, not merely parsed equality.

    This independently reparses the output, locates edited values by object path,
    restores their original raw source, and requires byte-identical original data.
    Thus accidentally modified commands, item NBT, dependencies, whitespace or IDs
    fail verification even if they are not mentioned in the text schema.
    """
    parsed = Parser(result, document.relative + " (converted)").parse()
    if document.root is None:
        if result != document.text:
            raise ValueError("Empty legacy file changed unexpectedly")
        return
    if parsed is None:
        raise ValueError("Conversion removed a nonempty document")
    reverse = []
    for field, expected_value in replacements:
        node = lookup_path(parsed, field.path)
        expected = Parser("{v:" + expected_value + "}").parse().value["v"]
        if node.semantic() != expected.semantic() or node.kind != expected.kind:
            raise ValueError(f"{document.relative}: replacement value differs at {field.identity}")
        reverse.append((node.start, node.end, document.text[field.node.start:field.node.end]))
    restored = replace_spans(result, reverse).encode("utf-8")
    if restored != document.data:
        raise ValueError(f"{document.relative}: bytes outside selected text values were changed")


def unique_json_pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def read_json(path: Path):
    if path.stat().st_size > MAX_FILE_BYTES:
        raise ValueError(f"JSON file exceeds {MAX_FILE_BYTES} bytes: {path}")
    with path.open("rb") as stream:
        data = stream.read(MAX_FILE_BYTES + 1)
    if len(data) > MAX_FILE_BYTES:
        raise ValueError(f"JSON file exceeds {MAX_FILE_BYTES} bytes: {path}")
    return json.loads(data.decode("utf-8-sig"), object_pairs_hook=unique_json_pairs)


def json_bytes(value: Any) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")


def digest_json(value: Any) -> str:
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                    separators=(",", ":")).encode("utf-8")).hexdigest()


def new_destination(path: Path) -> Path:
    path = path.resolve()
    if path.exists():
        raise ValueError(f"Refusing to overwrite existing output: {path}")
    return path


def require_separate_trees(source: Path, output: Path):
    source, output = source.resolve(), output.resolve()
    if output.is_relative_to(source) or source.is_relative_to(output):
        raise ValueError("Input and output must be separate trees")


def write_new(path: Path, data: bytes):
    path.parent.mkdir(parents=True, exist_ok=True)
    # Exclusive creation also covers a file appearing after an initial check.
    with path.open("xb") as stream:
        stream.write(data)


def validate_value(text: str, key: str):
    if not isinstance(text, str):
        raise ValueError(f"Translation must be a string: {key}")
    try:
        text.encode("utf-8")
    except UnicodeEncodeError as error:
        raise ValueError(f"Translation contains an unpaired Unicode surrogate: {key}") from error
    if len(text.encode("utf-16-le")) // 2 > MAX_VALUE_LENGTH or "\x00" in text:
        raise ValueError(f"Translation exceeds {MAX_VALUE_LENGTH} UTF-16 units or contains NUL: {key}")
    if "{" + PREFIX in text:
        raise ValueError(f"Quest translations cannot reference quest translation keys: {key}")
    if "{@" in text or "{image:" in text:
        raise ValueError(f"Page and image controls belong in quest structure, not translations: {key}")


def validate_dictionary(catalog: Any) -> dict[str, str]:
    if not isinstance(catalog, dict):
        raise ValueError("Language JSON must be a flat key:string object")
    if len(catalog) > MAX_ENTRIES:
        raise ValueError(f"Language JSON exceeds {MAX_ENTRIES} entries")
    for key, text in catalog.items():
        if (not isinstance(key, str) or len(key) > MAX_KEY_LENGTH or
                not key.startswith(PREFIX) or not CATALOG_KEY.fullmatch(key)):
            raise ValueError(f"Invalid quest translation key: {key!r}")
        validate_value(text, key)
    return catalog


def dictionary_bytes(catalog: dict[str, str]) -> bytes:
    data = json_bytes(validate_dictionary(catalog))
    if len(data) > MAX_FILE_BYTES:
        raise ValueError(f"Encoded language JSON exceeds {MAX_FILE_BYTES} bytes")
    return data


def load_source_catalog(quests: Path) -> dict[str, str]:
    if read_json(quests / "lang" / "gtlcore-i18n.json") != MANIFEST:
        raise ValueError("Quest language manifest differs from this tool's schema")
    return validate_dictionary(read_json(quests / "lang" / f"{DEFAULT_LANGUAGE}.json"))


def validate_language_files(files: dict[str, bytes]):
    languages = {}
    total_bytes = len(files["lang/gtlcore-i18n.json"])
    for relative, data in files.items():
        path = Path(relative)
        if path.parent.as_posix() != "lang" or path.suffix != ".json" or path.name == "gtlcore-i18n.json":
            continue
        if not LANGUAGE.fullmatch(path.stem):
            raise ValueError(f"Invalid language filename: {relative}")
        if len(data) > MAX_FILE_BYTES or len(languages) >= MAX_LANGUAGES:
            raise ValueError("Quest translations exceed the per-file or language-count limit")
        total_bytes += len(data)
        if total_bytes > MAX_TOTAL_BYTES:
            raise ValueError("Quest translations exceed the total catalog size limit")
        languages[path.stem] = validate_dictionary(json.loads(data.decode("utf-8-sig"), object_pairs_hook=unique_json_pairs))
    source = languages[DEFAULT_LANGUAGE]
    for locale, catalog in languages.items():
        if not set(catalog).issubset(source):
            raise ValueError(f"Unknown source keys in language {locale}")
    return languages


def reuse_translations(generated: dict[str, bytes], previous: Path) -> dict[str, Any]:
    """Carry translations forward only when both stable key and source agree."""
    previous = previous.resolve()
    old_files = {}
    total_bytes = 0
    for path in sorted((previous / "lang").glob("*.json")):
        if not path.resolve().is_relative_to(previous):
            raise ValueError(f"Previous translation symlink escapes its tree: {path}")
        if path.stat().st_size > MAX_FILE_BYTES:
            raise ValueError(f"JSON file exceeds {MAX_FILE_BYTES} bytes: {path}")
        with path.open("rb") as stream:
            data = stream.read(MAX_FILE_BYTES + 1)
        if len(data) > MAX_FILE_BYTES:
            raise ValueError(f"JSON file exceeds {MAX_FILE_BYTES} bytes: {path}")
        total_bytes += len(data)
        if total_bytes > MAX_TOTAL_BYTES or len(old_files) >= MAX_LANGUAGES + 1:
            raise ValueError("Previous translations exceed total size or language-count limit")
        old_files[path.relative_to(previous).as_posix()] = data
    manifest = "lang/gtlcore-i18n.json"
    if manifest not in old_files or json.loads(old_files[manifest].decode("utf-8-sig"), object_pairs_hook=unique_json_pairs) != MANIFEST:
        raise ValueError("Previous translation manifest differs from the selected schema")
    if f"lang/{DEFAULT_LANGUAGE}.json" not in old_files:
        raise ValueError("Previous translations have no default-language source dictionary")
    old_languages = validate_language_files(old_files)
    new_languages = validate_language_files(generated)
    old_source, new_source = old_languages[DEFAULT_LANGUAGE], new_languages[DEFAULT_LANGUAGE]
    totals = collections.Counter(reused=0, source_changed=0, key_removed=0, new_translation_present=0)
    languages = {}
    for locale, translations in sorted(old_languages.items()):
        if locale == DEFAULT_LANGUAGE:
            continue
        current = dict(new_languages.get(locale, {}))
        counts = collections.Counter(reused=0, source_changed=0, key_removed=0, new_translation_present=0)
        for key, value in translations.items():
            if key not in new_source:
                counts["key_removed"] += 1
            elif old_source[key] != new_source[key]:
                counts["source_changed"] += 1
            elif key in current:
                counts["new_translation_present"] += 1
            else:
                current[key] = value
                counts["reused"] += 1
        if counts["reused"]:
            generated[f"lang/{locale}.json"] = dictionary_bytes(current)
        languages[locale] = dict(counts)
        totals.update(counts)
    validate_language_files(generated)
    actual_paths = {p.relative_to(previous).as_posix() for p in (previous / "lang").glob("*.json")}
    if actual_paths != set(old_files):
        raise ValueError("Previous translation file list changed while converting")
    for relative, data in old_files.items():
        if (previous / relative).read_bytes() != data:
            raise ValueError(f"Previous translation changed during conversion: {relative}")
    return {"source": str(previous), **dict(totals), "languages": languages,
            "previous_files_sha256": {p: hashlib.sha256(data).hexdigest() for p, data in old_files.items()}}


def component_at(root: Any, path: tuple[Any, ...]) -> Any:
    node = root
    for part in path:
        node = node[part]
    return node


def replace_component_at(root: Any, path: tuple[Any, ...], value: Any) -> Any:
    if not path:
        return value
    component_at(root, path[:-1])[path[-1]] = value
    return root


def translate_component(original: Any, base_key: str, put) -> tuple[Any, list[dict[str, Any]]]:
    """Translate only Component text nodes; leave event targets and style intact."""
    ledger = []

    def text_key(text: str, path: tuple[Any, ...], kind: str) -> str:
        key = f"{base_key}.text.{len(ledger):04d}"
        put(key, text)
        ledger.append({"key": key, "path": list(path), "kind": kind, "source": text})
        return key

    def visit(node: Any, path: tuple[Any, ...]) -> Any:
        if isinstance(node, str):
            return {"translate": text_key(node, path, "string")} if node else node
        if isinstance(node, list):
            return [visit(value, path + (index,)) for index, value in enumerate(node)]
        if not isinstance(node, dict):
            return node
        result = copy.deepcopy(node)
        if isinstance(result.get("text"), str) and result["text"]:
            if "translate" in result:
                raise ValueError("Component contains both text and translate; manual review required")
            text = result.pop("text")
            result["translate"] = text_key(text, path, "text")
        for name in ("extra", "with"):
            if name in result:
                if not isinstance(result[name], list):
                    raise ValueError(f"Component {name} is not a list")
                result[name] = [visit(child, path + (name, index)) for index, child in enumerate(result[name])]
        hover = result.get("hoverEvent")
        if isinstance(hover, dict) and hover.get("action") == "show_text":
            for name in ("contents", "value"):
                if name in hover:
                    hover[name] = visit(hover[name], path + ("hoverEvent", name))
        return result

    transformed = visit(original, ())
    restored = copy.deepcopy(transformed)
    for entry in reversed(ledger):
        path = tuple(entry["path"])
        node = component_at(restored, path)
        if not isinstance(node, dict) or node.get("translate") != entry["key"]:
            raise ValueError("Transformed Component failed translation-node round trip")
        if entry["kind"] == "string":
            if set(node) != {"translate"}:
                raise ValueError("Unexpected metadata added to a string Component")
            restored = replace_component_at(restored, path, entry["source"])
        else:
            del node["translate"]
            node["text"] = entry["source"]
    if restored != original:
        raise ValueError("Component semantic round trip changed style, events or other metadata")
    return transformed, ledger


def convert(source: Path, output: Path, audit_path: Path | None = None,
            reuse_from: Path | None = None) -> dict[str, Any]:
    source = source.resolve()
    output = new_destination(output)
    require_separate_trees(source, output)
    if audit_path is not None:
        audit_path = new_destination(audit_path)
        require_separate_trees(source, audit_path)
        require_separate_trees(output, audit_path)
    if reuse_from is not None:
        reuse_from = reuse_from.resolve()
        require_separate_trees(reuse_from, output)
        if audit_path is not None:
            require_separate_trees(reuse_from, audit_path)
    documents, fields, stats = scan(source)
    original_files = {}
    for path in sorted(source.rglob("*")):
        if path.is_file():
            if not path.resolve().is_relative_to(source):
                raise ValueError(f"Source symlink escapes its tree: {path}")
            original_files[path.relative_to(source).as_posix()] = path.read_bytes()
    default_path = source / "lang" / f"{DEFAULT_LANGUAGE}.json"
    catalog = read_json(default_path) if default_path.exists() else {}
    validate_dictionary(catalog)
    manifest_path = source / "lang" / "gtlcore-i18n.json"
    if manifest_path.exists() and read_json(manifest_path) != MANIFEST:
        raise ValueError("Existing manifest differs from the selected schema")
    new_keys = {}

    def put(key: str, text: str):
        if not KEY.fullmatch(key):
            raise ValueError(f"Generated invalid key: {key}")
        if key in new_keys:
            raise ValueError(f"Duplicate generated key: {key}")
        if key in catalog and catalog[key] != text:
            raise ValueError(f"Existing source key conflicts with source text: {key}")
        validate_value(text, key)
        new_keys[key] = text
        catalog[key] = text

    skips = collections.Counter()
    transformed_fields = []
    replacements = collections.defaultdict(list)
    existing_paragraph_ids = collections.defaultdict(list)
    paragraph_key = re.compile(r"^(gtlcore\.quest\.gtl\.[a-z_]+\.(?:[0-9a-f]{16}|root)\.[a-z_]+)\.(\d{4})(?:\.text\.\d{4})?$")
    for key in catalog:
        if match := paragraph_key.fullmatch(key):
            existing_paragraph_ids[match.group(1)].append(int(match.group(2)))

    def line(text: str, key: str, has_segment_space: bool = True) -> tuple[str, list[dict[str, Any]]]:
        def record(new_key: str, source_text: str):
            # Existing references and already-translated rich Components never
            # allocate a segment, even when the final available ID is in use.
            if not has_segment_space:
                raise ValueError(f"Paragraph ID space exhausted: {key.rsplit('.', 1)[0]}")
            put(new_key, source_text)

        if text == "" or text.startswith(("{@", "{image:")):
            skips["blank_or_control"] += 1
            return text, []
        if REFERENCE.fullmatch(text):
            skips["existing_reference"] += 1
            return text, []
        if text.lstrip().startswith(("{", "[")):
            try:
                component = json.loads(text, object_pairs_hook=unique_json_pairs)
            except (ValueError, json.JSONDecodeError) as error:
                raise ValueError(f"Possible raw Component requires manual review at {key}: {error}") from error
            converted, ledger = translate_component(component, key, record)
            if not ledger:
                skips["component_already_translated_or_no_text"] += 1
                return text, []
            return json.dumps(converted, ensure_ascii=False, separators=(",", ":")), ledger
        record(key, text)
        return "{" + key + "}", []

    for field in fields:
        base_key = PREFIX + field.identity
        value = field.source
        ledger = []
        if isinstance(value, list):
            changed = []
            # Once a field is migrated, suffixes identify paragraphs rather than
            # current list positions. New paragraphs get fresh IDs above the
            # existing maximum; inserting a line cannot rebind old translations.
            existing_suffixes = existing_paragraph_ids[base_key]
            initial_migration = not existing_suffixes
            next_id = max(existing_suffixes, default=-1) + 1
            for index, text in enumerate(value):
                paragraph_id = index if initial_migration else next_id
                result, nodes = line(text, f"{base_key}.{paragraph_id:04d}", paragraph_id <= 9999)
                changed.append(result)
                ledger.extend(nodes)
                if not initial_migration and result != text:
                    next_id += 1
        else:
            changed, ledger = line(value, base_key)
        if changed == value:
            continue
        if isinstance(value, list):
            # Preserve original list spacing as well as blank/control-line bytes.
            raw_value = field.document.text[field.node.start:field.node.end]
            edits = [(node.start - field.node.start, node.end - field.node.start,
                      json.dumps(changed[index], ensure_ascii=False))
                     for index, node in enumerate(field.node.value) if changed[index] != value[index]]
            rendered = replace_spans(raw_value, edits)
        else:
            rendered = json.dumps(changed, ensure_ascii=False)
        replacements[field.document.relative].append((field, rendered))
        transformed_fields.append({"file": field.document.relative, "path": list(field.path),
                                   "identity": field.identity, "source_hash": digest_json(value),
                                   "source": value, "component_nodes": ledger})

    generated = dict(original_files)
    for doc in documents:
        edits = replacements[doc.relative]
        result = replace_spans(doc.text, [(field.node.start, field.node.end, rendered) for field, rendered in edits])
        assert_selected_values_only(doc, result, edits)
        generated[doc.relative] = result.encode("utf-8")
    generated[f"lang/{DEFAULT_LANGUAGE}.json"] = dictionary_bytes(catalog)
    generated["lang/gtlcore-i18n.json"] = json_bytes(MANIFEST)
    validate_language_files(generated)
    reused = reuse_translations(generated, reuse_from) if reuse_from is not None else None
    # A missing target remains absent. Do not seed Chinese as fake English.
    if set(original_files) != {p.relative_to(source).as_posix() for p in source.rglob("*") if p.is_file()}:
        raise ValueError("Source file list changed while converting")
    for relative, original in original_files.items():
        if (source / relative).read_bytes() != original:
            raise ValueError(f"Source changed during conversion: {relative}")
    output.mkdir(parents=True, exist_ok=False)
    for relative, data in generated.items():
        destination = output / relative
        write_new(destination, data)
    audit = {
        "schema": MANIFEST, "source": str(source), "output": str(output),
        "statistics": stats, "converted_fields": len(transformed_fields),
        "added_keys": len(new_keys), "total_source_keys": len(catalog), "skipped": dict(skips),
        "source_files_sha256": {p: hashlib.sha256(data).hexdigest() for p, data in original_files.items()},
        "output_files_sha256": {p: hashlib.sha256(data).hexdigest() for p, data in generated.items()},
        "validation": {"exact_snbt_reverse_byte_equality": True, "component_semantic_equality": True,
                       "source_files_unchanged": True},
        "fields": transformed_fields,
    }
    if reused is not None:
        audit["translation_reuse"] = reused
    if audit_path is not None:
        write_new(audit_path, json_bytes(audit))
    return {key: value for key, value in audit.items() if key not in ("fields", "source_files_sha256", "output_files_sha256", "statistics")}


def validate_translation(catalog: dict[str, str], translations: Any) -> dict[str, str]:
    validate_dictionary(catalog)
    validate_dictionary(translations)
    unknown = sorted(set(translations) - set(catalog))
    if unknown:
        raise ValueError(f"Unknown translation keys: {unknown[:10]}")
    for key, text in translations.items():
        if not text and catalog[key]:
            raise ValueError(f"Empty target would hide source text: {key}; omit untranslated keys")
        if re.search(r"\{(?:@|image:)[^}]*\}", text):
            raise ValueError(f"Translation must not inject FTB control directives: {key}")
        source_formats = collections.Counter(re.findall(r"(?<!%)%(?:\d+\$)?[sd]", catalog[key]))
        target_formats = collections.Counter(re.findall(r"(?<!%)%(?:\d+\$)?[sd]", text))
        if source_formats != target_formats:
            raise ValueError(f"Formatting placeholders differ: {key}")
        source_codes = collections.Counter(re.findall(r"&[0-9a-fklmnor]", catalog[key], re.IGNORECASE))
        target_codes = collections.Counter(re.findall(r"&[0-9a-fklmnor]", text, re.IGNORECASE))
        if source_codes != target_codes:
            raise ValueError(f"Minecraft formatting tokens differ: {key}")
        source_urls = collections.Counter(re.findall(r"https?://[^\s<>]+", catalog[key]))
        target_urls = collections.Counter(re.findall(r"https?://[^\s<>]+", text))
        if source_urls != target_urls:
            raise ValueError(f"Embedded URLs differ: {key}")
    return translations


def export_job(quests: Path, output: Path, target_language: str):
    if not LANGUAGE.fullmatch(target_language) or target_language == DEFAULT_LANGUAGE:
        raise ValueError("Choose a valid target locale different from the source locale")
    output = new_destination(output)
    require_separate_trees(quests, output)
    catalog = load_source_catalog(quests)
    current = quests / "lang" / f"{target_language}.json"
    translated = read_json(current) if current.exists() else {}
    validate_translation(catalog, translated)
    metadata = new_destination(output.with_suffix(".meta.json"))
    require_separate_trees(quests, metadata)
    # The translator receives a plain key:string JSON file, without job wrappers
    # or administrative strings that might themselves get translated.
    write_new(output, dictionary_bytes(catalog))
    write_new(metadata, json_bytes({"format": 1, "source_language": DEFAULT_LANGUAGE,
                                   "target_language": target_language, "source_sha256": digest_json(catalog)}))
    return {"export": str(output), "metadata": str(metadata), "source_keys": len(catalog),
            "existing_target_keys": len(translated)}


def import_job(quests: Path, job_path: Path, output: Path, metadata_path: Path | None = None):
    output = new_destination(output)
    require_separate_trees(quests, output)
    require_separate_trees(job_path, output)
    catalog = load_source_catalog(quests)
    job = read_json(metadata_path or job_path.with_suffix(".meta.json"))
    if not isinstance(job, dict) or job.get("format") != 1 or job.get("source_language") != DEFAULT_LANGUAGE:
        raise ValueError("Unsupported translation-job format")
    locale = job.get("target_language", "")
    if not LANGUAGE.fullmatch(locale) or locale == DEFAULT_LANGUAGE:
        raise ValueError("Invalid target language")
    if job.get("source_sha256") != digest_json(catalog):
        raise ValueError("Source text changed since export; review and export a new translation job")
    target = validate_translation(catalog, read_json(job_path))
    if output.name != locale + ".json":
        raise ValueError("Import output filename must match the target locale")
    write_new(output, dictionary_bytes(target))
    return {"import": str(output), "translated_keys": len(target), "fallback_keys": len(catalog) - len(target),
            "values_identical_to_source": sum(value == catalog[key] for key, value in target.items())}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Existing config/ftbquests/quests directory; read only")
    parser.add_argument("--compare", type=Path, help="Optional older snapshot to compare")
    parser.add_argument("--output", type=Path, help="New migrated quests directory outside the input tree")
    parser.add_argument("--audit", type=Path, help="Optional migration audit JSON outside both input and output trees")
    parser.add_argument("--reuse-translations", type=Path,
                        help="With --output: reuse old locale entries only when key and source text are unchanged")
    parser.add_argument("--export-job", type=Path, help="Export flat key:string JSON for machine translation; metadata stored separately")
    parser.add_argument("--target-language", default="en_us")
    parser.add_argument("--import-job", type=Path, help="Import and validate a translated flat JSON file")
    parser.add_argument("--import-metadata", type=Path, help="Export metadata if the translated file was renamed")
    parser.add_argument("--import-output", type=Path, help="Fresh target locale JSON; never overwrites the user's source")
    args = parser.parse_args()
    if args.audit and not args.output:
        parser.error("--audit requires --output")
    if args.reuse_translations and not args.output:
        parser.error("--reuse-translations requires --output")
    if args.output and args.import_job:
        parser.error("Migration and translation import are separate operations")
    if args.output:
        print(json.dumps(convert(args.source, args.output, args.audit, args.reuse_translations), ensure_ascii=False, indent=2))
        if args.export_job:
            print(json.dumps(export_job(args.output, args.export_job, args.target_language), ensure_ascii=False))
        return
    if args.import_job:
        if not args.import_output:
            parser.error("--import-job requires --import-output")
        print(json.dumps(import_job(args.source, args.import_job, args.import_output, args.import_metadata), ensure_ascii=False))
        return
    if args.export_job:
        print(json.dumps(export_job(args.source, args.export_job, args.target_language), ensure_ascii=False))
        return
    _, _, stats = scan(args.source)
    if args.compare:
        _, _, previous = scan(args.compare)
        before, after = previous["source_files_sha256"], stats["source_files_sha256"]
        stats["comparison"] = {
            "previous_source": previous["source"],
            "added_files": sorted(set(after) - set(before)),
            "removed_files": sorted(set(before) - set(after)),
            "changed_files": sorted(k for k in set(before) & set(after) if before[k] != after[k]),
            "previous_objects": previous["objects"],
        }
    print(json.dumps(stats, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
