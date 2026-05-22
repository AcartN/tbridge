#!/usr/bin/env python3
"""Compact summary of a uiautomator XML dump.

Filters nodes with signal (clickable / content-desc / text / accessibility-focused),
shows class, key attributes, and highlights the focused node.
"""
import sys
import xml.etree.ElementTree as ET


def walk(node, depth=0):
    attrs = node.attrib
    cd = attrs.get("content-desc", "") or ""
    text = attrs.get("text", "") or ""
    clickable = attrs.get("clickable") == "true"
    focused = attrs.get("accessibility-focused") == "true"
    klass = (attrs.get("class", "") or "").split(".")[-1]
    rid = attrs.get("resource-id", "") or ""
    rid_short = rid.split("/")[-1] if "/" in rid else rid
    bounds = attrs.get("bounds", "") or ""

    # rid sert aussi de signal : un node avec resource-id (testId) est intéressant
    # pour l'accessibility même s'il n'a pas de cd/text (heading invisible, conteneur
    # nommé, etc.).
    has_signal = bool(cd or text or clickable or focused or rid_short)
    if has_signal:
        marker = ">>>" if focused else "   "
        flags = "C" if clickable else "-"
        line = f"{marker} {' ' * depth}{klass:<24} [{flags}] "
        if cd:
            line += f"desc={cd!r} "
        if text:
            line += f"text={text!r} "
        if rid_short:
            line += f"id={rid_short!r} "
        if bounds:
            line += f"bounds={bounds}"
        print(line)

    for child in node:
        walk(child, depth + 1)


def main():
    if len(sys.argv) < 2:
        print("Usage: parse_dump.py <xml_file>", file=sys.stderr)
        sys.exit(1)
    try:
        tree = ET.parse(sys.argv[1])
    except ET.ParseError as e:
        print(f"ERR parsing XML: {e}", file=sys.stderr)
        sys.exit(1)
    walk(tree.getroot())


if __name__ == "__main__":
    main()
