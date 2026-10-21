package dev.bwr.mod.grid;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Coordinates plus durable identity. Resolution never loads a chunk. */
public record DeviceReference(String dimension,BlockPos pos,UUID id) {
    public DeviceReference {pos=pos.immutable();}
    public BlockEntity resolve(Level origin) {
        if(origin==null||origin.getServer()==null)return null;
        var name=ResourceLocation.tryParse(dimension);if(name==null)return null;
        var world=origin.getServer().getLevel(ResourceKey.create(Registries.DIMENSION,name));
        return world!=null?dev.bwr.mod.world.LoadedWorld.blockEntity(world,pos):null;
    }
    public CompoundTag save(){var t=new CompoundTag();t.putString("Dimension",dimension);t.putLong("Pos",pos.asLong());t.putUUID("Id",id);return t;}
    public static DeviceReference load(CompoundTag t){
        String dim=t.getString("Dimension");
        return ResourceLocation.tryParse(dim)==null||!t.hasUUID("Id")||!t.contains("Pos")?null:new DeviceReference(dim,BlockPos.of(t.getLong("Pos")),t.getUUID("Id"));
    }
}
