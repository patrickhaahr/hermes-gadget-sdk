"""Every command the documentation tells users to run exists in a CLI."""

from __future__ import annotations

import argparse
import re
from pathlib import Path

import pytest

from hermes_gadget import cli
from hermes_gadget_plugin import cli as plugin_cli

REPO = Path(__file__).resolve().parents[1]
DOC_FILES = sorted([REPO / "README.md", REPO / "CONTRIBUTING.md", *REPO.glob("docs/*.md"), *REPO.glob("examples/**/*.md")])

# `hermes gadget pair`, `hermes-gadget linux status`, ... as (prefix, words after it).
COMMAND = re.compile(r"\b(hermes gadget|hermes-gadget)((?: [a-z][a-z0-9-]*)+)")


def _subcommands(parser: argparse.ArgumentParser) -> dict[str, dict]:
    """Nested subcommand names, aliases included, as {name: {nested name: ...}}."""
    found: dict[str, dict] = {}
    for action in parser._actions:
        if isinstance(action, argparse._SubParsersAction):
            for name, sub in action.choices.items():
                found[name] = _subcommands(sub)
    return found


def _documented() -> list[tuple[str, str, list[str]]]:
    uses = []
    for path in DOC_FILES:
        for match in COMMAND.finditer(path.read_text(encoding="utf-8")):
            uses.append((path.relative_to(REPO).as_posix(), match.group(1), match.group(2).split()))
    return uses


def _known() -> dict[str, dict]:
    plugin_parser = argparse.ArgumentParser()
    plugin_cli.setup_argparse(plugin_parser)
    return {"hermes gadget": _subcommands(plugin_parser), "hermes-gadget": _subcommands(cli.build_parser())}


@pytest.mark.parametrize("where,prefix,words", _documented(), ids=lambda v: v if isinstance(v, str) else " ".join(v))
def test_documented_command_exists(where, prefix, words):
    """Walk the documented words down the parser tree while they name subcommands."""
    level = _known()[prefix]
    assert words[0] in level, f"{where}: `{prefix} {words[0]}` is not a command; known: {sorted(level)}"
    level = level[words[0]]
    for word in words[1:]:
        if not level:  # positional arguments follow; nothing more to check
            break
        assert word in level, f"{where}: `{prefix} {' '.join(words)}`: `{word}` is not a subcommand; known: {sorted(level)}"
        level = level[word]


def test_the_docs_mention_commands():
    assert len(_documented()) > 20
