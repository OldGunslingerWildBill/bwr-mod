package dev.bwr.mod.panel.component;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Annunciator control module configuration: the annunciator group its ACK and RESET buttons act on. No latched
 * state: every button is 1 only while held. Saved only when the group is not 1, so a module at the defaults saves
 * nothing.
 */
public final class AnnunciatorControlData extends ComponentData {
    public int group = AnnunciatorData.MIN_GROUP;

    @Override
    public void save(CompoundTag tag) {
        if (group != AnnunciatorData.MIN_GROUP) {
            tag.putInt("AnnGroup", group);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        group = tag.contains("AnnGroup", Tag.TAG_ANY_NUMERIC)
                ? Math.max(AnnunciatorData.MIN_GROUP, Math.min(AnnunciatorData.MAX_GROUP, tag.getInt("AnnGroup")))
                : AnnunciatorData.MIN_GROUP;
    }
}
