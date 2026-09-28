package com.sbancuz.plannh.data.channels;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** A recipe input as the channel analysis tells inputs apart; amounts are not part of it. */
public sealed interface Ingredient permits Ingredient.Item,Ingredient.Fluid {

    /** @param nbt the stack's tag as text, or null when it has none */
    record Item(@Nonnull String id, int meta, @Nullable String nbt) implements Ingredient {}

    record Fluid(@Nonnull String name) implements Ingredient {}
}
