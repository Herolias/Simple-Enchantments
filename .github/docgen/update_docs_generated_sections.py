#!/usr/bin/env python3
"""Generate source-derived reference pages for the Markdown wiki."""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path


ENCHANTMENT_SOURCE = Path("src/main/java/org/herolias/plugin/enchantment/EnchantmentType.java")
CONFIG_SOURCE = Path("src/main/java/org/herolias/plugin/config/EnchantingConfig.java")
LANG_SOURCE = Path("src/main/resources/Server/Languages/en-US/server.lang")
DOCS_DIR = Path("docs")
REFERENCE_DIR = DOCS_DIR / "reference"
DOCSTAT_PATTERN = re.compile(r"<!--\s*DOCSTAT:([^>]+?)\s*-->.*?<!--\s*/DOCSTAT\s*-->", re.DOTALL)


@dataclass
class Multiplier:
    key: str
    default: str
    label_key: str


@dataclass
class Enchantment:
    constant: str
    factory: str
    id: str
    name: str
    description: str
    max_level: int
    requires_durability: bool
    legendary: bool
    default_multiplier: str
    bonus_template: str
    categories: list[str]
    owner_mod_name: str | None = None
    scroll_base_name: str | None = None
    multipliers: list[Multiplier] = field(default_factory=list)
    conflicts: list[str] = field(default_factory=list)
    disabled_by_default: bool = False


def read(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except FileNotFoundError:
        raise RuntimeError(f"Required source file is missing: {path}") from None


def write_if_changed(path: Path, content: str) -> bool:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists() and path.read_text(encoding="utf-8") == content:
        return False

    path.write_text(content, encoding="utf-8")
    return True


def extract_parenthesized(text: str, open_paren_index: int) -> tuple[str, int]:
    depth = 0
    in_string = False
    escaped = False
    start = open_paren_index + 1

    for index in range(open_paren_index, len(text)):
        char = text[index]

        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            continue

        if char == '"':
            in_string = True
        elif char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return text[start:index], index

    raise RuntimeError("Unclosed parenthesized expression in Java source")


def split_java_args(text: str) -> list[str]:
    args: list[str] = []
    depth = 0
    in_string = False
    escaped = False
    start = 0

    for index, char in enumerate(text):
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            continue

        if char == '"':
            in_string = True
        elif char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
        elif char == "," and depth == 0:
            args.append(text[start:index].strip())
            start = index + 1

    tail = text[start:].strip()
    if tail:
        args.append(tail)

    return args


def unquote_java_string(value: str) -> str:
    value = value.strip()
    if not (value.startswith('"') and value.endswith('"')):
        return value

    value = value[1:-1]
    return (
        value.replace(r"\\", "\\")
        .replace(r"\"", '"')
        .replace(r"\n", "\n")
        .replace(r"\t", "\t")
    )


def parse_bool(value: str) -> bool:
    if value.strip() == "true":
        return True
    if value.strip() == "false":
        return False
    raise RuntimeError(f"Expected Java boolean literal, got: {value}")


def parse_category(token: str) -> str:
    return token.strip().removeprefix("ItemCategory.")


def parse_language_file(text: str) -> dict[str, str]:
    translations: dict[str, str] = {}
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue

        key, value = line.split("=", 1)
        translations[key.strip()] = value.strip().replace(r"\n", " ")

    return translations


def parse_enchantments(text: str) -> list[Enchantment]:
    pattern = re.compile(
        r"public\s+static\s+final\s+EnchantmentType\s+([A-Z0-9_]+)\s*=\s*"
        r"(builtin(?:CustomScroll|Collab)?)\s*\(",
        re.MULTILINE,
    )
    enchantments: list[Enchantment] = []

    for match in pattern.finditer(text):
        constant = match.group(1)
        factory = match.group(2)
        open_paren = match.end() - 1
        args = split_java_args(extract_parenthesized(text, open_paren)[0])

        if len(args) < 9:
            raise RuntimeError(f"Could not parse arguments for {constant}")

        id_value = unquote_java_string(args[0])
        name = unquote_java_string(args[1])
        description = unquote_java_string(args[2])
        max_level = int(args[3])
        requires_durability = parse_bool(args[4])
        legendary = parse_bool(args[5])
        default_multiplier = args[6].strip()
        bonus_template = unquote_java_string(args[7])
        scroll_base_name = None
        owner_mod_name = None

        if factory == "builtinCustomScroll":
            scroll_base_name = unquote_java_string(args[8])
            category_args = args[9:]
        elif factory == "builtinCollab":
            owner_mod_name = unquote_java_string(args[8])
            category_args = args[9:]
        else:
            category_args = args[8:]

        enchantments.append(
            Enchantment(
                constant=constant,
                factory=factory,
                id=id_value,
                name=name,
                description=description,
                max_level=max_level,
                requires_durability=requires_durability,
                legendary=legendary,
                default_multiplier=default_multiplier,
                bonus_template=bonus_template,
                categories=[parse_category(arg) for arg in category_args],
                owner_mod_name=owner_mod_name,
                scroll_base_name=scroll_base_name,
            )
        )

    return enchantments


def parse_multiplier_definitions(text: str) -> dict[str, list[Multiplier]]:
    definitions: dict[str, list[Multiplier]] = {}
    block_pattern = re.compile(
        r"([A-Z0-9_]+)\.setMultiplierDefinitions\(\s*java\.util\.List\.of\((.*?)\)\s*\);",
        re.DOTALL,
    )
    definition_pattern = re.compile(
        r"new\s+MultiplierDefinition\(\s*\"([^\"]+)\"\s*,\s*([0-9.]+)\s*,\s*\"([^\"]+)\"\s*\)",
        re.DOTALL,
    )

    for block in block_pattern.finditer(text):
        constant = block.group(1)
        definitions[constant] = [
            Multiplier(key=match.group(1), default=match.group(2), label_key=match.group(3))
            for match in definition_pattern.finditer(block.group(2))
        ]

    return definitions


def parse_conflicts(text: str) -> dict[str, list[str]]:
    conflicts: dict[str, list[str]] = {}
    for left, right in re.findall(r'registry\.addConflict\("([^"]+)",\s*"([^"]+)"\);', text):
        conflicts.setdefault(left, []).append(right)
        conflicts.setdefault(right, []).append(left)
    return conflicts


def parse_disabled_defaults(config_text: str) -> set[str]:
    method_match = re.search(
        r"private\s+void\s+initializeDefaultDisabledEnchantments\(\)\s*\{(.*?)\n\s*public\s+void\s+initializeDefaultRecipes",
        config_text,
        re.DOTALL,
    )
    if not method_match:
        return set()

    true_branch = re.search(
        r"if\s*\((.*?)\)\s*\{\s*disabledEnchantments\.put\(type\.getId\(\),\s*true\);",
        method_match.group(1),
        re.DOTALL,
    )
    if not true_branch:
        return set()

    return set(re.findall(r"EnchantmentType\.([A-Z0-9_]+)", true_branch.group(1)))


def parse_general_defaults(config_text: str) -> list[tuple[str, str]]:
    wanted = [
        "configVersion",
        "maxEnchantmentsPerItem",
        "enableEnchantmentGlow",
        "allowSameScrollUpgrades",
        "burnDuration",
        "freezeDuration",
        "poisonDuration",
        "disableEnchantmentCrafting",
        "returnEnchantmentOnCleanse",
        "salvagerYieldsScroll",
        "engravingTableCraftingTier",
        "enchantingTableCraftingTier",
        "hasSkippedTooltipAnnouncement",
        "showWelcomeMessage",
    ]
    defaults: dict[str, str] = {}
    pattern = re.compile(
        r"public\s+(?:double|int|boolean|String)\s+([A-Za-z0-9_]+)\s*=\s*([^;]+);"
    )

    for name, value in pattern.findall(config_text):
        if name in wanted:
            defaults[name] = value.strip()

    return [(name, defaults[name]) for name in wanted if name in defaults]


def parse_recipe_counts(config_text: str) -> tuple[int, int]:
    scroll_recipe_count = len(re.findall(r'\baddScrollRecipe\("', config_text))
    table_upgrade_count = len(re.findall(r'\baddTableUpgrade\("', config_text))
    return scroll_recipe_count, table_upgrade_count


def parse_scroll_recipe_tiers(config_text: str) -> dict[str, str]:
    return dict(re.findall(r'\baddScrollRecipe\("([^"]+)",\s*([0-9]+),', config_text))


def bool_from_java(value: str) -> bool:
    if value == "true":
        return True
    if value == "false":
        return False
    raise RuntimeError(f"Expected Java boolean default, got: {value}")


def apply_source_data(
    enchantments: list[Enchantment],
    multipliers: dict[str, list[Multiplier]],
    conflicts: dict[str, list[str]],
    disabled_defaults: set[str],
) -> None:
    for enchantment in enchantments:
        enchantment.multipliers = multipliers.get(enchantment.constant, [])
        enchantment.conflicts = sorted(conflicts.get(enchantment.id, []))
        enchantment.disabled_by_default = enchantment.constant in disabled_defaults


def markdown_escape(value: str) -> str:
    value = value.replace("\n", " ")
    return value.replace("|", r"\|")


def format_categories(categories: list[str], translations: dict[str, str]) -> str:
    fallback_labels = {
        "LEGS": "Leg Armor",
    }
    names = [
        translations.get(f"itemCategory.{category}", fallback_labels.get(category, category))
        for category in categories
    ]
    return ", ".join(names)


def format_bool(value: bool) -> str:
    return "Yes" if value else "No"


def format_default(value: str) -> str:
    return value


def format_human_number(value: str) -> str:
    if value.endswith(".0"):
        return value[:-2]
    return value


def format_percent(value: str) -> str:
    percent = float(value) * 100
    if percent.is_integer():
        return f"{int(percent)}%"
    return f"{percent:g}%"


def format_code(value: str) -> str:
    return f"`{value}`"


def roman(value: int) -> str:
    numerals = [
        (100, "C"),
        (90, "XC"),
        (50, "L"),
        (40, "XL"),
        (10, "X"),
        (9, "IX"),
        (5, "V"),
        (4, "IV"),
        (1, "I"),
    ]
    result = []
    remaining = value
    for amount, symbol in numerals:
        while remaining >= amount:
            result.append(symbol)
            remaining -= amount
    return "".join(result)


def ordinal(value: str) -> str:
    number = int(value)
    if 10 <= number % 100 <= 20:
        suffix = "th"
    else:
        suffix = {1: "st", 2: "nd", 3: "rd"}.get(number % 10, "th")
    return f"{number}{suffix}"


def level_range(max_level: int) -> str:
    if max_level <= 1:
        return "I"
    return f"I-{roman(max_level)}"


def primary_multiplier(enchantment: Enchantment) -> Multiplier | None:
    for multiplier in enchantment.multipliers:
        if multiplier.key == enchantment.id:
            return multiplier
    return enchantment.multipliers[0] if enchantment.multipliers else None


def format_multiplier_for_summary(multiplier: Multiplier) -> str:
    if multiplier.key.endswith(":duration"):
        return f"{format_human_number(multiplier.default)}s"
    if multiplier.key in {"burn", "poison"}:
        return f"{format_human_number(multiplier.default)} damage/s"
    if multiplier.key == "regeneration":
        return f"{format_human_number(multiplier.default)} HP/s"
    return format_percent(multiplier.default)


def sentence(value: str) -> str:
    value = value.strip()
    if not value:
        return value
    if value[-1] in ".!?":
        return value
    return f"{value}."


def build_docstat_context(
    enchantments: list[Enchantment],
    general_defaults: dict[str, str],
    recipe_counts: tuple[int, int],
    scroll_recipe_tiers: dict[str, str],
) -> dict[str, str]:
    scroll_recipe_count, table_upgrade_count = recipe_counts
    context: dict[str, str] = {
        "enchantments.count": str(len(enchantments)),
        "recipes.scroll.count": str(scroll_recipe_count),
        "enchantingTable.upgrade.count": str(table_upgrade_count),
        "enchantingTable.tier.count": str(table_upgrade_count + 1),
        "enchantingTable.upgrade.range": f"2-{table_upgrade_count + 1}",
    }

    for name, value in general_defaults.items():
        context[f"config.{name}"] = value

    for recipe, tier in scroll_recipe_tiers.items():
        context[f"recipe.{recipe}.tier"] = tier

    for enchantment in enchantments:
        context[f"enchantment.{enchantment.id}.maxLevel"] = str(enchantment.max_level)
        context[f"enchantment.{enchantment.id}.enabled"] = str(not enchantment.disabled_by_default).lower()
        context[f"enchantment.{enchantment.id}.legendary"] = str(enchantment.legendary).lower()
        for multiplier in enchantment.multipliers:
            context[f"multiplier.{multiplier.key}"] = multiplier.default

    return context


def resolve_docstat(payload: str, context: dict[str, str]) -> str:
    key, _, format_name = payload.strip().partition("|")
    format_name = format_name or "raw"

    if key not in context:
        raise RuntimeError(f"Unknown DOCSTAT key: {key}")

    value = context[key]

    match format_name:
        case "raw":
            return value
        case "code":
            return format_code(value)
        case "ordinal":
            return ordinal(value)
        case "percent":
            return format_percent(value)
        case "percent_code":
            return format_code(format_percent(value))
        case "enabled":
            return "Enabled" if bool_from_java(value) else "Disabled"
        case "enabled_code":
            return format_code("Enabled" if bool_from_java(value) else "Disabled")
        case "enabled_sentence":
            return "enabled by default" if bool_from_java(value) else "disabled by default"
        case "yes_no":
            return format_bool(bool_from_java(value))
        case "will":
            return "will" if bool_from_java(value) else "will not"
        case _:
            raise RuntimeError(f"Unknown DOCSTAT format: {format_name}")


def update_inline_docstats(path: Path, context: dict[str, str]) -> bool:
    content = path.read_text(encoding="utf-8")

    def replace(match: re.Match[str]) -> str:
        payload = match.group(1)
        return f"<!-- DOCSTAT:{payload} -->{resolve_docstat(payload, context)}<!-- /DOCSTAT -->"

    updated = DOCSTAT_PATTERN.sub(replace, content)
    return write_if_changed(path, updated)


def update_generated_block(path: Path, name: str, block_content: str) -> bool:
    marker_start = f"<!-- AUTO-GENERATED:{name}:start -->"
    marker_end = f"<!-- AUTO-GENERATED:{name}:end -->"
    content = path.read_text(encoding="utf-8")
    pattern = re.compile(
        rf"{re.escape(marker_start)}.*?{re.escape(marker_end)}",
        re.DOTALL,
    )

    if not pattern.search(content):
        return False

    block = f"{marker_start}\n{block_content.rstrip()}\n{marker_end}"
    return write_if_changed(path, pattern.sub(block, content))


def render_reference_index() -> str:
    return "\n".join(
        [
            "# Reference",
            "",
            "Generated reference pages for values that are maintained in the mod source.",
            "",
            "- [Config Defaults](config-defaults.md)",
            "- [Enchantment Modifiers](enchantment-modifiers.md)",
            "",
        ]
    )


def render_config_defaults(
    enchantments: list[Enchantment],
    general_defaults: list[tuple[str, str]],
    recipe_counts: tuple[int, int],
) -> str:
    lines = [
        "# Config Defaults",
        "",
        "> This page is generated from `EnchantingConfig.java` and `EnchantmentType.java`.",
        "",
        "## General Settings",
        "",
        "| Setting | Default |",
        "|---|---:|",
    ]

    for name, value in general_defaults:
        lines.append(f"| `{name}` | `{format_default(value)}` |")

    lines.extend(
        [
            "",
            "## Enchantment Enablement",
            "",
            "| Enchantment | ID | Enabled By Default |",
            "|---|---|---|",
        ]
    )

    for enchantment in enchantments:
        enabled = not enchantment.disabled_by_default
        lines.append(f"| {markdown_escape(enchantment.name)} | `{enchantment.id}` | {format_bool(enabled)} |")

    scroll_recipe_count, table_upgrade_count = recipe_counts
    lines.extend(
        [
            "",
            "## Recipe Defaults",
            "",
            f"- Scroll recipes configured in defaults: `{scroll_recipe_count}`",
            f"- Enchanting table upgrade tiers configured in defaults: `{table_upgrade_count}`",
            "- Full recipe ingredient tables are still maintained in `EnchantingConfig.initializeDefaultRecipes()`.",
            "",
        ]
    )

    return "\n".join(lines)


def render_enchantment_modifiers(enchantments: list[Enchantment], translations: dict[str, str]) -> str:
    lines = [
        "# Enchantment Modifiers",
        "",
        "> This page is generated from `EnchantmentType.java` and `Server/Languages/en-US/server.lang`.",
        "",
        "## Configurable Multipliers",
        "",
        "| Enchantment | Multiplier Key | Label | Default | Max Level |",
        "|---|---|---|---:|---:|",
    ]

    for enchantment in enchantments:
        for multiplier in enchantment.multipliers:
            label = translations.get(multiplier.label_key, multiplier.label_key)
            lines.append(
                "| "
                f"{markdown_escape(enchantment.name)} | "
                f"`{multiplier.key}` | "
                f"{markdown_escape(label)} | "
                f"`{format_default(multiplier.default)}` | "
                f"{enchantment.max_level} |"
            )

    lines.extend(
        [
            "",
            "## Built-In Enchantments",
            "",
            "| Enchantment | ID | Max Level | Legendary | Durability Required | Categories | Conflicts |",
            "|---|---|---:|---|---|---|---|",
        ]
    )

    for enchantment in enchantments:
        conflicts = ", ".join(f"`{conflict}`" for conflict in enchantment.conflicts) or "-"
        categories = format_categories(enchantment.categories, translations)
        lines.append(
            "| "
            f"{markdown_escape(enchantment.name)} | "
            f"`{enchantment.id}` | "
            f"{enchantment.max_level} | "
            f"{format_bool(enchantment.legendary)} | "
            f"{format_bool(enchantment.requires_durability)} | "
            f"{markdown_escape(categories)} | "
            f"{conflicts} |"
        )

    lines.append("")
    return "\n".join(lines)


def render_enchantment_summary(enchantments: list[Enchantment]) -> str:
    lines: list[str] = []

    for enchantment in enchantments:
        details = [markdown_escape(sentence(enchantment.description))]
        multiplier = primary_multiplier(enchantment)
        if multiplier:
            details.append(f"Default modifier: `{format_multiplier_for_summary(multiplier)}`.")
        if enchantment.disabled_by_default:
            details.append("Disabled by default.")
        if enchantment.owner_mod_name:
            details.append(f"Requires `{markdown_escape(enchantment.owner_mod_name)}`.")
        if enchantment.conflicts:
            conflicts = ", ".join(f"`{conflict}`" for conflict in enchantment.conflicts)
            details.append(f"Conflicts with {conflicts}.")

        lines.append(f"* **{markdown_escape(enchantment.name)}** ({level_range(enchantment.max_level)}): {' '.join(details)}")

    return "\n".join(lines)


def render_disabled_enchantments(enchantments: list[Enchantment]) -> str:
    disabled = [enchantment for enchantment in enchantments if enchantment.disabled_by_default]
    if not disabled:
        return "No built-in enchantments are disabled by default."

    return "\n".join(f"* **{markdown_escape(enchantment.name)}**" for enchantment in disabled)


def update_integrated_pages(enchantments: list[Enchantment], context: dict[str, str]) -> list[Path]:
    changed: list[Path] = []
    markdown_files = sorted(DOCS_DIR.rglob("*.md"))

    for path in markdown_files:
        if update_inline_docstats(path, context):
            changed.append(path)

    block_updates = [
        (
            DOCS_DIR / "welcome-to-simple-enchantments" / "enchantments" / "README.md",
            "enchantment-summary",
            render_enchantment_summary(enchantments),
        ),
        (
            DOCS_DIR / "welcome-to-simple-enchantments" / "enchantments" / "hiddendisabled-enchantments.md",
            "disabled-enchantments",
            render_disabled_enchantments(enchantments),
        ),
    ]

    for path, name, block in block_updates:
        if path.exists() and update_generated_block(path, name, block):
            changed.append(path)

    return list(dict.fromkeys(changed))


def ensure_root_reference_link(root_readme: Path) -> bool:
    marker_start = "<!-- AUTO-GENERATED:reference-navigation:start -->"
    marker_end = "<!-- AUTO-GENERATED:reference-navigation:end -->"
    block = "\n".join(
        [
            marker_start,
            "",
            "## Generated Reference",
            "",
            "- [Reference](reference)",
            "  - [Config Defaults](reference/config-defaults.md)",
            "  - [Enchantment Modifiers](reference/enchantment-modifiers.md)",
            "",
            marker_end,
            "",
        ]
    )

    if not root_readme.exists():
        return False

    content = root_readme.read_text(encoding="utf-8")
    pattern = re.compile(
        rf"{re.escape(marker_start)}.*?{re.escape(marker_end)}\n?",
        re.DOTALL,
    )

    if pattern.search(content):
        updated = pattern.sub(block, content)
    else:
        updated = content.rstrip() + "\n\n" + block

    return write_if_changed(root_readme, updated)


def generate() -> list[Path]:
    enchantment_text = read(ENCHANTMENT_SOURCE)
    config_text = read(CONFIG_SOURCE)
    translations = parse_language_file(read(LANG_SOURCE))

    enchantments = parse_enchantments(enchantment_text)
    apply_source_data(
        enchantments,
        parse_multiplier_definitions(enchantment_text),
        parse_conflicts(enchantment_text),
        parse_disabled_defaults(config_text),
    )

    general_defaults = dict(parse_general_defaults(config_text))
    recipe_counts = parse_recipe_counts(config_text)
    scroll_recipe_tiers = parse_scroll_recipe_tiers(config_text)
    context = build_docstat_context(enchantments, general_defaults, recipe_counts, scroll_recipe_tiers)

    changed: list[Path] = []
    targets = {
        REFERENCE_DIR / "README.md": render_reference_index(),
        REFERENCE_DIR / "config-defaults.md": render_config_defaults(
            enchantments,
            list(general_defaults.items()),
            recipe_counts,
        ),
        REFERENCE_DIR / "enchantment-modifiers.md": render_enchantment_modifiers(enchantments, translations),
    }

    for path, content in targets.items():
        if write_if_changed(path, content):
            changed.append(path)

    if ensure_root_reference_link(DOCS_DIR / "README.md"):
        changed.append(DOCS_DIR / "README.md")

    changed.extend(update_integrated_pages(enchantments, context))

    return list(dict.fromkeys(changed))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Fail if generated files are not up to date.")
    args = parser.parse_args()

    changed = generate()
    if args.check and changed:
        print("Generated docs are out of date:", file=sys.stderr)
        for path in changed:
            print(f"  {path}", file=sys.stderr)
        return 1

    if changed:
        print("Updated generated docs:")
        for path in changed:
            print(f"  {path}")
    else:
        print("Generated docs are up to date.")

    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1)
