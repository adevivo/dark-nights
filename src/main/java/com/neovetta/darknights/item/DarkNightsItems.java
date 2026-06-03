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
    public static Item INFECTED_BRAIN;
    public static Item VAMPIRE_FANG;
    public static Item HOLY_WATER;

    public static void register() {
        RAW_WOLF_MEAT    = registerFood("raw_wolf_meat",  3, 0.3f);
        INFECTED_BRAIN   = registerFood("infected_brain", 1, 0.1f);
        VAMPIRE_FANG     = registerSimple("vampire_fang");
        HOLY_WATER       = registerSimple("holy_water");
    }

    private static Item registerSimple(String name) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath(DarkNights.MOD_ID, name));
        Item item = new Item(new Item.Properties().setId(key));
        Registry.register(BuiltInRegistries.ITEM, key, item);
        return item;
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
