package dev.bwr.mod.devtest;

import dev.bwr.mod.fuel.*;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.client.Minecraft;
import net.minecraft.util.RandomSource;

final class FuelCatalogueClientCheck {
    static void run(Minecraft mc) {
        int fuel=0,rods=0;
        for(var stack:BwrItems.FUEL_TAB.get().getDisplayItems()) {
            String texture;
            if(stack.is(BwrItems.FUEL_ASSEMBLY.get())) { fuel++; texture=FuelAssemblies.dataOf(stack).fuelTypeName(); }
            else if(stack.is(BwrItems.SPECIALTY_ROD.get())) { rods++; texture=SpecialtyRodItem.data(stack).kind().id(); }
            else continue;
            var model=mc.getItemRenderer().getModel(stack,mc.level,mc.player,0);
            var quads=model.getQuads(null,null,RandomSource.create(0));
            // The depleted uranium INGOT owns item/fuel/depleted_uranium.png, so its assembly has its own PNG (alpha.48).
            String sprite="depleted_uranium".equals(texture)?"depleted_uranium_assembly":texture;
            if(quads.isEmpty()||quads.stream().anyMatch(q->!q.getSprite().contents().name().getPath().equals("item/fuel/"+sprite)))
                throw new IllegalStateException("Wrong/missing fuel texture for "+texture);
            if(stack.getHoverName().getString().startsWith("fuel.bwr.")||stack.getHoverName().getString().startsWith("insert.bwr."))
                throw new IllegalStateException("Untranslated fuel name");
        }
        if(fuel!=18||rods!=8)throw new IllegalStateException("Fuel tab incomplete: "+fuel+"/"+rods);
        if(BwrItems.TAB.get().getDisplayItems().stream().anyMatch(SpecialtyRodItem::isCoreItem))throw new IllegalStateException("Fuels still fill machine tab");
        com.mojang.logging.LogUtils.getLogger().info("FUEL CATALOGUE CLIENT PASS: {} grades, {} inserts; distinct PNGs and translated names",fuel,rods);
    }
}
