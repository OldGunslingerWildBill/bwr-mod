package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.component.*;
import dev.bwr.mod.fuel.BwrDataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.*;

/** Pure item/save checks; no world fixture positions are occupied. */
@GameTestHolder("bwr") @PrefixGameTestTemplate(false)
public final class PanelFootprintDefaultTests {
    @GameTest(template="empty")
    public static void pistolDefaultsAndSavedSizes(GameTestHelper h) {
        var type=ComponentTypes.CONTROL_SWITCH;
        var item=net.minecraft.core.registries.BuiltInRegistries.ITEM.get(type.id());
        var fresh=new ItemStack(item);
        h.assertTrue(ComponentItem.footprint(fresh).equals(Footprint.of(12,16)),"new GE target pistol ignored preferred size");
        fresh.set(BwrDataComponents.COMPONENT_VARIANT.get(),0);
        h.assertTrue(ComponentItem.footprint(fresh).equals(Footprint.of(8,12)),"stored small size changed");
        for(var entry:PanelStyles.TABLE) if(entry.type().equals("control_switch")) {
            int expected=entry.models().getFirst().equals("ge_sb_target_pistol")||entry.models().getFirst().equals("w2_pistol_hd")?1:0;
            h.assertTrue(PanelStyles.preferredFootprint(entry.type(),entry.style(),entry.variant())==expected,"wrong variant preference "+entry);
        }
        var old=new PanelComponent(1,type,0,0,Footprint.of(8,12),"OLD");
        var restored=PanelComponent.load(old.save());
        h.assertTrue(restored.w()==8&&restored.h()==12,"old placed pistol resized");
        h.succeed();
    }
}
