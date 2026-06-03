package com.neovetta.darknights.item;

import com.neovetta.darknights.DarkNights;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.component.Consumables;

public class DarkNightsItems {

    public static Item RAW_WOLF_MEAT;

    public static void register() {
        RAW_WOLF_MEAT = registerFood("raw_wolf_meat", 3, 0.3f);
    }

    private static Item registerFood(String name, int nutrition, float saturation) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath(DarkNights.MOD_ID, name));
        FoodProperties food = new FoodProperties.Builder()
            .nutrition(nutrition)
            .saturationModifier(saturation)
            .build();
        Consumable consumable = Consumables.defaultFood().build();
        Item item = new Item(new Item.Properties().setId(key).food(food, consumable));
        Registry.register(BuiltInRegistries.ITEM, key, item);
        return item;
    }
}
