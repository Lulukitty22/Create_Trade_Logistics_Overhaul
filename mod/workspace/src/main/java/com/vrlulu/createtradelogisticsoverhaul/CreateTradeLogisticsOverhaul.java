package com.vrlulu.createtradelogisticsoverhaul;

import com.mojang.logging.LogUtils;
import com.vrlulu.createtradelogisticsoverhaul.logistics.ModContent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Create Trade Logistics Overhaul.
 *
 * <p>Server side: on-demand dispatching for Create's package logistics (see DESIGN.md).
 * Client side: a local web server serving the 3D logistics map to the player's browser.
 */
@Mod(CreateTradeLogisticsOverhaul.ID)
public class CreateTradeLogisticsOverhaul {
    public static final String ID = "createtradelogisticsoverhaul";
    public static final Logger LOG = LogUtils.getLogger();

    public CreateTradeLogisticsOverhaul(IEventBus modBus, ModContainer container) {
        LOG.info("Create Trade Logistics Overhaul loading");
        ModContent.register(modBus);
    }
}
