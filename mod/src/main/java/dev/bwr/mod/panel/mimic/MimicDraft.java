package dev.bwr.mod.panel.mimic;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.List;

/**
 * The path a player is drawing, kept on the tape stack itself (so the client sees it for the live preview with no
 * packet of its own, and it survives swapping items): the panel block of the first point and the points so far as
 * {@code x0, y0, x1, y1, ...} in that block's cell space.
 */
public record MimicDraft(BlockPos anchor, List<Integer> points) {
    public static final Codec<MimicDraft> CODEC = RecordCodecBuilder.<MimicDraft>create(i -> i.group(
            BlockPos.CODEC.fieldOf("anchor").forGetter(MimicDraft::anchor),
            Codec.INT.listOf().fieldOf("points").forGetter(MimicDraft::points)).apply(i, MimicDraft::new))
            .validate(draft -> draft.valid() ? DataResult.success(draft)
                    : DataResult.error(() -> "A mimic draft needs 1 to " + MimicOps.MAX_POINTS + " points as x, y pairs"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MimicDraft> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, MimicDraft::anchor,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(MimicOps.MAX_POINTS * 2 + 2)), MimicDraft::points,
            MimicDraft::new);

    public MimicDraft {
        points = List.copyOf(points);
    }

    /**
     * Whether the list is whole x, y pairs, at least one and at most {@link MimicOps#MAX_POINTS}. A draft is created with
     * its first point, so an empty or odd list can only come from a hand-made stack, and drawing on it would index past the
     * end.
     */
    public boolean valid() {
        return points.size() >= 2 && points.size() % 2 == 0 && points.size() <= MimicOps.MAX_POINTS * 2;
    }

    public int pointCount() {
        return points.size() / 2;
    }

    public int x(int index) {
        return points.get(index * 2);
    }

    public int y(int index) {
        return points.get(index * 2 + 1);
    }

    public int[] toArray() {
        int[] out = new int[points.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = points.get(i);
        }
        return out;
    }

    public MimicDraft with(int x, int y) {
        List<Integer> next = new ArrayList<>(points);
        next.add(x);
        next.add(y);
        return new MimicDraft(anchor, next);
    }
}
