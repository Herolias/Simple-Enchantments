# Enchantment Modifiers

> This page is generated from `EnchantmentType.java` and `Server/Languages/en-US/server.lang`.

## Configurable Multipliers

| Enchantment | Multiplier Key | Label | Default | Max Level |
|---|---|---|---:|---:|
| Sharpness | `sharpness` | Damage Per Level | `0.10` | 3 |
| Life Leech | `life_leech` | Heal % Per Level | `0.10` | 1 |
| Durability | `durability` | Reduction Per Level | `0.25` | 3 |
| Dexterity | `dexterity` | Stamina Reduction Per Level | `0.20` | 3 |
| Protection | `protection` | Damage Reduction Per Level | `0.04` | 3 |
| Efficiency | `efficiency` | Speed Per Level | `0.20` | 3 |
| Fortune | `fortune` | Drop Chance Per Level | `0.25` | 3 |
| Strength | `strength` | Damage Per Level | `0.10` | 3 |
| Eagle's Eye | `eagles_eye` | Distance Bonus Per Level | `0.005` | 3 |
| Looting | `looting` | Drop Chance Per Level | `0.25` | 3 |
| Looting | `looting:quantity` | Quantity Bonus Per Level | `0.25` | 3 |
| Feather Falling | `feather_falling` | Reduction Per Level | `0.20` | 3 |
| Waterbreathing | `waterbreathing` | Drain Reduction Per Level | `0.20` | 3 |
| Burn | `burn` | Damage Per Second | `5.0` | 1 |
| Burn | `burn:duration` | Duration (Seconds) | `3.0` | 1 |
| Freeze | `freeze` | Slow Amount | `0.5` | 1 |
| Freeze | `freeze:duration` | Duration (Seconds) | `5.0` | 1 |
| Thrift | `thrift` | Restore % Per Level | `0.20` | 3 |
| Elemental Heart | `elemental_heart` | Save Chance Per Level | `1.0` | 1 |
| Knockback | `knockback` | Strength Per Level | `0.6` | 3 |
| Reflection | `reflection` | Reflect % Per Level | `0.10` | 3 |
| Absorption | `absorption` | Heal % Per Level | `0.10` | 3 |
| Swift Swim | `fast_swim` | Speed Per Level | `0.25` | 3 |
| Ranged Protection | `ranged_protection` | Damage Reduction Per Level | `0.04` | 3 |
| Frenzy | `frenzy` | Charge Speed Per Level | `0.15` | 3 |
| Riposte | `riposte` | Damage Per Level | `0.10` | 3 |
| Coup de Grâce | `coup_de_grace` | Damage Per Level | `0.15` | 3 |
| Poison | `poison` | Damage Per Second | `3.0` | 1 |
| Poison | `poison:duration` | Duration (Seconds) | `4.0` | 1 |
| Env. Protection | `environmental_protection` | Reduction Per Level | `0.04` | 3 |
| Regeneration | `regeneration` | HP Per Second | `0.5` | 1 |
| Second Stomach | `second_stomach` | Bonus Per Level | `0.15` | 3 |

## Built-In Enchantments

| Enchantment | ID | Max Level | Legendary | Durability Required | Categories | Conflicts |
|---|---|---:|---|---|---|---|
| Sharpness | `sharpness` | 3 | No | No | Melee Weapons | - |
| Life Leech | `life_leech` | 1 | Yes | No | Melee Weapons | - |
| Durability | `durability` | 3 | No | Yes | Melee Weapons, Ranged Weapons, Tools, Pickaxes, Shovels, Axes, Armor, Staffs, Mana Staffs, Essence Staffs | - |
| Sturdy | `sturdy` | 1 | Yes | Yes | Melee Weapons, Ranged Weapons, Tools, Pickaxes, Shovels, Axes, Armor, Staffs, Mana Staffs, Essence Staffs | - |
| Dexterity | `dexterity` | 3 | No | No | Melee Weapons, Shields, Staffs, Mana Staffs, Essence Staffs | - |
| Protection | `protection` | 3 | No | No | Armor | - |
| Efficiency | `efficiency` | 3 | No | No | Pickaxes, Axes, Shovels | - |
| Fortune | `fortune` | 3 | No | No | Pickaxes | `pick_perfect` |
| Smelting | `smelting` | 1 | Yes | No | Pickaxes | `pick_perfect` |
| Strength | `strength` | 3 | No | No | Ranged Weapons | - |
| Eagle's Eye | `eagles_eye` | 3 | No | No | Ranged Weapons | - |
| Looting | `looting` | 3 | No | No | Melee Weapons, Ranged Weapons, Staffs, Mana Staffs, Essence Staffs | - |
| Feather Falling | `feather_falling` | 3 | No | No | Leg Armor | - |
| Waterbreathing | `waterbreathing` | 3 | No | No | Helmets | - |
| Burn | `burn` | 1 | Yes | No | Melee Weapons, Ranged Weapons | `freeze`, `poison` |
| Freeze | `freeze` | 1 | Yes | No | Ranged Weapons, Melee Weapons | `burn` |
| Eternal Shot | `eternal_shot` | 1 | No | No | Ranged Weapons | - |
| Pick Perfect | `pick_perfect` | 1 | Yes | No | Pickaxes, Axes, Shovels | `fortune`, `smelting` |
| Thrift | `thrift` | 3 | No | No | Mana Staffs | - |
| Elemental Heart | `elemental_heart` | 1 | Yes | No | Essence Staffs | - |
| Knockback | `knockback` | 3 | No | No | Melee Weapons, Shields | - |
| Reflection | `reflection` | 3 | No | No | Shields | `absorption` |
| Absorption | `absorption` | 3 | No | No | Shields | `reflection` |
| Swift Swim | `fast_swim` | 3 | No | No | Gloves | - |
| Night Vision | `night_vision` | 1 | Yes | No | Helmets | - |
| Ranged Protection | `ranged_protection` | 3 | No | No | Armor | - |
| Frenzy | `frenzy` | 3 | No | No | Melee Weapons, Ranged Weapons, Staffs, Mana Staffs, Essence Staffs | - |
| Riposte | `riposte` | 3 | No | No | Melee Weapons | - |
| Coup de Grâce | `coup_de_grace` | 3 | No | No | Melee Weapons | - |
| Poison | `poison` | 1 | Yes | No | Melee Weapons, Ranged Weapons | `burn` |
| Env. Protection | `environmental_protection` | 3 | No | No | Armor | - |
| Regeneration | `regeneration` | 1 | Yes | No | Chestplates | `second_stomach` |
| Second Stomach | `second_stomach` | 3 | No | No | Chestplates | `regeneration` |
