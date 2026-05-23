#!/usr/bin/env python3
"""Generate source-derived reference pages for the Markdown wiki."""

from __future__ import annotations

import argparse
import html
import json
import os
import re
import shutil
import sys
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import quote


ENCHANTMENT_SOURCE = Path("src/main/java/org/herolias/plugin/enchantment/EnchantmentType.java")
CONFIG_SOURCE = Path("src/main/java/org/herolias/plugin/config/EnchantingConfig.java")
SCROLL_ITEM_GENERATOR_SOURCE = Path("src/main/java/org/herolias/plugin/enchantment/ScrollItemGenerator.java")
LANG_SOURCE = Path("src/main/resources/Server/Languages/en-US/server.lang")
COMMON_RESOURCES_DIR = Path("src/main/resources/Common")
ASSETS_DIR = Path("Assets")
ASSET_LANG_SOURCE = ASSETS_DIR / "Server/Languages/en-US/server.lang"
ASSET_ITEM_DIR = ASSETS_DIR / "Server/Item/Items"
ASSET_COMMON_DIR = ASSETS_DIR / "Common"
RECIPE_ITEM_NAME_CACHE = Path(".github/docgen/recipe_item_names.json")
DOCS_DIR = Path("docs")
REFERENCE_DIR = DOCS_DIR / "reference"
ENCHANTMENT_PAGE_DIR = DOCS_DIR / "welcome-to-simple-enchantments" / "enchantments"
ENCHANTMENT_ICON_DIR = DOCS_DIR / "media" / "enchantment-icons"
RECIPE_ICON_DIR = DOCS_DIR / "media" / "recipe-icons"
DOCSTAT_PATTERN = re.compile(r"<!--\s*DOCSTAT:([^>]+?)\s*-->.*?<!--\s*/DOCSTAT\s*-->", re.DOTALL)
# Keep raw media URLs stable across branch-specific GitHub Actions runs.
RAW_GITHUB_BRANCH = os.environ.get("DOCS_MEDIA_BRANCH") or "main"
RAW_GITHUB_BASE_URL = f"https://raw.githubusercontent.com/Herolias/Simple-Enchantments/{RAW_GITHUB_BRANCH}"


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


@dataclass
class RecipeIngredient:
    item_id: str
    amount: int


@dataclass
class ScrollRecipe:
    id: str
    unlock_tier: int
    ingredients: list[RecipeIngredient]


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


def copy_binary_if_changed(source: Path, target: Path) -> bool:
    target.parent.mkdir(parents=True, exist_ok=True)
    source_bytes = source.read_bytes()
    if target.exists() and target.read_bytes() == source_bytes:
        return False

    shutil.copy2(source, target)
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


def parse_scroll_recipes(config_text: str) -> list[ScrollRecipe]:
    recipes: list[ScrollRecipe] = []
    pattern = re.compile(r"\baddScrollRecipe\s*\(")

    for match in pattern.finditer(config_text):
        args = split_java_args(extract_parenthesized(config_text, match.end() - 1)[0])
        if not args or not args[0].strip().startswith('"'):
            continue
        if len(args) < 4 or len(args[2:]) % 2 != 0:
            raise RuntimeError(f"Could not parse scroll recipe arguments: {args}")

        ingredients: list[RecipeIngredient] = []
        for index in range(2, len(args), 2):
            ingredients.append(
                RecipeIngredient(
                    item_id=unquote_java_string(args[index]),
                    amount=int(args[index + 1]),
                )
            )

        recipes.append(
            ScrollRecipe(
                id=unquote_java_string(args[0]),
                unlock_tier=int(args[1]),
                ingredients=ingredients,
            )
        )

    return recipes


def parse_enchantment_icon_paths(scroll_generator_text: str) -> dict[str, str]:
    method_match = re.search(
        r"private\s+static\s+String\s+getIconForEnchantment\(String\s+enchantmentId\)\s*\{(.*?)\n\s*\}",
        scroll_generator_text,
        re.DOTALL,
    )
    if not method_match:
        return {}

    method_body = method_match.group(1)
    base_match = re.search(r'String\s+base\s*=\s*"([^"]+)";', method_body)
    base = base_match.group(1) if base_match else ""
    return {
        enchantment_id: base + icon_path
        for enchantment_id, icon_path in re.findall(
            r'case\s+"([^"]+)"\s*:\s*return\s+base\s*\+\s*"([^"]+)";',
            method_body,
        )
    }


def load_optional_language_file(path: Path) -> dict[str, str]:
    if not path.exists():
        return {}
    return parse_language_file(path.read_text(encoding="utf-8"))


def load_cached_item_translations() -> dict[str, str]:
    if not RECIPE_ITEM_NAME_CACHE.exists():
        return {}

    try:
        cache = json.loads(RECIPE_ITEM_NAME_CACHE.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return {}

    if not isinstance(cache, dict):
        return {}

    return {
        f"items.{item_id}.name": name
        for item_id, name in cache.items()
        if isinstance(item_id, str) and isinstance(name, str)
    }


def load_asset_item_icons() -> dict[str, Path]:
    if not ASSET_ITEM_DIR.exists():
        return {}

    item_icons: dict[str, Path] = {}
    for path in ASSET_ITEM_DIR.rglob("*.json"):
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError):
            continue

        icon_path = data.get("Icon")
        if isinstance(icon_path, str):
            item_icons[path.stem] = ASSET_COMMON_DIR / icon_path

    return item_icons


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


def markdown_alt_escape(value: str) -> str:
    return value.replace("[", "(").replace("]", ")")


def slugify(value: str) -> str:
    value = value.lower().replace("_", "-")
    value = re.sub(r"[^a-z0-9-]+", "-", value)
    value = re.sub(r"-+", "-", value).strip("-")
    return value or "page"


def page_for_enchantment(enchantment: Enchantment) -> Path:
    return ENCHANTMENT_PAGE_DIR / f"{slugify(enchantment.id)}.md"


def relative_markdown_path(from_file: Path, to_file: Path) -> str:
    return os.path.relpath(to_file, start=from_file.parent).replace(os.sep, "/")


def raw_github_url(path: Path) -> str:
    return f"{RAW_GITHUB_BASE_URL}/{quote(path.as_posix(), safe='/')}"


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


def level_count(max_level: int) -> str:
    return f"{max_level} ({level_range(max_level)})"


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


def format_multiplier_details(enchantment: Enchantment, translations: dict[str, str]) -> str:
    if not enchantment.multipliers:
        return "None"

    details = []
    for multiplier in enchantment.multipliers:
        label = translations.get(multiplier.label_key, multiplier.key)
        details.append(f"{markdown_escape(label)}: `{format_multiplier_for_summary(multiplier)}`")

    return "; ".join(details)


def format_multiplier_details_html(enchantment: Enchantment, translations: dict[str, str]) -> str:
    if not enchantment.multipliers:
        return "None"

    details = []
    for multiplier in enchantment.multipliers:
        label = translations.get(multiplier.label_key, multiplier.key)
        details.append(
            f"{html.escape(label)}: <code>{html.escape(format_multiplier_for_summary(multiplier))}</code>"
        )

    return "; ".join(details)


def format_description_placeholder(multiplier: Multiplier | None) -> str:
    if not multiplier:
        return ""
    if multiplier.key.endswith(":duration"):
        return format_human_number(multiplier.default)
    if multiplier.key in {"burn", "poison", "regeneration"}:
        return format_human_number(multiplier.default)
    return format_human_number(str(float(multiplier.default) * 100))


def render_enchantment_description(enchantment: Enchantment, translations: dict[str, str]) -> str:
    description = translations.get(f"enchantment.{enchantment.id}.description", sentence(enchantment.description))
    primary = primary_multiplier(enchantment)
    duration = next((multiplier for multiplier in enchantment.multipliers if multiplier.key.endswith(":duration")), None)

    if primary:
        description = description.replace("{amount}", format_description_placeholder(primary))
    if duration:
        description = description.replace("{duration}", format_description_placeholder(duration))

    return sentence(description)


def scroll_recipe_level(recipe_id: str) -> int:
    roman_values = {"I": 1, "II": 2, "III": 3, "IV": 4, "V": 5}
    suffix = recipe_id.rsplit("_", 1)[-1]
    return roman_values.get(suffix, 1)


def normalize_scroll_recipe_base(recipe_id: str) -> str:
    base = recipe_id.removeprefix("Scroll_")
    if re.search(r"_(?:I|II|III|IV|V)$", base):
        base = base.rsplit("_", 1)[0]

    special_cases = {
        "Silktouch": "pick_perfect",
        "FastSwim": "fast_swim",
        "ElementalHeart": "elemental_heart",
    }
    if base in special_cases:
        return special_cases[base]

    base = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", base)
    return base.lower()


def group_recipes_by_enchantment(
    recipes: list[ScrollRecipe],
    enchantments: list[Enchantment],
) -> dict[str, list[ScrollRecipe]]:
    enchantment_ids = {enchantment.id for enchantment in enchantments}
    grouped: dict[str, list[ScrollRecipe]] = {}

    for recipe in recipes:
        enchantment_id = normalize_scroll_recipe_base(recipe.id)
        if enchantment_id in enchantment_ids:
            grouped.setdefault(enchantment_id, []).append(recipe)

    for recipe_group in grouped.values():
        recipe_group.sort(key=lambda recipe: scroll_recipe_level(recipe.id))

    return grouped


def item_display_name(item_id: str, translations: dict[str, str]) -> str:
    return (
        translations.get(f"items.{item_id}.name")
        or translations.get(f"server.items.{item_id}.name")
        or item_id.replace("_", " ")
    )


def recipe_item_ids(recipes: list[ScrollRecipe]) -> list[str]:
    item_ids = {
        ingredient.item_id
        for recipe in recipes
        for ingredient in recipe.ingredients
        if recipe.id != "Scroll_Cleansing"
    }
    return sorted(item_ids)


def render_recipe_item_name_cache(item_ids: list[str], translations: dict[str, str]) -> str:
    cache = {
        item_id: item_display_name(item_id, translations)
        for item_id in item_ids
    }
    return json.dumps(cache, indent=2, ensure_ascii=True, sort_keys=True) + "\n"


def existing_media_file(directory: Path, stem: str) -> Path | None:
    for suffix in (".png", ".webp", ".jpg", ".jpeg"):
        path = directory / f"{stem}{suffix}"
        if path.exists():
            return path
    return None


def copy_enchantment_icon(
    enchantment: Enchantment,
    icon_paths: dict[str, str],
    changed: list[Path],
) -> Path | None:
    existing = existing_media_file(ENCHANTMENT_ICON_DIR, slugify(enchantment.id))
    icon_path = icon_paths.get(enchantment.id)
    if not icon_path:
        return existing

    source = COMMON_RESOURCES_DIR / icon_path
    if not source.exists():
        return existing

    target = ENCHANTMENT_ICON_DIR / f"{slugify(enchantment.id)}{source.suffix}"
    if copy_binary_if_changed(source, target):
        changed.append(target)
    return target


def copy_recipe_icon(
    item_id: str,
    item_icons: dict[str, Path],
    changed: list[Path],
) -> Path | None:
    existing = existing_media_file(RECIPE_ICON_DIR, item_id)
    source = item_icons.get(item_id)
    if not source or not source.exists():
        fallback = ASSET_COMMON_DIR / "Icons" / "ItemsGenerated" / f"{item_id}.png"
        source = fallback if fallback.exists() else None

    if not source:
        return existing

    target = RECIPE_ICON_DIR / f"{item_id}{source.suffix}"
    if copy_binary_if_changed(source, target):
        changed.append(target)
    return target


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


def parse_docstat_payload(payload: str) -> tuple[str, str]:
    payload = payload.strip()
    if "|" in payload:
        key, _, format_name = payload.partition("|")
    elif ";" in payload:
        key, _, format_name = payload.partition(";")
    else:
        key, format_name = payload, "raw"

    return key.strip(), (format_name.strip() or "raw")


def format_docstat_payload(key: str, format_name: str) -> str:
    if format_name == "raw":
        return key
    return f"{key};{format_name}"


def resolve_docstat(payload: str, context: dict[str, str]) -> str:
    key, format_name = parse_docstat_payload(payload)
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
        key, format_name = parse_docstat_payload(payload)
        canonical_payload = format_docstat_payload(key, format_name)
        return f"<!-- DOCSTAT:{canonical_payload} -->{resolve_docstat(payload, context)}<!-- /DOCSTAT -->"

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

    return "\n".join(
        f"* [{markdown_escape(enchantment.name)}]({page_for_enchantment(enchantment).name})"
        for enchantment in disabled
    )


def render_enchantment_index(enchantments: list[Enchantment], translations: dict[str, str]) -> str:
    lines = [
        (
            '<div class="se-table-card se-enchantment-index-card" '
            'style="border: 1px solid rgba(148, 163, 184, 0.22); border-radius: 8px; '
            'background: rgba(148, 163, 184, 0.04); overflow: hidden;">'
        ),
        (
            '<table class="se-doc-table se-enchantment-index-table" '
            'style="width: 100%; border-collapse: collapse; table-layout: fixed;">'
        ),
        "<colgroup>",
        '<col style="width: 16%;">',
        '<col style="width: 8%;">',
        '<col style="width: 13%;">',
        "<col>",
        '<col style="width: 13%;">',
        "</colgroup>",
        "<thead>",
        "<tr>",
        (
            '<th style="padding: 10px 12px; text-align: left; font-weight: 600; '
            'opacity: 0.75; border-bottom: 1px solid rgba(148, 163, 184, 0.18);">'
            "Enchantment</th>"
        ),
        (
            '<th style="padding: 10px 12px; text-align: left; font-weight: 600; '
            'opacity: 0.75; border-bottom: 1px solid rgba(148, 163, 184, 0.18);">'
            "Levels</th>"
        ),
        (
            '<th style="padding: 10px 12px; text-align: left; font-weight: 600; '
            'opacity: 0.75; border-bottom: 1px solid rgba(148, 163, 184, 0.18);">'
            "Default Modifier</th>"
        ),
        (
            '<th style="padding: 10px 12px; text-align: left; font-weight: 600; '
            'opacity: 0.75; border-bottom: 1px solid rgba(148, 163, 184, 0.18);">'
            "Applies To</th>"
        ),
        (
            '<th style="padding: 10px 12px; text-align: left; font-weight: 600; '
            'opacity: 0.75; border-bottom: 1px solid rgba(148, 163, 184, 0.18);">'
            "Enabled By Default</th>"
        ),
        "</tr>",
        "</thead>",
        "<tbody>",
    ]

    cell_style = (
        "padding: 10px 12px; border-top: 1px solid rgba(148, 163, 184, 0.14); "
        "vertical-align: top;"
    )
    nowrap_cell_style = f"{cell_style} white-space: nowrap;"

    for enchantment in enchantments:
        multiplier = primary_multiplier(enchantment)
        default_modifier = (
            f"<code>{html.escape(format_multiplier_for_summary(multiplier))}</code>"
            if multiplier
            else "None"
        )
        page_name = html.escape(page_for_enchantment(enchantment).name, quote=True)
        lines.extend(
            [
                "<tr>",
                (
                    f'<td style="{nowrap_cell_style}"><a href="{page_name}">'
                    f"{html.escape(enchantment.name)}</a></td>"
                ),
                f'<td style="{nowrap_cell_style}">{html.escape(level_range(enchantment.max_level))}</td>',
                f'<td style="{nowrap_cell_style}">{default_modifier}</td>',
                (
                    f'<td style="{cell_style}">'
                    f"{html.escape(format_categories(enchantment.categories, translations))}</td>"
                ),
                f'<td style="{nowrap_cell_style}">{format_bool(not enchantment.disabled_by_default)}</td>',
                "</tr>",
            ]
        )

    lines.extend(["</tbody>", "</table>", "</div>"])
    return "\n".join(lines)


def extract_manual_block(existing_content: str | None, name: str, default: str = "") -> str:
    if not existing_content:
        return default

    pattern = re.compile(
        rf"<!--\s*MANUAL:{re.escape(name)}:start\s*-->(.*?)<!--\s*MANUAL:{re.escape(name)}:end\s*-->",
        re.DOTALL,
    )
    match = pattern.search(existing_content)
    return match.group(1) if match else default


def manual_block(name: str, value: str = "") -> str:
    return f"<!-- MANUAL:{name}:start -->{value}<!-- MANUAL:{name}:end -->"


def render_enchantment_links(
    ids: list[str],
    enchantment_by_id: dict[str, Enchantment],
) -> str:
    links: list[str] = []
    for enchantment_id in ids:
        target = enchantment_by_id.get(enchantment_id)
        if target:
            links.append(f"[{markdown_escape(target.name)}]({page_for_enchantment(target).name})")
        else:
            links.append(f"`{enchantment_id}`")
    return ", ".join(links) if links else "None"


def render_enchantment_links_html(
    ids: list[str],
    enchantment_by_id: dict[str, Enchantment],
) -> str:
    links: list[str] = []
    for enchantment_id in ids:
        target = enchantment_by_id.get(enchantment_id)
        if target:
            links.append(
                f'<a href="{html.escape(page_for_enchantment(target).name, quote=True)}">'
                f"{html.escape(target.name)}</a>"
            )
        else:
            links.append(f"<code>{html.escape(enchantment_id)}</code>")
    return ", ".join(links) if links else "None"


def render_recipe_table(
    page_path: Path,
    recipes: list[ScrollRecipe],
    item_translations: dict[str, str],
    item_icons: dict[str, Path],
    changed: list[Path],
) -> str:
    if not recipes:
        return "<p>No default scroll recipe is configured.</p>"

    recipes = sorted(recipes, key=lambda recipe: scroll_recipe_level(recipe.id))
    ingredient_ids: list[str] = []
    for recipe in recipes:
        for ingredient in recipe.ingredients:
            if ingredient.item_id not in ingredient_ids:
                ingredient_ids.append(ingredient.item_id)

    lines = [
        (
            '<div class="se-recipe-card" style="border: 1px solid rgba(148, 163, 184, 0.22); '
            'border-radius: 8px; padding: 10px 12px; '
            'background: rgba(148, 163, 184, 0.04);">'
        ),
        (
            '<div class="se-recipe-grid" style="display: grid; '
            'grid-template-columns: minmax(0, 1fr) max-content; '
            'column-gap: 16px; align-items: center;">'
        ),
        (
            '<div class="se-recipe-heading" style="font-weight: 600; opacity: 0.75; padding: 0 0 8px;">'
            "Ingredient</div>"
        ),
        (
            '<div class="se-recipe-heading se-recipe-amount" '
            'style="font-weight: 600; opacity: 0.75; padding: 0 0 8px; '
            'text-align: right;">Amount</div>'
        ),
    ]

    for item_id in ingredient_ids:
        item_name = item_display_name(item_id, item_translations)
        icon_path = copy_recipe_icon(item_id, item_icons, changed)
        icon = ""
        if icon_path:
            icon = (
                f'<img src="{html.escape(raw_github_url(icon_path), quote=True)}" '
                f'alt="{html.escape(item_name, quote=True)}" '
                'class="se-recipe-icon" '
                'style="width: 28px; height: 28px; object-fit: contain; '
                'display: inline-block; flex: 0 0 28px; margin: 0;">'
            )

        amounts = []
        for recipe in recipes:
            amount = next(
                (ingredient.amount for ingredient in recipe.ingredients if ingredient.item_id == item_id),
                None,
            )
            amounts.append(str(amount) if amount is not None else "-")

        row_border = "border-top: 1px solid rgba(148, 163, 184, 0.18);"
        lines.extend(
            [
                (
                    f'<div class="se-recipe-cell se-recipe-ingredient" '
                    f'style="{row_border} display: flex; align-items: center; '
                    'gap: 10px; min-height: 40px; padding: 7px 0;">'
                    f'{icon}<span class="se-recipe-name">{html.escape(item_name)}</span></div>'
                ),
                (
                    f'<div class="se-recipe-cell se-recipe-amount" '
                    f'style="{row_border} display: flex; align-items: center; '
                    'justify-content: flex-end; min-height: 40px; padding: 7px 0;">'
                    f"<code>{html.escape('/'.join(amounts))}</code></div>"
                ),
            ]
        )

    lines.extend(["</div>", "</div>"])

    return "\n".join(lines)


def render_stats_table(stats_rows: list[tuple[str, str]]) -> str:
    lines = [
        (
            '<div class="se-stats-card" style="border: 1px solid rgba(148, 163, 184, 0.22); '
            'border-radius: 8px; padding: 10px 12px; '
            'background: rgba(148, 163, 184, 0.04);">'
        ),
        (
            '<div class="se-stats-grid" style="display: grid; '
            'grid-template-columns: max-content minmax(0, 1fr); '
            'column-gap: 22px; align-items: center;">'
        ),
        (
            '<div class="se-stats-heading" style="font-weight: 600; opacity: 0.75; padding: 0 0 8px;">'
            "Field</div>"
        ),
        (
            '<div class="se-stats-heading" style="font-weight: 600; opacity: 0.75; padding: 0 0 8px;">'
            "Value</div>"
        ),
    ]

    row_border = "border-top: 1px solid rgba(148, 163, 184, 0.18);"
    for label, value in stats_rows:
        lines.extend(
            [
                (
                    f'<div class="se-stats-cell se-stats-label" '
                    f'style="{row_border} display: flex; align-items: center; '
                    'min-height: 38px; padding: 7px 0; font-weight: 600; white-space: nowrap;">'
                    f"{html.escape(label)}</div>"
                ),
                (
                    f'<div class="se-stats-cell se-stats-value" '
                    f'style="{row_border} display: flex; align-items: center; '
                    'min-height: 38px; padding: 7px 0;">'
                    f"{value}</div>"
                ),
            ]
        )

    lines.extend(["</div>", "</div>"])

    return "\n".join(lines)


def render_enchantment_page(
    enchantment: Enchantment,
    enchantment_by_id: dict[str, Enchantment],
    translations: dict[str, str],
    item_translations: dict[str, str],
    recipe_map: dict[str, list[ScrollRecipe]],
    item_icons: dict[str, Path],
    icon_paths: dict[str, str],
    changed: list[Path],
) -> str:
    page_path = page_for_enchantment(enchantment)
    existing_content = page_path.read_text(encoding="utf-8") if page_path.exists() else None
    added_version = extract_manual_block(existing_content, "added-version", " ")
    showcase = extract_manual_block(
        existing_content,
        "showcase",
        "\n<!-- Add a GIF or screenshot here. -->\n",
    )
    icon_path = copy_enchantment_icon(enchantment, icon_paths, changed)

    stats_rows = [
        ("Added in Version", manual_block("added-version", added_version)),
        ("Default Modifier", format_multiplier_details_html(enchantment, translations)),
        ("Amount of Levels", level_count(enchantment.max_level)),
        ("ID", f"<code>{html.escape(enchantment.id)}</code>"),
        ("Can Be Applied To", html.escape(format_categories(enchantment.categories, translations))),
        ("Enabled By Default", format_bool(not enchantment.disabled_by_default)),
    ]
    if recipe_map.get(enchantment.id):
        tiers = "/".join(str(recipe.unlock_tier) for recipe in recipe_map[enchantment.id])
        stats_rows.append(("Crafting Tier", f"<code>{html.escape(tiers)}</code>"))
    if enchantment.owner_mod_name:
        stats_rows.append(("Requires", html.escape(enchantment.owner_mod_name)))
    if enchantment.conflicts:
        stats_rows.append(("Conflicts With", render_enchantment_links_html(enchantment.conflicts, enchantment_by_id)))

    lines = [
        f"# {markdown_escape(enchantment.name)}",
        "",
    ]

    if icon_path:
        lines.extend(
            [
                f"![{markdown_alt_escape(enchantment.name)} scroll icon]({raw_github_url(icon_path)})",
                "",
            ]
        )

    lines.extend(
        [
            render_enchantment_description(enchantment, translations),
            "",
            "## Stats and Recipe",
            "",
            '<div class="se-stats-recipe" style="display: flex; gap: 24px; align-items: flex-start; flex-wrap: wrap;">',
            '<div class="se-stats-panel" style="flex: 1 1 460px; min-width: 360px;">',
            "<h3>Stats</h3>",
            render_stats_table(stats_rows),
        ]
    )

    lines.extend(
        [
            "</div>",
            '<div class="se-recipe-panel" style="flex: 0 1 360px; min-width: 280px;">',
            "<h3>Recipe</h3>",
            render_recipe_table(
                page_path,
                recipe_map.get(enchantment.id, []),
                item_translations,
                item_icons,
                changed,
            ),
            "</div>",
            "</div>",
            "",
            "## Showcase",
            "",
            manual_block("showcase", showcase),
            "",
        ]
    )

    return "\n".join(lines)


def update_integrated_pages(
    enchantments: list[Enchantment],
    translations: dict[str, str],
    context: dict[str, str],
) -> list[Path]:
    changed: list[Path] = []
    markdown_files = sorted(DOCS_DIR.rglob("*.md"))

    for path in markdown_files:
        if update_inline_docstats(path, context):
            changed.append(path)

    block_updates = [
        (
            DOCS_DIR / "welcome-to-simple-enchantments" / "enchantments" / "README.md",
            "enchantment-index",
            render_enchantment_index(enchantments, translations),
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
    scroll_generator_text = read(SCROLL_ITEM_GENERATOR_SOURCE)
    translations = parse_language_file(read(LANG_SOURCE))
    item_translations = load_cached_item_translations()
    item_translations.update(load_optional_language_file(ASSET_LANG_SOURCE))
    item_icons = load_asset_item_icons()

    enchantments = parse_enchantments(enchantment_text)
    apply_source_data(
        enchantments,
        parse_multiplier_definitions(enchantment_text),
        parse_conflicts(enchantment_text),
        parse_disabled_defaults(config_text),
    )
    enchantment_by_id = {enchantment.id: enchantment for enchantment in enchantments}

    general_defaults = dict(parse_general_defaults(config_text))
    recipe_counts = parse_recipe_counts(config_text)
    scroll_recipe_tiers = parse_scroll_recipe_tiers(config_text)
    scroll_recipes = parse_scroll_recipes(config_text)
    scroll_recipe_item_ids = recipe_item_ids(scroll_recipes)
    recipe_map = group_recipes_by_enchantment(scroll_recipes, enchantments)
    icon_paths = parse_enchantment_icon_paths(scroll_generator_text)
    context = build_docstat_context(enchantments, general_defaults, recipe_counts, scroll_recipe_tiers)

    changed: list[Path] = []
    targets: dict[Path, str] = {}
    if ASSET_LANG_SOURCE.exists():
        targets[RECIPE_ITEM_NAME_CACHE] = render_recipe_item_name_cache(scroll_recipe_item_ids, item_translations)

    for enchantment in enchantments:
        targets[page_for_enchantment(enchantment)] = render_enchantment_page(
            enchantment,
            enchantment_by_id,
            translations,
            item_translations,
            recipe_map,
            item_icons,
            icon_paths,
            changed,
        )

    for path, content in targets.items():
        if write_if_changed(path, content):
            changed.append(path)

    changed.extend(update_integrated_pages(enchantments, translations, context))

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
