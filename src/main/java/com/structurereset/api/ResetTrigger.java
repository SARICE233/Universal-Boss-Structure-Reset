package com.structurereset.api;

/** What makes a structure reset fire. */
public enum ResetTrigger {

    /** A player enters the structure. */
    IN_STRUCTURE,

    /** A player interacts with the anchor block. */
    ANCHOR_BLOCK,

    /** Either of the two triggers. */
    EITHER
}
