package com.realisticclimbing;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public class IceAxeItem extends Item {
    public IceAxeItem(Properties properties) {
        super(properties.stacksTo(1).durability(250));
    }

    public static boolean canGripSurface(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        
        // Запрет на сыпучие и мягкие блоки
        if (state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(Blocks.GRAVEL) || state.is(Blocks.SNOW_BLOCK)) {
            return false;
        }

        // Разрешение на камень, руды, лед
        return state.is(BlockTags.MINEABLE_WITH_PICKAXE) || 
               state.is(Blocks.ICE) || 
               state.is(Blocks.PACKED_ICE) || 
               state.is(Blocks.BLUE_ICE) ||
               state.is(BlockTags.BASE_STONE_OVERWORLD);
    }
}
