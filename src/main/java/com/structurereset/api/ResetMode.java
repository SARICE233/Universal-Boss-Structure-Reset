package com.structurereset.api;

/** How widely a structure reset applies. */
public enum ResetMode {

    /** One shared reset state for everyone. */
    GLOBAL,

    /** One independent reset state per player. */
    PER_PLAYER
}
