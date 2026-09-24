package io.github.brooswitminecraft.dynamicpopulation;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import io.github.brooswitminecraft.dynamicpopulation.cellfield.CellFieldStorage;
import io.github.brooswitminecraft.dynamicpopulation.config.DynamicPopulationConfig;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * Entry point. DPOP-11/DPOP-2 landed the empty scaffold; this story
 * (DPOP-4/DPOP-12) adds the cell field storage attachment and the config
 * scaffold it depends on -- still no propagation/King/Fillager/spawn logic,
 * which all remain later stories.
 */
@Mod(DynamicPopulationMod.MODID)
public class DynamicPopulationMod {
    public static final String MODID = "dynamicpopulation";
    public static final Logger LOGGER = LogUtils.getLogger();

    public DynamicPopulationMod(IEventBus modEventBus, ModContainer modContainer) {
        DynamicPopulationConfig.register(modContainer, modEventBus);
        CellFieldStorage.register(modEventBus);
        modEventBus.addListener(this::commonSetup);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Dynamic Population scaffold loaded");
    }
}
