---
title: "Getting Started"
order: 1
published: true
draft: false
---

To compile against the API, place the Simple Enchantments jar in your add-on project's `lib` folder and add it as a `compileOnly` dependency. The jar is provided at runtime by Hytale when the mod is installed, so you should not bundle it into your own jar.

The current Simple Enchantments mod version in this repository is `1.1.0`. Replace that version in the examples below with the version you intentionally target.

## Gradle Setup

```gradle
dependencies {
    implementation(files("$hytaleHome/install/$patchline/package/game/latest/Server/HytaleServer.jar"))
    compileOnly files("lib/SimpleEnchantments-1.1.0.jar")
}
```

If your project uses a `build.properties` variable, the example project style also works:

```gradle
dependencies {
    implementation(files("$hytaleHome/install/$patchline/package/game/latest/Server/HytaleServer.jar"))
    compileOnly files("lib/SimpleEnchantments-${simple_enchantments_version}.jar")
}
```

## Dependency Types

Choose the manifest style that matches your mod.

| Project type | Use this when | Manifest key | Runtime behavior |
|---|---|---|---|
| Enchantment add-on | Your mod mainly adds enchantments, categories, or scrolls for Simple Enchantments. | `Dependencies` | Your mod will not load unless Simple Enchantments is installed. |
| Optional integration | Your mod has its own features and only integrates with Simple Enchantments when present. | `OptionalDependencies` | Your mod can load without Simple Enchantments, but API calls must be guarded. |

## Full Dependency Manifest

Use a full dependency when your mod requires Simple Enchantments.

```json
{
  "Main": "com.example.plugin.MyEnchantmentAddon",
  "Dependencies": {
    "org.herolias:SimpleEnchantments": ">=1.0.0"
  }
}
```

With a full dependency, Hytale loads Simple Enchantments first. You should still handle a missing API instance gracefully during development, but you do not need a `NoClassDefFoundError` guard.

```java
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import org.herolias.plugin.api.EnchantmentApi;
import org.herolias.plugin.api.EnchantmentApiProvider;

import javax.annotation.Nonnull;

public class MyEnchantmentAddon extends JavaPlugin {

    public MyEnchantmentAddon(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        EnchantmentApi api = EnchantmentApiProvider.get();
        if (api == null) {
            throw new IllegalStateException("Simple Enchantments API is not ready");
        }

        registerEnchantments(api);
    }

    private void registerEnchantments(EnchantmentApi api) {
        // Register categories, crafting tabs, enchantments, and systems here.
    }
}
```

## Optional Dependency Manifest

Use an optional dependency when your mod should still run without Simple Enchantments.

```json
{
  "Main": "com.example.plugin.MyStandaloneMod",
  "OptionalDependencies": {
    "org.herolias:SimpleEnchantments": ">=1.0.0"
  }
}
```

Optional integrations must protect all Simple Enchantments class references. The safest pattern is to keep the integration in one method and catch `NoClassDefFoundError` around the call.

```java
@Override
protected void setup() {
    registerCoreSystems();
    tryRegisterSimpleEnchantmentsIntegration();
}

private void tryRegisterSimpleEnchantmentsIntegration() {
    try {
        EnchantmentApi api = EnchantmentApiProvider.get();
        if (api == null) {
            return;
        }

        api.registerItemToCategory("My_Custom_Sword", "MELEE_WEAPON");
    } catch (NoClassDefFoundError ignored) {
        // Simple Enchantments is not installed. Keep your core mod running.
    }
}
```

## When To Register Things

Register item categories, crafting categories, enchantments, and event listeners during your plugin `setup()` method. Register ECS systems after your enchantments are registered, so the systems can safely look up their `EnchantmentType` definitions during gameplay.

For add-ons that define actual enchantment effects, the usual setup order is:

1. Get the API.
2. Register custom item categories.
3. Register custom Enchanting Table tabs.
4. Register enchantments and scroll definitions.
5. Register ECS systems, commands, or event listeners that use those enchantments.

Next pages:

* Use [How to Build Your Own Enchantment](https://wiki.hytalemodding.dev/mod/simple-enchantments/how-to-build-your-own-enchantment) for the full add-on flow.
* Use [Register Items to Categories](https://wiki.hytalemodding.dev/mod/simple-enchantments/items-and-categories) if you only want existing enchantments to work on your custom items.
* Use [Work With Enchanted Items](https://wiki.hytalemodding.dev/mod/simple-enchantments/working-with-enchanted-items) if you want to read, add, or remove enchantments from code.

## Import Checklist

Most add-ons need these imports:

```java
import org.herolias.plugin.api.EnchantmentApi;
import org.herolias.plugin.api.EnchantmentApiProvider;
import org.herolias.plugin.api.ScaleType;
import org.herolias.plugin.enchantment.EnchantmentType;
import org.herolias.plugin.enchantment.ItemCategory;
```

Event listeners also need:

```java
import org.herolias.plugin.api.event.EnchantmentActivatedEvent;
import org.herolias.plugin.api.event.ItemEnchantedEvent;
```
