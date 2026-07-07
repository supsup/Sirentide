package com.sirentide.ir;

/// One divider inside a labeled sequence BLOCK — an `else` line inside an `alt`, or an `and` line
/// inside a `par`. `atMsg` is the flat-message index of the FIRST message that follows the divider
/// (the first message of the new branch): the divider is drawn just above that message's row. When
/// the divider is the last thing in the block (no following message) `atMsg == messages.size()`, and
/// layout skips it (a degenerate divider with no branch → inert, never a stray line). `label` is the
/// free text after the keyword (`else is busy` → `is busy`); may be empty (a bare `else`/`and`).
public record Divider(int atMsg, String label) {}
