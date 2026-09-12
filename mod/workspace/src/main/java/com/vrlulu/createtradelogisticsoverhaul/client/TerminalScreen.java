package com.vrlulu.createtradelogisticsoverhaul.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vrlulu.createtradelogisticsoverhaul.net.ClientTerminals;
import com.vrlulu.createtradelogisticsoverhaul.net.Payloads;
import com.vrlulu.createtradelogisticsoverhaul.web.MapWebServer;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * The terminal's in-game window: everything about a terminal except trade listings can be set here,
 * without going to the map.
 *
 * <p>It needs no container menu, because it moves no items. The client already holds every
 * terminal's details for the map, and saving reuses the very packet the map sends.
 */
public class TerminalScreen extends Screen {
    private static final String[] ROLES = {"PRODUCER", "CONSUMER", "WAREHOUSE", "POST_OFFICE"};
    private static final String[] ACCESS = {"NONE", "VIEW", "ORDER", "ADMIN"};
    private static final int W = 280;

    private final BlockPos pos;
    private Payloads.TerminalInfo terminal;
    private JsonObject settings = new JsonObject();
    private boolean built;

    private EditBox name;
    private EditBox address;
    private EditBox batch;
    private EditBox wait;
    private EditBox priority;
    private CycleButton<String> role;
    private CycleButton<String> publicAccess;
    private CycleButton<Boolean> autoDispatch;
    private String status = "";

    public TerminalScreen(BlockPos pos) {
        super(Component.literal("Logistics Terminal"));
        this.pos = pos;
    }

    @Override
    protected void init() {
        ClientTerminals.requestRefresh(true);
        find();
        if (terminal == null) {
            return;             // tick() builds the widgets once the server answers
        }
        built = true;
        int left = (width - W) / 2;
        int y = height / 2 - 90;
        boolean admin = "ADMIN".equals(terminal.access());

        name = field(left + 96, y, terminal.name(), 48);
        y += 22;
        address = field(left + 96, y, terminal.address(), 64);

        y += 22;
        role = CycleButton.<String>builder(Component::literal)
                .withValues(ROLES).withInitialValue(str("role", "WAREHOUSE"))
                .displayOnlyValue()
                .create(left + 96, y, 184, 20, Component.literal("Role"), (b, v) -> { });
        y += 22;
        publicAccess = CycleButton.<String>builder(Component::literal)
                .withValues(ACCESS).withInitialValue(str("publicAccess", "VIEW"))
                .displayOnlyValue()
                .create(left + 96, y, 184, 20, Component.literal("Public"), (b, v) -> { });
        y += 22;
        autoDispatch = CycleButton.onOffBuilder(bool("autoDispatch"))
                .displayOnlyValue()
                .create(left + 96, y, 184, 20, Component.literal("Auto dispatch"), (b, v) -> { });
        addRenderableWidget(role);
        addRenderableWidget(publicAccess);
        addRenderableWidget(autoDispatch);

        y += 22;
        batch = small(left + 96, y, num("batchSize", 1));
        wait = small(left + 158, y, num("maxWaitSeconds", 120));
        priority = small(left + 220, y, num("priority", 0));

        y += 26;
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> save())
                .bounds(left + 96, y, 90, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Open map"), b -> openMap())
                .bounds(left + 190, y, 90, 20).build());

        for (EditBox box : new EditBox[]{name, address, batch, wait, priority}) {
            box.setEditable(admin);
        }
        role.active = admin;
        publicAccess.active = admin;
        autoDispatch.active = admin;
        if (!admin) {
            status = "Only the owner can change this terminal";
        }
    }

    private EditBox field(int x, int y, String value, int max) {
        EditBox box = new EditBox(font, x, y, 184, 18, Component.empty());
        box.setMaxLength(max);
        box.setValue(value);
        addRenderableWidget(box);
        return box;
    }

    private EditBox small(int x, int y, int value) {
        EditBox box = new EditBox(font, x, y, 56, 18, Component.empty());
        box.setMaxLength(6);
        box.setValue(Integer.toString(value));
        addRenderableWidget(box);
        return box;
    }

    private void find() {
        for (Payloads.TerminalInfo info : ClientTerminals.get()) {
            if (info.pos().equals(pos)) {
                terminal = info;
                try {
                    settings = JsonParser.parseString(info.settingsJson()).getAsJsonObject();
                } catch (RuntimeException e) {
                    settings = new JsonObject();
                }
                return;
            }
        }
    }

    private String str(String key, String fallback) {
        try {
            return settings.has(key) ? settings.get(key).getAsString() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private int num(String key, int fallback) {
        try {
            return settings.has(key) ? settings.get(key).getAsInt() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private boolean bool(String key) {
        try {
            return settings.has(key) && settings.get(key).getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Sends only the fields this window owns, so listings and people edited elsewhere survive. */
    private void save() {
        JsonObject out = new JsonObject();
        out.addProperty("name", name.getValue());
        out.addProperty("address", address.getValue());
        out.addProperty("role", role.getValue());
        out.addProperty("publicAccess", publicAccess.getValue());
        out.addProperty("autoDispatch", autoDispatch.getValue());
        out.addProperty("batchSize", intOf(batch, 1));
        out.addProperty("maxWaitSeconds", intOf(wait, 120));
        out.addProperty("priority", intOf(priority, 0));
        ClientTerminals.updateSettings(pos, out.toString());
        status = "Saved";
    }

    private void openMap() {
        MapWebServer server = ClientInit.server();
        if (server == null || !server.isRunning()) {
            status = "The map server is not running";
            return;
        }
        Util.getPlatform().openUri("http://127.0.0.1:" + server.port() + "/");
    }

    private static int intOf(EditBox box, int fallback) {
        try {
            return Integer.parseInt(box.getValue().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public void tick() {
        if (!built) {
            find();
            if (terminal != null) {
                rebuildWidgets();
            }
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        if (terminal == null) {
            g.drawCenteredString(font, "Reading the network...", width / 2, height / 2, 0xFFFFFF);
            return;
        }
        int left = (width - W) / 2;
        int y = height / 2 - 90;
        g.drawString(font, title.copy().withStyle(ChatFormatting.GOLD), left, y - 28, 0xFFFFFF, false);
        g.drawString(font, terminal.tuned() ? "linked to a network" : "not linked to a network yet",
                left, y - 16, terminal.tuned() ? 0x77DD88 : 0xDD8877, false);

        label(g, left, y, "Name");
        label(g, left, y + 22, "Address");
        label(g, left, y + 44, "Role");
        label(g, left, y + 66, "Public access");
        label(g, left, y + 88, "Auto dispatch");
        label(g, left, y + 110, "Batch/wait/pri");

        int listTop = y + 162;
        g.drawString(font, "Stock: " + terminal.stock().size() + " kind(s)"
                + (terminal.networkLoaded() ? "" : " - network not loaded"), left, listTop, 0xAAAAAA, false);
        int shown = 0;
        for (Payloads.StockLine line : terminal.stock()) {
            if (shown >= 5) {
                g.drawString(font, "...and " + (terminal.stock().size() - shown) + " more, on the map",
                        left, listTop + 12 + shown * 10, 0x888888, false);
                break;
            }
            g.drawString(font, line.display() + " x" + line.count(),
                    left, listTop + 12 + shown * 10, 0xDDDDDD, false);
            shown++;
        }
        if (!status.isEmpty()) {
            g.drawCenteredString(font, status, width / 2, listTop + 78, 0xFFD37A);
        }
    }

    private void label(GuiGraphics g, int left, int y, String text) {
        g.drawString(font, text, left, y + 5, 0x9AA1B2, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
