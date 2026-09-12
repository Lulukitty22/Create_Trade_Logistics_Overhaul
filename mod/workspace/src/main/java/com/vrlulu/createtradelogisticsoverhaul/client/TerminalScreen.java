package com.vrlulu.createtradelogisticsoverhaul.client;

import com.google.gson.JsonArray;
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

import java.util.ArrayList;
import java.util.List;

/**
 * The terminal's in-game window.
 *
 * <p>It needs no container menu, because it moves no items: the client already holds every
 * terminal's details for the map, and each tab saves through the very packet the map sends, so the
 * two views cannot drift apart.
 *
 * <p>Three tabs. Settings is the terminal itself; Trade is what other players may buy and for how
 * much; Stock is what the network is holding, with a way to order it somewhere.
 */
public class TerminalScreen extends Screen {
    private static final String[] ROLES = {"PRODUCER", "CONSUMER", "WAREHOUSE", "POST_OFFICE"};
    private static final String[] ACCESS = {"NONE", "VIEW", "ORDER", "ADMIN"};
    private static final int W = 300;
    private static final int H = 220;
    private static final int ROWS = 6;

    private enum Tab { SETTINGS, TRADE, STOCK }

    private final BlockPos pos;
    private Payloads.TerminalInfo terminal;
    private JsonObject settings = new JsonObject();
    private Tab tab = Tab.SETTINGS;
    private boolean built;
    private int scroll;
    private String status = "";

    /** One editable trade listing while the window is open. */
    private static final class Listing {
        String item = "";
        int price;
        int maxPerOrder;
    }

    private final List<Listing> listings = new ArrayList<>();
    private EditBox name;
    private EditBox address;
    private EditBox batch;
    private EditBox wait;
    private EditBox priority;
    private EditBox deliverTo;
    private CycleButton<String> role;
    private CycleButton<String> publicAccess;
    private CycleButton<Boolean> autoDispatch;

    public TerminalScreen(BlockPos pos) {
        super(Component.literal("Logistics Terminal"));
        this.pos = pos;
    }

    private int left() {
        return (width - W) / 2;
    }

    private int top() {
        return (height - H) / 2;
    }

    private boolean admin() {
        return terminal != null && "ADMIN".equals(terminal.access());
    }

    private boolean mayOrder() {
        return terminal != null && ("ADMIN".equals(terminal.access()) || "ORDER".equals(terminal.access()));
    }

    @Override
    protected void init() {
        ClientTerminals.requestRefresh(true);
        find();
        if (terminal == null) {
            return;             // tick() builds the widgets once the server answers
        }
        built = true;
        int x = left();
        int y = top();

        addRenderableWidget(tabButton(x + 8, y + 6, "Settings", Tab.SETTINGS));
        addRenderableWidget(tabButton(x + 78, y + 6, "Trade", Tab.TRADE));
        addRenderableWidget(tabButton(x + 148, y + 6, "Stock", Tab.STOCK));

        switch (tab) {
            case SETTINGS -> initSettings(x, y + 34);
            case TRADE -> initTrade(x, y + 34);
            case STOCK -> initStock(x, y + 34);
        }
    }

    private Button tabButton(int x, int y, String label, Tab target) {
        Button button = Button.builder(Component.literal(label), b -> {
            tab = target;
            scroll = 0;
            status = "";
            rebuildWidgets();
        }).bounds(x, y, 66, 18).build();
        button.active = tab != target;
        return button;
    }

    // ------------------------------------------------------------------ settings
    private void initSettings(int x, int y) {
        name = field(x + 104, y, 180, terminal.name(), 48);
        address = field(x + 104, y + 22, 180, terminal.address(), 64);

        role = CycleButton.<String>builder(Component::literal)
                .withValues(ROLES).withInitialValue(str("role", "WAREHOUSE")).displayOnlyValue()
                .create(x + 104, y + 44, 180, 18, Component.literal("Role"), (b, v) -> { });
        publicAccess = CycleButton.<String>builder(Component::literal)
                .withValues(ACCESS).withInitialValue(str("publicAccess", "VIEW")).displayOnlyValue()
                .create(x + 104, y + 66, 180, 18, Component.literal("Public"), (b, v) -> { });
        autoDispatch = CycleButton.onOffBuilder(bool("autoDispatch")).displayOnlyValue()
                .create(x + 104, y + 88, 180, 18, Component.literal("Auto"), (b, v) -> { });
        addRenderableWidget(role);
        addRenderableWidget(publicAccess);
        addRenderableWidget(autoDispatch);

        batch = field(x + 104, y + 110, 54, Integer.toString(num("batchSize", 1)), 6);
        wait = field(x + 167, y + 110, 54, Integer.toString(num("maxWaitSeconds", 120)), 6);
        priority = field(x + 230, y + 110, 54, Integer.toString(num("priority", 0)), 6);

        addRenderableWidget(Button.builder(Component.literal("Save"), b -> saveSettings())
                .bounds(x + 104, y + 136, 88, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Open map"), b -> openMap())
                .bounds(x + 196, y + 136, 88, 18).build());

        boolean admin = admin();
        for (EditBox box : new EditBox[]{name, address, batch, wait, priority}) {
            box.setEditable(admin);
        }
        role.active = admin;
        publicAccess.active = admin;
        autoDispatch.active = admin;
    }

    // ------------------------------------------------------------------ trade
    private void initTrade(int x, int y) {
        if (listings.isEmpty()) {
            loadListings();
        }
        boolean admin = admin();
        int shown = Math.min(ROWS, Math.max(0, listings.size() - scroll));
        for (int i = 0; i < shown; i++) {
            Listing listing = listings.get(scroll + i);
            int row = y + i * 22;
            EditBox item = field(x + 8, row, 150, listing.item, 96);
            item.setResponder(v -> listing.item = v);
            EditBox price = field(x + 162, row, 46, Integer.toString(listing.price), 6);
            price.setResponder(v -> listing.price = parse(v, 0));
            EditBox max = field(x + 212, row, 46, Integer.toString(listing.maxPerOrder), 6);
            max.setResponder(v -> listing.maxPerOrder = parse(v, 0));
            int index = scroll + i;
            Button remove = Button.builder(Component.literal("x"), b -> {
                listings.remove(index);
                rebuildWidgets();
            }).bounds(x + 262, row, 18, 18).build();
            addRenderableWidget(remove);
            item.setEditable(admin);
            price.setEditable(admin);
            max.setEditable(admin);
            remove.active = admin;
        }
        Button add = Button.builder(Component.literal("Add listing"), b -> {
            listings.add(new Listing());
            scroll = Math.max(0, listings.size() - ROWS);
            rebuildWidgets();
        }).bounds(x + 8, y + ROWS * 22 + 6, 100, 18).build();
        Button save = Button.builder(Component.literal("Save listings"), b -> saveListings())
                .bounds(x + 112, y + ROWS * 22 + 6, 100, 18).build();
        add.active = admin;
        save.active = admin;
        addRenderableWidget(add);
        addRenderableWidget(save);
        addScrollButtons(x, y, listings.size());
    }

    // ------------------------------------------------------------------ stock
    private void initStock(int x, int y) {
        deliverTo = field(x + 104, y, 180, lastAddress(), 64);
        List<Payloads.StockLine> stock = terminal.stock();
        int shown = Math.min(ROWS, Math.max(0, stock.size() - scroll));
        for (int i = 0; i < shown; i++) {
            Payloads.StockLine line = stock.get(scroll + i);
            int row = y + 26 + i * 20;
            EditBox count = field(x + 200, row, 40, "1", 5);
            Button order = Button.builder(Component.literal("Order"), b -> order(line.item(), count))
                    .bounds(x + 244, row, 40, 18).build();
            order.active = mayOrder();
            count.setEditable(mayOrder());
            addRenderableWidget(order);
        }
        addScrollButtons(x, y + 26, stock.size());
    }

    private void addScrollButtons(int x, int y, int total) {
        if (total <= ROWS) {
            return;
        }
        addRenderableWidget(Button.builder(Component.literal("^"), b -> {
            scroll = Math.max(0, scroll - ROWS);
            rebuildWidgets();
        }).bounds(x + W - 26, y, 18, 18).build());
        addRenderableWidget(Button.builder(Component.literal("v"), b -> {
            scroll = Math.min(Math.max(0, total - ROWS), scroll + ROWS);
            rebuildWidgets();
        }).bounds(x + W - 26, y + 22, 18, 18).build());
    }

    // ------------------------------------------------------------------ helpers
    private EditBox field(int x, int y, int w, String value, int max) {
        EditBox box = new EditBox(font, x, y, w, 18, Component.empty());
        box.setMaxLength(max);
        box.setValue(value);
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

    private void loadListings() {
        listings.clear();
        if (!settings.has("listings")) {
            return;
        }
        try {
            for (var element : settings.getAsJsonArray("listings")) {
                JsonObject entry = element.getAsJsonObject();
                Listing listing = new Listing();
                listing.item = entry.get("item").getAsString();
                listing.price = entry.has("price") ? entry.get("price").getAsInt() : 0;
                listing.maxPerOrder = entry.has("maxPerOrder") ? entry.get("maxPerOrder").getAsInt() : 0;
                listings.add(listing);
            }
        } catch (RuntimeException ignored) {
            // a malformed listing simply doesn't show
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

    private static int parse(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String lastAddress() {
        return terminal.address();
    }

    /** Sends only the fields this tab owns, so the other tabs' work survives. */
    private void saveSettings() {
        JsonObject out = new JsonObject();
        out.addProperty("name", name.getValue());
        out.addProperty("address", address.getValue());
        out.addProperty("role", role.getValue());
        out.addProperty("publicAccess", publicAccess.getValue());
        out.addProperty("autoDispatch", autoDispatch.getValue());
        out.addProperty("batchSize", parse(batch.getValue(), 1));
        out.addProperty("maxWaitSeconds", parse(wait.getValue(), 120));
        out.addProperty("priority", parse(priority.getValue(), 0));
        ClientTerminals.updateSettings(pos, out.toString());
        status = "Saved";
    }

    private void saveListings() {
        JsonArray array = new JsonArray();
        for (Listing listing : listings) {
            if (listing.item.isBlank()) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("item", listing.item.trim());
            entry.addProperty("price", listing.price);
            entry.addProperty("maxPerOrder", listing.maxPerOrder);
            array.add(entry);
        }
        JsonObject out = new JsonObject();
        out.add("listings", array);
        ClientTerminals.updateSettings(pos, out.toString());
        status = "Saved " + array.size() + " listing(s)";
    }

    private void order(String item, EditBox count) {
        String to = deliverTo == null ? "" : deliverTo.getValue().trim();
        if (to.isEmpty()) {
            status = "Enter a delivery address first";
            return;
        }
        ClientTerminals.order(pos, item, Math.max(1, parse(count.getValue(), 1)), to);
        status = "Ordering " + count.getValue() + " x " + item.substring(item.indexOf(':') + 1);
    }

    private void openMap() {
        MapWebServer server = ClientInit.server();
        if (server == null || !server.isRunning()) {
            status = "The map server is not running";
            return;
        }
        Util.getPlatform().openUri("http://127.0.0.1:" + server.port() + "/");
    }

    @Override
    public void tick() {
        // Keep in step with the server: the linked state and the stock both change underneath us.
        Payloads.TerminalInfo previous = terminal;
        find();
        if (!built && terminal != null) {
            rebuildWidgets();
        } else if (tab == Tab.STOCK && previous != null && terminal != null
                && previous.stock().size() != terminal.stock().size()) {
            rebuildWidgets();       // rows changed, so the order buttons have to follow
        }
        ClientTerminals.requestRefresh(false);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        if (terminal == null) {
            g.drawCenteredString(font, "Reading the network...", width / 2, height / 2, 0xFFFFFF);
            return;
        }
        int x = left();
        int y = top();
        g.drawString(font, Component.literal(terminal.name()).withStyle(ChatFormatting.GOLD),
                x + 8, y - 22, 0xFFFFFF, false);
        g.drawString(font, terminal.tuned()
                        ? "linked to a network"
                        : "on its own network - hold this block, right-click a Stock Link",
                x + 8, y - 11, terminal.tuned() ? 0x77DD88 : 0xDD8877, false);
        g.drawString(font, terminal.access(), x + W - 8 - font.width(terminal.access()), y - 11, 0x8899AA, false);

        switch (tab) {
            case SETTINGS -> renderSettings(g, x, y + 34);
            case TRADE -> renderTrade(g, x, y + 34);
            case STOCK -> renderStock(g, x, y + 34);
        }
        if (!status.isEmpty()) {
            g.drawString(font, status, x + 8, y + H - 14, 0xFFD37A, false);
        }
    }

    private void renderSettings(GuiGraphics g, int x, int y) {
        label(g, x, y, "Name");
        label(g, x, y + 22, "Address");
        label(g, x, y + 44, "Role");
        label(g, x, y + 66, "Public access");
        label(g, x, y + 88, "Auto dispatch");
        label(g, x, y + 110, "Batch/wait/pri");
        if (!admin()) {
            g.drawString(font, "read only", x + 8, y + 140, 0x8899AA, false);
        }
    }

    private void renderTrade(GuiGraphics g, int x, int y) {
        g.drawString(font, "item", x + 8, y - 11, 0x9AA1B2, false);
        g.drawString(font, "price", x + 162, y - 11, 0x9AA1B2, false);
        g.drawString(font, "max", x + 212, y - 11, 0x9AA1B2, false);
        if (listings.isEmpty()) {
            g.drawString(font, "Nothing offered. Other players can order nothing from here.",
                    x + 8, y + 4, 0x8899AA, false);
        }
    }

    private void renderStock(GuiGraphics g, int x, int y) {
        label(g, x, y, "Deliver to");
        List<Payloads.StockLine> stock = terminal.stock();
        if (stock.isEmpty()) {
            g.drawString(font, terminal.tuned()
                            ? "Nothing in stock, or the network is not loaded."
                            : "Link this terminal to a network first.",
                    x + 8, y + 30, 0x8899AA, false);
            return;
        }
        int shown = Math.min(ROWS, Math.max(0, stock.size() - scroll));
        for (int i = 0; i < shown; i++) {
            Payloads.StockLine line = stock.get(scroll + i);
            int row = y + 26 + i * 20;
            g.drawString(font, line.display(), x + 8, row + 5, 0xDDDDDD, false);
            String count = Integer.toString(line.count());
            g.drawString(font, count, x + 196 - font.width(count), row + 5, 0x9AA1B2, false);
        }
        if (stock.size() > ROWS) {
            g.drawString(font, (scroll + shown) + " / " + stock.size(),
                    x + 8, y + 26 + ROWS * 20 + 4, 0x8899AA, false);
        }
    }

    private void label(GuiGraphics g, int x, int y, String text) {
        g.drawString(font, text, x + 8, y + 5, 0x9AA1B2, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
