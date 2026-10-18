package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.MachineNames;
import dev.bwr.mod.panel.net.PanelPayloads;
import dev.bwr.mod.panel.networking.NetworkingPayloads;
import dev.bwr.mod.panel.networking.NetworkingScreenPayloads;
import dev.bwr.mod.panel.wiring.WiringOps;
import dev.bwr.mod.signal.Kind;
import dev.bwr.mod.signal.Quantity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The machine Networking screen, opened by sneak-right-clicking a device with the Networking Wrench. A scrolling list
 * (the rows of {@link ConfigLayout} and {@link ScrollRows}): the machine's name and caption template, its type and
 * position, every signal with its live value, unit, quantity and how many components read it, every command with how
 * many components send it, and the components linked to it, each with an Unlink button. "Name linked components"
 * copies the machine name to the captions of the components that read or command it through a line link; it is a
 * button, never automatic.
 *
 * <p>The values arrive by one request and response, asked for once a second while the screen is open. Nothing here
 * decides anything: Done, Name linked components and Unlink each send a packet the server validates.
 */
public final class NetworkingScreen extends Screen {
    private static NetworkingScreen current;

    private NetworkingScreenPayloads.ScreenData data;
    private List<String> values = List.of();
    private String nameText;
    private String templateText;
    private ConfigLayout layout;
    private ScrollRows rows;
    private final List<TextRow> texts = new ArrayList<>();
    private int ticks;

    /** A row drawn as text (a heading, a signal, a command, a link without its button). */
    private record TextRow(int row, java.util.function.Supplier<Component> text, int color, boolean heading, int reserve) {
    }

    private NetworkingScreen(NetworkingScreenPayloads.ScreenData data) {
        super(Component.translatable("screen.bwr.networking.title"));
        this.data = data;
        this.nameText = data.name();
        this.templateText = data.template();
    }

    /** The server sent the screen (opening it, or refreshing it): shows it, keeping what was typed when it is already open. */
    public static void open(NetworkingScreenPayloads.ScreenData data) {
        Minecraft minecraft = Minecraft.getInstance();
        if (current != null && minecraft.screen == current && current.data.device().equals(data.device())) {
            current.data = data;
            current.values = List.of();
            current.rebuildWidgets();
            return;
        }
        current = new NetworkingScreen(data);
        minecraft.setScreen(current);
    }

    /** Live values arrived for the open screen. */
    public static void values(NetworkingScreenPayloads.Values payload) {
        if (current != null && Minecraft.getInstance().screen == current && current.data.device().equals(payload.device())) {
            current.values = payload.values();
        }
    }

    /** The data on show, for checks. */
    public NetworkingScreenPayloads.ScreenData data() {
        return data;
    }

    /** The live values on show, for checks. */
    public List<String> shownValues() {
        return values;
    }

    private int rowCount() {
        return 3 + 1 + data.signals().size() + 1 + data.commands().size() + 1 + Math.max(1, data.links().size());
    }

    @Override
    protected void init() {
        layout = new ConfigLayout(width, height, rowCount());
        rows = new ScrollRows(layout);
        texts.clear();
        int row = 0;

        EditBox name = box(row++, nameText, MachineNames.MAX_NAME);
        name.setResponder(value -> nameText = value);
        text(row - 1, () -> Component.translatable("screen.bwr.networking.name"), 0xCCCCCC, false);
        EditBox template = box(row++, templateText, MachineNames.MAX_TEMPLATE);
        template.setResponder(value -> templateText = value);
        text(row - 1, () -> Component.translatable("screen.bwr.networking.template"), 0xCCCCCC, false);
        BlockPos at = data.device();
        text(row++, () -> Component.translatable("screen.bwr.networking.where", Component.translatable(data.typeKey()),
                at.getX(), at.getY(), at.getZ()), 0x9FE0FF, false);

        text(row++, () -> Component.translatable("screen.bwr.networking.signals", data.signals().size()), 0xFFFFFF, true);
        for (int i = 0; i < data.signals().size(); i++) {
            int index = i;
            text(row++, () -> signalLine(index), 0xE0E0E0, false);
        }
        text(row++, () -> Component.translatable("screen.bwr.networking.commands", data.commands().size()), 0xFFFFFF, true);
        for (int i = 0; i < data.commands().size(); i++) {
            int index = i;
            text(row++, () -> commandLine(index), 0xE0E0E0, false);
        }
        text(row++, () -> Component.translatable("screen.bwr.networking.links", data.links().size()), 0xFFFFFF, true);
        if (data.links().isEmpty()) {
            text(row++, () -> Component.translatable("screen.bwr.networking.no_links"), 0x999999, false);
        }
        for (NetworkingScreenPayloads.NetLink link : data.links()) {
            int y = layout.rowY(row);
            int buttonWidth = 56;
            texts.add(new TextRow(row, () -> linkLine(link), link.command() ? 0xFFB070 : 0x9FE0FF, false, buttonWidth + 4));
            rows.add(addRenderableWidget(Button.builder(Component.translatable("screen.bwr.networking.unlink"), button -> unlink(link))
                    .bounds(layout.left + layout.width - buttonWidth, y, buttonWidth, layout.rowHeight - 2).build()));
            row++;
        }

        int third = (layout.width - 8) / 3;
        addRenderableWidget(Button.builder(Component.translatable("screen.bwr.networking.name_linked"), button -> {
            sendEdit();
            PacketDistributor.sendToServer(new NetworkingPayloads.NameLinked(data.device()));
            PacketDistributor.sendToServer(new NetworkingScreenPayloads.Open(data.device()));
        }).bounds(layout.left, layout.buttonY, third + 40, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> {
            sendEdit();
            onClose();
        }).bounds(layout.left + third + 44, layout.buttonY, third - 40, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .bounds(layout.left + 2 * third + 8, layout.buttonY, third, 20).build());
    }

    private EditBox box(int row, String value, int maxLength) {
        EditBox box = new EditBox(font, layout.widgetX, layout.rowY(row), layout.widgetWidth, layout.rowHeight - 2,
                Component.empty());
        box.setMaxLength(maxLength);
        box.setValue(value);
        rows.add(addRenderableWidget(box));
        return box;
    }

    private void text(int row, java.util.function.Supplier<Component> text, int color, boolean heading) {
        texts.add(new TextRow(row, text, color, heading, 0));
    }

    private void sendEdit() {
        PacketDistributor.sendToServer(new NetworkingPayloads.Edit(data.device(), nameText, templateText));
    }

    private void unlink(NetworkingScreenPayloads.NetLink link) {
        PacketDistributor.sendToServer(new PanelPayloads.Bind(link.panel(), link.id(), link.command(), link.port(),
                (link.command() ? WiringOps.BindKind.REMOVE_SINK : WiringOps.BindKind.NONE).ordinal(), BlockPos.ZERO, "",
                BlockPos.ZERO, 0, link.command() ? link.sink() : 0, 1.0));
        PacketDistributor.sendToServer(new NetworkingScreenPayloads.Open(data.device()));
    }

    private Component signalLine(int index) {
        NetworkingScreenPayloads.NetSignal signal = data.signals().get(index);
        Quantity quantity = Quantity.byId(signal.quantity());
        String value = index < values.size() ? values.get(index) : "...";
        return Component.literal(signal.label() + ":  " + value + "   [" + (quantity == null ? "?" : quantity.name().toLowerCase(Locale.ROOT))
                + "]  " + Component.translatable("screen.bwr.networking.readers", signal.readers()).getString());
    }

    private Component commandLine(int index) {
        NetworkingScreenPayloads.NetCommand command = data.commands().get(index);
        Kind kind = Kind.values()[Math.max(0, Math.min(Kind.values().length - 1, command.kind()))];
        return Component.literal(command.label() + "  (" + kind.name().toLowerCase(Locale.ROOT) + ")  "
                + Component.translatable("screen.bwr.networking.users", command.users()).getString());
    }

    private Component linkLine(NetworkingScreenPayloads.NetLink link) {
        String caption = link.caption().isEmpty() ? "" : "  \"" + link.caption() + "\"";
        return Component.literal(link.component() + caption + "  " + (link.command() ? "-> " : "<- ") + link.keyLabel());
    }

    @Override
    public void tick() {
        super.tick();
        if (ticks++ % 20 == 0) {
            PacketDistributor.sendToServer(new NetworkingScreenPayloads.ValuesRequest(data.device()));
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        renderBackground(graphics, mouseX, mouseY, partial);
        graphics.fill(layout.left - 6, layout.top - 4, layout.left + layout.width + 6, layout.panelBottom + 2, 0xC0101010);
        Component name = nameText.isBlank() ? Component.translatable(data.typeKey()) : Component.literal(nameText);
        graphics.drawString(font, Component.translatable("screen.bwr.networking.heading", name), layout.left, layout.titleY(),
                0xFFFFFF);
        for (TextRow row : texts) {
            if (!rows.visible(row.row())) {
                continue;
            }
            Component line = row.text().get();
            int y = rows.y(row.row()) + 5;
            if (row.heading()) {
                graphics.fill(layout.left, y + 9, layout.left + layout.width, y + 10, 0x60FFFFFF);
            }
            boolean label = row.row() < 2 && !row.heading();
            int limit = label ? layout.labelWidth - 4 : layout.width - row.reserve();
            graphics.drawString(font, font.substrByWidth(line, limit).getString(), layout.left, y, row.color());
        }
        for (Renderable widget : renderables) {
            widget.render(graphics, mouseX, mouseY, partial);
        }
        rows.renderScrollbar(graphics);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY) || rows.scroll(scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        if (current == this) {
            current = null;
        }
    }
}
