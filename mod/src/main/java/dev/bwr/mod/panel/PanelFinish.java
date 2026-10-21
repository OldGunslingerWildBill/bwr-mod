package dev.bwr.mod.panel;

import dev.bwr.mod.text.BwrText;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/**
 * The paint finish of a control-room block: panel bodies, trim and furniture. One blockstate property, {@code finish},
 * that the model colours through a block and an item colour handler ({@code PanelFinishColors}); the models carry grey
 * paint textures and a {@code neoforge_TintIndex} on every painted material, so one texture serves every finish.
 *
 * <p>{@link #SEAFOAM} is the first value, so it is the default of every state, and a saved block that has no
 * {@code finish} (everything placed before the property existed) loads as seafoam: the look the blocks always had.
 *
 * <p>A dropped block keeps its finish on the item as the vanilla {@code minecraft:block_state} component, which a
 * {@code BlockItem} applies again when it places (the loot tables write it for the other four finishes and nothing for
 * seafoam, so a seafoam drop still stacks with a crafted one).
 *
 * <h2>Tint indices</h2>
 * Panel models (grey textures, {@link #PANEL_NEUTRAL} / 255 of full brightness): {@link #TINT_PANEL_PAINT} the painted
 * faces, {@link #TINT_PANEL_BEZEL} the bezel edges and the rear. Furniture models (the white concrete texture, tinted
 * by the colour itself, as they always were): {@link #TINT_FURNITURE_PAINT}, {@link #TINT_FURNITURE_BEZEL},
 * {@link #TINT_FURNITURE_TOP} (the desk laminate) and {@link #TINT_FURNITURE_FRAME} (the thin housing of the mimic bands
 * and wall displays, dark in seafoam).
 */
public enum PanelFinish implements StringRepresentable {
    /** The original look (the GE green of the owner's first boards); the default. Values are the old model colours. */
    SEAFOAM("seafoam", "Seafoam", 0xA7BCAE, 0x7D9284, 0xBCCDC1, 0x3A3F42),
    /** Cream/ivory boards of the US and French rooms. */
    CREAM("cream", "Cream", 0xDDD6BB, 0xA8A38E, 0xE3DDC6, 0x4A4638),
    /** Tan/beige with a brown trim, the Shearon Harris style. */
    TAN("tan", "Tan", 0xC2AE8A, 0x9A8A6C, 0xCDBC9C, 0x4A3F2E),
    /** Pale grey (Sizewell B, Qinshan). */
    GREY("grey", "Grey", 0xB4B9BC, 0x8A9092, 0xC6CACC, 0x3A3D40),
    /** The light blue of the owner's simulator (a sample of its panel in the screenshots is 5F8A9B under its shading). */
    LIGHT_BLUE("light_blue", "Light blue", 0x9CC4D6, 0x7499AA, 0xB0D0DE, 0x33434C);

    public static final EnumProperty<PanelFinish> FINISH = EnumProperty.create("finish", PanelFinish.class);
    /** The name of the property, as a block item's state component spells it. */
    public static final String PROPERTY = "finish";

    public static final int TINT_PANEL_PAINT = 0;
    public static final int TINT_PANEL_BEZEL = 1;
    public static final int TINT_FURNITURE_PAINT = 4;
    public static final int TINT_FURNITURE_BEZEL = 5;
    public static final int TINT_FURNITURE_TOP = 6;
    public static final int TINT_FURNITURE_FRAME = 7;

    /** The grey level (of 255) of the paint in the panel textures; a panel's tint is its colour over this. */
    public static final int PANEL_NEUTRAL = 230;

    private final String serializedName;
    private final String english;
    private final int paint;
    private final int bezel;
    private final int top;
    private final int frame;

    PanelFinish(String serializedName, String english, int paint, int bezel, int top, int frame) {
        this.serializedName = serializedName;
        this.english = english;
        this.paint = paint;
        this.bezel = bezel;
        this.top = top;
        this.frame = frame;
    }

    @Override
    public String getSerializedName() {
        return serializedName;
    }

    /** The finish's name as a translatable component (the key {@code finish.bwr.<name>}, English fallback). */
    public Component displayName() {
        return BwrText.tr("finish.bwr." + serializedName, english);
    }

    /** The finish after this one, wrapping round: what the wrench steps to. */
    public PanelFinish next() {
        PanelFinish[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    public int paint() {
        return paint;
    }

    public int bezel() {
        return bezel;
    }

    public int top() {
        return top;
    }

    public int frame() {
        return frame;
    }

    /**
     * The colour (RGB, no alpha) a model material with this tint index is multiplied by, or {@code -1} for an index
     * that carries no finish.
     */
    public int tint(int tintIndex) {
        return switch (tintIndex) {
            case TINT_PANEL_PAINT -> panelTint(paint);
            case TINT_PANEL_BEZEL -> panelTint(bezel);
            case TINT_FURNITURE_PAINT -> paint;
            case TINT_FURNITURE_BEZEL -> bezel;
            case TINT_FURNITURE_TOP -> top;
            case TINT_FURNITURE_FRAME -> frame;
            default -> -1;
        };
    }

    /** The tint that turns the grey panel texture into {@code colour}: each channel over the texture's grey level. */
    private static int panelTint(int colour) {
        int r = Math.min(255, Math.round(((colour >> 16) & 0xFF) * 255f / PANEL_NEUTRAL));
        int g = Math.min(255, Math.round(((colour >> 8) & 0xFF) * 255f / PANEL_NEUTRAL));
        int b = Math.min(255, Math.round((colour & 0xFF) * 255f / PANEL_NEUTRAL));
        return (r << 16) | (g << 8) | b;
    }

    // ---- reading it back

    /** The finish of a state; seafoam for a state with no finish at all. */
    public static PanelFinish of(BlockState state) {
        return state.hasProperty(FINISH) ? state.getValue(FINISH) : SEAFOAM;
    }

    /** The finish an item places with (its {@code minecraft:block_state} component); seafoam when it has none. */
    public static PanelFinish of(ItemStack stack) {
        BlockItemStateProperties properties = stack.get(DataComponents.BLOCK_STATE);
        if (properties == null) {
            return SEAFOAM;
        }
        String name = properties.properties().get(PROPERTY);
        for (PanelFinish finish : values()) {
            if (finish.serializedName.equals(name)) {
                return finish;
            }
        }
        return SEAFOAM;
    }

    /** {@code stack} with its finish set: the state component for any other finish than seafoam, none for seafoam. */
    public static ItemStack withFinish(ItemStack stack, PanelFinish finish) {
        if (finish == SEAFOAM) {
            stack.remove(DataComponents.BLOCK_STATE);
        } else {
            stack.set(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY.with(FINISH, finish));
        }
        return stack;
    }
}
