package com.terraTowns.block;

import com.terraTowns.registry.TerraTownsRegistries;
import com.terraTowns.structure.BuildingCategory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * A {@link BuildingPlaqueBlock}'s state: which {@link BuildingCategory} it names (null when it
 * names nothing — no settlement, or a room with no qualifying workstation), shown as the sign's
 * own text so the plaque reads as what it registers.
 *
 * <p>Extends vanilla's {@link SignBlockEntity} so vanilla's sign renderer draws it; the text is
 * written here and synced by the sign's own update packet.</p>
 */
public class BuildingPlaqueBlockEntity extends SignBlockEntity {

    @Nullable
    private BuildingCategory category;

    public BuildingPlaqueBlockEntity(BlockPos pos, BlockState state) {
        super(TerraTownsRegistries.BUILDING_PLAQUE_BE.get(), pos, state);
    }

    @Nullable
    public BuildingCategory getCategory() {
        return category;
    }

    /** Name {@code category} (or nothing) and write it on the sign. */
    public void show(@Nullable BuildingCategory category) {
        this.category = category;
        if (category != null) {
            write("sign.terra_towns.building." + category.id());
        }
        setChanged();
    }

    /** Name nothing and explain why, using a two-line sign message key. */
    public void showProblem(String messageKey) {
        this.category = null;
        write(messageKey);
        setChanged();
    }

    /**
     * Put a two-line message in the middle two rows of both faces. Each message is two lang keys
     * ({@code key.1}, {@code key.2}) rather than one string split here: the server can't measure
     * rendered text, and long names ("Barracks / Guard Post") would be clipped by the sign's
     * width. Translations can wrap wherever their language needs to.
     */
    private void write(String key) {
        SignText text = new SignText()
                .setMessage(1, Component.translatable(key + ".1"))
                .setMessage(2, Component.translatable(key + ".2"));
        setText(text, true);
        setText(text, false);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        category = tag.contains("Category") ? BuildingCategory.byId(tag.getString("Category")) : null;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        if (category != null) {
            tag.putString("Category", category.id());
        }
    }
}
