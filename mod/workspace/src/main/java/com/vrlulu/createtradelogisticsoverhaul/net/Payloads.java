package com.vrlulu.createtradelogisticsoverhaul.net;

import com.vrlulu.createtradelogisticsoverhaul.CreateTradeLogisticsOverhaul;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** Everything that travels between the client's map and the server, over the player's own connection. */
public final class Payloads {
    private Payloads() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(CreateTradeLogisticsOverhaul.ID, path);
    }

    /** One item line of a terminal's stock. */
    public record StockLine(String item, String display, int count) {
        public static final StreamCodec<RegistryFriendlyByteBuf, StockLine> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, StockLine::item,
                ByteBufCodecs.STRING_UTF8, StockLine::display,
                ByteBufCodecs.VAR_INT, StockLine::count,
                StockLine::new);
    }

    /** A terminal as the map sees it. */
    public record TerminalInfo(BlockPos pos, String dimension, String name, String address, String owner,
                               boolean tuned, boolean networkLoaded, List<StockLine> stock) {
        // Written by hand: the composite builder tops out at six fields.
        public static final StreamCodec<RegistryFriendlyByteBuf, TerminalInfo> CODEC = StreamCodec.of(
                (buf, info) -> {
                    BlockPos.STREAM_CODEC.encode(buf, info.pos());
                    buf.writeUtf(info.dimension());
                    buf.writeUtf(info.name());
                    buf.writeUtf(info.address());
                    buf.writeUtf(info.owner());
                    buf.writeBoolean(info.tuned());
                    buf.writeBoolean(info.networkLoaded());
                    buf.writeVarInt(info.stock().size());
                    for (StockLine line : info.stock()) {
                        StockLine.CODEC.encode(buf, line);
                    }
                },
                buf -> {
                    BlockPos pos = BlockPos.STREAM_CODEC.decode(buf);
                    String dimension = buf.readUtf();
                    String name = buf.readUtf();
                    String address = buf.readUtf();
                    String owner = buf.readUtf();
                    boolean tuned = buf.readBoolean();
                    boolean loaded = buf.readBoolean();
                    int count = buf.readVarInt();
                    List<StockLine> stock = new java.util.ArrayList<>(Math.min(count, 512));
                    for (int i = 0; i < count; i++) {
                        stock.add(StockLine.CODEC.decode(buf));
                    }
                    return new TerminalInfo(pos, dimension, name, address, owner, tuned, loaded, stock);
                });
    }

    /** Client asks for the terminals it may see. */
    public record RequestTerminals() implements CustomPacketPayload {
        public static final Type<RequestTerminals> TYPE = new Type<>(id("request_terminals"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestTerminals> CODEC =
                StreamCodec.unit(new RequestTerminals());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server's answer. */
    public record Terminals(List<TerminalInfo> terminals) implements CustomPacketPayload {
        public static final Type<Terminals> TYPE = new Type<>(id("terminals"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Terminals> CODEC = StreamCodec.composite(
                TerminalInfo.CODEC.apply(ByteBufCodecs.list(512)), Terminals::terminals, Terminals::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** "Send me these items from that terminal's network, to this address." */
    public record PlaceOrder(BlockPos terminal, String item, int count, String address)
            implements CustomPacketPayload {
        public static final Type<PlaceOrder> TYPE = new Type<>(id("place_order"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PlaceOrder> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, PlaceOrder::terminal,
                ByteBufCodecs.STRING_UTF8, PlaceOrder::item,
                ByteBufCodecs.VAR_INT, PlaceOrder::count,
                ByteBufCodecs.STRING_UTF8, PlaceOrder::address,
                PlaceOrder::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** How the order went. */
    public record OrderResult(boolean ok, String message) implements CustomPacketPayload {
        public static final Type<OrderResult> TYPE = new Type<>(id("order_result"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OrderResult> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, OrderResult::ok,
                ByteBufCodecs.STRING_UTF8, OrderResult::message,
                OrderResult::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
